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
    var loginHandler: (() async throws -> String)?
    var exchangeHandler: (() async throws -> String)?
    var heartbeatHandler: (() async throws -> Data)?
    init(_ response: Data) { self.response = response }
    func initialize(headers: [String: String]) async throws -> Data { requests.append(headers); return response }
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
        XCTAssertEqual(sut.stored.deviceCode, "")
        _ = await call(sut, "agreePrivacyPolicy", page: "web_agreement")
        XCTAssertEqual(sut.stored.deviceCode.count, 32)
        _ = await call(sut, "notifyLoginSuccess", ["accountToken": "h5-token"], page: "web_login")
        XCTAssertEqual(vault.token, "h5-token"); XCTAssertEqual(sut.phase, .game)
    }
    func testChannelDirectoryFeedsBridgeAndHTTPHeadersBeforeAndAfterConsent() async throws {
        let payload = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: payload) }
        try FileManager.default.createDirectory(at: payload.appendingPathComponent("Game.app/packCode/1-218"), withIntermediateDirectories: true)
        let channelIdentity = GameIdentity(gameID: 29, version: "1.0.1", packageCode: ChannelCodeReader.read(payloadDirectory: payload))
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
    func testAPIRequestContainsAllSixHeadersAndRejectsHTTPError() async throws {
        let transport = CapturingTransport { request in
            XCTAssertEqual(request.httpMethod, "POST"); XCTAssertEqual(request.value(forHTTPHeaderField: "Packagecode"), "")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Devicetype"), "ios")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Devicecode"), ""); XCTAssertEqual(request.value(forHTTPHeaderField: "Accountoken"), "")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Gameid"), "29"); XCTAssertEqual(request.value(forHTTPHeaderField: "Gameversion"), "1.0.1")
            XCTAssertEqual(request.httpBody, Data("{}".utf8))
            return (Data("{\"code\":0}".utf8), 503)
        }
        let client = APIClient(baseURL: URL(string: "https://fixture.test")!, policy: policy, transport: transport)
        do { _ = try await client.initialize(headers: identity.headers(consented: false, deviceCode: "hidden", token: "hidden")); XCTFail("HTTP 503 不能成功") }
        catch { XCTAssertTrue(error.localizedDescription.contains("503")) }
    }
}
