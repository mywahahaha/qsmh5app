import XCTest
import UIKit
@testable import QSMH5App
#if QSM_ALIYUN
import ATAuthSDK
#endif

@MainActor final class AuthPresentationTests: XCTestCase {
    func testLandscapeAndPortraitPackagesNeverUseDisjointSDKOrientation() {
        XCTAssertEqual(AuthPresentationPolicy.mask(["UIInterfaceOrientationLandscapeLeft","UIInterfaceOrientationLandscapeRight"]),.landscape)
        XCTAssertEqual(AuthPresentationPolicy.mask(["UIInterfaceOrientationPortrait"]),.portrait)
        XCTAssertEqual(AuthPresentationPolicy.orientations(),.allButUpsideDown)
    }
    #if QSM_ALIYUN
    func model(orientations: UIInterfaceOrientationMask = .landscape) throws -> TXCustomModel {
        try AliyunAuthGateway.makeModel(style: OnekeyAuthStyle(raw: OnekeyAppearanceTests().raw()), orientations: orientations, carrierName:"中国电信",
            viewport: { CGRect(x:0,y:0,width:926,height:428) },
            onClose:{},onInvalidLayout:{ XCTFail("正常布局不应回退") })
    }
    func testActualSDKModelUsesPackageDirectionsInsteadOfDefaultPortrait() throws {
        XCTAssertEqual(TXCustomModel().supportedInterfaceOrientations,.portrait)
        let model = try model()
        XCTAssertEqual(model.supportedInterfaceOrientations,.landscape)
        XCTAssertFalse(model.checkBoxIsChecked)
        let portrait = try self.model(orientations:.portrait)
        XCTAssertEqual(portrait.supportedInterfaceOrientations,.portrait)
        XCTAssertThrowsError(try self.model(orientations:[]))
    }
    func testActualSDKBrandAndPrivacyControlsUsePublicConfiguration() throws {
        let m = try model()
        XCTAssertTrue(m.alertBarIsHidden); XCTAssertTrue(m.logoIsHidden)
        XCTAssertEqual(m.alertBlurViewAlpha,0.45,accuracy:0.001); XCTAssertEqual(m.alertContentViewColor,OnekeyBrand.panel)
        XCTAssertEqual(m.alertCornerRadiusArray,[14,14,14,14]); XCTAssertEqual(m.navTitle.string,"")
        XCTAssertEqual(m.loginBtnText.string,"手机号一键登录"); XCTAssertEqual(m.loginBtnBgImgs.count,3)
        XCTAssertEqual(m.sloganText.string,"中国电信提供认证服务")
        XCTAssertEqual(m.privacyFont.pointSize,12); XCTAssertEqual(m.privacyNavColor,OnekeyBrand.red)
        XCTAssertTrue(m.expandAuthPageCheckedScope); XCTAssertFalse(m.checkBoxIsChecked); XCTAssertFalse(m.privacyVCIsCustomized)
        XCTAssertEqual(m.checkBoxWH,44); XCTAssertEqual(m.checkBoxWH-m.checkBoxImageEdgeInsets.left-m.checkBoxImageEdgeInsets.right,14)
        XCTAssertFalse(m.tapAuthPageMaskClosePage)
        let panel = try XCTUnwrap(m.contentViewFrameBlock)(CGSize(width:926,height:428),CGSize(width:926,height:428),.zero)
        XCTAssertEqual(panel.width,926*0.6,accuracy:0.01)
        XCTAssertEqual(panel.height,428*0.75,accuracy:0.01)
        let size = panel.size
        let login = try XCTUnwrap(m.loginBtnFrameBlock)(.zero,size,.zero)
        let change = try XCTUnwrap(m.changeBtnFrameBlock)(.zero,size,.zero)
        let privacy = try XCTUnwrap(m.privacyFrameBlock)(.zero,size,.zero)
        XCTAssertGreaterThanOrEqual(login.height,44); XCTAssertGreaterThanOrEqual(change.height,44)
        XCTAssertLessThanOrEqual(change.maxY,privacy.minY); XCTAssertLessThanOrEqual(privacy.maxY,size.height)
        let container = UIView(frame:CGRect(origin:.zero,size:size))
        try XCTUnwrap(m.customViewBlock)(container)
        XCTAssertTrue(container.subviews.contains { $0 is OnekeyBrandHeaderView })
    }
    func testSDKDialogUsesActualScreenInsteadOfInsetParent() throws {
        // SDK 会再以实际屏幕检查返回框；不能在横屏模拟器中伪造超出实际高度的竖屏框。
        let viewport = UIScreen.main.bounds
        let style = try OnekeyAuthStyle(raw:OnekeyAppearanceTests().raw(ui:["dialog_width_percent":50,"dialog_height_percent":80]))
        let model = try AliyunAuthGateway.makeModel(style:style,orientations:.allButUpsideDown,
            viewport:{ viewport },onClose:{},onInvalidLayout:{ XCTFail("正常布局不应回退") })
        let frame = try XCTUnwrap(model.contentViewFrameBlock)
        let panel = frame(viewport.size,CGSize(width:viewport.width-24,height:viewport.height-21),.zero)
        XCTAssertEqual(panel.width,viewport.width*0.5,accuracy:0.001)
        XCTAssertEqual(panel.height,viewport.height*0.8,accuracy:0.001)
        XCTAssertEqual(panel.midX,viewport.width/2,accuracy:0.001)
        XCTAssertEqual(panel.midY,viewport.height/2,accuracy:0.001)
        XCTAssertEqual(frame(.zero,.zero,.zero),panel)
    }
    func testSDKLoginBackgroundStaysOrangeForEveryState() throws {
        let model = try model()
        for checked in [false,true] {
            model.checkBoxIsChecked = checked
            for background in model.loginBtnBgImgs {
                var rgba = [UInt8](repeating:0,count:4)
                let context = try XCTUnwrap(CGContext(data:&rgba,width:1,height:1,bitsPerComponent:8,bytesPerRow:4,
                    space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue))
                let cgImage = try XCTUnwrap(background.cgImage)
                context.draw(cgImage,in:CGRect(x:-5,y:-5,width:12,height:12))
                XCTAssertEqual(rgba,[255,102,0,255],"协议勾选及按钮状态不应改变背景色")
            }
        }
    }
    func testUncheckedLoginPresentsReminderAboveAuthPageWithoutChangingConsent() async throws {
        let previousWindow = UIApplication.shared.windows.first { $0.isKeyWindow }
        let scene = try XCTUnwrap(previousWindow?.windowScene)
        let window = UIWindow(windowScene:scene)
        let host = UIViewController(); window.rootViewController = host; window.makeKeyAndVisible()
        defer { window.isHidden = true; previousWindow?.makeKey() }
        let auth = UIViewController()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void,Never>) in
            host.present(auth,animated:false) { continuation.resume() }
        }
        XCTAssertNotNil(auth.viewIfLoaded?.window)
        let model = try model()
        XCTAssertFalse(model.privacyAlertIsNeedShow)
        let unchecked: [String:Any] = ["resultCode":"700002","isChecked":NSNumber(value:false)]
        AliyunAuthGateway.showPrivacyReminderIfNeeded(for:unchecked,from:host,isChecked:{
            XCTFail("应优先使用点击事件中的实际勾选状态"); return true
        })
        let reminder = try XCTUnwrap(auth.view.subviews.compactMap { $0 as? OnekeyPrivacyReminderView }.first)
        XCTAssertEqual(reminder.subviews.compactMap { $0 as? UILabel }.first?.text,"请阅读并同意用户注册协议及隐私政策")
        var white: CGFloat = 1, alpha: CGFloat = 0
        XCTAssertTrue(try XCTUnwrap(reminder.backgroundColor).getWhite(&white,alpha:&alpha))
        XCTAssertEqual(white,0,accuracy:0.001); XCTAssertEqual(alpha,0.60,accuracy:0.001)
        XCTAssertFalse(reminder.isUserInteractionEnabled)
        XCTAssertFalse(reminder.subviews.contains { $0 is UIButton })
        XCTAssertNil(auth.presentedViewController)
        XCTAssertFalse(model.checkBoxIsChecked)
        try await Task.sleep(nanoseconds:600_000_000)
        XCTAssertTrue(reminder.superview === auth.view)
        AliyunAuthGateway.showPrivacyReminderIfNeeded(for:unchecked,from:host,isChecked:{ false })
        XCTAssertEqual(auth.view.subviews.filter { $0 is OnekeyPrivacyReminderView }.count,1)
        XCTAssertTrue(auth.view.subviews.contains { $0 === reminder })
        try await Task.sleep(nanoseconds:550_000_000)
        XCTAssertNil(reminder.superview,"提示应在首次点击 1 秒后消失，重复点击不能延长展示")
        AliyunAuthGateway.showPrivacyReminderIfNeeded(for:unchecked,from:host,isChecked:{ false })
        XCTAssertEqual(auth.view.subviews.filter { $0 is OnekeyPrivacyReminderView }.count,1,"消失后再次点击仍可提示")
    }
    func testCheckedLoginAndOtherSDKEventsDoNotPresentReminder() throws {
        let previousWindow = UIApplication.shared.windows.first { $0.isKeyWindow }
        let scene = try XCTUnwrap(previousWindow?.windowScene)
        let window = UIWindow(windowScene:scene)
        let host = UIViewController(); window.rootViewController = host; window.makeKeyAndVisible()
        defer { window.isHidden = true; previousWindow?.makeKey() }
        for result: [String:Any] in [
            ["resultCode":"700002","isChecked":NSNumber(value:true)],
            ["resultCode":"700003","isChecked":NSNumber(value:false)],
            ["resultCode":"700004","isChecked":NSNumber(value:false)],
            ["resultCode":"600000"],
            ["resultCode":"700002"]] {
            AliyunAuthGateway.showPrivacyReminderIfNeeded(for:result,from:host,isChecked:{ true })
            XCTAssertNil(host.presentedViewController)
            XCTAssertFalse(host.view.subviews.contains { $0 is OnekeyPrivacyReminderView })
        }
        // 兼容 SDK 未携带 isChecked 的情况，使用 SDK 公开查询方法的返回值。
        AliyunAuthGateway.showPrivacyReminderIfNeeded(for:["resultCode":"700002"],from:host,isChecked:{ false })
        XCTAssertNotNil(host.view.subviews.first { $0 is OnekeyPrivacyReminderView })
    }
    #endif
}
