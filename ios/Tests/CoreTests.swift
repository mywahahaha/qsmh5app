import XCTest
@testable import QSMH5App

@MainActor final class MemoryRepository: StateRepository {
    var state: StoredState?
    var failSave = false
    init(_ state: StoredState? = nil) { self.state = state }
    func load() throws -> StoredState? { state }
    func save(_ state: StoredState) throws {
        if failSave { throw QSMError.storage("测试写入失败") }
        self.state = state
    }
}

@MainActor final class MemoryVault: TokenVault {
    var token: String?
    var failWrite = false
    func read() throws -> String? { token }
    func write(_ token: String?) throws {
        if failWrite { throw QSMError.storage("测试 Keychain 写入失败") }
        self.token = token
    }
}

@MainActor final class StubBackend: Backend {
    var response: Data
    var requests: [[String: String]] = []
    var initializeHandler: (() async throws -> Data)?
    var loginHandler: (() async throws -> String)?
    var exchangeHandler: (() async throws -> String)?
    var heartbeatHandler: (() async throws -> Data)?
    init(_ response: Data) { self.response = response }
    func initialize(headers: [String: String]) async throws -> Data { requests.append(headers); return try await initializeHandler?() ?? response }
    func login(token: String, headers: [String: String]) async throws -> String {
        requests.append(headers); return try await loginHandler?() ?? "renewed-token"
    }
    func exchange(credential: String, url: URL, headers: [String: String]) async throws -> String {
        requests.append(headers); return try await exchangeHandler?() ?? "exchanged-token"
    }
    func floatingBallHeartbeat(url: URL, headers: [String: String]) async throws -> Data {
        requests.append(headers); return try await heartbeatHandler?() ?? Data("{\"code\":0,\"data\":{}}".utf8)
    }
}

@MainActor final class StubGateway: AuthGateway {
    var available = true
    var result: Result<String, Error> = .success("test-credential")
    var starts = 0
    func credential(configuration: AuthConfiguration, presented: @escaping @MainActor () async -> Void) async throws -> String {
        starts += 1; await presented(); return try result.get()
    }
    func cancel() {}
}

struct CapturingTransport: HTTPTransport {
    let handler: (URLRequest) throws -> (Data, Int)
    func send(_ request: URLRequest) async throws -> (Data, Int) { try handler(request) }
}

