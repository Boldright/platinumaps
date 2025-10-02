# Platinumaps Integration Guide (iOS)

## Folder Structure

```
./README.md
./platinumaps-sdk
./platinumaps-sdk/Errors
./platinumaps-sdk/Errors/PMError.swift
./platinumaps-sdk/Types
./platinumaps-sdk/Types/PMLocale.swift
./platinumaps-sdk/ViewControllers
./platinumaps-sdk/ViewControllers/PMWebViewController.swift
./platinumaps-sdk/ViewControllers/PMMainViewController.swift
./platinumaps-sdk/Views
./platinumaps-sdk/Views/PMWebView.swift
./platinumaps-sdk/Platinumaps.bundle
```

### README.md

This file.

### PMMainViewController.swift

Main screen.

### PMWebViewController.swift

In-app browser screen.

### PMWebView.swift

Web browser component.
This is for displaying content that extends to the SafeArea area.

### Platinumaps.bundle

Contains localization files used exclusively by the SDK.

## Integration Steps

1.  This SDK uses location information, the camera, and the microphone. Please configure your project to enable these features.
2.  Add the entire `/platinumaps-sdk` directory to your project.
3.  Set the required information for `PMMainViewController` as follows.

```swift
let vc = PMMainViewController()
vc.mapSlug = "map_slug"
vc.mapQuery["key1"] = "value1"
vc.mapQuery["key2"] = "value2"
```

  * `mapSlug`  
    Required  
    Please set the string for the URL.
  * `mapQuery`  
    Optional  
    Please set the query parameters in a dictionary format.
  * `beaconUuid`
    Optional  
    If you are using indoor positioning with beacons, please set the UUID of the target beacon to be scanned (in hyphenated format).

### Handling Links

To handle links yourself, please set the delegate as shown below to ensure links are processed.

```swift
import UIKit
import SafariServices

class ViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        // Do any additional setup after loading the view.
    }

    @IBAction func openPlatinumaps(_ sender: Any) {
        let vc = WebPageViewController()
        let nc = UINavigationController(rootViewController: vc)
        present(nc, animated: true)
    }
}

extension ViewController: PMMainViewControllerDelegate {
    func openLink(_ url: URL, sharedCookie: Bool) {
        if sharedCookie {
            // Please display in the in-app browser as it is necessary to carry over user information for things like stamp rally rewards.
        } else {
            // It can be displayed in SFSafariViewController or the standard browser.
            if let nav = navigationController {
                let vc = SFSafariViewController(url: url)
                nav.present(vc, animated: true, completion: nil)
            }
        }
    }
}
```
