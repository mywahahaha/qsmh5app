import Foundation
import CoreFoundation

enum QSMError: LocalizedError, Equatable {
    case invalid(String)
    case business(Int, String)
    case storage(String)
    case cancelled
    var errorDescription: String? {
        switch self {
        case .invalid(let message), .storage(let message): return message
        case .business(_, let message): return message
        case .cancelled: return "操作已取消"
        }
    }
}

enum JSON {
    static func object(_ value: Any?, _ name: String) throws -> [String: Any] {
        guard let value = value as? [String: Any] else { throw QSMError.invalid("\(name) 必须为对象") }
        return value
    }
    static func integer(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.isFinite, number.doubleValue.rounded() == number.doubleValue,
              let result = Int(number.stringValue) else { return nil }
        return result
    }
    static func boolean(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }
    static func text(_ value: Any?, _ name: String) throws -> String {
        guard let value = value as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw QSMError.invalid("\(name) 缺失或为空")
        }
        return value
    }
    static func envelope(_ data: Data) throws -> [String: Any] {
        let root = try object(JSONSerialization.jsonObject(with: data), "响应")
        guard let code = integer(root["code"]) else { throw QSMError.invalid("响应缺少整数 code") }
        if code != 0 {
            let message = (root["msg"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "接口返回 code=\(code)"
            throw QSMError.business(code, message)
        }
        return root
    }
}

struct Origin: Hashable {
    let scheme: String
    let host: String
    let port: Int
    init?(_ url: URL) {
        guard let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = url.host?.lowercased(), url.user == nil, url.password == nil else { return nil }
        self.scheme = scheme; self.host = host; self.port = url.port ?? (scheme == "https" ? 443 : 80)
    }
}

enum LayoutValue: Equatable {
    case points(Double), percent(Double), center
    static func parse(_ value: Any?, axis: Bool) throws -> LayoutValue {
        if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() {
            let n = number.doubleValue
            guard n.isFinite, axis || n > 0 else { throw QSMError.invalid("布局数值无效") }
            return .points(n)
        }
        guard let text = value as? String else { throw QSMError.invalid("布局字段缺失或类型错误") }
        if text == "center", axis { return .center }
        if text.hasSuffix("%"), let n = Double(text.dropLast()), n.isFinite, n >= 0, n <= 100, axis || n > 0 {
            return .percent(n / 100)
        }
        if let n = Double(text), n.isFinite, axis || n > 0 { return .points(n) }
        throw QSMError.invalid("布局字段格式错误")
    }
    func resolve(in total: Double, length: Double = 0) -> Double {
        switch self {
        case .points(let n): return n
        case .percent(let n): return total * n
        case .center: return (total - length) / 2
        }
    }
}

struct PageSpec {
    let instance: String
    let url: URL
    let width: LayoutValue
    let height: LayoutValue
    let left: LayoutValue
    let top: LayoutValue
}

struct AuthConfiguration {
    let enabled: Bool
    let secret: String?
    let exchangeURL: URL?
    let timeout: Double
    let raw: [String: Any]
    static let disabled = AuthConfiguration(enabled: false, secret: nil, exchangeURL: nil, timeout: 5, raw: [:])
}

struct UpdateOffer {
    let title: String
    let detail: String
    let version: String
    let time: String
    let url: URL
    let force: Bool
    let width: UpdateDimension
    let height: UpdateDimension?
}

enum UpdateDimension: Equatable {
    case points(Double)
    case percent(Double)

    func resolve(in total: CGFloat) -> CGFloat {
        switch self {
        case .points(let value): return CGFloat(value)
        case .percent(let value): return total * CGFloat(value)
        }
    }
}

enum UpdatePolicy {
    static func offer(_ value: Any?) -> UpdateOffer? {
        guard let raw = value as? [String: Any], JSON.integer(raw["open"]) == 1 else { return nil }
        func flag(_ name: String) -> Bool? {
            guard let value = JSON.integer(raw[name]), value == 0 || value == 1 else { return nil }
            return value == 1
        }
        func dimension(_ value: Any?) -> UpdateDimension? {
            if let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() {
                let points = n.doubleValue
                guard points.isFinite, points > 0 else { return nil }
                return .points(points)
            }
            guard let raw = value as? String else { return nil }
            let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if text.hasSuffix("%") {
                guard let number = Double(text.dropLast()), number.isFinite, number > 0, number <= 100 else { return nil }
                return .percent(number / 100)
            }
            guard let points = Double(text), points.isFinite, points > 0 else { return nil }
            return .points(points)
        }
        guard let force = flag("force"), (raw["popup"] == nil || flag("popup") != nil), let width = dimension(raw["width"]),
              let title = raw["title"] as? String, let detail = raw["desc"] as? String,
              let version = raw["version"] as? String, let time = raw["time"] as? String,
              let urlText = raw["url"] as? String,
              let url = URL(string: urlText.trimmingCharacters(in: .whitespacesAndNewlines)),
              ["http", "https"].contains(url.scheme?.lowercased() ?? ""), url.host != nil,
              url.user == nil, url.password == nil,
              url.pathExtension.lowercased() != "apk" else { return nil }
        let height: UpdateDimension?
        if let rawHeight = raw["height"] as? String,
           rawHeight.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "auto" {
            height = nil
        } else {
            guard let parsed = dimension(raw["height"]) else { return nil }
            height = parsed
        }
        return UpdateOffer(title: title, detail: detail, version: version, time: time, url: url,
                           force: force, width: width, height: height)
    }
}

struct InitConfiguration {
    let privacyAuto: Bool
    let backgroundURL: URL?
    let pages: [String: PageSpec]
    let jumpHosts: Set<String>
    let auth: AuthConfiguration
    let updateOffer: UpdateOffer?
    let floatingBall: FloatingBallConfiguration?
    let popupInterval: FloatingBallPollingConfiguration?
    init(privacyAuto: Bool, backgroundURL: URL? = nil, pages: [String: PageSpec], jumpHosts: Set<String>, auth: AuthConfiguration, updateOffer: UpdateOffer?,
         floatingBall: FloatingBallConfiguration? = nil, popupInterval: FloatingBallPollingConfiguration? = nil) {
        self.backgroundURL = backgroundURL; self.privacyAuto = privacyAuto; self.pages = pages; self.jumpHosts = jumpHosts; self.auth = auth; self.updateOffer = updateOffer
        self.floatingBall = floatingBall; self.popupInterval = popupInterval
    }
    static func parse(_ data: Data, policy: URLPolicy) throws -> InitConfiguration {
        let root = try JSON.envelope(data)
        let body = try JSON.object(root["data"], "data")
        let privacy: Bool
        if let value = body["privacyAuto"] {
            guard let parsed = JSON.boolean(value) else { throw QSMError.invalid("privacyAuto 必须为布尔值") }
            privacy = parsed
        } else { privacy = false }
        let specs = try JSON.object(body["h5Pages"], "h5Pages")
        let expected = ["agreement": "web_agreement", "login": "web_login", "game": "web_game", "welfare": "web_welfare"]
        var pages: [String: PageSpec] = [:]
        for (key, instance) in expected {
            guard let raw = specs[key] else {
                if key == "welfare" { continue }
                throw QSMError.invalid("缺少 \(key) 页面")
            }
            let item = try JSON.object(raw, key)
            guard try JSON.text(item["instance"], "instance") == instance else { throw QSMError.invalid("页面实例名错误") }
            guard let url = URL(string: try JSON.text(item["url"], "url")), policy.allows(url) else {
                throw QSMError.invalid("页面 URL 未获准")
            }
            pages[instance] = PageSpec(instance: instance, url: url,
                width: try LayoutValue.parse(item["width"], axis: false), height: try LayoutValue.parse(item["height"], axis: false),
                left: try LayoutValue.parse(item["left"], axis: true), top: try LayoutValue.parse(item["top"], axis: true))
        }
        let jumps: Set<String>
        if let raw = body["jumpDomain"] {
            guard let strings = raw as? [String] else { throw QSMError.invalid("jumpDomain 必须为字符串数组") }
            guard strings.allSatisfy({ !$0.isEmpty && $0 == $0.trimmingCharacters(in: .whitespacesAndNewlines) &&
                URL(string: "https://" + $0)?.host?.lowercased() == $0.lowercased() && !$0.contains("/") && !$0.contains(":") && !$0.contains("@") }) else {
                throw QSMError.invalid("jumpDomain 包含无效主机")
            }
            jumps = Set(strings.map { $0.lowercased() })
        } else { jumps = [] }
        // 可选认证配置不影响基础 H5 登录；只有 open=1 才解析子配置。
        var auth = AuthConfiguration.disabled
        if let raw = body["aliyunOnekeyConfig"] as? [String: Any], JSON.integer(raw["open"]) == 1 {
            let config = raw["config"] as? [String: Any] ?? [:]
            let secret = (config["scheme_secret"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            let exchange = (config["exchange_url"] as? String).flatMap(URL.init(string:)).flatMap { policy.allows($0) ? $0 : nil }
            let millis = JSON.integer(config["launch_total_timeout_ms"]) ?? 5000
            auth = AuthConfiguration(enabled: true, secret: secret, exchangeURL: exchange,
                                     timeout: Double(min(max(millis, 1000), 30000)) / 1000, raw: config)
        }
        let backgroundText = (body["bgImage"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let backgroundURL = backgroundText.isEmpty ? nil : URL(string: backgroundText).flatMap { policy.allows($0) ? $0 : nil }
        return InitConfiguration(privacyAuto: privacy, backgroundURL: backgroundURL, pages: pages, jumpHosts: jumps, auth: auth,
                                 updateOffer: UpdatePolicy.offer(body["updateInfo"]),
                                 floatingBall: try body["floatball"].map { try FloatingBallConfiguration.parse($0, policy: policy) },
                                 popupInterval: FloatingBallPollingConfiguration.parse(body["popupInterval"], policy: policy))
    }
}

struct URLPolicy {
    let localOrigin: Origin?
    func allows(_ url: URL) -> Bool {
        guard let origin = Origin(url) else { return false }
        return origin.scheme == "https" || origin == localOrigin
    }
    func accepts(source: URL, trusted: URL, isMainFrame: Bool) -> Bool {
        isMainFrame && allows(source) && Origin(source) == Origin(trusted)
    }
}

enum ScreenMode: String, Equatable {
    case horizontal
    case vertical
}

struct GameIdentity {
    let gameID: Int
    let version: String
    let packageCode: String
    func headers(consented: Bool, deviceCode: String, token: String, notchScreen: Bool = false,
                 screenMode: ScreenMode = .vertical) -> [String: String] {
        ["Gameid": String(gameID), "Gameversion": version, "Packagecode": packageCode,
         "Devicetype": "ios", "Devicecode": consented ? deviceCode : "", "Accountoken": consented ? token : "",
         "Notchscreen": notchScreen ? "1" : "0", "Screenmode": screenMode.rawValue]
    }
}

struct BridgeResult {
    let code: Int
    let message: String
    let data: Any
    var object: [String: Any] { ["code": code, "msg": message, "data": data] }
    static func ok(_ data: Any = [String: Any]()) -> BridgeResult { BridgeResult(code: 0, message: "ok", data: data) }
    static func failure(_ code: Int, _ message: String, data: Any = NSNull()) -> BridgeResult {
        BridgeResult(code: code, message: message, data: data)
    }
}
