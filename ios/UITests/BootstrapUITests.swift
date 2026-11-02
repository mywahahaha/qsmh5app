import XCTest

@MainActor final class BootstrapUITests: XCTestCase {
    let app = XCUIApplication()
    override func setUpWithError() throws { continueAfterFailure = false }
    func launch(_ scenario: String = "normal", reset: Bool = true) {
        app.launchArguments = reset ? ["-qsm-reset"] : []
        app.launchEnvironment["QSM_LOCAL_SCENARIO"] = scenario
        app.launch()
    }
    func text(_ value: String) -> XCUIElement { app.webViews.staticTexts[value].firstMatch }
    func button(_ value: String) -> XCUIElement { app.webViews.buttons[value].firstMatch }
    func agreeAndLogin() {
        XCTAssertTrue(button("同意并继续").waitForExistence(timeout: 15))
        button("同意并继续").tap()
        XCTAssertTrue(button("使用测试账号登录").waitForExistence(timeout: 10))
        button("使用测试账号登录").tap()
        XCTAssertTrue(text("游戏页面（本地测试）").waitForExistence(timeout: 10))
    }
    func testPrivacyRejectReconsiderAndLogin() {
        launch()
        XCTAssertTrue(button("拒绝").waitForExistence(timeout: 15)); button("拒绝").tap()
        XCTAssertTrue(app.staticTexts["需要同意隐私政策后继续"].waitForExistence(timeout: 5))
        app.buttons["重新选择"].tap(); agreeAndLogin()
        button("读取游戏信息").tap()
        XCTAssertTrue(text("gameId=29 deviceType=ios 设备码已生成 已登录 packageCode=").waitForExistence(timeout: 5))
    }
    func testMalformedArgumentsReturnOnOriginalCallbackAndUnavailableSDKKeepsH5() {
        launch("no-privacy")
        XCTAssertTrue(button("校验错误参数").waitForExistence(timeout: 15)); button("校验错误参数").tap()
        XCTAssertTrue(text("结果：1001 \"\"").waitForExistence(timeout: 5))
        button("尝试一键登录").tap()
        XCTAssertTrue(text("结果：3003 null").waitForExistence(timeout: 5))
        XCTAssertTrue(button("使用测试账号登录").exists)
    }
    func testWelfareReusesGameDocumentAndCrossOriginNavigationIsRejected() {
        launch(); agreeAndLogin()
        button("增加游戏计数").tap(); XCTAssertTrue(text("游戏计数：1").exists)
        button("打开福利窗口").tap()
        XCTAssertTrue(button("返回游戏").waitForExistence(timeout: 5)); button("返回游戏").tap()
        XCTAssertTrue(text("游戏计数：1").waitForExistence(timeout: 5))
        app.webViews.links["尝试跨来源导航"].tap()
        XCTAssertTrue(text("游戏页面（本地测试）").exists); XCTAssertFalse(text("不可信页面").exists)
        button("切换游戏地址").tap()
        XCTAssertTrue(text("游戏计数：0").waitForExistence(timeout: 5))
        button("读取游戏信息").tap()
        XCTAssertTrue(text("gameId=29 deviceType=ios 设备码已生成 已登录 packageCode=").waitForExistence(timeout: 5))
        button("关闭游戏").tap()
        XCTAssertTrue(app.staticTexts["游戏已关闭"].waitForExistence(timeout: 5))
    }
    func testLogoutPreservesGenericStorageAndRelaunchUsesSavedToken() {
        launch(); agreeAndLogin()
        button("保存测试数据").tap(); XCTAssertTrue(text("结果：0 true").waitForExistence(timeout: 5))
        button("退出登录").tap()
        XCTAssertTrue(button("使用测试账号登录").waitForExistence(timeout: 5)); button("使用测试账号登录").tap()
        XCTAssertTrue(button("读取测试数据").waitForExistence(timeout: 5)); button("读取测试数据").tap()
        XCTAssertTrue(text("结果：0 \"42\"").waitForExistence(timeout: 5))
        app.terminate(); launch(reset: false)
        XCTAssertTrue(text("游戏页面（本地测试）").waitForExistence(timeout: 15))
        XCTAssertFalse(button("同意并继续").exists); XCTAssertFalse(button("使用测试账号登录").exists)
    }
    func testPrivacyRevocationConfirmationAndRestartRemainBlocked() {
        launch(); agreeAndLogin()
        button("撤回隐私同意").tap()
        XCTAssertTrue(app.alerts["撤回隐私同意"].waitForExistence(timeout: 5)); app.alerts.buttons["取消"].tap()
        XCTAssertTrue(text("游戏页面（本地测试）").exists)
        button("撤回隐私同意").tap(); app.alerts.buttons["确认撤回"].tap()
        XCTAssertTrue(app.staticTexts["已撤回隐私同意"].waitForExistence(timeout: 10))
        app.terminate(); launch("no-privacy", reset: false)
        XCTAssertTrue(app.staticTexts["已撤回隐私同意"].waitForExistence(timeout: 10))
        app.buttons["查看协议并重新选择"].tap()
        XCTAssertTrue(button("同意并继续").waitForExistence(timeout: 10))
    }
    func testInitBusinessAndMalformedResponseDoNotOpenLogin() {
        launch("init-error")
        XCTAssertTrue(app.staticTexts["游戏初始化失败：本地初始化失败"].waitForExistence(timeout: 15))
        XCTAssertFalse(button("使用测试账号登录").exists)
        app.terminate(); launch("invalid-code")
        XCTAssertTrue(app.staticTexts["游戏初始化失败：响应缺少整数 code"].waitForExistence(timeout: 15))
        XCTAssertFalse(button("使用测试账号登录").exists)
    }
    func testActualHTTPReceiverSeesExplicitEmptyHeaders() async throws {
        launch(); agreeAndLogin(); app.terminate(); launch(reset: false)
        XCTAssertTrue(text("游戏页面（本地测试）").waitForExistence(timeout: 15))
        let (data, _) = try await URLSession.shared.data(from: URL(string: "http://localhost:18740/__requests")!)
        let rows = try JSONSerialization.jsonObject(with: data) as! [[String: Any]]
        let initRow = rows.last { ($0["path"] as? String)?.hasSuffix("h5_app/init") == true }
        let headers = initRow?["headers"] as? [String: Any]
        XCTAssertEqual(headers?["Packagecode"] as? String, "")
        XCTAssertEqual(headers?["Devicetype"] as? String, "ios"); XCTAssertEqual(headers?["Gameid"] as? String, "29")
        let initial = rows.first { ($0["headers"] as? [String: Any])?["Devicecode"] as? String == "" }
        XCTAssertEqual((initial?["headers"] as? [String: Any])?["Accountoken"] as? String, "")
    }
    var ball: XCUIElement { app.buttons["floating.ball"] }
    func attachScreenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name
        attachment.lifetime = .keepAlways; add(attachment)
    }
    func waitForBall(visible: Bool, active: Bool? = nil, timeout: TimeInterval = 5) {
        let predicate = NSPredicate { [self] _, _ in
            guard ball.exists == visible else { return false }
            return !visible || active == nil || ball.value as? String == (active! ? "active" : "default")
        }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: nil)], timeout: timeout), .completed)
    }
    func testFloatingBallDragWelfareReuseAndLatestRuntimeState() async throws {
        launch("floatball")
        XCTAssertTrue(button("同意并继续").waitForExistence(timeout: 15)); XCTAssertFalse(ball.exists)
        button("同意并继续").tap()
        XCTAssertTrue(button("使用测试账号登录").waitForExistence(timeout: 10)); XCTAssertFalse(ball.exists)
        button("使用测试账号登录").tap(); waitForBall(visible: true, active: false)
        attachScreenshot("悬浮球_默认位置")
        button("增加游戏计数").tap()
        let initialX = ball.frame.minX
        let bottom = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0)).withOffset(CGVector(dx: ball.frame.midX, dy: app.frame.maxY - 2))
        ball.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 0.1, thenDragTo: bottom)
        XCTAssertEqual(ball.frame.minX, initialX, accuracy: 1); XCTAssertLessThanOrEqual(ball.frame.maxY, app.frame.maxY)
        XCTAssertFalse(button("返回游戏").exists); XCTAssertTrue(text("游戏计数：1").exists)
        let top = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0)).withOffset(CGVector(dx: ball.frame.midX, dy: 1))
        ball.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 0.1, thenDragTo: top)
        XCTAssertEqual(ball.frame.minX, initialX, accuracy: 1); XCTAssertGreaterThanOrEqual(ball.frame.minY, 0)
        let draggedY = ball.frame.minY
        ball.tap(); XCTAssertTrue(button("返回游戏").waitForExistence(timeout: 5)); waitForBall(visible: false)
        attachScreenshot("悬浮球_福利置顶暂隐")
        button("返回游戏").tap(); waitForBall(visible: true, active: false)
        XCTAssertTrue(text("游戏计数：1").exists); XCTAssertEqual(ball.frame.minY, draggedY, accuracy: 1)
        ball.tap(); button("福利中隐藏激活球").tap(); button("返回游戏").tap(); waitForBall(visible: false)
        button("显示激活悬浮球").tap(); waitForBall(visible: true, active: true)
        ball.tap(); button("换地址返回游戏").tap(); waitForBall(visible: true, active: true)
        XCTAssertTrue(text("游戏计数：0").waitForExistence(timeout: 5))
        button("增加游戏计数").tap(); ball.tap(); button("返回游戏").tap()
        waitForBall(visible: true, active: true); XCTAssertTrue(text("游戏计数：1").waitForExistence(timeout: 5))
        attachScreenshot("悬浮球_激活及福利返回保留新文档")
        button("恢复默认悬浮球").tap(); waitForBall(visible: true, active: false)
        let (data, _) = try await URLSession.shared.data(from: URL(string: "http://localhost:18740/__requests")!)
        let rows = try JSONSerialization.jsonObject(with: data) as! [[String: Any]]
        XCTAssertEqual(rows.filter { $0["path"] as? String == "/images/default.png" }.count, 1)
        XCTAssertEqual(rows.filter { $0["path"] as? String == "/images/active.png" }.count, 1)
        button("关闭游戏").tap(); XCTAssertTrue(app.staticTexts["游戏已关闭"].waitForExistence(timeout: 5)); waitForBall(visible: false)
    }
    func testFloatingBallInitiallyHiddenAndLogoutResetsState() {
        launch("floatball-hidden"); agreeAndLogin(); waitForBall(visible: false)
        button("显示激活悬浮球").tap(); waitForBall(visible: true, active: true)
        button("退出登录").tap(); XCTAssertTrue(button("使用测试账号登录").waitForExistence(timeout: 5)); waitForBall(visible: false)
        button("使用测试账号登录").tap(); XCTAssertTrue(text("游戏页面（本地测试）").waitForExistence(timeout: 5)); waitForBall(visible: false)
        button("恢复默认悬浮球").tap(); waitForBall(visible: true, active: false)
    }
    func testFloatingBallHeartbeatCanRestoreHiddenBallAndUsesSessionHeaders() async throws {
        launch("floatball-heartbeat"); agreeAndLogin(); waitForBall(visible: true, active: false)
        waitForBall(visible: false, timeout: 5); waitForBall(visible: true, active: true, timeout: 5)
        let (data, _) = try await URLSession.shared.data(from: URL(string: "http://localhost:18740/__requests")!)
        let rows = try JSONSerialization.jsonObject(with: data) as! [[String: Any]]
        let polls = rows.filter { $0["path"] as? String == "/scenario/floatball-heartbeat/poll" }
        XCTAssertGreaterThanOrEqual(polls.count, 2)
        let headers = polls.last?["headers"] as? [String: String]
        XCTAssertEqual(headers?.count, 6); XCTAssertEqual(headers?["Accountoken"], "fixture-h5-token")
        XCTAssertEqual(headers?["Gameid"], "29"); XCTAssertEqual(headers?["Devicetype"], "ios")
        XCTAssertEqual(headers?["Packagecode"], ""); XCTAssertFalse(headers?["Devicecode"]?.isEmpty ?? true)
        XCTAssertEqual((polls.last?["body"] as? [String: Any])?.count, 0)
    }
}
