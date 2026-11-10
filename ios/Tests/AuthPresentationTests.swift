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
            viewport: { (CGRect(x:0,y:0,width:926,height:428),UIEdgeInsets(top:0,left:47,bottom:21,right:47)) },
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
        XCTAssertEqual(panel.width,(926-94-24)*0.6,accuracy:0.01)
        XCTAssertEqual(panel.height,(428-21-24)*0.75,accuracy:0.01)
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
    #endif
}
