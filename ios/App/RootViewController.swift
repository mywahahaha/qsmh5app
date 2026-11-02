import UIKit
import WebKit

@MainActor final class RootViewController: UIViewController {
    let coordinator: AppCoordinator
    private var pages: [String: WebPageController] = [:]
    private let status = UIStackView()
    private let label = UILabel()
    private let floatingBallLayer = FloatingBallLayer()
    private var floatingBall: FloatingBallView?
    private var resetLocalState: (() async throws -> Void)?
    init(coordinator: AppCoordinator, resetLocalState: (() async throws -> Void)? = nil) {
        self.coordinator = coordinator; self.resetLocalState = resetLocalState
        super.init(nibName: nil, bundle: nil)
        coordinator.onView = { [weak self] event in self?.apply(event) }
        coordinator.confirmRevocation = { [weak self] in await self?.confirmRevocation() ?? false }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .systemBackground; view.accessibilityIdentifier = "qsm.root"
        status.axis = .vertical; status.spacing = 20; status.alignment = .center; status.translatesAutoresizingMaskIntoConstraints = false
        label.numberOfLines = 0; label.textAlignment = .center; label.accessibilityIdentifier = "native.status"
        status.addArrangedSubview(label); view.addSubview(status)
        view.addSubview(floatingBallLayer)
        NSLayoutConstraint.activate([status.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            status.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor),
            status.widthAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.widthAnchor, constant: -40)])
        Task { [weak self] in
            guard let self = self else { return }
            do { try await self.resetLocalState?(); await self.coordinator.start() }
            catch { self.showStatus(.failure(error.localizedDescription)) }
        }
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let bounds = view.safeAreaLayoutGuide.layoutFrame
        floatingBallLayer.frame = bounds
        floatingBall?.layout(in: floatingBallLayer.bounds.size)
        for page in pages.values {
            let width = min(bounds.width, max(1, page.spec.width.resolve(in: bounds.width)))
            let height = min(bounds.height, max(1, page.spec.height.resolve(in: bounds.height)))
            let left = min(max(0, page.spec.left.resolve(in: bounds.width, length: width)), bounds.width - width)
            let top = min(max(0, page.spec.top.resolve(in: bounds.height, length: height)), bounds.height - height)
            page.view.frame = CGRect(x: bounds.minX + left, y: bounds.minY + top, width: width, height: height)
        }
    }
    private func apply(_ event: ViewEvent) {
        switch event {
        case .status(let state): showStatus(state)
        case .removeAll:
            for name in Array(pages.keys) { remove(name) }
            floatingBall?.destroy(); floatingBall = nil
        case .remove(let name): remove(name)
        case .show(let spec, let navigate):
            do {
                status.isHidden = true
                if let existing = pages[spec.instance] { existing.update(spec, navigate: navigate); view.bringSubviewToFront(existing.view) }
                else {
                    let page = try WebPageController(spec: spec, coordinator: coordinator)
                    pages[spec.instance] = page; addChild(page); view.addSubview(page.view); page.didMove(toParent: self)
                }
                view.setNeedsLayout()
            } catch { showStatus(.failure(error.localizedDescription)) }
        case .floatingBall(let presentation):
            if floatingBall?.configuration != presentation.configuration { floatingBall?.destroy(); floatingBall = nil }
            if presentation.state.visible, floatingBall == nil, let config = presentation.configuration {
                let ball = FloatingBallView(configuration: config)
                ball.addAction(UIAction { [weak self] _ in self?.coordinator.openWelfareFromFloatingBall() }, for: .touchUpInside)
                floatingBall = ball; floatingBallLayer.addSubview(ball)
                ball.layout(in: floatingBallLayer.bounds.size)
            }
            floatingBall?.setActive(presentation.state.active)
            floatingBall?.isHidden = !presentation.state.visible
            view.bringSubviewToFront(floatingBallLayer)
        }
    }
    private func remove(_ name: String) {
        guard let page = pages.removeValue(forKey: name) else { return }
        page.invalidate(); page.willMove(toParent: nil); page.view.removeFromSuperview(); page.removeFromParent()
    }
    private func showStatus(_ state: ScreenState) {
        for item in status.arrangedSubviews where item !== label { status.removeArrangedSubview(item); item.removeFromSuperview() }
        status.isHidden = false; view.bringSubviewToFront(status)
        func action(_ title: String, _ block: @escaping () -> Void) {
            let button = UIButton(type: .system); button.setTitle(title, for: .normal)
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            button.addAction(UIAction { _ in block() }, for: .touchUpInside); status.addArrangedSubview(button)
        }
        switch state {
        case .loading(let text):
            label.text = text
            let spinner = UIActivityIndicatorView(style: .medium); spinner.startAnimating(); status.addArrangedSubview(spinner)
        case .failure(let message):
            label.text = message
            action("重新初始化") { [weak self] in Task { await self?.coordinator.start() } }
        case .privacyDenied:
            label.text = "需要同意隐私政策后继续"
            action("重新选择") { [weak self] in Task { await self?.coordinator.reconsiderPrivacy() } }
        case .revoked(let message):
            label.text = message
            if coordinator.stored.cleanupPending {
                action("重试清理") { [weak self] in Task { await self?.coordinator.retryCleanup() } }
            } else {
                action("查看协议并重新选择") { [weak self] in Task { await self?.coordinator.reconsiderPrivacy() } }
            }
        case .closed:
            label.text = "游戏已关闭"
            action("重新进入") { [weak self] in Task { await self?.coordinator.start() } }
        }
    }
    private func confirmRevocation() async -> Bool {
        await withCheckedContinuation { continuation in
            let alert = UIAlertController(title: "撤回隐私同意", message: "撤回后将退出当前游戏，并清理本机登录与游戏存储数据。服务端账号不会因此注销。是否继续？", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "取消", style: .cancel) { _ in continuation.resume(returning: false) })
            alert.addAction(UIAlertAction(title: "确认撤回", style: .destructive) { _ in continuation.resume(returning: true) })
            present(alert, animated: true)
        }
    }
    static func clearWebData() async {
        let store = WKWebsiteDataStore.default()
        await store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast)
    }
}
