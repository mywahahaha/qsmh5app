import Foundation
import CoreFoundation

struct FloatingBallConfiguration: Equatable {
    let show: Bool
    let imageDefault: URL
    let imageActive: URL
    let width: Int
    let height: Int
    let left: Int?
    let right: Int?
    let top: Double?
    let bottom: Double?

    static func parse(_ value: Any, policy: URLPolicy) throws -> FloatingBallConfiguration {
        let raw = try JSON.object(value, "floatball")
        func integer(_ key: String, required: Bool = false) throws -> Int? {
            guard let value = raw[key] else {
                if required { throw QSMError.invalid("floatball.\(key) 缺失") }
                return nil
            }
            guard let n = JSON.integer(value) else { throw QSMError.invalid("floatball.\(key) 必须为整数") }
            return n
        }
        func offset(_ key: String) throws -> Double? {
            guard let value = raw[key] else { return nil }
            guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite, n.doubleValue >= 0 else {
                throw QSMError.invalid("floatball.\(key) 必须为非负有限数值")
            }
            return n.doubleValue
        }
        func image(_ key: String) throws -> URL {
            guard let url = URL(string: try JSON.text(raw[key], "floatball.\(key)")), policy.allows(url) else {
                throw QSMError.invalid("floatball.\(key) 图片地址未获准")
            }
            return url
        }
        let width = try integer("width", required: true)!, height = try integer("height", required: true)!
        guard width > 0, height > 0 else { throw QSMError.invalid("floatball 尺寸无效") }
        let show = raw["show"] == nil ? false : JSON.boolean(raw["show"])
        guard let show = show else { throw QSMError.invalid("floatball.show 必须为布尔值") }
        let top = try offset("top")
        return try FloatingBallConfiguration(show: show, imageDefault: image("image_default"), imageActive: image("image_active"),
            width: width, height: height, left: integer("left"), right: integer("right"), top: top,
            bottom: top == nil ? offset("bottom") : nil)
    }
}

struct FloatingBallState: Equatable {
    let visible: Bool
    let active: Bool
    static let hidden = FloatingBallState(visible: false, active: false)
}

struct FloatingBallController {
    let initiallyEnabled: Bool
    private(set) var enabled: Bool
    private(set) var active = false
    var sessionReady = false
    var welfareAboveGame = false
    init(initiallyEnabled: Bool) { self.initiallyEnabled = initiallyEnabled; self.enabled = initiallyEnabled }
    var state: FloatingBallState { FloatingBallState(visible: enabled && sessionReady && !welfareAboveGame, active: active) }
    mutating func setRuntimeState(show: Bool, active: Bool) { enabled = show; self.active = active }
    mutating func resetSession() { enabled = initiallyEnabled; active = false; sessionReady = false; welfareAboveGame = false }
}

enum FloatingBallLayout {
    // Android dp 在 iOS 映射为 pt；只对水平偏移沿用 360 宽设计基准。
    static func frame(configuration c: FloatingBallConfiguration, width: Double, height: Double) -> CGRect {
        let w = max(0, width), h = max(0, height)
        guard w > 0, h > 0 else { return .zero }
        let ballWidth = min(w, Double(c.width)), ballHeight = min(h, Double(c.height))
        let x = c.left.map { Double($0) * w / 360 } ?? (w - Double(c.right ?? 0) * w / 360 - ballWidth)
        let offset = c.top ?? c.bottom ?? 0
        let distance = offset > 0 && offset < 1 ? offset * h : offset
        let y = c.top != nil || c.bottom == nil ? distance : h - ballHeight - distance
        return CGRect(x: floor(min(max(0, x), w - ballWidth)), y: floor(min(max(0, y), h - ballHeight)),
                      width: floor(ballWidth), height: floor(ballHeight))
    }
}

struct FloatingBallPresentation {
    let configuration: FloatingBallConfiguration?
    let state: FloatingBallState
}

struct FloatingBallPollingConfiguration {
    let url: URL
    let interval: Int
    static func parse(_ value: Any?, policy: URLPolicy) -> FloatingBallPollingConfiguration? {
        guard let raw = value as? [String: Any], let text = raw["url"] as? String,
              let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)), policy.allows(url),
              let interval = JSON.integer(raw["interval"]), interval > 0 else { return nil }
        return FloatingBallPollingConfiguration(url: url, interval: interval)
    }
    static func state(from data: Data) throws -> (show: Bool, active: Bool)? {
        let root = try JSON.envelope(data)
        guard let body = root["data"] as? [String: Any], let raw = body["floating_ball"] as? [String: Any],
              let show = JSON.boolean(raw["show"]), let active = JSON.boolean(raw["active"]) else { return nil }
        return (show, active)
    }
}
