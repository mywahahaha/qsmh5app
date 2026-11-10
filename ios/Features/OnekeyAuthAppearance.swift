import UIKit

struct OnekeyProtocol {
    let name: String
    let url: String
}

struct OnekeyAuthStyle {
    let widthPercent: CGFloat
    let heightPercent: CGFloat
    let loginText: String
    let otherLoginText: String
    let protocols: [OnekeyProtocol]

    init(raw: [String: Any]) throws {
        let ui: [String: Any]
        if let value = raw["ui"] { ui = try JSON.object(value, "一键登录 UI") } else { ui = [:] }
        func percent(_ key: String, _ fallback: CGFloat) throws -> CGFloat {
            guard let value = ui[key] else { return fallback }
            guard let n = JSON.integer(value) else { throw QSMError.invalid("一键登录比例必须为整数") }
            return CGFloat(min(100, max(1, n)))
        }
        func text(_ key: String, _ fallback: String) throws -> String {
            guard let value = ui[key] else { return fallback }
            let result = try JSON.text(value, key)
            guard result.count <= 40 else { throw QSMError.invalid("一键登录按钮文字过长") }
            return result
        }
        widthPercent = try percent("dialog_width_percent", 60)
        heightPercent = try percent("dialog_height_percent", 75)
        loginText = try text("login_text", "手机号一键登录")
        otherLoginText = try text("other_login_text", "使用其他方式登录")
        guard let items = raw["app_protocols"] as? [[String: Any]], (1...2).contains(items.count) else {
            throw QSMError.invalid("一键登录需要一至两项业务协议")
        }
        protocols = try items.map {
            let name = try JSON.text($0["name"], "协议名称")
            let rawURL = try JSON.text($0["url"], "协议地址")
            guard name.count <= 200, let url = URL(string: rawURL), Origin(url)?.scheme == "https" else {
                throw QSMError.invalid("一键登录业务协议无效")
            }
            return OnekeyProtocol(name: name, url: rawURL)
        }
        guard Set(protocols.map(\.name)).count == protocols.count else { throw QSMError.invalid("一键登录协议名称重复") }
    }
    func panel(in bounds: CGRect, safeArea: UIEdgeInsets) -> CGRect {
        let available = bounds.inset(by: safeArea).insetBy(dx: 12, dy: 12)
        let width = max(1, available.width * widthPercent / 100)
        let height = max(1, available.height * heightPercent / 100)
        return CGRect(x: available.midX - width / 2, y: available.midY - height / 2, width: width, height: height)
    }
}

@MainActor enum OnekeyBrand {
    static let red = UIColor(red: 197/255, green: 45/255, blue: 40/255, alpha: 1)
    static let panel = UIColor(white: 238/255, alpha: 1)
    static let orange = UIColor(red: 1, green: 102/255, blue: 0, alpha: 1)
    static let text = UIColor(white: 102/255, alpha: 1)
    static let number = UIColor(white: 85/255, alpha: 1)
    static let secondary = UIColor(white: 153/255, alpha: 1)
    static let headerHeight: CGFloat = 53.7
    static func image(size: CGSize, draw: (CGRect) -> Void) -> UIImage {
        UIGraphicsImageRenderer(size: size).image { _ in draw(CGRect(origin: .zero, size: size)) }
    }
    static func buttonImage(_ color: UIColor) -> UIImage {
        image(size: CGSize(width: 12, height: 12)) { rect in
            color.setFill(); UIBezierPath(roundedRect: rect, cornerRadius: 5).fill()
        }.resizableImage(withCapInsets: UIEdgeInsets(top: 5, left: 5, bottom: 5, right: 5))
    }
    static func checkbox(checked: Bool) -> UIImage {
        image(size: CGSize(width: 14, height: 14)) { rect in
            let path = UIBezierPath(roundedRect: rect.insetBy(dx: 0.5, dy: 0.5), cornerRadius: 3)
            (checked ? orange : .white).setFill(); path.fill()
            (checked ? orange : UIColor(white: 0.65, alpha: 1)).setStroke(); path.lineWidth = 1; path.stroke()
            if checked {
                let tick = UIBezierPath(); tick.move(to: CGPoint(x: 3, y: 7)); tick.addLine(to: CGPoint(x: 6, y: 10)); tick.addLine(to: CGPoint(x: 11, y: 4))
                UIColor.white.setStroke(); tick.lineWidth = 1.6; tick.lineCapStyle = .round; tick.lineJoinStyle = .round; tick.stroke()
            }
        }
    }
    static func closeImage() -> UIImage {
        image(size: CGSize(width: 20, height: 20)) { _ in
            let p = UIBezierPath(); p.move(to: CGPoint(x: 4, y: 4)); p.addLine(to: CGPoint(x: 16, y: 16))
            p.move(to: CGPoint(x: 16, y: 4)); p.addLine(to: CGPoint(x: 4, y: 16))
            UIColor.white.setStroke(); p.lineWidth = 1.3; p.stroke()
        }
    }
    static func backImage() -> UIImage {
        image(size: CGSize(width: 24, height: 24)) { _ in
            let p = UIBezierPath(); p.move(to: CGPoint(x: 15, y: 4)); p.addLine(to: CGPoint(x: 7, y: 12)); p.addLine(to: CGPoint(x: 15, y: 20))
            UIColor.white.setStroke(); p.lineWidth = 1.7; p.lineCapStyle = .round; p.stroke()
        }
    }
}

