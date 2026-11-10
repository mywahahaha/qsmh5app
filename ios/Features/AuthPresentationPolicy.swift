import UIKit

// SDK 授权页与宿主使用同一方向集合，避免横屏 App 弹出默认竖屏控制器。
enum AuthPresentationPolicy {
    static func orientations(in bundle: Bundle = .main) -> UIInterfaceOrientationMask {
        mask(bundle.object(forInfoDictionaryKey: "UISupportedInterfaceOrientations") as? [String] ?? [])
    }
    static func mask(_ values: [String]) -> UIInterfaceOrientationMask {
        var result: UIInterfaceOrientationMask = []
        for value in values {
            switch value {
            case "UIInterfaceOrientationLandscapeLeft": result.insert(.landscapeLeft)
            case "UIInterfaceOrientationLandscapeRight": result.insert(.landscapeRight)
            case "UIInterfaceOrientationPortrait": result.insert(.portrait)
            case "UIInterfaceOrientationPortraitUpsideDown": result.insert(.portraitUpsideDown)
            default: break
            }
        }
        return result.isEmpty ? .portrait : result
    }
}
