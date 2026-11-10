import Foundation

@MainActor protocol AuthGateway: AnyObject {
    var available: Bool { get }
    func credential(configuration: AuthConfiguration, presented: @escaping @MainActor () async -> Void) async throws -> String
    func cancel()
}

@MainActor final class UnavailableAuthGateway: AuthGateway {
    var available: Bool { false }
    func credential(configuration: AuthConfiguration, presented: @escaping @MainActor () async -> Void) async throws -> String {
        throw QSMError.business(3001, "当前构建未接入一键登录 SDK，请使用 H5 登录")
    }
    func cancel() {}
}

enum ScreenState: Equatable {
    case loading(String), waitingForNetwork, failure(String), privacyDenied, revoked(String), closed
}

enum ViewEvent {
    case status(ScreenState), show(PageSpec, navigate: Bool = false), remove(String), removeAll
    case floatingBall(FloatingBallPresentation)
    case background(URL?), dismissSplash, initializationPending(Bool)
    case update(UpdateOffer), dismissUpdate
}

struct BridgeContext {
    let instance: String
    let epoch: UInt64
    let isCurrent: @MainActor () -> Bool
}

@MainActor final class AppCoordinator {
    typealias Reply = @MainActor (BridgeResult) async -> Void
    enum Phase { case initializing, privacy, updating, authenticating, login, game, revoked, closed, failed }
    let identity: GameIdentity
    let policy: URLPolicy
    private let backend: Backend
    private let repository: StateRepository
    private let vault: TokenVault
    let gateway: AuthGateway
    private let clearWebData: @MainActor () async -> Void
    private let initialGameURL: URL?
    private let notchScreen: @MainActor () -> Bool
    private let screenModeProvider: @MainActor () -> ScreenMode
    private let changeScreenMode: @MainActor (ScreenMode) -> Void
    var onView: ((ViewEvent) -> Void)?
    var confirmRevocation: (@MainActor () async -> Bool)?
    private(set) var epoch: UInt64 = 0
    private(set) var phase: Phase = .initializing
    private(set) var stored = StoredState()
    private(set) var token = ""
    private(set) var configuration: InitConfiguration?
    private var authID: UUID?
    private var initializationTask: Task<Data, Error>?
    private let initializationPause: @MainActor (TimeInterval) async throws -> Void
    private var authTask: Task<Void, Never>?
    private var resumeToLogin = false
    private var updateChecked = false
    private var revocationActive = false
    private var loginCommitActive = false
    private var floatingBallController: FloatingBallController?
    private var floatingBallHeartbeat: FloatingBallHeartbeat?
    private var gamePageReady = false
    private var windowStack: [String] = []
    private var foreground = true
    var floatingBallState: FloatingBallState { floatingBallController?.state ?? .hidden }
    var keepScreenAwake: Bool { foreground && phase == .game && windowStack.contains("web_game") }
    private var notchScreenValue: Int { notchScreen() ? 1 : 0 }
    private var screenMode: ScreenMode { screenModeProvider() }

