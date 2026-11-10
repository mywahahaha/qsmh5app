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
    func testOptionalUpdateDismissesToLoginAndForceHasNoClose() {
        launch("update-offer")
        XCTAssertTrue(button("同意并继续").waitForExistence(timeout: 15)); button("同意并继续").tap()
        XCTAssertTrue(app.buttons["update.action"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons["update.close"].exists)
        XCTAssertTrue(app.staticTexts["发现新版本，体验优化"].isHittable)
        XCTAssertTrue(app.staticTexts["最新版本：1.0.2"].isHittable)
        XCTAssertTrue(app.staticTexts["更新时间：2026-11-04"].isHittable)
        attachScreenshot("普通更新弹窗")
        app.buttons["update.close"].tap()
        XCTAssertTrue(button("使用测试账号登录").waitForExistence(timeout: 8))
        launch("update-force")
        XCTAssertTrue(button("同意并继续").waitForExistence(timeout: 15)); button("同意并继续").tap()
        XCTAssertTrue(app.buttons["update.action"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["update.close"].exists)
        attachScreenshot("强制更新弹窗")
    }
    func testInvalidUpdateEntryContinuesToLogin() {
        launch("update-invalid")
        XCTAssertTrue(button("同意并继续").waitForExistence(timeout: 15)); button("同意并继续").tap()
        XCTAssertTrue(button("使用测试账号登录").waitForExistence(timeout: 8))
    }
    func testFullScreenGameAndPopupReachWindowEdgesAndCannotDragPage() {
        launch("fullscreen"); agreeAndLogin()
        func assertFullScreen(_ name: String) {
            let web = app.webViews[name].firstMatch
            XCTAssertTrue(web.exists)
            XCTAssertEqual(web.frame.minX,app.frame.minX,accuracy:1)
            XCTAssertEqual(web.frame.minY,app.frame.minY,accuracy:1)
            XCTAssertEqual(web.frame.maxX,app.frame.maxX,accuracy:1)
            XCTAssertEqual(web.frame.maxY,app.frame.maxY,accuracy:1)
        }
        assertFullScreen("web_game")
        let before = text("游戏页面（本地测试）").frame
        app.webViews["web_game"].swipeUp()
        XCTAssertEqual(text("游戏页面（本地测试）").frame,before)
        button("检查页面滚动").tap(); XCTAssertTrue(text("页面滚动：0").waitForExistence(timeout:5))
        button("打开福利窗口").tap()
        XCTAssertTrue(button("返回游戏").waitForExistence(timeout:5)); assertFullScreen("web_welfare")
        attachScreenshot("全屏H5福利_铺满四边")
        app.webViews["web_welfare"].swipeDown()
        XCTAssertTrue(button("返回游戏").isHittable)
        button("返回游戏").tap(); assertFullScreen("web_game")
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
    func testChangeScreenModeIsIdempotentAndRotatesBothDirections() {
        launch(); agreeAndLogin()
        XCTAssertGreaterThan(app.frame.width, app.frame.height)
        button("切换横屏").tap()
        XCTAssertTrue(text("结果：0 true").waitForExistence(timeout: 5))
        button("切换竖屏").tap()
        let portrait = NSPredicate { [self] _, _ in app.frame.height > app.frame.width }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: portrait, object: app)], timeout: 8), .completed)
        button("切换横屏").tap()
        let landscape = NSPredicate { [self] _, _ in app.frame.width > app.frame.height }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: landscape, object: app)], timeout: 8), .completed)
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
        XCTAssertNotNil(headers?["Notchscreen"] as? String)
        XCTAssertEqual(headers?["Screenmode"] as? String, "horizontal")
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
    func testFloatingBallRightZeroReachesScreenEdge() {
        launch("floatball-edge"); agreeAndLogin()
        waitForBall(visible: true, active: false)
        XCTAssertEqual(ball.frame.maxX, app.frame.maxX, accuracy: 1)
        attachScreenshot("悬浮球_right0_贴屏幕右边")
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
        launch("floatball-heartbeat"); agreeAndLogin()
        // 首次轮询可能早于 XCTest 完成页面查询；等待第二次轮询恢复后的最终状态。
        waitForBall(visible: true, active: true, timeout: 12)
        let (data, _) = try await URLSession.shared.data(from: URL(string: "http://localhost:18740/__requests")!)
        let rows = try JSONSerialization.jsonObject(with: data) as! [[String: Any]]
        let polls = rows.filter { $0["path"] as? String == "/scenario/floatball-heartbeat/poll" }
        XCTAssertGreaterThanOrEqual(polls.count, 2)
        let headers = polls.last?["headers"] as? [String: String]
        XCTAssertEqual(headers?.count, 8); XCTAssertEqual(headers?["Accountoken"], "fixture-h5-token")
        XCTAssertTrue(["0", "1"].contains(headers?["Notchscreen"] ?? ""))
        XCTAssertEqual(headers?["Screenmode"], "horizontal")
        XCTAssertEqual(headers?["Gameid"], "29"); XCTAssertEqual(headers?["Devicetype"], "ios")
        XCTAssertEqual(headers?["Packagecode"], ""); XCTAssertFalse(headers?["Devicecode"]?.isEmpty ?? true)
        XCTAssertEqual((polls.last?["body"] as? [String: Any])?.count, 0)
    }
    func testRemoteBackgroundRemainsBehindLoginGameAndWelfareAndClearsOnRevocation() {
        launch("background")
        let background = app.images["startup.background"]
        let loaded = NSPredicate(format: "value == %@", "loaded")
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: loaded, object: background)], timeout: 10), .completed)
        agreeAndLogin(); attachScreenshot("背景图_透明游戏及安全区域")
        button("打开福利窗口").tap(); XCTAssertTrue(button("返回游戏").waitForExistence(timeout: 5))
        XCTAssertEqual(background.value as? String, "loaded")
        button("返回游戏").tap(); button("撤回隐私同意").tap(); app.alerts.buttons["确认撤回"].tap()
        XCTAssertTrue(app.staticTexts["已撤回隐私同意"].waitForExistence(timeout: 10))
        XCTAssertEqual(background.value as? String, "empty")
    }
    func testBackgroundDownloadFailureDoesNotBlockGame() {
        launch("background-fail"); agreeAndLogin()
        XCTAssertEqual(app.images["startup.background"].value as? String, "empty")
        attachScreenshot("背景图失败_游戏正常")
    }
    func testSlowFirstPageAndNoSplashReplayAfterLogin() {
        launch("slow-page"); agreeAndLogin()
        XCTAssertFalse(app.otherElements["startup.splash"].exists)
        button("退出登录").tap()
        XCTAssertTrue(button("使用测试账号登录").waitForExistence(timeout: 10))
        XCTAssertFalse(app.otherElements["startup.splash"].exists)
        attachScreenshot("慢页面就绪_闪图不重播")
    }

    func testFirstNetworkAuthorizationWaitKeepsSplashBeyondFiveSecondsAndResumes() async throws {
        func gate(_ action: String) async throws {
            var request = URLRequest(url: URL(string: "http://localhost:18740/__network/" + action)!)
            request.httpMethod = "POST"
            _ = try await URLSession.shared.data(for: request)
        }
        try await gate("reset")
        launch("network-wait")
        XCTAssertTrue(app.staticTexts["等待网络连接…\n请在系统提示中允许使用网络"].waitForExistence(timeout: 10))
        try await Task.sleep(nanoseconds: 6_000_000_000)
        XCTAssertTrue(app.otherElements["startup.splash"].exists)
        XCTAssertFalse(app.buttons["重新初始化"].exists)
        XCTAssertFalse(button("同意并继续").exists)
        attachScreenshot("首次联网等待_超过五秒保留闪图")
        try await gate("release")
        agreeAndLogin()
        XCTAssertFalse(app.otherElements["startup.splash"].exists)
        app.terminate(); launch("network-wait", reset: false)
        XCTAssertTrue(text("游戏页面（本地测试）").waitForExistence(timeout: 15))
    }

}
