import XCTest
import UIKit
@testable import QSMH5App

@MainActor final class OnekeyAppearanceTests: XCTestCase {
    func raw(ui: [String:Any] = [:]) -> [String:Any] {
        ["ui":ui,"app_protocols":[["name":"《用户协议》","url":"https://fixture.test/user"],["name":"《隐私政策》","url":"https://fixture.test/privacy"]]]
    }
    func testConfigurationDefaultsClampingAndInvalidTypes() throws {
        let style = try OnekeyAuthStyle(raw:raw())
        XCTAssertEqual(style.widthPercent,60); XCTAssertEqual(style.heightPercent,75)
        let clamped = try OnekeyAuthStyle(raw:raw(ui:["dialog_width_percent":0,"dialog_height_percent":120,"title":"不应显示","primary_color":"#000000"]))
        XCTAssertEqual(clamped.widthPercent,1); XCTAssertEqual(clamped.heightPercent,100)
        for bad: Any in [true,"60",NSNull(),10.2] {
            XCTAssertThrowsError(try OnekeyAuthStyle(raw:raw(ui:["dialog_width_percent":bad])))
        }
        XCTAssertThrowsError(try OnekeyAuthStyle(raw:["ui":[],"app_protocols":[]]))
        var invalid = raw(); invalid["app_protocols"] = [["name":"隐私","url":"https://user:pass@fixture.test/privacy"]]
        XCTAssertThrowsError(try OnekeyAuthStyle(raw:invalid))
    }
    func testLandscapeLivePercentagesPortraitAndCompactNeverOverlap() throws {
        for (size,insets,w,h) in [
            (CGSize(width:926,height:428),UIEdgeInsets(top:0,left:47,bottom:21,right:47),50,70),
            (CGSize(width:844,height:390),UIEdgeInsets(top:0,left:47,bottom:21,right:47),60,75),
            (CGSize(width:736,height:414),UIEdgeInsets.zero,50,70),
            (CGSize(width:390,height:844),UIEdgeInsets(top:47,left:0,bottom:34,right:0),90,65)] {
            let style = try OnekeyAuthStyle(raw:raw(ui:["dialog_width_percent":w,"dialog_height_percent":h]))
            let panel = style.panel(in:CGRect(origin:.zero,size:size),safeArea:insets)
            let layout = try OnekeyAuthLayout.make(size:panel.size,style:style)
            let frames = [layout.number,layout.slogan,layout.login,layout.change,layout.privacy]
            for (a,b) in zip(frames,frames.dropFirst()) { XCTAssertLessThanOrEqual(a.maxY,b.minY) }
            XCTAssertGreaterThanOrEqual(layout.numberFont.pointSize,20); XCTAssertGreaterThanOrEqual(layout.login.height,44)
            XCTAssertLessThanOrEqual(layout.privacy.maxY,panel.height-8)
            XCTAssertTrue(CGRect(origin:.zero,size:size).inset(by:insets).contains(panel))
        }
    }
    func testTinyAndExcessivelyLongProtocolCannotRenderClipped() throws {
        let style = try OnekeyAuthStyle(raw:raw(ui:["dialog_width_percent":1,"dialog_height_percent":1]))
        XCTAssertThrowsError(try OnekeyAuthLayout.make(size:CGSize(width:8,height:3),style:style))
        var value = raw(); value["app_protocols"] = [["name":String(repeating:"协议",count:80),"url":"https://fixture.test/privacy"]]
        XCTAssertThrowsError(try OnekeyAuthLayout.make(size:CGSize(width:300,height:250),style:OnekeyAuthStyle(raw:value)))
    }
    func testHeaderUsesBrandAssetAndCloseIsSingleAction() throws {
        var closes = 0; let header = OnekeyBrandHeaderView { closes += 1 }
        header.frame = CGRect(x:0,y:0,width:400,height:OnekeyBrand.headerHeight); header.layoutIfNeeded()
        header.close.sendActions(for:.touchUpInside)
        XCTAssertEqual(closes,1); XCTAssertEqual(header.close.bounds.size,CGSize(width:44,height:44))
        XCTAssertNotNil(Bundle.main.url(forResource:"box_logo",withExtension:"png",subdirectory:"AuthBrand"))
        XCTAssertEqual(header.bounds.height,53.7,accuracy:0.01)
        XCTAssertEqual(header.logo.bounds.width,112.2,accuracy:0.01)
        XCTAssertEqual(header.logo.bounds.height,37.4,accuracy:0.01)
        XCTAssertEqual(header.close.frame.minX,351,accuracy:0.01)
        XCTAssertEqual(OnekeyBrand.closeImage().size,CGSize(width:20,height:20))
        XCTAssertEqual(header.logo.frame.midY,header.bounds.midY,accuracy:0.01)
        XCTAssertEqual(header.close.frame.midY,header.bounds.midY,accuracy:0.01)
        XCTAssertEqual(header.close.imageEdgeInsets.top,header.close.imageEdgeInsets.bottom)
    }
    func testNativeBrandLayoutPreviews() throws {
        for (name,size,insets,w,h) in [
            ("横屏_现网50乘70",CGSize(width:926,height:428),UIEdgeInsets(top:0,left:47,bottom:21,right:47),50,70),
            ("横屏_紧凑60乘75",CGSize(width:844,height:390),UIEdgeInsets(top:0,left:47,bottom:21,right:47),60,75),
            ("竖屏_90乘65",CGSize(width:390,height:844),UIEdgeInsets(top:47,left:0,bottom:34,right:0),90,65)] {
            let style = try OnekeyAuthStyle(raw:raw(ui:["dialog_width_percent":w,"dialog_height_percent":h]))
            let root = UIView(frame:CGRect(origin:.zero,size:size)); root.backgroundColor = UIColor(white:0.18,alpha:1)
            let caption = UILabel(frame:CGRect(x:20,y:0,width:size.width-40,height:30)); caption.text="布局测试预览 · 虚构号码，非真实取号截图"; caption.textColor = .white; caption.font = .systemFont(ofSize:12); root.addSubview(caption)
            let panel = UIView(frame:style.panel(in:root.bounds,safeArea:insets)); panel.backgroundColor=OnekeyBrand.panel; panel.layer.cornerRadius=14; panel.clipsToBounds=true; root.addSubview(panel)
            let layout = try OnekeyAuthLayout.make(size:panel.bounds.size,style:style)
            let header = OnekeyBrandHeaderView {}; header.frame=CGRect(x:0,y:0,width:panel.bounds.width,height:OnekeyBrand.headerHeight); panel.addSubview(header)
            func label(_ text:String,_ frame:CGRect,_ font:UIFont,_ color:UIColor) {
                let v=UILabel(frame:frame); v.text=text; v.font=font; v.textColor=color; v.textAlignment = .center; panel.addSubview(v)
            }
            label("138****8000",layout.number,layout.numberFont,OnekeyBrand.number)
            label("中国移动提供认证服务",layout.slogan,.systemFont(ofSize:12),OnekeyBrand.secondary)
            let login=UIButton(type:.custom); login.frame=layout.login; login.setTitle(style.loginText,for:.normal); login.titleLabel?.font = .systemFont(ofSize:16); login.setBackgroundImage(OnekeyBrand.buttonImage(OnekeyBrand.orange),for:.normal); panel.addSubview(login)
            label(style.otherLoginText,layout.change,.systemFont(ofSize:13),OnekeyBrand.text)
            let check=UIImageView(image:OnekeyBrand.checkbox(checked:false)); check.frame=CGRect(x:layout.privacy.minX+15,y:layout.privacy.minY+15,width:14,height:14); panel.addSubview(check)
            let privacy=UILabel(frame:CGRect(x:layout.privacy.minX+48,y:layout.privacy.minY,width:layout.privacy.width-48,height:layout.privacy.height)); privacy.numberOfLines=0; privacy.font = .systemFont(ofSize:12)
            let copy=NSMutableAttributedString(string:"我已阅读并同意",attributes:[.foregroundColor:OnekeyBrand.text])
            copy.append(NSAttributedString(string:"《中国移动认证服务条款》和《用户协议》、《隐私政策》",attributes:[.foregroundColor:UIColor.blue])); privacy.attributedText=copy; panel.addSubview(privacy)
            root.layoutIfNeeded(); header.layoutIfNeeded()
            let image=UIGraphicsImageRenderer(size:size).image { context in root.layer.render(in:context.cgContext) }
            let attachment=XCTAttachment(image:image); attachment.name="授权品牌布局_"+name; attachment.lifetime = .keepAlways; add(attachment)
        }
    }
}