@MainActor final class OnekeyBrandHeaderView: UIView {
    private let shape = CAShapeLayer()
    let logo = UIImageView()
    let close = UIButton(type: .custom)
    init(onClose: @escaping () -> Void) {
        super.init(frame: .zero)
        layer.addSublayer(shape); shape.fillColor = OnekeyBrand.red.cgColor
        logo.image = Bundle.main.url(forResource: "box_logo", withExtension: "png", subdirectory: "AuthBrand").flatMap { UIImage(contentsOfFile: $0.path) }
        logo.contentMode = .scaleAspectFit; logo.accessibilityLabel = "骑士盟"; addSubview(logo)
        close.setImage(OnekeyBrand.closeImage(), for: .normal); close.accessibilityLabel = "关闭一键登录"
        close.imageEdgeInsets = UIEdgeInsets(top:0,left:10,bottom:0,right:0)
        close.addAction(UIAction { _ in onClose() }, for: .touchUpInside); addSubview(close)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width, h = bounds.height, r: CGFloat = min(14, h/2)
        let p = UIBezierPath(); p.move(to: CGPoint(x: r, y: 0)); p.addLine(to: CGPoint(x: w-r, y: 0))
        p.addQuadCurve(to: CGPoint(x: w, y: r), controlPoint: CGPoint(x: w, y: 0)); p.addLine(to: CGPoint(x: w, y: h))
        p.addQuadCurve(to: CGPoint(x: 0, y: h), controlPoint: CGPoint(x: w/2, y: h*0.62)); p.addLine(to: CGPoint(x: 0, y: r))
        p.addQuadCurve(to: CGPoint(x: r, y: 0), controlPoint: .zero); p.close()
        shape.frame = bounds; shape.path = p.cgPath
        logo.frame = CGRect(x: 10, y: (h-37.4)/2, width: 112.2, height: 37.4)
        close.frame = CGRect(x: w-49, y: (h-44)/2, width: 44, height: 44)
    }
}

@MainActor struct OnekeyAuthLayout {
    let number: CGRect
    let slogan: CGRect
    let login: CGRect
    let change: CGRect
    let privacy: CGRect
    let numberFont: UIFont
    let compact: Bool

    static func make(size: CGSize, style: OnekeyAuthStyle, fixedNumberSize: CGFloat? = nil) throws -> Self {
        let margin = max(16, size.width * 0.09), width = size.width - 2*margin
        guard width > 100, size.height.isFinite, size.width.isFinite else { throw QSMError.invalid("授权窗口过小") }
        let paragraph = NSMutableParagraphStyle(); paragraph.lineSpacing = 2
        // 按三家运营商条款最长换行预留；最终仍使用 SDK 实际协议框检查，绝不替换真实条款。
        let carrierNames = ["《中国移动认证服务条款》", "《天翼账号服务与隐私协议》", "《联通统一认证服务条款》"]
        let privacyHeight = max(44, carrierNames.map { carrier -> CGFloat in
            let text = "我已阅读并同意" + carrier + "和" + style.protocols.map(\.name).joined(separator: "、")
            let rect = (text as NSString).boundingRect(with: CGSize(width: max(1, width-48), height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: UIFont.systemFont(ofSize: 12), .paragraphStyle: paragraph], context: nil)
            return ceil(rect.height) + 4
        }.max() ?? 44)
        for compact in [false, true] {
            let font = UIFont.systemFont(ofSize: fixedNumberSize ?? (compact ? 20 : 28))
            // SDK 号码 frame block 只接受 x/y；额外保留其原生号码栏留白，避免按纯字高挤压说明。
            let numberH = max(40,ceil(font.lineHeight)), sloganH = ceil(UIFont.systemFont(ofSize: 12).lineHeight)
            let bottom: CGFloat = compact ? 8 : 16
            let gaps: [CGFloat] = compact ? [2,2,2,2,2] : [8,4,16,8,8]
            let privacyY = size.height-bottom-privacyHeight
            let required = OnekeyBrand.headerHeight + gaps.reduce(0,+) + numberH + sloganH + 44 + 44
            guard required <= privacyY else { continue }
            let extra = min(16, (privacyY-required)/3)
            let n = CGRect(x: margin, y: OnekeyBrand.headerHeight+gaps[0]+extra, width: width, height: numberH)
            let s = CGRect(x: margin, y: n.maxY+gaps[1], width: width, height: sloganH)
            let l = CGRect(x: margin, y: s.maxY+gaps[2]+extra, width: width, height: 44)
            let c = CGRect(x: margin, y: l.maxY+gaps[3], width: width, height: 44)
            guard (style.loginText as NSString).size(withAttributes: [.font:UIFont.systemFont(ofSize:16)]).width <= width-12,
                  (style.otherLoginText as NSString).size(withAttributes: [.font:UIFont.systemFont(ofSize:13)]).width <= width-8 else { continue }
            return Self(number:n,slogan:s,login:l,change:c,privacy:CGRect(x:margin,y:privacyY,width:width,height:privacyHeight),numberFont:font,compact:compact)
        }
        throw QSMError.invalid("授权窗口无法完整显示操作和协议，请使用 H5 登录")
    }
}
