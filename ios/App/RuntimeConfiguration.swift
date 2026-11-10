import Foundation

struct RuntimeConfiguration {
    let identity: GameIdentity
    let baseURL: URL
    let policy: URLPolicy
    let resetLocalState: Bool
    let initialGameURL: URL?

    static func load(bundle: Bundle = .main, environment: [String: String] = ProcessInfo.processInfo.environment,
                     arguments: [String] = ProcessInfo.processInfo.arguments) throws -> RuntimeConfiguration {
        let packageCode = ChannelCodeReader.read(bundle: bundle)
        #if LOCAL_TESTING
        let base = URL(string: "http://localhost:18740")!
        let scenarios: Set<String> = ["normal", "no-privacy", "init-error", "invalid-code", "token-rejected", "onekey", "delayed-login",
                                      "floatball", "floatball-edge", "floatball-hidden", "floatball-heartbeat", "background", "background-fail", "slow-page", "network-wait", "fullscreen", "update-offer", "update-force", "update-invalid"]
        let scenario = environment["QSM_LOCAL_SCENARIO"] ?? "normal"
        guard scenarios.contains(scenario) else { throw QSMError.invalid("未知本地测试场景") }
        return RuntimeConfiguration(identity: GameIdentity(gameID: Int(bundle.object(forInfoDictionaryKey: "QSMGameID") as? String ?? "") ?? 29,
            version: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.1", packageCode: packageCode),
            baseURL: base.appendingPathComponent("scenario/\(scenario)"), policy: URLPolicy(localOrigin: Origin(base)),
            resetLocalState: arguments.contains("-qsm-reset"), initialGameURL: nil)
        #else
        guard let raw = bundle.object(forInfoDictionaryKey: "QSMGameID") as? String, let gameID = Int(raw), gameID > 0,
              let version = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
              version.range(of: "^[0-9]+\\.[0-9]+\\.[0-9]+$", options: .regularExpression) != nil else {
            throw QSMError.invalid("请先填写正确的游戏身份。详见 iOS 联调指南。")
        }
        return RuntimeConfiguration(identity: GameIdentity(gameID: gameID, version: version, packageCode: packageCode),
            baseURL: URL(string: "https://box.74meng.com")!, policy: URLPolicy(localOrigin: nil), resetLocalState: false,
            initialGameURL: (bundle.object(forInfoDictionaryKey: "QSMInitialGameURL") as? String).flatMap(URL.init(string:)))
        #endif
    }
}
