import UIKit
import Capacitor

// In Main.storyboard, set the bridge view controller's Custom Class to
// MyViewController. This is the supported Capacitor 6 registration path for
// local Swift plugins.
class MyViewController: CAPBridgeViewController {
    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(DeviceAudioPlugin())
    }
}