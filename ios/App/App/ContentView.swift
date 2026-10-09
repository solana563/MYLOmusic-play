import AVFoundation
import Foundation
import SwiftUI
import UniformTypeIdentifiers

private let myloBackground = Color(red: 12 / 255.0, green: 10 / 255.0, blue: 18 / 255.0)
private let myloSurface = Color(red: 28 / 255.0, green: 24 / 255.0, blue: 37 / 255.0)
private let myloAccent = Color(red: 230 / 255.0, green: 0, blue: 35 / 255.0)

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
        }
    }

    private var listenView: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    title("Listen now")
                    introCard("Radio · Your music", "A local-first player for live radio, your audio files, and playlists.")
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
