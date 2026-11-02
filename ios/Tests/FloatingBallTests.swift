import XCTest
import UIKit
@testable import QSMH5App

@MainActor final class FloatingBallTests: XCTestCase {
    let policy = URLPolicy(localOrigin: nil)
    func raw(show: Bool = true) -> [String: Any] {
        ["show": show, "image_default": "https://fixture.test/default.png", "image_active": "https://fixture.test/active.gif",
         "width": 28, "height": 28, "right": 10, "top": 0.2]
    }
    func config(_ changes: [String: Any] = [:], removing: [String] = []) throws -> FloatingBallConfiguration {
        var value = raw(); changes.forEach { value[$0.key] = $0.value }; removing.forEach { value.removeValue(forKey: $0) }
        return try FloatingBallConfiguration.parse(value, policy: policy)
    }
    func testParsingPrioritiesAndInvalidEffectiveOffsets() throws {
        XCTAssertEqual(try config(["top": 0, "bottom": "ignored"]).top, 0)
        XCTAssertNil(try config(["top": 0, "bottom": "ignored"]).bottom)
        for bad: Any in ["0.2", true, NSNull(), -0.1] {
            XCTAssertThrowsError(try config(["top": bad]))
            XCTAssertThrowsError(try config(["bottom": bad], removing: ["top"]))
        }
        for change in [["width": 0], ["height": -1], ["width": 1.5], ["show": 1], ["image_default": "http://fixture.test/icon"]] as [[String: Any]] {
            XCTAssertThrowsError(try config(change))
        }
        var value = raw(); value.removeValue(forKey: "show")
        XCTAssertFalse(try FloatingBallConfiguration.parse(value, policy: policy).show)
    }
    func testSizeAndHorizontalOffsetsMatchAndroidLogicalUnits() throws {
        let c = try config(["width": 20, "height": 20, "left": 36, "right": 100])
        XCTAssertEqual(FloatingBallLayout.frame(configuration: c, width: 360, height: 500).width, 20)
        XCTAssertEqual(FloatingBallLayout.frame(configuration: c, width: 720, height: 500).width, 20)
        XCTAssertEqual(FloatingBallLayout.frame(configuration: c, width: 720, height: 500).minX, 72)
        XCTAssertEqual(FloatingBallLayout.frame(configuration: try config(["right": 36]), width: 720, height: 500).minX, 620)
    }
    func testVerticalRatiosTopPriorityAndAbsolutePoints() throws {
        let cases: [([String: Any], [String], Double)] = [
            (["top": 0.2, "bottom": 200], [], 200), (["bottom": 0.2], ["top"], 700),
            (["top": 0, "bottom": 0.2], [], 0), ([:], ["top", "bottom"], 0),
            (["top": 1], [], 1), (["top": 20], [], 20), (["bottom": 20], ["top"], 880),
            (["bottom": 0], ["top"], 900), (["top": 0.99], [], 900),
            (["top": Double.greatestFiniteMagnitude], [], 900), (["bottom": Double.greatestFiniteMagnitude], ["top"], 0)]
        for (changes, removing, expected) in cases {
            var all = changes; all["height"] = 100
            let frame = FloatingBallLayout.frame(configuration: try config(all, removing: removing), width: 500, height: 1000)
            XCTAssertEqual(frame.minY, expected)
        }
        XCTAssertEqual(FloatingBallLayout.frame(configuration: try config(), width: 500, height: 333).minY, 66)
        let clipped = FloatingBallLayout.frame(configuration: try config(["width": 1000, "height": 1000]), width: 90, height: 50)
        XCTAssertEqual(clipped, CGRect(x: 0, y: 0, width: 90, height: 50))
    }
    func testControllerPreservesLatestStateBehindWelfareAndResetsSession() {
        var c = FloatingBallController(initiallyEnabled: true)
        XCTAssertFalse(c.state.visible); c.sessionReady = true; XCTAssertTrue(c.state.visible)
        c.welfareAboveGame = true; c.setRuntimeState(show: false, active: true)
        c.welfareAboveGame = false; XCTAssertEqual(c.state, FloatingBallState(visible: false, active: true))
        c.resetSession(); XCTAssertEqual(c.state, .hidden)
        c.sessionReady = true; XCTAssertEqual(c.state, FloatingBallState(visible: true, active: false))
        var hidden = FloatingBallController(initiallyEnabled: false)
        hidden.setRuntimeState(show: true, active: true); hidden.resetSession(); hidden.sessionReady = true
        XCTAssertFalse(hidden.state.visible)
    }
    func make(show: Bool = true, include: Bool = true) -> AppCoordinator {
        let pages = Dictionary(uniqueKeysWithValues: ["agreement", "login", "game", "welfare"].map {
            ($0, ["instance": "web_" + $0, "url": "https://fixture.test/" + $0, "width": "100%", "height": "100%", "left": 0, "top": 0] as [String: Any])
        })
        var body: [String: Any] = ["privacyAuto": false, "h5Pages": pages]
        if include { body["floatball"] = raw(show: show) }
        let data = try! JSONSerialization.data(withJSONObject: ["code": 0, "data": body])
        return AppCoordinator(identity: GameIdentity(gameID: 29, version: "1.0.1", packageCode: "1-218"), policy: policy,
            backend: StubBackend(data), repository: MemoryRepository(), vault: MemoryVault(), gateway: UnavailableAuthGateway())
    }
    func context(_ sut: AppCoordinator, _ instance: String = "web_game") -> BridgeContext {
        BridgeContext(instance: instance, epoch: sut.epoch, isCurrent: { true })
    }
    func call(_ sut: AppCoordinator, _ method: String, _ args: [String: Any] = [:], instance: String = "web_game") async -> BridgeResult? {
        var reply: BridgeResult?
        await sut.handle(method: method, args: args, context: context(sut, instance)) { reply = $0 }
        return reply
    }
    func login(_ sut: AppCoordinator) async {
        _ = await call(sut, "notifyLoginSuccess", ["accountToken": "float-token"], instance: "web_login")
    }
    func testCoordinatorRequiresLoadedGameAndRestoresStateAfterWelfareAndGameFront() async {
        let sut = make(); await sut.start(); XCTAssertFalse(sut.floatingBallState.visible)
        await login(sut); XCTAssertFalse(sut.floatingBallState.visible)
        sut.gameDocumentBecameVisible(context: context(sut)); XCTAssertTrue(sut.floatingBallState.visible)
        sut.openWelfareFromFloatingBall(); XCTAssertFalse(sut.floatingBallState.visible)
        let changed = await call(sut, "setFloatingBall", ["show": true, "active": true], instance: "web_welfare")
        XCTAssertEqual(changed?.data as? Bool, true); XCTAssertFalse(sut.floatingBallState.visible)
        _ = await call(sut, "changeGameUrl", ["url": "https://fixture.test/game-next"], instance: "web_welfare")
        XCTAssertEqual(sut.floatingBallState, FloatingBallState(visible: true, active: true))
        _ = await call(sut, "openMiniWindow", ["page": "web_welfare"])
        _ = await call(sut, "setFloatingBall", ["show": false, "active": false], instance: "web_welfare")
        _ = await call(sut, "closePage", instance: "web_welfare")
        XCTAssertEqual(sut.floatingBallState, .hidden)
    }
    func testLogoutResetOldEpochAndInitiallyHiddenCanBeEnabled() async {
        let sut = make(show: false); await sut.start(); await login(sut)
        sut.gameDocumentBecameVisible(context: context(sut)); XCTAssertFalse(sut.floatingBallState.visible)
        _ = await call(sut, "setFloatingBall", ["show": true, "active": true]); XCTAssertTrue(sut.floatingBallState.visible)
        let old = context(sut)
        _ = await call(sut, "logout"); XCTAssertEqual(sut.floatingBallState, .hidden)
        sut.gameDocumentBecameVisible(context: old); XCTAssertFalse(sut.floatingBallState.visible)
        await login(sut); sut.gameDocumentBecameVisible(context: context(sut)); XCTAssertEqual(sut.floatingBallState, .hidden)
        _ = await call(sut, "setFloatingBall", ["show": true, "active": false]); XCTAssertTrue(sut.floatingBallState.visible)
        _ = await call(sut, "closePage"); XCTAssertFalse(sut.floatingBallState.visible)
    }
    func testBridgeBadTypesAndMissingConfigurationDoNotBlockGame() async {
        let sut = make(include: false); await sut.start(); await login(sut)
        XCTAssertEqual(sut.phase, .game)
        let absent = await call(sut, "setFloatingBall", ["show": true, "active": true])
        XCTAssertEqual(absent?.code, 0); XCTAssertEqual(absent?.data as? Bool, false)
        let invalid = await call(sut, "setFloatingBall", ["show": 1, "active": false])
        XCTAssertEqual(invalid?.code, 1001); XCTAssertEqual(invalid?.data as? Bool, false)
    }
    func testImageStateAndVisibilityDoNotReloadSameURLAndLayoutDoesNotResetDrag() async throws {
        var requests: [URL] = []
        let ball = FloatingBallView(configuration: try config(), imageLoader: { url in requests.append(url); return UIImage() })
        ball.layout(in: CGSize(width: 720, height: 400))
        ball.setActive(false)
        await Task.yield(); await Task.yield()
        ball.setActive(false); ball.isHidden = true; ball.isHidden = false
        XCTAssertEqual(requests.count, 1)
        ball.setActive(true); await Task.yield(); await Task.yield()
        XCTAssertEqual(requests.count, 2)
        ball.setActive(false); await Task.yield(); XCTAssertEqual(requests.count, 2)
        ball.frame.origin.y = 120; ball.layout(in: CGSize(width: 720, height: 400)); XCTAssertEqual(ball.frame.minY, 120)
        ball.layout(in: CGSize(width: 400, height: 720)); XCTAssertEqual(ball.frame.minY, 144)
        let layer = FloatingBallLayer(frame: CGRect(x: 0, y: 0, width: 400, height: 720)); layer.addSubview(ball)
        XCTAssertNil(layer.hitTest(CGPoint(x: 1, y: 700), with: nil))
        ball.destroy()
    }
    func testPollingConfigurationAndMalformedResponsesDoNotChangeState() throws {
        XCTAssertNil(FloatingBallPollingConfiguration.parse(["url": "https://fixture.test/poll", "interval": 0], policy: policy))
        XCTAssertNil(FloatingBallPollingConfiguration.parse(["url": "http://fixture.test/poll", "interval": 30], policy: policy))
        for json in ["{\"code\":0,\"data\":[]}", "{\"code\":0,\"data\":{}}", "{\"code\":0,\"data\":{\"floating_ball\":{\"show\":1,\"active\":true}}}"] {
            XCTAssertNil(try FloatingBallPollingConfiguration.state(from: Data(json.utf8)))
        }
        XCTAssertThrowsError(try FloatingBallPollingConfiguration.state(from: Data("{\"code\":1}".utf8)))
    }
    func testHeartbeatRequestUsesPOSTEmptyBodyAndSameSixHeaders() async throws {
        let identity = GameIdentity(gameID: 29, version: "1.0.1", packageCode: "1-218")
        let headers = identity.headers(consented: true, deviceCode: "device", token: "token")
        let api = APIClient(baseURL: URL(string: "https://fixture.test")!, policy: policy, transport: CapturingTransport { request in
            XCTAssertEqual(request.httpMethod, "POST"); XCTAssertEqual(request.httpBody, Data("{}".utf8))
            for (key, value) in headers { XCTAssertEqual(request.value(forHTTPHeaderField: key), value) }
            return (Data("{\"code\":0}".utf8), 200)
        })
        _ = try await api.floatingBallHeartbeat(url: URL(string: "https://fixture.test/poll")!, headers: headers)
    }
}