@MainActor final class CoreTests: XCTestCase {
    let policy = URLPolicy(localOrigin: nil)
    let identity = GameIdentity(gameID: 29, version: "1.0.1", packageCode: "")
    func testIOS14BridgeCallbackSerializesUntrustedTextAsData() throws {
        let hostile = "\");globalThis.injected=true;//\u{2028}中文\u{2029}"
        let script = try XCTUnwrap(BridgeCallbackScript.make(documentID: "document", id: "42",
            result: .ok(["text": hostile])))
        let prefix = "window.__QSMH5APP_CALLBACK__.apply(window, "
        XCTAssertTrue(script.hasPrefix(prefix)); XCTAssertTrue(script.hasSuffix(");"))
        XCTAssertFalse(script.contains("\u{2028}")); XCTAssertFalse(script.contains("\u{2029}"))
        let json = String(script.dropFirst(prefix.count).dropLast(2))
        let arguments = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [Any])
        XCTAssertEqual(arguments[0] as? String, "document")
        XCTAssertEqual(arguments[1] as? String, "42")
        let response = try XCTUnwrap(arguments[2] as? [String: Any])
        XCTAssertEqual((response["data"] as? [String: Any])?["text"] as? String, hostile)
    }
    func fixture(privacy: Bool = true, auth: Bool = false) -> Data {
        let pages = Dictionary(uniqueKeysWithValues: ["agreement", "login", "game", "welfare"].map { name in
            (name, ["instance": "web_" + name, "url": "https://fixture.test/" + name, "width": "100%", "height": "100%", "left": "center", "top": "center"] as [String: Any])
        })
        let config: Any = auth ? ["scheme_secret": "test-secret", "exchange_url": "https://fixture.test/exchange"] : "故意提供坏配置"
        return try! JSONSerialization.data(withJSONObject: ["code": 0, "data": ["privacyAuto": privacy, "h5Pages": pages,
            "aliyunOnekeyConfig": ["open": auth ? 1 : 0, "config": config], "updateInfo": ["open": 0]]])
    }
    func make(privacy: Bool = true, state: StoredState? = nil, auth: Bool = false,
              gateway: AuthGateway? = nil) -> (AppCoordinator, MemoryRepository, MemoryVault, StubBackend) {
        let repository = MemoryRepository(state), vault = MemoryVault(), backend = StubBackend(fixture(privacy: privacy, auth: auth))
        let sut = AppCoordinator(identity: identity, policy: policy, backend: backend, repository: repository, vault: vault,
                                 gateway: gateway ?? UnavailableAuthGateway())
        return (sut, repository, vault, backend)
    }
    func context(_ sut: AppCoordinator, _ name: String) -> BridgeContext { BridgeContext(instance: name, epoch: sut.epoch, isCurrent: { true }) }
    func call(_ sut: AppCoordinator, _ method: String, _ args: Any = [String: Any](), page: String = "web_game") async -> BridgeResult? {
        var response: BridgeResult?
        await sut.handle(method: method, args: args, context: context(sut, page)) { response = $0 }
        return response
    }
    func assertCode(_ code: Int, _ sut: AppCoordinator, _ method: String, _ args: Any = [String: Any](), page: String = "web_game") async {
        let result = await call(sut, method, args, page: page)
        XCTAssertEqual(result?.code, code)
    }
    func testStrictEnvelopeRejectsMissingStringBooleanFractionAndNullCodes() throws {
        for body in ["{}", "{\"code\":\"0\"}", "{\"code\":false}", "{\"code\":0.5}", "{\"code\":null}"] {
            XCTAssertThrowsError(try JSON.envelope(Data(body.utf8)))
        }
        XCTAssertNoThrow(try JSON.envelope(Data("{\"code\":0}".utf8)))
    }
    func testBusinessErrorPreservesChineseMessage() {
        XCTAssertThrowsError(try JSON.envelope(Data("{\"code\":100,\"msg\":\"当前版本已作废\"}".utf8))) { error in
            XCTAssertEqual(error.localizedDescription, "当前版本已作废")
        }
    }
    func testDisabledBadAuthConfigDoesNotBlockH5() throws {
        let parsed = try InitConfiguration.parse(fixture(), policy: policy)
        XCTAssertFalse(parsed.auth.enabled); XCTAssertEqual(parsed.pages.count, 4)
    }
    func testUpdatePolicyRejectsInvalidEntrance() throws {
        var raw: [String: Any] = ["open":1,"width":"80%","height":"auto","force":1,
            "title":"新版本","desc":"说明","version":"1.0.2","time":"今天","url":"https://fixture.test/update"]
        XCTAssertTrue(try XCTUnwrap(UpdatePolicy.offer(raw)).force)
        raw["url"] = "https://fixture.test/app.apk"; XCTAssertNil(UpdatePolicy.offer(raw))
        raw["url"] = "http://fixture.test/update"; XCTAssertNotNil(UpdatePolicy.offer(raw))
        raw["url"] = "https://fixture.test/update"; raw["width"] = "NaN"; XCTAssertNil(UpdatePolicy.offer(raw))
        raw["open"] = 0; XCTAssertNil(UpdatePolicy.offer(raw))
    }
    func testOnlineUpdateConfigWithHTTPEntryProducesOffer() throws {
        let raw: [String: Any] = ["open": 1, "width": "50", "height": "auto", "popup": 1,
            "force": 0, "title": "邀请您体验新版本", "time": " 2026-11-05", "desc": "游戏有更新啦!",
            "version": "", "url": "http://t469.g.22.run/box29"]
        let offer = try XCTUnwrap(UpdatePolicy.offer(raw))
        XCTAssertEqual(offer.url.absoluteString, "http://t469.g.22.run/box29")
        if case .points(let width) = offer.width { XCTAssertEqual(width, 50) } else { XCTFail("纯数字宽度应按 dp 解析") }
        XCTAssertNil(offer.height)
        XCTAssertTrue(UpdatePromptView.canPresent(offer, in: CGSize(width: 844, height: 390)))
        XCTAssertFalse(offer.force)
    }
    func testUpdateDimensionsKeepDPPercentAndAutoSemantics() throws {
        let base: [String: Any] = ["open": 1, "force": 0, "title": "标题", "desc": "说明",
            "version": "1.0.2", "time": "2026-11-05", "url": "https://fixture.test/update"]
        var points = base; points["width"] = 320; points["height"] = "240"
        let pointOffer = try XCTUnwrap(UpdatePolicy.offer(points))
        XCTAssertEqual(pointOffer.width, .points(320)); XCTAssertEqual(pointOffer.height, .points(240))
        var percent = base; percent["width"] = "50%"; percent["height"] = "75%"
        let percentOffer = try XCTUnwrap(UpdatePolicy.offer(percent))
        XCTAssertEqual(percentOffer.width, .percent(0.5)); XCTAssertEqual(percentOffer.height, .percent(0.75))
        XCTAssertEqual(percentOffer.width.resolve(in: 844), 422)
        XCTAssertEqual(try XCTUnwrap(percentOffer.height).resolve(in: 390), 292.5)
        var automatic = base; automatic["width"] = "50%"; automatic["height"] = "auto"
        let automaticOffer = try XCTUnwrap(UpdatePolicy.offer(automatic))
        XCTAssertEqual(automaticOffer.width, .percent(0.5)); XCTAssertNil(automaticOffer.height)
    }
    func testUpdateOfferWaitsForDismissalAndMalformedConfigContinuesLogin() async throws {
        func payload(_ update: Any) throws -> Data {
            var root = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture(privacy: false)) as? [String: Any])
            var body = try XCTUnwrap(root["data"] as? [String: Any]); body["updateInfo"] = update; root["data"] = body
            return try JSONSerialization.data(withJSONObject: root)
        }
        let offer: [String: Any] = ["open":1,"width":"80%","height":"auto","force":0,
            "title":"新版本","desc":"说明","version":"1.0.2","time":"今天","url":"https://fixture.test/update"]
        let backend = StubBackend(try payload(offer))
        let sut = AppCoordinator(identity: identity, policy: policy, backend: backend,
            repository: MemoryRepository(), vault: MemoryVault(), gateway: UnavailableAuthGateway())
        var shown = false
        sut.onView = { event in if case .update = event { shown = true } }
        await sut.start(); XCTAssertTrue(shown); XCTAssertEqual(sut.phase, .updating)
        sut.finishUpdate()
        for _ in 0..<10 where sut.phase != .login { await Task.yield() }
        XCTAssertEqual(sut.phase, .login)
        let invalidBackend = StubBackend(try payload(["open":1,"url":"https://fixture.test/app.apk"]))
        let normal = AppCoordinator(identity: identity, policy: policy, backend: invalidBackend,
            repository: MemoryRepository(), vault: MemoryVault(), gateway: UnavailableAuthGateway())
        await normal.start(); XCTAssertEqual(normal.phase, .login)
    }
    func testOpenUpdateShowsEvenWhenTargetVersionEqualsInstalledVersion() async throws {
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture(privacy: false)) as? [String: Any])
        var body = try XCTUnwrap(root["data"] as? [String: Any])
        body["updateInfo"] = ["open":1,"width":"80%","height":"auto","force":0,
            "title":"当前版本维护提示","desc":"查看更新入口","version":"1.0.2","time":"今天",
            "url":"https://fixture.test/update"]
        root["data"] = body
        let backend = StubBackend(try JSONSerialization.data(withJSONObject: root))
        let sut = AppCoordinator(identity: GameIdentity(gameID: 29, version: "1.0.2", packageCode: ""),
            policy: policy, backend: backend, repository: MemoryRepository(), vault: MemoryVault(),
            gateway: UnavailableAuthGateway())
        var updateShown = false
        sut.onView = { event in if case .update = event { updateShown = true } }
        await sut.start()
        XCTAssertTrue(updateShown)
        XCTAssertEqual(sut.phase, .updating)
    }
    func testPageParsingRejectsInvalidDimensionsAndInstance() throws {
        var root = try JSONSerialization.jsonObject(with: fixture()) as! [String: Any]
        var body = root["data"] as! [String: Any], pages = body["h5Pages"] as! [String: [String: Any]]
        pages["game"]?["width"] = "center"; body["h5Pages"] = pages; root["data"] = body
        XCTAssertThrowsError(try InitConfiguration.parse(JSONSerialization.data(withJSONObject: root), policy: policy))
        let invalid: [Any] = ["NaN%", "Infinity", "101%", 0, true, NSNull()]
        for value in invalid {
            XCTAssertThrowsError(try LayoutValue.parse(value, axis: false))
        }
    }
    func testOriginsAreExactAndMainFrameOnly() {
        let trusted = URL(string: "https://fixture.test/path")!
        XCTAssertTrue(policy.accepts(source: URL(string: "https://fixture.test:443/next")!, trusted: trusted, isMainFrame: true))
        for source in ["https://sub.fixture.test", "https://fixture.test:444", "http://fixture.test", "https://fixture.test.evil", "https://user@fixture.test"] {
            XCTAssertFalse(policy.accepts(source: URL(string: source)!, trusted: trusted, isMainFrame: true))
        }
        XCTAssertFalse(policy.accepts(source: trusted, trusted: trusted, isMainFrame: false))
    }
    func testPlainHTTPOnlyAllowsExactDebugOrigin() {
        let local = URLPolicy(localOrigin: Origin(URL(string: "http://localhost:18740")!))
        XCTAssertTrue(local.allows(URL(string: "http://localhost:18740/pages/game")!))
        XCTAssertFalse(local.allows(URL(string: "http://localhost:18741")!))
        XCTAssertFalse(local.allows(URL(string: "http://127.0.0.1:18740")!))
        XCTAssertFalse(policy.allows(URL(string: "http://localhost:18740")!))
    }
    func testHeadersBeforeAndAfterConsent() async {
        let (sut, _, vault, backend) = make()
        await sut.start()
        XCTAssertEqual(backend.requests.first?["Devicecode"], ""); XCTAssertEqual(backend.requests.first?["Accountoken"], "")
        XCTAssertEqual(backend.requests.first?["Packagecode"], ""); XCTAssertEqual(backend.requests.first?["Devicetype"], "ios")
        XCTAssertEqual(backend.requests.first?["Notchscreen"], "0")
        XCTAssertEqual(backend.requests.first?["Screenmode"], "vertical")
        XCTAssertEqual(sut.stored.deviceCode, "")
        _ = await call(sut, "agreePrivacyPolicy", page: "web_agreement")
        XCTAssertEqual(sut.stored.deviceCode.count, 32)
        _ = await call(sut, "notifyLoginSuccess", ["accountToken": "h5-token"], page: "web_login")
        XCTAssertEqual(vault.token, "h5-token"); XCTAssertEqual(sut.phase, .game)
    }
    func testNotchHeaderUsesCurrentDeviceState() async {
        var notched = true
        let backend = StubBackend(fixture())
        let sut = AppCoordinator(identity: identity, policy: policy, backend: backend,
            repository: MemoryRepository(), vault: MemoryVault(), gateway: UnavailableAuthGateway(),
            notchScreen: { notched })
        await sut.start()
        XCTAssertEqual(backend.requests.first?["Notchscreen"], "1")
        let bridgeNotched = await call(sut, "getGameInfo", page: "web_agreement")
        XCTAssertEqual((bridgeNotched?.data as? [String: Any])?["notchScreen"] as? Int, 1)
        notched = false
        XCTAssertEqual(sut.headers["Notchscreen"], "0")
        let bridgePlain = await call(sut, "getGameInfo", page: "web_agreement")
        XCTAssertEqual((bridgePlain?.data as? [String: Any])?["notchScreen"] as? Int, 0)
        XCTAssertTrue(NotchScreenDetector.hasCutout(insets: UIEdgeInsets(top: 47, left: 0, bottom: 34, right: 0), orientation: .portrait))
        XCTAssertTrue(NotchScreenDetector.hasCutout(insets: UIEdgeInsets(top: 0, left: 47, bottom: 21, right: 47), orientation: .landscapeLeft))
        XCTAssertFalse(NotchScreenDetector.hasCutout(insets: UIEdgeInsets(top: 20, left: 0, bottom: 0, right: 0), orientation: .portrait))
    }
    func testScreenModeFeedsBridgeHeadersAndChangeCommand() async {
        var mode = ScreenMode.horizontal
        var changes: [ScreenMode] = []
        let backend = StubBackend(fixture())
        let sut = AppCoordinator(identity: identity, policy: policy, backend: backend,
            repository: MemoryRepository(), vault: MemoryVault(), gateway: UnavailableAuthGateway(),
            screenMode: { mode }, changeScreenMode: { requested in changes.append(requested); mode = requested })
        await sut.start()
        XCTAssertEqual(backend.requests.first?["Screenmode"], "horizontal")
        let info = await call(sut, "getGameInfo", page: "web_agreement")
        XCTAssertEqual((info?.data as? [String: Any])?["screenMode"] as? String, "horizontal")

        let unchanged = await call(sut, "changeScreenmode", ["mode": "horizontal"], page: "web_agreement")
        XCTAssertEqual(unchanged?.data as? Bool, true); XCTAssertTrue(changes.isEmpty)
        let changed = await call(sut, "changeScreenmode", ["mode": "vertical"], page: "web_agreement")
        XCTAssertEqual(changed?.data as? Bool, true); XCTAssertEqual(changes, [.vertical])
        XCTAssertEqual(sut.headers["Screenmode"], "vertical")
        let verticalInfo = await call(sut, "getGameInfo", page: "web_agreement")
        XCTAssertEqual((verticalInfo?.data as? [String: Any])?["screenMode"] as? String, "vertical")

        for invalid: Any in [[String: Any](), ["mode": "square"], ["mode": 1], false] {
            let response = await call(sut, "changeScreenmode", invalid, page: "web_agreement")
            XCTAssertEqual(response?.code, 1001); XCTAssertEqual(response?.message, "参数无效")
            XCTAssertEqual(response?.data as? Bool, false)
        }
    }
    func testScreenOrientationMappingUsesInterfaceOrientationThenBounds() {
        XCTAssertEqual(ScreenOrientationController.mode(interfaceOrientation: .landscapeLeft,
                                                        bounds: CGSize(width: 390, height: 844), fallback: .vertical), .horizontal)
        XCTAssertEqual(ScreenOrientationController.mode(interfaceOrientation: .portrait,
                                                        bounds: CGSize(width: 844, height: 390), fallback: .horizontal), .vertical)
        XCTAssertEqual(ScreenOrientationController.mode(interfaceOrientation: .unknown,
                                                        bounds: CGSize(width: 844, height: 390), fallback: .vertical), .horizontal)
        XCTAssertEqual(ScreenOrientationController.mode(interfaceOrientation: .unknown,
                                                        bounds: .zero, fallback: .vertical), .vertical)
    }
    func testChannelDirectoryFeedsBridgeAndHTTPHeadersBeforeAndAfterConsent() async throws {
        let payload = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: payload) }
        try FileManager.default.createDirectory(at: payload.appendingPathComponent("Game.app/packCode/1-218"), withIntermediateDirectories: true)
        let app = payload.appendingPathComponent("Game.app")
        let info: [String: Any] = ["CFBundleIdentifier":"test.channel.flow", "CFBundlePackageType":"APPL",
                                 "QSMGameID":"29", "CFBundleShortVersionString":"1.0.1"]
        try PropertyListSerialization.data(fromPropertyList:info,format:.xml,options:0).write(to:app.appendingPathComponent("Info.plist"))
        let channelIdentity = try RuntimeConfiguration.load(bundle:XCTUnwrap(Bundle(url:app))).identity
        let backend = StubBackend(fixture()), vault = MemoryVault()
        let sut = AppCoordinator(identity: channelIdentity, policy: policy, backend: backend,
            repository: MemoryRepository(), vault: vault, gateway: UnavailableAuthGateway())
        await sut.start()
        XCTAssertEqual(backend.requests.first?["Packagecode"], "1-218")
        let before = await call(sut, "getGameInfo", page: "web_agreement")
        XCTAssertEqual((before?.data as? [String: Any])?["packageCode"] as? String, "1-218")
        _ = await call(sut, "agreePrivacyPolicy", page: "web_agreement")
        let login = await call(sut, "notifyLoginSuccess", ["accountToken": "channel-token"], page: "web_login")
        XCTAssertEqual(login?.data as? Bool, true)
        let after = await call(sut, "getGameInfo")
        XCTAssertEqual((after?.data as? [String: Any])?["packageCode"] as? String, "1-218")
        let api = APIClient(baseURL: URL(string: "https://fixture.test")!, policy: policy, transport: CapturingTransport { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Packagecode"), "1-218")
            return (Data("{\"code\":0}".utf8), 200)
        })
        _ = try await api.initialize(headers: sut.headers)
        let opened = await call(sut, "openMiniWindow", ["page": "web_welfare"])
        let closed = await call(sut, "closeMiniWindow", ["page": "web_welfare"])
        XCTAssertEqual(opened?.data as? Bool, true); XCTAssertEqual(closed?.data as? Bool, true)
    }
    func testRejectionDoesNotGenerateIDAndCanReconsider() async {
        let (sut, repo, _, _) = make(); await sut.start()
        await assertCode(0, sut, "rejectPrivacyPolicy", page: "web_agreement")
        XCTAssertNil(repo.state?.installationID); XCTAssertEqual(repo.state?.consent, .unknown)
        await sut.reconsiderPrivacy(); XCTAssertEqual(sut.phase, .privacy)
    }
    func testPrivacyAutoFalsePersistsConsentAndInstallID() async {
        let (sut, repo, _, _) = make(privacy: false); await sut.start()
        XCTAssertEqual(sut.phase, .login); XCTAssertEqual(repo.state?.consent, .agreed); XCTAssertNotNil(repo.state?.installationID)
    }
    func testReinstallMarkerClearsStaleKeychain() async {
        let (sut, _, vault, _) = make(); vault.token = "old-installation-token"
        await sut.start(); XCTAssertNil(vault.token); XCTAssertEqual(sut.phase, .privacy)
    }
    func testRevokedOverridesPrivacyAutoFalseUntilExplicitConsent() async {
        var state = StoredState(); state.consent = .revoked
        let (sut, _, _, backend) = make(privacy: false, state: state)
        await sut.start(); XCTAssertEqual(sut.phase, .revoked); XCTAssertTrue(backend.requests.isEmpty)
        await sut.reconsiderPrivacy(); XCTAssertEqual(sut.phase, .privacy); XCTAssertEqual(sut.stored.consent, .revoked)
        _ = await call(sut, "agreePrivacyPolicy", page: "web_agreement")
        XCTAssertEqual(sut.phase, .login); XCTAssertEqual(sut.stored.consent, .agreed)
    }
    func testFailedPersistenceDoesNotReportSuccessfulLogin() async {
        let (sut, _, vault, _) = make(privacy: false); await sut.start(); vault.failWrite = true
        let response = await call(sut, "notifyLoginSuccess", ["accountToken": "h5-token"], page: "web_login")
        XCTAssertEqual(response?.code, 9999); XCTAssertEqual(sut.phase, .login); XCTAssertEqual(sut.token, "")
    }
    func testLoginSuccessIsOnlyAcceptedFromCurrentLoginPage() async {
        let (sut, _, vault, _) = make(privacy: false); await sut.start()
        await assertCode(1001, sut, "notifyLoginSuccess", ["accountToken": "h5-token"])
        XCTAssertNil(vault.token)
    }
    func testNonObjectArgsGetMethodSpecificError() async {
        let (sut, _, _, _) = make(privacy: false); await sut.start()
        let response = await call(sut, "getLocalStorage", 123, page: "web_login")
        XCTAssertEqual(response?.code, 1001); XCTAssertEqual(response?.data as? String, "")
    }
    func testStorageBatchValidationIsAtomicAndLogoutPreservesValues() async {
        let (sut, _, _, _) = make(privacy: false); await sut.start()
        let invalid: [[String: Any]] = [["key": "first", "value": 42], ["value": "bad"]]
        await assertCode(1001, sut, "saveLocalStorage", invalid)
        XCTAssertTrue(sut.stored.values.isEmpty)
        _ = await call(sut, "saveLocalStorage", [["key": "a", "value": 42], ["key": "b", "value": true], ["key": "a", "value": "last"]])
        XCTAssertEqual(sut.stored.values, ["a": "last", "b": ""])
        _ = await call(sut, "logout"); XCTAssertEqual(sut.stored.values["a"], "last")
    }
    func testClosedDocumentAndOldEpochCannotInvokeBridge() async {
        let (sut, _, _, _) = make(privacy: false); await sut.start()
        var replies = 0
        await sut.handle(method: "getGameInfo", args: [:], context: BridgeContext(instance: "web_game", epoch: sut.epoch, isCurrent: { false })) { _ in replies += 1 }
        let old = context(sut, "web_game"); _ = await call(sut, "logout")
        await sut.handle(method: "notifyLoginSuccess", args: ["accountToken": "late"], context: old) { _ in replies += 1 }
        XCTAssertEqual(replies, 0); XCTAssertEqual(sut.token, "")
    }
    func testTokenBusinessRejectionDeletesTokenButTransportFailureKeepsIt() async {
        var state = StoredState(); state.consent = .agreed; state.installationID = "installation"
        let (sut, _, vault, backend) = make(state: state); vault.token = "old"
        backend.loginHandler = { throw QSMError.business(401, "失效") }
        await sut.start(); XCTAssertNil(vault.token); XCTAssertEqual(sut.phase, .login)
        vault.token = "network-token"; backend.loginHandler = { throw URLError(.timedOut) }
        await sut.start(); XCTAssertEqual(vault.token, "network-token"); XCTAssertEqual(sut.phase, .login)
    }
    func testOldTokenLoginResponseCannotRestoreAccountAfterLogout() async {
        var state = StoredState(); state.consent = .agreed; state.installationID = "installation"
        let (sut, _, vault, backend) = make(state: state); vault.token = "old"
        var pending: CheckedContinuation<String, Error>?
        backend.loginHandler = { try await withCheckedThrowingContinuation { pending = $0 } }
        let task = Task { await sut.start() }
        guard await waitFor({ pending != nil }) else { task.cancel(); return }
        _ = await call(sut, "logout")
        pending?.resume(returning: "late-token"); await task.value
        XCTAssertNil(vault.token); XCTAssertEqual(sut.token, ""); XCTAssertEqual(sut.phase, .login)
    }
    func testOnekeySuccessCommitsOnlyAfterExchange() async {
        let gateway = StubGateway()
        let (sut, _, vault, backend) = make(privacy: false, auth: true, gateway: gateway)
        backend.exchangeHandler = { XCTAssertNil(vault.token); return "business-token" }
        await sut.start(); XCTAssertEqual(vault.token, "business-token"); XCTAssertEqual(sut.phase, .game)
    }
    func testOldExchangeCannotWriteTokenAfterLogout() async {
        let gateway = StubGateway()
        let (sut, _, vault, backend) = make(privacy: false, auth: true, gateway: gateway)
        var pending: CheckedContinuation<String, Error>?
        backend.exchangeHandler = { try await withCheckedThrowingContinuation { pending = $0 } }
        let task = Task { await sut.start() }
        guard await waitFor({ pending != nil }) else { task.cancel(); return }
        gateway.available = false
        _ = await call(sut, "logout")
        pending?.resume(returning: "late-exchange-token"); await task.value
        XCTAssertNil(vault.token); XCTAssertEqual(sut.phase, .login)
    }
    func testBackgroundBeforeExchangeCommitInvalidatesResult() async {
        let gateway = StubGateway()
        let (sut, _, vault, backend) = make(privacy: false, auth: true, gateway: gateway)
        var pending: CheckedContinuation<String, Error>?
        backend.exchangeHandler = { try await withCheckedThrowingContinuation { pending = $0 } }
        let task = Task { await sut.start() }
        guard await waitFor({ pending != nil }) else { task.cancel(); return }
        sut.enteredBackground(); pending?.resume(returning: "late-token"); await task.value
        sut.enteredForeground(); XCTAssertNil(vault.token); XCTAssertEqual(sut.phase, .login)
    }
    func testBackgroundDuringSavedTokenLoginDoesNotCommitLateResponse() async {
        var state = StoredState(); state.consent = .agreed; state.installationID = "installation"
        let (sut, _, vault, backend) = make(state: state); vault.token = "old-token"
        var pending: CheckedContinuation<String, Error>?
        backend.loginHandler = { try await withCheckedThrowingContinuation { pending = $0 } }
        let task = Task { await sut.start() }
        guard await waitFor({ pending != nil }) else { task.cancel(); return }
        sut.enteredBackground(); pending?.resume(returning: "late-token"); await task.value
        sut.enteredForeground(); XCTAssertEqual(vault.token, "old-token"); XCTAssertEqual(sut.phase, .login)
    }
    func waitFor(_ predicate: @escaping () -> Bool) async -> Bool {
        for _ in 0..<2000 {
            if predicate() { return true }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("测试异步请求未在限定时间内发起")
        return false
    }
    func testRevocationCancelRetainsAccountAndConfirmationClearsState() async {
        let (sut, repo, vault, _) = make(privacy: false); await sut.start()
        _ = await call(sut, "notifyLoginSuccess", ["accountToken": "token"], page: "web_login")
        _ = await call(sut, "saveLocalStorage", ["key": "x", "value": "y"])
        sut.confirmRevocation = { false }
        let cancelled = await call(sut, "revokePrivacyPolicy")
        XCTAssertEqual(cancelled?.data as? Bool, false); XCTAssertEqual(vault.token, "token"); XCTAssertEqual(sut.phase, .game)
        sut.confirmRevocation = { true }
        let completed = await call(sut, "revokePrivacyPolicy")
        XCTAssertEqual(completed?.data as? Bool, true); XCTAssertNil(vault.token)
        XCTAssertEqual(repo.state?.consent, .revoked); XCTAssertEqual(repo.state?.cleanupPending, false)
        XCTAssertTrue(sut.stored.values.isEmpty); XCTAssertNil(sut.stored.installationID)
    }
    func testInterruptedRevocationResumesCleanupAndRemainsRevoked() async {
        var state = StoredState(); state.consent = .revoked; state.cleanupPending = true; state.values = ["private": "data"]
        let (sut, repo, vault, backend) = make(state: state); vault.token = "stale"
        await sut.start(); XCTAssertNil(vault.token); XCTAssertEqual(repo.state?.consent, .revoked)
        XCTAssertEqual(repo.state?.cleanupPending, false); XCTAssertTrue(backend.requests.isEmpty)
    }
    func testCleanupFailureRemainsBlockedAndRetryCanFinish() async {
        let (sut, _, vault, _) = make(privacy: false); await sut.start()
        _ = await call(sut, "notifyLoginSuccess", ["accountToken": "token"], page: "web_login")
        sut.confirmRevocation = { true }; vault.failWrite = true
        let failed = await call(sut, "revokePrivacyPolicy")
        XCTAssertEqual(failed?.code, 9999); XCTAssertEqual(sut.stored.consent, .revoked); XCTAssertTrue(sut.stored.cleanupPending)
        vault.failWrite = false; await sut.retryCleanup(); XCTAssertFalse(sut.stored.cleanupPending); XCTAssertNil(vault.token)
    }
    func testGameURLChangeRejectsOtherOrigins() async {
        let (sut, _, _, _) = make(privacy: false); await sut.start()
        _ = await call(sut, "notifyLoginSuccess", ["accountToken": "token"], page: "web_login")
        await assertCode(1001, sut, "changeGameUrl", ["url": "https://evil.test/game"])
        await assertCode(0, sut, "changeGameUrl", ["url": "https://fixture.test/game?next=1"])
    }
    func testFileStoreRoundTrip() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = FileStateRepository(url: folder.appendingPathComponent("state.json"))
        XCTAssertNil(try file.load())
        var state = StoredState(); state.consent = .agreed; state.installationID = "test-installation"; state.values = ["k": "v"]
        try file.save(state); XCTAssertEqual(try file.load(), state)
    }
    func testAPIRequestContainsAllHeadersAndRejectsHTTPError() async throws {
        let transport = CapturingTransport { request in
            XCTAssertEqual(request.httpMethod, "POST"); XCTAssertEqual(request.value(forHTTPHeaderField: "Packagecode"), "")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Devicetype"), "ios")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Devicecode"), ""); XCTAssertEqual(request.value(forHTTPHeaderField: "Accountoken"), "")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Gameid"), "29"); XCTAssertEqual(request.value(forHTTPHeaderField: "Gameversion"), "1.0.1")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Notchscreen"), "0")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Screenmode"), "vertical")
            XCTAssertEqual(request.httpBody, Data("{}".utf8))
            return (Data("{\"code\":0}".utf8), 503)
        }
        let client = APIClient(baseURL: URL(string: "https://fixture.test")!, policy: policy, transport: transport)
        do { _ = try await client.initialize(headers: identity.headers(consented: false, deviceCode: "hidden", token: "hidden")); XCTFail("HTTP 503 不能成功") }
        catch { XCTAssertTrue(error.localizedDescription.contains("503")) }
    }
}
