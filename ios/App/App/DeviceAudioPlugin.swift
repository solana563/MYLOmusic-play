import Foundation
import Capacitor
import MediaPlayer
import AVFoundation
import UIKit

@objc(DeviceAudioPlugin)
public class DeviceAudioPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "DeviceAudioPlugin"
    public let jsName = "DeviceAudio"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "scanDeviceAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "materializeAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openSettings", returnType: CAPPluginReturnPromise)
    ]

    @objc func checkPermissions(_ call: CAPPluginCall) {
        let status = MPMediaLibrary.authorizationStatus()
        call.resolve([
            "granted": status == .authorized,
            "status": statusName(status)
        ])
    }

    @objc func requestPermissions(_ call: CAPPluginCall) {
        MPMediaLibrary.requestAuthorization { status in
            DispatchQueue.main.async {
                call.resolve([
                    "granted": status == .authorized,
                    "status": self.statusName(status)
                ])
            }
        }
    }

    @objc func scanDeviceAudio(_ call: CAPPluginCall) {
        guard MPMediaLibrary.authorizationStatus() == .authorized else {
            call.reject("Media Library permission is not granted")
            return
        }

        DispatchQueue.global(qos: .userInitiated).async {
            let query = MPMediaQuery.songs()
            let items = query.items ?? []
            var tracks: [[String: Any]] = []

            for item in items {
                let duration = item.playbackDuration
                if duration > 0 && duration < 4 { continue }

                let persistentID = String(item.persistentID)
                let assetURL = item.assetURL
                let isCloud = item.value(forProperty: MPMediaItemPropertyIsCloudItem) as? Bool ?? false
                let isProtected = item.hasProtectedAsset

                var row: [String: Any] = [
                    "id": "device_\(persistentID)",
                    "nativeId": persistentID,
                    "title": item.title?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty ?? "Unknown Title",
                    "artist": item.artist?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty ?? "Unknown Artist",
                    "album": item.albumTitle?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty ?? "Device Audio",
                    "duration": duration,
                    "playable": assetURL != nil && !isProtected,
                    "isCloud": isCloud,
                    "isProtected": isProtected,
                    "nativeSource": "ios-media-library"
                ]

                if let url = assetURL { row["assetUrl"] = url.absoluteString }

                if let image = item.artwork?.image(at: CGSize(width: 260, height: 260)),
                   let jpeg = image.jpegData(compressionQuality: 0.76) {
                    row["art"] = "data:image/jpeg;base64," + jpeg.base64EncodedString()
                }

                tracks.append(row)
            }

            DispatchQueue.main.async {
                call.resolve(["tracks": tracks, "count": tracks.count])
            }
        }
    }

    // Web audio cannot consume ipod-library:// URLs consistently. Materialize
    // only the selected item into the app cache, not the entire music library.
    @objc func materializeAudio(_ call: CAPPluginCall) {
        guard let idString = call.getString("id"), let persistentID = UInt64(idString) else {
            call.reject("A valid media item id is required")
            return
        }
        guard MPMediaLibrary.authorizationStatus() == .authorized else {
            call.reject("Media Library permission is not granted")
            return
        }

        let predicate = MPMediaPropertyPredicate(
            value: NSNumber(value: persistentID),
            forProperty: MPMediaItemPropertyPersistentID,
            comparisonType: .equalTo
        )
        let query = MPMediaQuery.songs()
        query.addFilterPredicate(predicate)

        guard let item = query.items?.first else {
            call.reject("The song is no longer in the Media Library")
            return
        }
        guard !item.hasProtectedAsset, let source = item.assetURL else {
            call.reject("This Apple Music or protected item cannot be exported. Download an unprotected local copy first.")
            return
        }

        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MYLODeviceAudio", isDirectory: true)
        do { try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true) }
        catch { call.reject("Could not create the audio cache", nil, error); return }

        let ext = source.pathExtension.nonEmpty ?? "m4a"
        let target = cache.appendingPathComponent("\(persistentID).\(ext)")

        if FileManager.default.fileExists(atPath: target.path) {
            call.resolve(["path": target.absoluteString])
            return
        }

        if source.isFileURL {
            do {
                try FileManager.default.copyItem(at: source, to: target)
                call.resolve(["path": target.absoluteString])
            } catch {
                call.reject("Could not prepare the selected song", nil, error)
            }
            return
        }

        let asset = AVURLAsset(url: source)
        guard let exporter = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else {
            call.reject("This song format cannot be prepared for MYLO")
            return
        }

        // MPMediaItem asset URLs normally export to M4A. Use a stable M4A
        // target when the source has no normal file extension.
        let exportTarget = source.pathExtension.isEmpty
            ? cache.appendingPathComponent("\(persistentID).m4a")
            : target
        exporter.outputURL = exportTarget
        exporter.outputFileType = .m4a
        exporter.shouldOptimizeForNetworkUse = false
        exporter.exportAsynchronously {
            DispatchQueue.main.async {
                switch exporter.status {
                case .completed:
                    call.resolve(["path": exportTarget.absoluteString])
                case .cancelled:
                    call.reject("Preparing the song was cancelled")
                default:
                    call.reject("Could not prepare this song", nil, exporter.error)
                }
            }
        }
    }

    @objc func openSettings(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            guard let url = URL(string: UIApplication.openSettingsURLString) else {
                call.reject("Settings URL is unavailable")
                return
            }
            UIApplication.shared.open(url, options: [:]) { opened in
                opened ? call.resolve() : call.reject("Could not open Settings")
            }
        }
    }

    private func statusName(_ status: MPMediaLibraryAuthorizationStatus) -> String {
        switch status {
        case .authorized: return "granted"
        case .denied: return "denied"
        case .restricted: return "restricted"
        case .notDetermined: return "prompt"
        @unknown default: return "unknown"
        }
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}