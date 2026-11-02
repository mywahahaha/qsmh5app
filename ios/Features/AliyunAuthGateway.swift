#if QSM_ALIYUN
import UIKit
import ATAuthSDK

// 首期只接通官方 SDK 生命周期与凭证边界；品牌弹窗完整视觉另行验收。
@MainActor final class AliyunAuthGateway: AuthGateway {
    var presenter: (() -> UIViewController?)?
    var available: Bool { true }
    private var handler: TXCommonHandler?
    private var continuation: CheckedContinuation<String, Error>?
    private var flowID: UUID?
    private var timeoutTask: Task<Void, Never>?
    private var deliveredPresentation = false
    private var receivedPresentation = false
    private var pendingToken: String?

    func credential(configuration: AuthConfiguration, presented: @escaping @MainActor () async -> Void) async throws -> String {
        guard continuation == nil, let host = presenter?(), host.viewIfLoaded?.window != nil,
              let secret = configuration.secret else { throw QSMError.invalid("一键登录宿主或配置不可用") }
        let protocols = configuration.raw["app_protocols"] as? [[String: Any]] ?? []
        guard !protocols.isEmpty, protocols.count <= 2,
              protocols.allSatisfy({ ($0["name"] as? String)?.isEmpty == false &&
                  ($0["url"] as? String).flatMap(URL.init(string:))?.scheme == "https" }) else {
            throw QSMError.invalid("一键登录需要有效的业务协议")
        }
        let id = UUID(); flowID = id; deliveredPresentation = false; receivedPresentation = false; pendingToken = nil
        let sdk = TXCommonHandler.sharedInstance(); handler = sdk
        sdk.getReporter().setConsolePrintLoggerEnable(false)
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                timeoutTask = Task { [weak self] in
                    do { try await Task.sleep(nanoseconds: UInt64(configuration.timeout + 30) * 1_000_000_000) } catch { return }
                    guard self?.flowID == id else { return }
                    self?.finish(.failure(QSMError.invalid("认证流程超时")))
                }
                sdk.setAuthSDKInfo(secret) { [weak self, weak host] result in
                    Task { @MainActor in
                        guard let self = self, self.flowID == id, let host = host else { return }
                        guard (result["resultCode"] as? String) == "600000" else {
                            self.finish(.failure(QSMError.invalid("认证方案初始化失败"))); return
                        }
                        let model = TXCustomModel()
                        model.checkBoxIsChecked = false
                        model.navTitle = NSAttributedString(string: "手机快捷登录")
                        model.privacyOne = [protocols[0]["name"] as! String, protocols[0]["url"] as! String]
                        if protocols.count > 1 { model.privacyTwo = [protocols[1]["name"] as! String, protocols[1]["url"] as! String] }
                        model.tapAuthPageMaskClosePage = false
                        sdk.getLoginToken(withTimeout: configuration.timeout, controller: host, model: model) { [weak self] result in
                            Task { @MainActor in
                                guard let self = self, self.flowID == id else { return }
                                let code = result["resultCode"] as? String ?? ""
                                switch code {
                                case "600001":
                                    guard !self.receivedPresentation else { return }
                                    self.receivedPresentation = true
                                    self.timeoutTask?.cancel()
                                    await presented()
                                    guard self.flowID == id else { return }
                                    self.deliveredPresentation = true
                                    if let token = self.pendingToken { self.finish(.success(token)) }
                                case "600000":
                                    guard self.receivedPresentation, let token = result["token"] as? String, !token.isEmpty else {
                                        self.finish(.failure(QSMError.invalid("认证凭证无效"))); return
                                    }
                                    if self.deliveredPresentation { self.finish(.success(token)) }
                                    else { self.pendingToken = token }
                                case "700000", "700001": self.finish(.failure(QSMError.cancelled))
                                case "700002", "700003", "700004", "700006", "700007": break
                                default: self.finish(.failure(QSMError.invalid("运营商认证未完成（\(code)）")))
                                }
                            }
                        }
                    }
                }
            }
        }, onCancel: { [weak self] in Task { @MainActor in self?.cancel() } })
    }
    private func finish(_ result: Result<String, Error>) {
        let pending = continuation; continuation = nil; flowID = nil
        timeoutTask?.cancel(); timeoutTask = nil; pendingToken = nil
        handler?.cancelLoginVC(animated: false, complete: nil)
        pending?.resume(with: result)
    }
    func cancel() {
        guard continuation != nil else { return }
        finish(.failure(CancellationError()))
    }
}
#endif
