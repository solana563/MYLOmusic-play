import SwiftUI

@main
@MainActor
struct MYLOApp: App {
    @StateObject private var music = MusicStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(music)
                .preferredColorScheme(.dark)
        }
    }
}
