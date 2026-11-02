import XCTest
@testable import QSMH5App

@MainActor private final class ManualHeartbeatClock {
    var delays: [Int] = []
    var waiting: [CheckedContinuation<Void, Error>] = []
    func sleep(_ seconds: Int) async throws {
        delays.append(seconds)
        try await withCheckedThrowingContinuation { waiting.append($0) }
    }
    func advance() { waiting.removeFirst().resume() }
    func cancelAll() { let old = waiting; waiting.removeAll(); old.forEach { $0.resume(throwing: CancellationError()) } }
}

@MainActor final class FloatingBallHeartbeatTests: XCTestCase {
    func waitFor(_ condition: @escaping () -> Bool) async -> Bool {
        for _ in 0..<2000 {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("心跳测试未到达预期状态"); return false
    }
    func testWaitsFullIntervalAndKeepsPollingWhileHiddenWithoutParallelRequests() async {
        let clock = ManualHeartbeatClock(); var polls = 0, delivered = 0
        let service = FloatingBallHeartbeat(interval: 30, sleep: clock.sleep, poll: { polls += 1; return Data() }, deliver: { _ in delivered += 1 })
        defer { service.stop(); clock.cancelAll() }
        service.update(sessionReady: false, visible: true, foreground: true)
        service.update(sessionReady: true, visible: false, foreground: true)
        await Task.yield(); XCTAssertTrue(clock.delays.isEmpty)
        service.update(sessionReady: true, visible: true, foreground: true)
        guard await waitFor({ clock.waiting.count == 1 }) else { return }
        XCTAssertEqual(polls, 0); XCTAssertEqual(clock.delays, [30])
        for _ in 0..<5 { service.update(sessionReady: true, visible: true, foreground: true) }
        XCTAssertEqual(clock.waiting.count, 1)
        clock.advance()
        guard await waitFor({ delivered == 1 && clock.waiting.count == 1 }) else { return }
        service.update(sessionReady: true, visible: false, foreground: true)
        clock.advance()
        guard await waitFor({ delivered == 2 && clock.waiting.count == 1 }) else { return }
        XCTAssertEqual(polls, 2); XCTAssertEqual(clock.delays, [30, 30, 30])
    }
    func testLateHTTPResponseCannotAffectStoppedOrNewSession() async {
        let clock = ManualHeartbeatClock(); var pending: CheckedContinuation<Data, Error>?
        var polls = 0, delivered = 0
        let service = FloatingBallHeartbeat(interval: 30, sleep: clock.sleep, poll: {
            polls += 1
            if polls == 1 { return try await withCheckedThrowingContinuation { pending = $0 } }
            return Data()
        }, deliver: { _ in delivered += 1 })
        defer { service.stop(); clock.cancelAll(); pending?.resume(throwing: CancellationError()) }
        service.update(sessionReady: true, visible: true, foreground: true)
        guard await waitFor({ clock.waiting.count == 1 }) else { return }
        clock.advance()
        guard await waitFor({ pending != nil }) else { return }
        service.stop(); service.update(sessionReady: true, visible: true, foreground: true)
        let old = pending; pending = nil; old?.resume(returning: Data())
        guard await waitFor({ clock.waiting.count == 1 }) else { return }
        XCTAssertEqual(delivered, 0)
        clock.advance()
        guard await waitFor({ delivered == 1 && clock.waiting.count == 1 }) else { return }
        XCTAssertEqual(polls, 2)
    }
    func testBackgroundPausesAndForegroundCanResumeAlreadyStartedHiddenBall() async {
        let clock = ManualHeartbeatClock(); var polls = 0
        let service = FloatingBallHeartbeat(interval: 30, sleep: clock.sleep, poll: { polls += 1; return Data() }, deliver: { _ in })
        defer { service.stop(); clock.cancelAll() }
        service.update(sessionReady: true, visible: true, foreground: true)
        guard await waitFor({ clock.waiting.count == 1 }) else { return }
        service.update(sessionReady: true, visible: false, foreground: false); clock.cancelAll()
        await Task.yield(); XCTAssertEqual(polls, 0)
        service.update(sessionReady: true, visible: false, foreground: true)
        guard await waitFor({ clock.waiting.count == 1 }) else { return }
        clock.advance()
        guard await waitFor({ polls == 1 && clock.waiting.count == 1 }) else { return }
        XCTAssertEqual(clock.delays, [30, 30, 30])
    }
    func testFailureRetriesOnlyOnNextFullInterval() async {
        let clock = ManualHeartbeatClock(); var polls = 0, delivered = 0
        let service = FloatingBallHeartbeat(interval: 30, sleep: clock.sleep, poll: {
            polls += 1; if polls == 1 { throw URLError(.timedOut) }; return Data()
        }, deliver: { _ in delivered += 1 })
        defer { service.stop(); clock.cancelAll() }
        service.update(sessionReady: true, visible: true, foreground: true)
        guard await waitFor({ clock.waiting.count == 1 }) else { return }
        clock.advance()
        guard await waitFor({ polls == 1 && clock.waiting.count == 1 }) else { return }
        XCTAssertEqual(delivered, 0); XCTAssertEqual(clock.delays, [30, 30])
        clock.advance()
        guard await waitFor({ delivered == 1 && clock.waiting.count == 1 }) else { return }
    }
}
