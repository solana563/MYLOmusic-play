import AVFoundation
import AuthenticationServices
import Combine
import CryptoKit
import Foundation
import Security
import SwiftUI
import UIKit
import UniformTypeIdentifiers

private let myloBackground = Color(red: 12 / 255.0, green: 10 / 255.0, blue: 18 / 255.0)
private let myloSurface = Color(red: 28 / 255.0, green: 24 / 255.0, blue: 37 / 255.0)
private let myloAccent = Color(red: 230 / 255.0, green: 0, blue: 35 / 255.0)
private let myloAuthRedirect = "mylo://auth-callback"

@MainActor
final class SupabaseAuthStore: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    @Published var emailInput = ""
    @Published var passwordInput = ""
    @Published private(set) var signedInEmail = ""
    @Published private(set) var isBusy = false
    @Published var errorMessage = ""

    private let supabaseURL = Bundle.main.object(forInfoDictionaryKey: "MYLO_SUPABASE_URL") as? String ?? ""
    private let anonKey = Bundle.main.object(forInfoDictionaryKey: "MYLO_SUPABASE_ANON_KEY") as? String ?? ""
    private var accessToken = ""
    private var webSession: ASWebAuthenticationSession?

    var isConfigured: Bool {
        URLComponents(string: supabaseURL)?.scheme == "https" &&
            URLComponents(string: supabaseURL)?.host != nil && !anonKey.isEmpty &&
            !supabaseURL.hasPrefix("$(") && !anonKey.hasPrefix("$(")
    }

    var isSignedIn: Bool { !signedInEmail.isEmpty }

    func restoreSession() async {
        guard isConfigured, let refreshToken = secureValue(for: "refresh") else { return }
        do {
            let response = try await request("token?grant_type=refresh_token", body: ["refresh_token": refreshToken])
            try apply(response)
        } catch {
            let failure = error as NSError
            if failure.domain == "MYLOAuth" && [400, 401].contains(failure.code) {
                removeSecureValue(for: "refresh")
                errorMessage = "Your saved sign-in expired. Please sign in again."
            } else {
                errorMessage = "Could not restore sign-in. Check your connection."
            }
        }
    }

    func signIn() {
        Task { await authenticate(createAccount: false) }
    }

    func createAccount() {
        Task { await authenticate(createAccount: true) }
    }

    func sendMagicLink() {
        Task {
            guard validateEmail() else { return }
            do {
                let verifier = try makeVerifier()
                try saveSecureValue(verifier, for: "verifier")
                _ = try await request("otp?redirect_to=\(encodedRedirect)",
                    body: ["email": emailInput.trimmingCharacters(in: .whitespacesAndNewlines),
                           "create_user": false,
                           "code_challenge": challenge(verifier),
                           "code_challenge_method": "s256"])
                errorMessage = "Check your inbox for the sign-in link."
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func signInWithGoogle() {
        guard isConfigured else {
            errorMessage = "Sign-in is not configured for this build."
            return
        }
        do {
            let verifier = try makeVerifier()
            try saveSecureValue(verifier, for: "verifier")
            var components = URLComponents(string: "\(baseURL)/auth/v1/authorize")
            components?.queryItems = [
                URLQueryItem(name: "provider", value: "google"),
                URLQueryItem(name: "redirect_to", value: myloAuthRedirect),
                URLQueryItem(name: "code_challenge", value: challenge(verifier)),
                URLQueryItem(name: "code_challenge_method", value: "s256")
            ]
            guard let url = components?.url else { throw URLError(.badURL) }
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "mylo") { [weak self] callback, error in
                Task { @MainActor in
                    guard let self else { return }
                    if let callback {
                        await self.handleCallback(callback)
                    } else if let error {
                        if (error as? ASWebAuthenticationSessionError)?.code != .canceledLogin {
                            self.errorMessage = error.localizedDescription
                        }
                    }
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            webSession = session
            if !session.start() { throw URLError(.cannotOpenFile) }
        } catch {
            errorMessage = "Could not start Google sign-in: \(error.localizedDescription)"
        }
    }

    func handleCallback(_ url: URL) async {
        guard url.scheme == "mylo", url.host == "auth-callback" else { return }
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        if let description = components?.queryItems?.first(where: { $0.name == "error_description" })?.value {
            errorMessage = description
            return
        }
        guard let code = components?.queryItems?.first(where: { $0.name == "code" })?.value,
              let verifier = secureValue(for: "verifier") else {
            errorMessage = "Sign-in callback was incomplete. Request a new link."
            return
        }
        removeSecureValue(for: "verifier")
        do {
            try apply(try await request("token?grant_type=pkce",
                body: ["auth_code": code, "code_verifier": verifier]))
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func signOut() {
        let token = accessToken
        accessToken = ""
        signedInEmail = ""
        removeSecureValue(for: "refresh")
        guard !token.isEmpty else { return }
        Task {
            do { _ = try await request("logout", body: [:], bearer: token) }
            catch { errorMessage = "Signed out on this device; remote sign-out failed." }
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }

    private var baseURL: String { supabaseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")) }
    private var encodedRedirect: String {
        myloAuthRedirect.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? myloAuthRedirect
    }

    private func authenticate(createAccount: Bool) async {
        guard validateEmail() else { return }
        guard passwordInput.count >= (createAccount ? 6 : 1) else {
            errorMessage = createAccount ? "Use at least 6 characters." : "Enter your password."
            return
        }
        isBusy = true
        defer { isBusy = false }
        do {
            let email = emailInput.trimmingCharacters(in: .whitespacesAndNewlines)
            var body: [String: Any] = ["email": email, "password": passwordInput]
            let endpoint: String
            if createAccount {
                let verifier = try makeVerifier()
                try saveSecureValue(verifier, for: "verifier")
                body["data"] = ["username": email.split(separator: "@").first.map(String.init) ?? ""]
                body["code_challenge"] = challenge(verifier)
                body["code_challenge_method"] = "s256"
                endpoint = "signup?redirect_to=\(encodedRedirect)"
            } else {
                endpoint = "token?grant_type=password"
            }
            let response = try await request(endpoint, body: body)
            if response["access_token"] == nil {
                errorMessage = "Account created. Check your email to confirm, then sign in."
            } else {
                try apply(response)
                passwordInput = ""
                errorMessage = ""
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func validateEmail() -> Bool {
        guard isConfigured else {
            errorMessage = "Sign-in is not configured for this build."
            return false
        }
        let email = emailInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard email.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) != nil else {
            errorMessage = "Enter a valid email address."
            return false
        }
        return true
    }

    private func request(_ endpoint: String, body: [String: Any], bearer: String? = nil) async throws -> [String: Any] {
        guard let url = URL(string: "\(baseURL)/auth/v1/\(endpoint)") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(anonKey, forHTTPHeaderField: "apikey")
        if let bearer { request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: request)
        let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard let httpResponse = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        let status = httpResponse.statusCode
        guard (200..<300).contains(status) else {
            let message = payload["msg"] as? String ?? payload["message"] as? String ??
                payload["error_description"] as? String ?? "Sign-in failed. Please try again."
            throw NSError(domain: "MYLOAuth", code: status, userInfo: [NSLocalizedDescriptionKey: message])
        }
        return payload
    }

    private func apply(_ response: [String: Any]) throws {
        guard let token = response["access_token"] as? String,
              let account = response["user"] as? [String: Any],
              let email = account["email"] as? String else {
            throw NSError(domain: "MYLOAuth", code: 2, userInfo: [NSLocalizedDescriptionKey: "Sign-in returned no session."])
        }
        if let refresh = response["refresh_token"] as? String {
            try saveSecureValue(refresh, for: "refresh")
        }
        removeSecureValue(for: "verifier")
        accessToken = token
        signedInEmail = email
        errorMessage = ""
    }

    private func makeVerifier() throws -> String {
        var bytes = Data(count: 32)
        let length = bytes.count
        let status = bytes.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, length, $0.baseAddress!)
        }
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
        return bytes.base64URLEncodedString()
    }

    private func challenge(_ verifier: String) -> String {
        Data(SHA256.hash(data: Data(verifier.utf8))).base64URLEncodedString()
    }

    private func saveSecureValue(_ value: String, for key: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.mylo.music.auth",
            kSecAttrAccount as String: key
        ]
        let data = Data(value.utf8)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let addStatus = SecItemAdd(item as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(addStatus)) }
        } else if status != errSecSuccess {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }

    private func secureValue(for key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.mylo.music.auth",
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func removeSecureValue(for key: String) {
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.mylo.music.auth",
            kSecAttrAccount as String: key
        ] as CFDictionary)
    }
}

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

struct LocalTrack: Identifiable, Codable, Hashable {
    var id: String
    var title: String
    var artist: String
    var filename: String
}

struct MusicPlaylist: Identifiable, Codable {
    var id: String
    var name: String
    var trackIDs: [String]
}

struct RadioStation: Identifiable, Decodable {
    var id: String { stationuuid }
    let stationuuid: String
    let name: String
    let tags: String
    let url_resolved: String
    let url: String

    var streamURL: URL? { URL(string: url_resolved.isEmpty ? url : url_resolved) }
}

@MainActor
final class MusicStore: ObservableObject {
    @Published var tracks: [LocalTrack] = []
    @Published var playlists: [MusicPlaylist] = []
    @Published var stations: [RadioStation] = []
    @Published var playingTitle = ""
    @Published var isPlaying = false
    @Published var errorMessage: String?
    @Published var loadingStations = false

    private let player = AVPlayer()
    private let defaults = UserDefaults.standard
    private let tracksKey = "mylo.native.tracks"
    private let playlistsKey = "mylo.native.playlists"
    private let countryKey = "mylo.native.country"
    private var playingTrackID: String?

    var country: String {
        get { defaults.string(forKey: countryKey) ?? "US" }
        set { defaults.set(newValue.uppercased(), forKey: countryKey) }
    }

    init() {
        tracks = decode([LocalTrack].self, key: tracksKey) ?? []
        playlists = decode([MusicPlaylist].self, key: playlistsKey) ?? []
        configureAudioSession()
    }

    private func decode<T: Decodable>(_ type: T.Type, key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        do { return try JSONDecoder().decode(type, from: data) }
        catch {
            errorMessage = "Saved music data could not be read."
            return nil
        }
    }

    private func save<T: Encodable>(_ value: T, key: String) {
        do { defaults.set(try JSONEncoder().encode(value), forKey: key) }
        catch { errorMessage = "Your changes could not be saved." }
    }

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            errorMessage = "Audio playback could not be initialized."
        }
    }

    func importFiles(_ urls: [URL]) {
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Music", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var imported: [LocalTrack] = []
            var failures: [String] = []
            for source in urls {
                let hasAccess = source.startAccessingSecurityScopedResource()
                defer { if hasAccess { source.stopAccessingSecurityScopedResource() } }
                do {
                    let filename = UUID().uuidString + "." + (source.pathExtension.isEmpty ? "audio" : source.pathExtension)
                    let destination = directory.appendingPathComponent(filename)
                    try FileManager.default.copyItem(at: source, to: destination)
                    imported.append(LocalTrack(id: UUID().uuidString,
                        title: source.deletingPathExtension().lastPathComponent,
                        artist: "Local audio", filename: filename))
                } catch {
                    failures.append(source.lastPathComponent)
                }
            }
            if !imported.isEmpty {
                tracks.append(contentsOf: imported)
                save(tracks, key: tracksKey)
            }
            if !failures.isEmpty {
                errorMessage = "Could not import: \(failures.joined(separator: ", "))"
            }
        } catch {
            errorMessage = "The music library could not be opened: \(error.localizedDescription)"
        }
    }

    func play(track: LocalTrack) {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Music", isDirectory: true)
            .appendingPathComponent(track.filename)
        playingTrackID = track.id
        play(url: url, title: track.title)
    }

    func play(station: RadioStation) {
        guard let url = station.streamURL, url.scheme?.lowercased() == "https" else {
            errorMessage = "This station does not have a secure, playable stream."
            return
        }
        playingTrackID = nil
        play(url: url, title: station.name)
    }

    private func play(url: URL, title: String) {
        player.replaceCurrentItem(with: AVPlayerItem(url: url))
        player.play()
        playingTitle = title
        isPlaying = true
    }

    func togglePlayback() {
        guard player.currentItem != nil else { return }
        if isPlaying { player.pause() } else { player.play() }
        isPlaying.toggle()
    }

    func loadStations(countryCode: String) async {
        let code = countryCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard code.count == 2, code.allSatisfy({ $0.isASCII && $0.isLetter }) else {
            errorMessage = "Enter a valid two-letter country code."
            return
        }
        country = code
        loadingStations = true
        defer { loadingStations = false }
        do {
            var components = URLComponents(string: "https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/\(code)")
            components?.queryItems = [
                URLQueryItem(name: "limit", value: "40"),
                URLQueryItem(name: "order", value: "clickcount"),
                URLQueryItem(name: "reverse", value: "true")
            ]
            guard let url = components?.url else { throw URLError(.badURL) }
            var request = URLRequest(url: url)
            request.setValue("MYLO/1.0", forHTTPHeaderField: "User-Agent")
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else {
                throw URLError(.badServerResponse)
            }
            stations = try JSONDecoder().decode([RadioStation].self, from: data)
        } catch {
            errorMessage = "Radio stations could not be loaded. Check your connection and try again."
        }
    }

    func createPlaylist(_ name: String) {
        let value = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        playlists.append(MusicPlaylist(id: UUID().uuidString, name: value, trackIDs: []))
        save(playlists, key: playlistsKey)
    }

    func add(_ track: LocalTrack, to playlist: MusicPlaylist) {
        guard let index = playlists.firstIndex(where: { $0.id == playlist.id }),
              !playlists[index].trackIDs.contains(track.id) else { return }
        playlists[index].trackIDs.append(track.id)
        save(playlists, key: playlistsKey)
    }

    func remove(_ track: LocalTrack) {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Music", isDirectory: true)
            .appendingPathComponent(track.filename)
        do {
            if FileManager.default.fileExists(atPath: url.path) {
                try FileManager.default.removeItem(at: url)
            }
            tracks.removeAll { $0.id == track.id }
            for index in playlists.indices {
                playlists[index].trackIDs.removeAll { $0 == track.id }
            }
            if playingTrackID == track.id {
                player.pause()
                player.replaceCurrentItem(with: nil)
                playingTrackID = nil
                playingTitle = ""
                isPlaying = false
            }
            save(tracks, key: tracksKey)
            save(playlists, key: playlistsKey)
        } catch {
            errorMessage = "This song could not be removed: \(error.localizedDescription)"
        }
    }

    func remove(_ playlist: MusicPlaylist) {
        playlists.removeAll { $0.id == playlist.id }
        save(playlists, key: playlistsKey)
    }

    func tracks(in playlist: MusicPlaylist) -> [LocalTrack] {
        playlist.trackIDs.compactMap { id in tracks.first(where: { $0.id == id }) }
    }
}

@MainActor
struct ContentView: View {
    @EnvironmentObject private var music: MusicStore
    @StateObject private var auth = SupabaseAuthStore()
    @State private var selectedTab = "Listen"
    @State private var showingImporter = false
    @State private var showingNewPlaylist = false
    @State private var playlistName = ""
    @State private var countryCode = "US"

    var body: some View {
        TabView(selection: $selectedTab) {
            listenView.tabItem { Label("Listen", systemImage: "play.circle.fill") }.tag("Listen")
            radioView.tabItem { Label("Radio", systemImage: "dot.radiowaves.left.and.right") }.tag("Radio")
            libraryView.tabItem { Label("Library", systemImage: "music.note.list") }.tag("Library")
            playlistsView.tabItem { Label("Playlists", systemImage: "music.note") }.tag("Playlists")
        }
        .tint(myloAccent)
        .safeAreaInset(edge: .bottom, spacing: 0) { nowPlayingBar }
        .background(myloBackground.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.audio],
                      allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): music.importFiles(urls)
            case .failure(let error): music.errorMessage = "Audio import failed: \(error.localizedDescription)"
            }
        }
        .alert("MYLO", isPresented: Binding(
            get: { music.errorMessage != nil },
            set: { if !$0 { music.errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { music.errorMessage = nil }
        } message: {
            Text(music.errorMessage ?? "")
        }
        .task {
            countryCode = music.country
            await music.loadStations(countryCode: countryCode)
            await auth.restoreSession()
        }
        .onOpenURL { url in Task { await auth.handleCallback(url) } }
    }

    private var listenView: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    title("Listen now")
                    introCard("Radio · Your music", "A local-first player for live radio, your audio files, and playlists.")
                    accountCard
                    Button { selectedTab = "Radio" } label: {
                        Label("Browse live radio", systemImage: "dot.radiowaves.left.and.right")
                    }.buttonStyle(PrimaryButtonStyle())
                    Button { showingImporter = true } label: {
                        Label("Import audio files", systemImage: "plus")
                    }.buttonStyle(SecondaryButtonStyle())
                    if !music.tracks.isEmpty {
                        title("Recently added")
                        ForEach(music.tracks.suffix(5)) { trackRow($0) }
                    }
                }.padding(20)
            }
            .background(myloBackground)
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    private var accountCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(auth.isSignedIn ? "Signed in" : "MYLO account").font(.headline)
            if auth.isSignedIn {
                Text(auth.signedInEmail).font(.subheadline).foregroundStyle(.secondary)
                Button("Sign out", action: auth.signOut).buttonStyle(SecondaryButtonStyle())
            } else {
                TextField("Email address", text: $auth.emailInput)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(12)
                    .background(myloBackground, in: RoundedRectangle(cornerRadius: 10))
                SecureField("Password", text: $auth.passwordInput)
                    .textContentType(.password)
                    .padding(12)
                    .background(myloBackground, in: RoundedRectangle(cornerRadius: 10))
                Button(auth.isBusy ? "Please wait…" : "Sign in", action: auth.signIn)
                    .buttonStyle(PrimaryButtonStyle()).disabled(auth.isBusy)
                Button("Create account", action: auth.createAccount)
                    .buttonStyle(SecondaryButtonStyle()).disabled(auth.isBusy)
                HStack {
                    Button("Magic link", action: auth.sendMagicLink)
                    Spacer()
                    Button("Continue with Google", action: auth.signInWithGoogle)
                }
                .font(.footnote.weight(.semibold))
                .tint(myloAccent)
                .disabled(auth.isBusy)
            }
            if !auth.errorMessage.isEmpty {
                Text(auth.errorMessage)
                    .font(.footnote)
                    .foregroundStyle(auth.errorMessage.hasPrefix("Check your inbox") ? .green : .red)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(myloSurface, in: RoundedRectangle(cornerRadius: 16))
    }

    private var radioView: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    title("Radio")
                    introCard("Live stations around the world", "Top stations are supplied by Radio Browser.")
                    HStack {
                        TextField("Country code", text: $countryCode)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .padding(12)
                            .background(myloSurface, in: RoundedRectangle(cornerRadius: 12))
                        Button {
                            Task { await music.loadStations(countryCode: countryCode) }
                        } label: {
                            Image(systemName: "magnifyingglass").frame(width: 46, height: 44)
                        }.buttonStyle(PrimaryButtonStyle())
                    }
                    if music.loadingStations { ProgressView("Finding stations…").tint(myloAccent) }
                    ForEach(music.stations) { station in
                        Button { music.play(station: station) } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(station.name).font(.headline).lineLimit(1)
                                    Text(station.tags.isEmpty ? music.country : station.tags)
                                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                                Spacer()
                                Image(systemName: "play.fill").foregroundStyle(myloAccent)
                            }.padding(14).background(myloSurface, in: RoundedRectangle(cornerRadius: 14))
                        }.buttonStyle(.plain)
                    }
                }.padding(20)
            }
            .background(myloBackground)
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    private var libraryView: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    title("Your library")
                    Button { showingImporter = true } label: {
                        Label("Import audio files", systemImage: "plus")
                    }.buttonStyle(PrimaryButtonStyle())
                    if music.tracks.isEmpty {
                        introCard("Your music stays yours", "Import audio files to keep a private, on-device library.")
                    } else {
                        ForEach(music.tracks) { track in trackRow(track) }
                    }
                }.padding(20)
            }
            .background(myloBackground)
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    private var playlistsView: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    title("Playlists")
                    Button { showingNewPlaylist = true } label: {
                        Label("New playlist", systemImage: "plus")
                    }.buttonStyle(PrimaryButtonStyle())
                    if music.playlists.isEmpty {
                        introCard("Make it yours", "Create a playlist and add songs from your library.")
                    }
                    ForEach(music.playlists) { playlist in
                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                Text(playlist.name).font(.headline)
                                Spacer()
                                Button(role: .destructive) {
                                    music.remove(playlist)
                                } label: {
                                    Image(systemName: "trash")
                                }.accessibilityLabel("Delete \(playlist.name)")
                            }
                            let songs = music.tracks(in: playlist)
                            if songs.isEmpty { Text("No songs yet").font(.caption).foregroundStyle(.secondary) }
                            ForEach(songs) { track in trackRow(track) }
                            if !music.tracks.isEmpty {
                                Menu("Add a song") {
                                    ForEach(music.tracks) { track in
                                        Button(track.title) { music.add(track, to: playlist) }
                                    }
                                }.tint(myloAccent)
                            }
                        }.padding(14).background(myloSurface, in: RoundedRectangle(cornerRadius: 14))
                    }
                }.padding(20)
            }
            .background(myloBackground)
            .toolbar(.hidden, for: .navigationBar)
            .alert("New playlist", isPresented: $showingNewPlaylist) {
                TextField("Playlist name", text: $playlistName)
                Button("Cancel", role: .cancel) { playlistName = "" }
                Button("Create") {
                    music.createPlaylist(playlistName)
                    playlistName = ""
                }
            }
        }
    }

    private var nowPlayingBar: some View {
        HStack(spacing: 12) {
            Image(systemName: "waveform").foregroundStyle(myloAccent)
            Text(music.playingTitle.isEmpty ? "Nothing playing" : music.playingTitle)
                .font(.subheadline.weight(.medium)).lineLimit(1)
            Spacer()
            Button { music.togglePlayback() } label: {
                Image(systemName: music.isPlaying ? "pause.fill" : "play.fill")
                    .frame(width: 40, height: 40)
            }.disabled(music.playingTitle.isEmpty)
        }
        .padding(.horizontal, 16)
        .background(myloSurface)
    }

    private func trackRow(_ track: LocalTrack) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "music.note").foregroundStyle(myloAccent)
            VStack(alignment: .leading, spacing: 4) {
                Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(track.artist).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button { music.play(track: track) } label: {
                Image(systemName: "play.fill").frame(width: 40, height: 40)
            }.tint(myloAccent)
            Menu {
                Button(role: .destructive) {
                    music.remove(track)
                } label: {
                    Label("Remove from library", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis").frame(width: 36, height: 40)
            }.tint(.secondary)
        }
        .padding(.horizontal, 12)
        .background(myloSurface, in: RoundedRectangle(cornerRadius: 12))
    }

    private func title(_ value: String) -> some View {
        HStack(spacing: 12) {
            Image("MyloWordmark")
                .resizable()
                .scaledToFit()
                .frame(width: 140, height: 52)
                .accessibilityLabel("MYLO")
            Text(value)
                .font(.largeTitle.bold())
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .padding(.top, 10)
    }

    private func introCard(_ heading: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(heading).font(.headline)
            Text(detail).font(.subheadline).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(myloSurface, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 46)
            .background(myloAccent.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 46)
            .background(myloSurface.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 12))
    }
}
