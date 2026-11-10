import UIKit
import WebKit

@MainActor final class RootViewController: UIViewController {
    let coordinator: AppCoordinator
    private let orientation: ScreenOrientationController
    private var pages: [String: WebPageController] = [:]
    private let status = UIStackView()
    private let label = UILabel()
    private let background = StartupBackgroundView()
    private let splash = StartupSplashView()
    private let floatingBallLayer = FloatingBallLayer()
    private var floatingBall: FloatingBallView?
    private var updatePrompt: UpdatePromptView?
    private var resetLocalState: (() async throws -> Void)?
    init(coordinator: AppCoordinator, orientation: ScreenOrientationController,
         resetLocalState: (() async throws -> Void)? = nil) {
        self.coordinator = coordinator; self.orientation = orientation; self.resetLocalState = resetLocalState
        super.init(nibName: nil, bundle: nil)
        coordinator.onView = { [weak self] event in self?.apply(event) }
        coordinator.confirmRevocation = { [weak self] in await self?.confirmRevocation() ?? false }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override var prefersStatusBarHidden: Bool { true }
    override var shouldAutorotate: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { orientation.supportedInterfaceOrientations }
    func setApplicationActive(_ active: Bool) {
        UIApplication.shared.isIdleTimerDisabled = active && coordinator.keepScreenAwake
    }
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .systemBackground; view.accessibilityIdentifier = "qsm.root"
        background.contentMode = .scaleAspectFill; background.clipsToBounds = true; background.isUserInteractionEnabled = false
        background.accessibilityIdentifier = "startup.background"; view.addSubview(background)
        status.axis = .vertical; status.spacing = 20; status.alignment = .center; status.translatesAutoresizingMaskIntoConstraints = false
        label.numberOfLines = 0; label.textAlignment = .center; label.accessibilityIdentifier = "native.status"
        status.addArrangedSubview(label); view.addSubview(status)
        view.addSubview(floatingBallLayer)
        view.addSubview(splash)
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
        background.frame = view.bounds; splash.frame = view.bounds
        let bounds = view.safeAreaLayoutGuide.layoutFrame
        // 水平位置按完整窗口计算；纵向仍使用安全区域避开系统操作区。
        floatingBallLayer.frame = CGRect(x: view.bounds.minX, y: bounds.minY,
                                         width: view.bounds.width, height: bounds.height)
        floatingBall?.layout(in: floatingBallLayer.bounds.size)
        updatePrompt?.frame = view.bounds
        for page in pages.values {
            page.view.frame = WebPageLayout.frame(for: page.spec, bounds: view.bounds, safeArea: bounds)
        }
    }
    private func apply(_ event: ViewEvent) {
        switch event {
        case .background(let url): background.load(url, policy: coordinator.policy)
        case .dismissSplash: splash.dismiss()
        case .initializationPending(let pending): splash.setInitializationPending(pending)
        case .dismissUpdate: updatePrompt?.removeFromSuperview(); updatePrompt = nil
        case .update(let offer):
            splash.dismiss()
            guard UpdatePromptView.canPresent(offer, in: view.bounds.size) else {
                coordinator.finishUpdate(error: true); break
            }
            let prompt = UpdatePromptView(offer: offer)
            prompt.onClose = { [weak self] in self?.coordinator.finishUpdate() }
            prompt.onOpen = { [weak self, weak prompt] in
                guard let self, let prompt else { return }
                prompt.setOpening(true)
                UIApplication.shared.open(offer.url, options: [:]) { [weak self, weak prompt] accepted in
                    Task { @MainActor in
                        guard let self, let prompt, self.updatePrompt === prompt else { return }
                        prompt.setOpening(false)
                        if accepted {
                            if !offer.force { self.coordinator.finishUpdate() }
                        } else {
                            self.coordinator.finishUpdate()
                            let alert = UIAlertController(title: "更新入口暂不可用", message: "暂时无法打开更新，请稍后再试。", preferredStyle: .alert)
                            alert.addAction(UIAlertAction(title: "知道了", style: .default))
                            self.present(alert, animated: true)
                        }
                    }
                }
            }
            updatePrompt?.removeFromSuperview(); updatePrompt = prompt
            prompt.frame = view.bounds; view.addSubview(prompt)
            view.bringSubviewToFront(prompt)
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
                    page.onContentReady = { [weak self] in self?.splash.contentReady() }
                    page.onTerminalFailure = { [weak self] in self?.splash.dismiss() }
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
        view.bringSubviewToFront(floatingBallLayer)
        if splash.superview != nil { view.bringSubviewToFront(splash) }
        if case .status(.waitingForNetwork) = event { view.bringSubviewToFront(status) }
        if let updatePrompt { view.bringSubviewToFront(updatePrompt) }
        setApplicationActive(UIApplication.shared.applicationState == .active)
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
        status.backgroundColor = .clear; status.layer.cornerRadius = 0
        switch state {
        case .loading, .waitingForNetwork: break
        default: splash.dismiss()
        }
        switch state {
        case .loading(let text):
            label.text = text
            let spinner = UIActivityIndicatorView(style: .medium); spinner.startAnimating(); status.addArrangedSubview(spinner)
        case .waitingForNetwork:
            splash.setInitializationPending(true)
            label.text = "等待网络连接…\n请在系统提示中允许使用网络"
            status.backgroundColor = UIColor.secondarySystemBackground.withAlphaComponent(0.94)
            status.layer.cornerRadius = 12; status.isLayoutMarginsRelativeArrangement = true
            status.layoutMargins = UIEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)
            action("前往设置") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
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
        splash.dismiss()
        return await withCheckedContinuation { continuation in
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

@MainActor final class UpdatePromptView: UIView {
    private static let actionHeight: CGFloat = 40
    private static let closeDiameter: CGFloat = 33
    private static let closeReserve: CGFloat = 48
    private static let footerHeight: CGFloat = 90
    let offer: UpdateOffer
    var onClose: (() -> Void)?
    var onOpen: (() -> Void)?
    private let card = UIView()
    private let header = UIView()
    private let headerImage = UIImageView(image: UIImage(named: "UpdateHeader"))
    private let title = UILabel()
    private let scroll = UIScrollView()
    private let copy = UIView()
    private let detail = UILabel()
    private let latest = UILabel()
    private let updated = UILabel()
    private let action = UIButton(type: .custom)
    private let close = UIButton(type: .custom)
    private let closeBackground = UIView()
    private let closeGlyph = CAShapeLayer()
    private var opening = false
    static func canPresent(_ offer: UpdateOffer, in size: CGSize) -> Bool {
        size.width > 0 && size.height > 0 && offer.width.resolve(in: size.width) > 0 &&
            (offer.height?.resolve(in: size.height) ?? size.height) > 0
    }
    init(offer: UpdateOffer) {
        self.offer = offer
        super.init(frame: .zero)
        backgroundColor = UIColor.black.withAlphaComponent(0.45)
        isAccessibilityElement = false
        card.backgroundColor = .white; card.layer.cornerRadius = 10.5; card.clipsToBounds = true
        addSubview(card)
        header.backgroundColor = UIColor(red: 1, green: 0.77, blue: 0.1, alpha: 1)
        card.addSubview(header)
        headerImage.contentMode = .scaleToFill; header.addSubview(headerImage)
        let titleStyle = NSMutableParagraphStyle(); titleStyle.alignment = .center; titleStyle.lineHeightMultiple = 0.8
        title.attributedText = NSAttributedString(string: offer.title,
            attributes: [.font: UIFont.systemFont(ofSize: 24, weight: .bold), .paragraphStyle: titleStyle,
                         .foregroundColor: UIColor.white])
        title.numberOfLines = 0
        header.addSubview(title)
        card.addSubview(scroll)
        scroll.addSubview(copy)
        detail.text = offer.detail; detail.numberOfLines = 0; detail.textAlignment = .center
        detail.textColor = UIColor(red: 1, green: 89/255, blue: 12/255, alpha: 1)
        detail.font = .systemFont(ofSize: 18)
        copy.addSubview(detail)
        let metadataFont = UIFont.systemFont(ofSize: 13)
        let version = offer.version.trimmingCharacters(in: .whitespacesAndNewlines)
        latest.text = "最新版本：\(version.isEmpty ? "--" : version)"; latest.textColor = UIColor(white: 117/255, alpha: 1)
        latest.font = metadataFont; latest.textAlignment = .center; latest.numberOfLines = 0
        copy.addSubview(latest)
        let time = offer.time.trimmingCharacters(in: .whitespacesAndNewlines)
        updated.text = "更新时间：\(time.isEmpty ? "--" : time)"; updated.textColor = UIColor(white: 117/255, alpha: 1)
        updated.font = metadataFont; updated.textAlignment = .center; updated.numberOfLines = 0
        copy.addSubview(updated)
        action.setTitle("立即更新", for: .normal); action.backgroundColor = UIColor(red: 65/255, green: 190/255, blue: 86/255, alpha: 1)
        action.layer.cornerRadius = 22; action.titleLabel?.font = .systemFont(ofSize: 16, weight: .medium)
        action.accessibilityIdentifier = "update.action"; action.addAction(UIAction { [weak self] _ in self?.onOpen?() }, for: .touchUpInside)
        card.addSubview(action)
        close.backgroundColor = .clear
        closeBackground.backgroundColor = .black
        closeBackground.layer.cornerRadius = Self.closeDiameter / 2; closeBackground.isUserInteractionEnabled = false
        closeGlyph.fillColor = nil; closeGlyph.strokeColor = UIColor.white.cgColor
        closeGlyph.lineWidth = 2; closeGlyph.lineCap = .round
        closeBackground.layer.addSublayer(closeGlyph)
        close.addSubview(closeBackground); close.sendSubviewToBack(closeBackground)
        close.accessibilityIdentifier = "update.close"; close.accessibilityLabel = "关闭更新"
        close.addAction(UIAction { [weak self] _ in self?.onClose?() }, for: .touchUpInside)
        if !offer.force { addSubview(close) }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    func setOpening(_ value: Bool) {
        opening = value; action.isEnabled = !value; action.setTitle(value ? "正在打开…" : "立即更新", for: .normal)
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        let available = bounds.inset(by: safeAreaInsets).insetBy(dx: 12, dy: 12)
        let width = min(available.width, offer.width.resolve(in: bounds.width))
        let cardWidth = width
        let idealHeaderHeight = cardWidth * 198 / 530 * 0.8
        let closeReserve: CGFloat = offer.force ? 0 : Self.closeReserve
        let maximum = max(0, available.height - closeReserve)
        let heightCap: CGFloat = offer.height.map { min(maximum, $0.resolve(in: bounds.height)) } ?? maximum
        let textWidth = max(0, cardWidth - 34)
        let measureWidth = max(1, textWidth)
        let detailHeight = offer.detail.isEmpty ? 0 : ceil(detail.sizeThatFits(CGSize(width: measureWidth, height: CGFloat.greatestFiniteMagnitude)).height)
        detail.frame = CGRect(x: 0, y: 0, width: textWidth, height: detailHeight)
        let metadataY = detailHeight + (detailHeight > 0 ? 10 : 0)
        let latestWidth = latest.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)).width
        let updatedWidth = updated.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)).width
        let metadataHeight: CGFloat
        if latestWidth + updatedWidth + 30 <= textWidth {
            let start = (textWidth - latestWidth - updatedWidth - 30) / 2
            metadataHeight = max(latest.font.lineHeight, updated.font.lineHeight)
            latest.frame = CGRect(x: start, y: metadataY, width: latestWidth, height: metadataHeight)
            updated.frame = CGRect(x: start + latestWidth + 30, y: metadataY, width: updatedWidth, height: metadataHeight)
        } else if textWidth >= 220 {
            let firstWidth = (textWidth - 30) * latestWidth / max(1, latestWidth + updatedWidth)
            let secondWidth = textWidth - 30 - firstWidth
            let firstHeight = ceil(latest.sizeThatFits(CGSize(width: firstWidth, height: CGFloat.greatestFiniteMagnitude)).height)
            let secondHeight = ceil(updated.sizeThatFits(CGSize(width: secondWidth, height: CGFloat.greatestFiniteMagnitude)).height)
            metadataHeight = max(firstHeight, secondHeight)
            latest.frame = CGRect(x: 0, y: metadataY, width: firstWidth, height: metadataHeight)
            updated.frame = CGRect(x: firstWidth + 30, y: metadataY, width: secondWidth, height: metadataHeight)
        } else {
            let firstHeight = ceil(latest.sizeThatFits(CGSize(width: measureWidth, height: CGFloat.greatestFiniteMagnitude)).height)
            let secondHeight = ceil(updated.sizeThatFits(CGSize(width: measureWidth, height: CGFloat.greatestFiniteMagnitude)).height)
            metadataHeight = firstHeight + 30 + secondHeight
            latest.frame = CGRect(x: 0, y: metadataY, width: textWidth, height: firstHeight)
            updated.frame = CGRect(x: 0, y: metadataY + firstHeight + 30, width: textWidth, height: secondHeight)
        }
        let textHeight = metadataY + metadataHeight
        let headerHeight = min(idealHeaderHeight, max(0, heightCap - Self.footerHeight - min(textHeight, 80)))
        let scrollHeight = min(textHeight, max(0, heightCap - headerHeight - Self.footerHeight))
        let cardHeight = offer.height == nil ? min(heightCap, headerHeight + scrollHeight + Self.footerHeight) : heightCap
        card.frame = CGRect(x: available.midX - cardWidth/2, y: available.midY - (cardHeight + closeReserve)/2,
                            width: cardWidth, height: cardHeight)
        header.frame = CGRect(x: 0, y: 0, width: cardWidth, height: headerHeight)
        headerImage.frame = header.bounds
        title.frame = header.bounds.insetBy(dx: 16, dy: 6); title.frame.origin.y -= 5
        scroll.frame = CGRect(x: 17, y: headerHeight + 10, width: textWidth, height: scrollHeight)
        copy.frame = CGRect(x: 0, y: 0, width: textWidth, height: textHeight)
        scroll.contentSize = copy.bounds.size
        let actionWidth = min(max(0, cardWidth - 34), max(160, cardWidth * 0.72))
        action.frame = CGRect(x: max(17, (cardWidth - actionWidth)/2), y: cardHeight - 60,
                              width: actionWidth, height: Self.actionHeight)
        action.layer.cornerRadius = Self.actionHeight / 2
        close.frame = CGRect(x: available.midX - 22, y: card.frame.maxY + 4, width: 44, height: 44)
        closeBackground.frame = CGRect(x: (44 - Self.closeDiameter) / 2, y: (44 - Self.closeDiameter) / 2,
                                       width: Self.closeDiameter, height: Self.closeDiameter)
        closeGlyph.frame = closeBackground.bounds
        let center = Self.closeDiameter / 2
        let path = UIBezierPath()
        path.move(to: CGPoint(x: center - 7, y: center - 7)); path.addLine(to: CGPoint(x: center + 7, y: center + 7))
        path.move(to: CGPoint(x: center + 7, y: center - 7)); path.addLine(to: CGPoint(x: center - 7, y: center + 7))
        closeGlyph.path = path.cgPath
    }
}
