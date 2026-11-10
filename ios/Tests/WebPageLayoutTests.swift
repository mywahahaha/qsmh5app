import XCTest
import UIKit
@testable import QSMH5App

@MainActor final class WebPageLayoutTests: XCTestCase {
    func page(width:LayoutValue = .percent(1),height:LayoutValue = .percent(1),left:LayoutValue = .center,top:LayoutValue = .center) -> PageSpec {
        PageSpec(instance:"web_game",url:URL(string:"https://fixture.test/game")!,width:width,height:height,left:left,top:top)
    }
    func testDoubleHundredPercentCoversEveryEdgeIncludingSafeArea() {
        let bounds=CGRect(x:0,y:0,width:926,height:428), safe=CGRect(x:47,y:0,width:832,height:407)
        XCTAssertEqual(WebPageLayout.frame(for:page(),bounds:bounds,safeArea:safe),bounds)
        XCTAssertEqual(WebPageLayout.frame(for:page(left:.points(10),top:.points(20)),bounds:bounds,safeArea:safe),bounds)
        let portrait=CGRect(x:0,y:0,width:390,height:844)
        XCTAssertEqual(WebPageLayout.frame(for:page(),bounds:portrait,safeArea:portrait.insetBy(dx:0,dy:47)),portrait)
    }
    func testPartialPageStillUsesSafeAreaAndCentering() {
        let safe=CGRect(x:47,y:0,width:832,height:407)
        let frame=WebPageLayout.frame(for:page(width:.percent(0.8),height:.percent(0.6)),bounds:CGRect(x:0,y:0,width:926,height:428),safeArea:safe)
        XCTAssertEqual(frame.width,safe.width*0.8,accuracy:0.001); XCTAssertEqual(frame.midX,safe.midX,accuracy:0.001)
        XCTAssertEqual(frame.midY,safe.midY,accuracy:0.001); XCTAssertTrue(safe.contains(frame))
    }
    func testWebViewDisablesNativeDraggingZoomBounceAndIndicators() throws {
        let (coordinator,_,_,_)=CoreTests().make()
        let controller=try WebPageController(spec:page(),coordinator:coordinator); controller.loadViewIfNeeded()
        defer { controller.invalidate() }
        let scroll=controller.webView.scrollView
        XCTAssertFalse(scroll.isScrollEnabled); XCTAssertFalse(scroll.bounces)
        XCTAssertFalse(scroll.showsVerticalScrollIndicator); XCTAssertFalse(scroll.showsHorizontalScrollIndicator)
        XCTAssertEqual(scroll.contentInsetAdjustmentBehavior,.never); XCTAssertEqual(scroll.contentInset,.zero)
        XCTAssertFalse(scroll.automaticallyAdjustsScrollIndicatorInsets)
        XCTAssertFalse(controller.webView.allowsLinkPreview); XCTAssertFalse(controller.webView.allowsBackForwardNavigationGestures)
        XCTAssertFalse(scroll.pinchGestureRecognizer?.isEnabled ?? false)
    }
}
