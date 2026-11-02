import UIKit
import WebKit

private final class WeakMessageHandler: NSObject, WKScriptMessageHandler {
    weak var owner: WebPageController?
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        owner?.receive(message)
    }
}

@MainActor final class WebPageController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    let instance: String
    let trustedURL: URL
    let sessionEpoch: UInt64
    private(set) var spec: PageSpec
    private(set) var webView: WKWebView!
    private weak var coordinator: AppCoordinator?
    private var documentID = UUID().uuidString
    private var seenIDs: Set<String> = []
    private var active = true
    private var navigation: WKNavigation?
    private var currentRequest: URLRequest
    private var attempts = 0
    private var timeout: Task<Void, Never>?
    private var failureView: UIView?
    private let script: String
    private let handler = WeakMessageHandler()

    init(spec: PageSpec, coordinator: AppCoordinator) throws {
        self.spec = spec; self.instance = spec.instance; self.trustedURL = spec.url
        self.currentRequest = URLRequest(url: spec.url); self.sessionEpoch = coordinator.epoch; self.coordinator = coordinator
        guard let file = Bundle.main.url(forResource: "bridge", withExtension: "js") else { throw QSMError.invalid("缺少 Bridge 资源") }
        self.script = try String(contentsOf: file, encoding: .utf8)
        super.init(nibName: nil, bundle: nil)
        handler.owner = self
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func loadView() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.userContentController.add(handler, name: "qsm")
        webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self; webView.uiDelegate = self
        webView.isOpaque = false; webView.backgroundColor = .clear
        webView.accessibilityIdentifier = instance
        view = UIView(); view.backgroundColor = .systemBackground; view.clipsToBounds = true
        webView.frame = view.bounds; webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]; view.addSubview(webView)
        navigate(spec.url)
    }
    func update(_ page: PageSpec, navigate: Bool = false) {
        spec = PageSpec(instance: page.instance, url: navigate ? page.url : spec.url, width: page.width,
                        height: page.height, left: page.left, top: page.top)
        if navigate { self.navigate(page.url) }
    }
    func navigate(_ url: URL) {
        attempts = 0; currentRequest = URLRequest(url: url); currentRequest.timeoutInterval = 5
        loadCurrent()
    }
    private func loadCurrent() {
        guard active else { return }
        failureView?.removeFromSuperview(); failureView = nil; webView.isHidden = false
        navigation = webView.load(currentRequest)
    }
    private func prepareDocument() {
        documentID = UUID().uuidString; seenIDs.removeAll()
        let controller = webView.configuration.userContentController
        controller.removeAllUserScripts()
        controller.addUserScript(WKUserScript(source: script.replacingOccurrences(of: "__QSM_DOCUMENT_ID__", with: documentID),
                                             injectionTime: .atDocumentStart, forMainFrameOnly: true))
    }
    func invalidate() {
        guard active else { return }
        active = false; documentID = UUID().uuidString; timeout?.cancel()
        webView?.stopLoading(); webView?.configuration.userContentController.removeScriptMessageHandler(forName: "qsm")
        webView?.configuration.userContentController.removeAllUserScripts()
        webView?.navigationDelegate = nil; webView?.uiDelegate = nil
    }
    fileprivate func receive(_ message: WKScriptMessage) {
        guard active, message.webView === webView, message.frameInfo.isMainFrame,
              let coordinator = coordinator, coordinator.epoch == sessionEpoch,
              let source = message.frameInfo.request.url,
              coordinator.policy.accepts(source: source, trusted: trustedURL, isMainFrame: true),
              let currentURL = webView.url, Origin(currentURL) == Origin(trustedURL),
              let string = message.body as? String, let data = string.data(using: .utf8),
              data.count <= 262144, let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              object["documentID"] as? String == documentID,
              let id = object["id"] as? String, !id.isEmpty, id.count <= 64,
              let method = object["method"] as? String, !seenIDs.contains(id), seenIDs.count < 10000 else { return }
        let security = message.frameInfo.securityOrigin
        guard security.protocol.lowercased() == Origin(trustedURL)?.scheme,
              security.host.lowercased() == Origin(trustedURL)?.host,
              (security.port == 0 ? (security.protocol == "https" ? 443 : 80) : security.port) == Origin(trustedURL)?.port else { return }
        seenIDs.insert(id)
        let stamp = documentID
        let context = BridgeContext(instance: instance, epoch: sessionEpoch) { [weak self] in
            self?.active == true && self?.documentID == stamp
        }
        Task { [weak self, weak coordinator] in
            guard let self = self, let coordinator = coordinator else { return }
            await coordinator.handle(method: method, args: object["args"] ?? NSNull(), context: context) { [weak self] result in
                guard let self = self, self.active, self.documentID == stamp else { return }
                _ = try? await self.webView.callAsyncJavaScript(
                    "return window.__QSMH5APP_CALLBACK__(documentID, id, result);",
                    arguments: ["documentID": stamp, "id": id, "result": result.object], in: nil, contentWorld: .page)
            }
        }
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard active, let url = action.request.url, let coordinator = coordinator else { decisionHandler(.cancel); return }
        if action.targetFrame?.isMainFrame == false {
            decisionHandler(coordinator.policy.allows(url) ? .allow : .cancel); return
        }
        guard coordinator.policy.accepts(source: url, trusted: trustedURL, isMainFrame: true), action.targetFrame != nil else {
            decisionHandler(.cancel)
            if action.navigationType == .linkActivated, coordinator.policy.allows(url),
               coordinator.configuration?.jumpHosts.contains(url.host?.lowercased() ?? "") == true {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
            return
        }
        currentRequest = action.request
        prepareDocument()
        decisionHandler(.allow)
    }
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        self.navigation = navigation
        timeout?.cancel(); let stamp = documentID
        timeout = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
            guard let self = self, self.active, self.documentID == stamp else { return }
            self.webView.stopLoading(); self.failed("页面请求超时", retryable: true)
        }
    }
    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        guard response.isForMainFrame, let http = response.response as? HTTPURLResponse, http.statusCode >= 400 else { decisionHandler(.allow); return }
        decisionHandler(.cancel)
        failed("页面请求失败 HTTP \(http.statusCode)", retryable: [408, 429].contains(http.statusCode) || http.statusCode >= 500)
    }
    private func notifyGameVisible() {
        let stamp = documentID
        coordinator?.gameDocumentBecameVisible(context: BridgeContext(instance: instance, epoch: sessionEpoch) { [weak self] in
            self?.active == true && self?.documentID == stamp
        })
    }
    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        guard active, navigation === self.navigation else { return }
        notifyGameVisible()
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard active, navigation === self.navigation else { return }
        timeout?.cancel(); attempts = 0; failureView?.removeFromSuperview(); failureView = nil; webView.isHidden = false
        notifyGameVisible()
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { fail(navigation, error) }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { fail(navigation, error) }
    private func fail(_ navigation: WKNavigation?, _ error: Error) {
        guard active, navigation === self.navigation else { return }
        let error = error as NSError
        guard error.code != NSURLErrorCancelled else { return }
        failed(error.localizedDescription, retryable: !(-1206 ... -1200).contains(error.code))
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { failed("网页进程已结束", retryable: true) }
    private func failed(_ message: String, retryable: Bool) {
        guard active else { return }
        timeout?.cancel()
        if retryable, (currentRequest.httpMethod ?? "GET") == "GET", attempts < 3 {
            attempts += 1; loadCurrent(); return
        }
        webView.isHidden = true
        let stack = UIStackView(); stack.axis = .vertical; stack.spacing = 18; stack.alignment = .center
        let label = UILabel(); label.text = "页面暂时无法加载\n\(message)"; label.numberOfLines = 0; label.textAlignment = .center
        label.accessibilityIdentifier = "web.error"
        let retry = UIButton(type: .system); retry.setTitle("重试页面", for: .normal)
        retry.addAction(UIAction { [weak self] _ in self?.attempts = 0; self?.loadCurrent() }, for: .touchUpInside)
        stack.addArrangedSubview(label); stack.addArrangedSubview(retry)
        stack.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(stack)
        NSLayoutConstraint.activate([stack.centerXAnchor.constraint(equalTo: view.centerXAnchor), stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
                                     stack.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, constant: -32)])
        failureView?.removeFromSuperview(); failureView = stack
    }
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
}