    init(identity: GameIdentity, policy: URLPolicy, backend: Backend, repository: StateRepository,
         vault: TokenVault, gateway: AuthGateway, initialGameURL: URL? = nil,
         notchScreen: @escaping @MainActor () -> Bool = { false },
         screenMode: @escaping @MainActor () -> ScreenMode = { .vertical },
         changeScreenMode: @escaping @MainActor (ScreenMode) -> Void = { _ in },
         initializationPause: @escaping @MainActor (TimeInterval) async throws -> Void = { seconds in
             try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
         }, clearWebData: @escaping @MainActor () async -> Void = {}) {
        self.identity = identity; self.policy = policy; self.backend = backend
        self.repository = repository; self.vault = vault; self.gateway = gateway; self.clearWebData = clearWebData
        self.initialGameURL = initialGameURL; self.notchScreen = notchScreen
        self.screenModeProvider = screenMode; self.changeScreenMode = changeScreenMode
        self.initializationPause = initializationPause
    }
    var headers: [String: String] {
        identity.headers(consented: stored.consent == .agreed && !stored.cleanupPending, deviceCode: stored.deviceCode, token: token,
                         notchScreen: notchScreenValue == 1, screenMode: screenMode)
    }
    private func save(_ state: StoredState) throws { try repository.save(state); stored = state }
    private func invalidate() {
        initializationTask?.cancel(); initializationTask = nil
        epoch &+= 1; authID = nil; authTask?.cancel(); authTask = nil; gateway.cancel()
        floatingBallHeartbeat?.stop(); floatingBallController?.resetSession(); gamePageReady = false
        windowStack.removeAll(); refreshFloatingBall()
    }
    func start() async {
        guard !revocationActive else { return }
        invalidate(); let stamp = epoch
        updateChecked = false; onView?(.dismissUpdate)
        onView?(.initializationPending(true))
        onView?(.background(nil))
        resumeToLogin = false; configuration = nil; token = ""; phase = .initializing
        removeAllPages(); onView?(.status(.loading("正在初始化游戏…")))
        do {
            if let previous = try repository.load() { stored = previous }
            else {
                // 安装标记缺失视为新安装，清理本 App 的遗留 Keychain 项。
                try vault.write(nil); try save(StoredState())
            }
            if stored.consent == .revoked || stored.cleanupPending {
                phase = .revoked
                if stored.cleanupPending { await finishCleanup(reply: nil) }
                else { onView?(.status(.revoked("已撤回隐私同意"))) }
                return
            }
            if stored.consent == .agreed { token = try vault.read() ?? "" }
            let data = try await loadInitialization(stamp: stamp)
            guard epoch == stamp, !Task.isCancelled else { return }
            let config = try parsedConfiguration(data)
            configuration = config
            onView?(.initializationPending(false))
            onView?(.background(config.backgroundURL))
            configureFloatingBall()
            if stored.consent != .agreed && config.privacyAuto {
                phase = .privacy; show("web_agreement")
            } else {
                if stored.consent != .agreed { try agree() }
                await beginLogin(stamp: stamp)
            }
        } catch {
            guard epoch == stamp, !Task.isCancelled, !(error is CancellationError) else { return }
            onView?(.initializationPending(false))
            phase = .failed; onView?(.status(.failure("游戏初始化失败：\(error.localizedDescription)")))
        }
    }
    func initializationWaitingForConnectivity() {
        guard phase == .initializing else { return }
        onView?(.status(.waitingForNetwork))
    }
    private func loadInitialization(stamp: UInt64) async throws -> Data {
        let request = Task { @MainActor () throws -> Data in
            var attempt = 0
            while self.epoch == stamp {
                try Task.checkCancellation()
                do { return try await self.backend.initialize(headers: self.headers) }
                catch {
                    try Task.checkCancellation()
                    guard self.epoch == stamp else { throw CancellationError() }
                    guard StartupConnectivity.shouldWait(for: error) else { throw error }
                    self.initializationWaitingForConnectivity()
                    attempt += 1
                    try await self.initializationPause(StartupConnectivity.retryDelay(attempt: attempt))
                }
            }
            throw CancellationError()
        }
        initializationTask = request
        defer { if epoch == stamp { initializationTask = nil } }
        return try await withTaskCancellationHandler(operation: { try await request.value }, onCancel: { request.cancel() })
    }
    private func agree() throws {
        var state = stored; state.consent = .agreed; state.cleanupPending = false
        if state.installationID == nil { state.installationID = UUID().uuidString }
        try save(state)
    }
    func reconsiderPrivacy() async {
        guard !stored.cleanupPending, !revocationActive else { return }
        if stored.consent == .revoked {
            // 只允许用户主动进入重新同意路径。先保留 revoked，避免 privacyAuto=false 自动放行。
            invalidate(); let stamp = epoch
            phase = .initializing; onView?(.status(.loading("正在加载隐私协议…")))
            do {
                let data = try await loadInitialization(stamp: stamp)
                guard stamp == epoch else { return }
                configuration = try parsedConfiguration(data)
                onView?(.background(configuration?.backgroundURL))
                configureFloatingBall()
                phase = .privacy; show("web_agreement")
            } catch {
                guard stamp == epoch else { return }
                phase = .revoked; onView?(.status(.revoked(error.localizedDescription)))
            }
        } else { phase = .privacy; show("web_agreement") }
    }
    private func parsedConfiguration(_ data: Data) throws -> InitConfiguration {
        let config = try InitConfiguration.parse(data, policy: policy)
        guard let url = initialGameURL else { return config }
        guard let game = config.pages["web_game"], policy.allows(url), Origin(url) == Origin(game.url) else {
            throw QSMError.invalid("包内游戏地址与初始化的可信来源不一致")
        }
        var pages = config.pages
        pages["web_game"] = PageSpec(instance: game.instance, url: url, width: game.width, height: game.height, left: game.left, top: game.top)
        return InitConfiguration(privacyAuto: config.privacyAuto, backgroundURL: config.backgroundURL, pages: pages, jumpHosts: config.jumpHosts,
                                 auth: config.auth, updateOffer: config.updateOffer,
                                 floatingBall: config.floatingBall, popupInterval: config.popupInterval)
    }
    private func beginLogin(stamp: UInt64, useSavedToken: Bool = true) async {
        guard epoch == stamp, stored.consent == .agreed else { return }
        if !updateChecked {
            updateChecked = true
            if let offer = configuration?.updateOffer {
                phase = .updating; onView?(.dismissSplash); onView?(.update(offer)); return
            }
        }
        phase = .authenticating
        if useSavedToken, !token.isEmpty {
            onView?(.status(.loading("正在验证登录状态…")))
            do {
                let newToken = try await backend.login(token: token, headers: headers)
                guard epoch == stamp, stored.consent == .agreed, !Task.isCancelled else { return }
                try vault.write(newToken); token = newToken; enterGame(); return
            } catch {
                guard epoch == stamp else { return }
                // 业务拒绝才删除旧 Token；网络/格式错误保留持久化账号，回退 H5。
                if case QSMError.business = error {
                    do { try vault.write(nil); token = "" }
                    catch { phase = .failed; onView?(.status(.failure(error.localizedDescription))); return }
                }
            }
        }
        showLogin()
        if configuration?.auth.enabled == true, gateway.available { await performOnekey(reply: nil) }
    }
    func finishUpdate(error: Bool = false) {
        guard phase == .updating else { return }
        phase = .authenticating
        onView?(.dismissUpdate)
        if error { onView?(.status(.loading("暂时无法打开更新，请稍后再试"))) }
        let stamp = epoch
        Task { await beginLogin(stamp: stamp) }
    }
    private func show(_ instance: String) {
        guard let page = configuration?.pages[instance] else { return }
        presentPage(page)
    }
    private func presentPage(_ page: PageSpec, navigate: Bool = false) {
        windowStack.removeAll { $0 == page.instance }; windowStack.append(page.instance)
        onView?(.show(page, navigate: navigate)); refreshFloatingBall()
    }
    private func removePage(_ name: String) {
        windowStack.removeAll { $0 == name }
        if name == "web_game" { gamePageReady = false; floatingBallController?.resetSession() }
        onView?(.remove(name)); refreshFloatingBall()
    }
    private func removeAllPages() {
        windowStack.removeAll(); gamePageReady = false; floatingBallController?.resetSession()
        floatingBallHeartbeat?.stop(); onView?(.removeAll); refreshFloatingBall()
    }
    private func configureFloatingBall() {
        floatingBallHeartbeat?.stop(); floatingBallHeartbeat = nil
        floatingBallController = configuration?.floatingBall.map { FloatingBallController(initiallyEnabled: $0.show) }
        if let polling = configuration?.popupInterval {
            floatingBallHeartbeat = FloatingBallHeartbeat(interval: polling.interval, poll: { [weak self] in
                guard let self = self, self.phase == .game, !self.token.isEmpty, self.stored.consent == .agreed else { throw CancellationError() }
                return try await self.backend.floatingBallHeartbeat(url: polling.url, headers: self.headers)
            }, deliver: { [weak self] data in
                guard let state = try? FloatingBallPollingConfiguration.state(from: data) else { return }
                _ = self?.setFloatingBall(show: state.show, active: state.active)
            })
        }
        refreshFloatingBall()
    }
    private func refreshFloatingBall() {
        let ready = gamePageReady && phase == .game && !token.isEmpty && stored.consent == .agreed && windowStack.contains("web_game")
        floatingBallController?.sessionReady = ready
        floatingBallController?.welfareAboveGame = (windowStack.firstIndex(of: "web_welfare") ?? -1) > (windowStack.firstIndex(of: "web_game") ?? -1)
        let state = floatingBallState
        onView?(.floatingBall(FloatingBallPresentation(configuration: configuration?.floatingBall,
            state: FloatingBallState(visible: state.visible && foreground, active: state.active))))
        floatingBallHeartbeat?.update(sessionReady: ready, visible: state.visible, foreground: foreground)
    }
    @discardableResult func setFloatingBall(show: Bool, active: Bool) -> Bool {
        guard floatingBallController != nil else { return false }
        floatingBallController?.setRuntimeState(show: show, active: active); refreshFloatingBall(); return true
    }
    func gameDocumentBecameVisible(context: BridgeContext) {
        guard context.instance == "web_game", context.epoch == epoch, context.isCurrent(), phase == .game else { return }
        gamePageReady = true; refreshFloatingBall()
    }
    func openWelfareFromFloatingBall() {
        guard foreground, floatingBallState.visible else { return }
        show("web_welfare")
    }
    private func showLogin() { phase = .login; show("web_login") }
    private func enterGame() {
        phase = .game; removePage("web_login"); removePage("web_agreement"); show("web_game")
    }
    func enteredBackground() {
        foreground = false; refreshFloatingBall()
        guard authID != nil || phase == .authenticating else { return }
        invalidate(); resumeToLogin = true
    }
    func enteredForeground() {
        foreground = true; refreshFloatingBall()
        if resumeToLogin { resumeToLogin = false; showLogin() }
    }
    private func performOnekey(reply: Reply?) async {
        guard let auth = configuration?.auth, auth.enabled else {
            await reply?(.failure(3003, "一键登录未开启")); return
        }
        guard stored.consent == .agreed, authID == nil, let _ = auth.secret, let exchangeURL = auth.exchangeURL else {
            await reply?(.failure(3001, "认证配置不可用或已有认证流程")); return
        }
        onView?(.dismissSplash)
        let id = UUID(), stamp = epoch
        authID = id; var didPresent = false
        defer { if authID == id { authID = nil } }
        do {
            let credential = try await gateway.credential(configuration: auth) { [weak self] in
                guard let self = self, self.epoch == stamp, self.authID == id, !didPresent else { return }
                didPresent = true
                await reply?(.ok(true))
                guard self.epoch == stamp, self.authID == id else { return }
                self.removePage("web_login"); self.onView?(.status(.loading("登录中，请稍候…")))
            }
            guard epoch == stamp, authID == id, !Task.isCancelled else { return }
            guard didPresent else { throw QSMError.invalid("授权页尚未展示") }
            let newToken = try await backend.exchange(credential: credential, url: exchangeURL, headers: headers)
            guard epoch == stamp, authID == id, stored.consent == .agreed, !Task.isCancelled else { return }
            try vault.write(newToken); token = newToken; enterGame()
        } catch {
            guard epoch == stamp, authID == id else { return }
            if !didPresent { await reply?(.failure(3001, "一键登录唤起失败，请使用 H5 登录")) }
            guard epoch == stamp, authID == id else { return }
            showLogin()
        }
    }
    func handle(method: String, args: Any, context: BridgeContext, reply: @escaping Reply) async {
        guard context.epoch == epoch, context.isCurrent(), !stored.cleanupPending, !revocationActive else { return }
        let object = args as? [String: Any]
        let falseMethods: Set<String> = ["notifyLoginSuccess", "logout", "changeGameUrl", "changeScreenmode", "saveLocalStorage", "revokePrivacyPolicy", "setFloatingBall"]
        func bad() -> BridgeResult { .failure(1001, "参数或当前页面状态无效", data: method == "getLocalStorage" ? "" : (falseMethods.contains(method) ? false : NSNull())) }
        if method != "saveLocalStorage", method != "changeScreenmode", object == nil { await reply(bad()); return }
        do {
            switch method {
            case "getGameInfo":
                await reply(.ok(["gameId": identity.gameID, "gameVersion": identity.version, "packageCode": identity.packageCode,
                                 "deviceType": "ios", "deviceCode": stored.deviceCode, "accountToken": stored.consent == .agreed ? token : "",
                                 "notchScreen": notchScreenValue, "screenMode": screenMode.rawValue]))
            case "getAccountToken": await reply(.ok(["accountToken": stored.consent == .agreed ? token : ""]))
            case "agreePrivacyPolicy":
                guard phase == .privacy, context.instance == "web_agreement" else { await reply(bad()); return }
                try agree(); let stamp = epoch; await reply(.ok())
                guard epoch == stamp, context.isCurrent() else { return }
                removePage("web_agreement"); await beginLogin(stamp: stamp, useSavedToken: false)
            case "rejectPrivacyPolicy":
                guard phase == .privacy, context.instance == "web_agreement" else { await reply(bad()); return }
                var state = stored
                if state.consent != .revoked { state.consent = .unknown }
                try save(state); await reply(.ok())
                guard context.epoch == epoch, context.isCurrent() else { return }
                invalidate(); phase = state.consent == .revoked ? .revoked : .privacy
                removeAllPages(); onView?(.status(state.consent == .revoked ? .revoked("已撤回隐私同意") : .privacyDenied))
            case "notifyLoginSuccess":
                guard phase == .login, stored.consent == .agreed, context.instance == "web_login", !loginCommitActive,
                      let value = object?["accountToken"] as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    await reply(bad()); return
                }
                loginCommitActive = true; defer { loginCommitActive = false }
                try vault.write(value); token = value
                await reply(.ok(true))
                guard context.epoch == epoch, context.isCurrent(), stored.consent == .agreed else { return }
                enterGame()
            case "requestPhoneLogin":
                guard stored.consent == .agreed, phase == .login || phase == .game else { await reply(.failure(3001, "当前不允许认证")); return }
                await performOnekey(reply: reply)
            case "logout":
                guard stored.consent == .agreed else { await reply(bad()); return }
                try vault.write(nil); token = ""
                invalidate(); let stamp = epoch
                await reply(.ok(true)); guard epoch == stamp else { return }
                removeAllPages(); await beginLogin(stamp: stamp, useSavedToken: false)
            case "setFloatingBall":
                guard let show = JSON.boolean(object?["show"]), let active = JSON.boolean(object?["active"]) else { await reply(bad()); return }
                await reply(.ok(setFloatingBall(show: show, active: active)))
            case "openMiniWindow":
                guard phase == .game, let name = object?["page"] as? String,
                      ["web_game", "web_welfare"].contains(name), configuration?.pages[name] != nil else { await reply(bad()); return }
                show(name); await reply(.ok(true))
            case "closeMiniWindow", "closePage":
                let name = method == "closePage" ? context.instance : (object?["page"] as? String ?? "")
                guard phase == .game, ["web_game", "web_welfare"].contains(name), configuration?.pages[name] != nil else { await reply(bad()); return }
                await reply(.ok(true))
                guard context.epoch == epoch, context.isCurrent() else { return }
                if name == "web_game" { invalidate(); phase = .closed; removeAllPages(); onView?(.status(.closed)) }
                else { removePage(name) }
            case "changeGameUrl":
                guard phase == .game, let raw = object?["url"] as? String, let url = URL(string: raw),
                      let game = configuration?.pages["web_game"], policy.allows(url), Origin(url) == Origin(game.url) else {
                    await reply(bad()); return
                }
                await reply(.ok(true))
                guard context.epoch == epoch, context.isCurrent() else { return }
                presentPage(PageSpec(instance: game.instance, url: url, width: game.width, height: game.height, left: game.left, top: game.top), navigate: true)
            case "changeScreenmode":
                guard let value = object?["mode"] as? String, let mode = ScreenMode(rawValue: value) else {
                    await reply(.failure(1001, "参数无效", data: false)); return
                }
                if screenMode != mode { changeScreenMode(mode) }
                await reply(.ok(true))
            case "saveLocalStorage":
                let items: [[String: Any]]
                if let array = args as? [Any] {
                    guard array.allSatisfy({ $0 is [String: Any] }) else { await reply(bad()); return }
                    items = array.map { $0 as! [String: Any] }
                } else if let object = object { items = [object] }
                else { await reply(bad()); return }
                guard items.allSatisfy({ $0["key"] is String }) else { await reply(bad()); return }
                var state = stored
                for item in items {
                    let value: String
                    if let text = item["value"] as? String { value = text }
                    else if let n = item["value"] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite { value = n.stringValue }
                    else { value = "" }
                    state.values[item["key"] as! String] = value
                }
                try save(state); await reply(.ok(true))
            case "getLocalStorage":
                guard let key = object?["key"] as? String else { await reply(bad()); return }
                await reply(.ok(stored.values[key] ?? ""))
            case "revokePrivacyPolicy":
                guard stored.consent == .agreed, phase == .game, let confirm = confirmRevocation else { await reply(bad()); return }
                revocationActive = true
                let accepted = await confirm()
                guard context.epoch == epoch, context.isCurrent() else { revocationActive = false; return }
                if !accepted { revocationActive = false; await reply(.ok(false)); return }
                // revoked 优先落盘，进程中断后继续清理；失败也不能恢复采集。
                var state = stored; state.consent = .revoked; state.cleanupPending = true
                do { try save(state) }
                catch { stored = state; invalidate(); phase = .revoked; revocationActive = false; await reply(.failure(9999, "无法保存撤回状态", data: false)); removeAllPages(); onView?(.status(.revoked("撤回状态保存失败，请重试清理"))); return }
                invalidate(); phase = .revoked
                await finishCleanup(reply: reply)
                revocationActive = false
            default:
                await reply(.failure(1001, "当前版本不支持此方法", data: ["isAppInstalled", "openApp", "setFloatingBall", "saveGameRole", "getGameRole"].contains(method) ? false : NSNull()))
            }
        } catch {
            await reply(.failure(9999, error.localizedDescription, data: method == "getLocalStorage" ? "" : (falseMethods.contains(method) ? false : NSNull())))
        }
    }
    func retryCleanup() async {
        guard !revocationActive else { return }
        revocationActive = true; defer { revocationActive = false }
        await finishCleanup(reply: nil)
    }
    private func finishCleanup(reply: Reply?) async {
        onView?(.background(nil))
        do {
            var pending = stored; pending.consent = .revoked; pending.cleanupPending = true
            try save(pending); try vault.write(nil); token = ""
            await clearWebData()
            var clean = StoredState(); clean.consent = .revoked
            try save(clean)
            await reply?(.ok(true))
            removeAllPages(); onView?(.status(.revoked("已撤回隐私同意")))
        } catch {
            token = ""; stored.consent = .revoked; stored.cleanupPending = true
            await reply?(.failure(9999, "部分本机数据未能清理，请重试", data: false))
            removeAllPages(); onView?(.status(.revoked("部分本机数据未能清理，请重试")))
        }
    }
}
