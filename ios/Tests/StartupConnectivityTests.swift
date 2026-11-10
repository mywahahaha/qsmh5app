import XCTest
@testable import QSMH5App

@MainActor final class StartupConnectivityTests: XCTestCase {
    func testOnlyRecoverableNetworkErrorsWait() {
        for code: URLError.Code in [.notConnectedToInternet,.networkConnectionLost,.timedOut,.dataNotAllowed,.dnsLookupFailed] {
            XCTAssertTrue(StartupConnectivity.shouldWait(for: URLError(code)))
        }
        for error: Error in [URLError(.cancelled),URLError(.secureConnectionFailed),QSMError.business(100,"失败"),QSMError.storage("失败")] {
            XCTAssertFalse(StartupConnectivity.shouldWait(for: error))
        }
        XCTAssertEqual(StartupConnectivity.retryDelay(attempt: 999),10)
    }
    func make(backend: StubBackend, pause: @escaping @MainActor (TimeInterval) async throws -> Void) -> AppCoordinator {
        AppCoordinator(identity: GameIdentity(gameID:29,version:"1.0.1",packageCode:""),policy:URLPolicy(localOrigin:nil),
            backend:backend,repository:MemoryRepository(),vault:MemoryVault(),gateway:UnavailableAuthGateway(),initializationPause:pause)
    }
    func testNetworkDenialRetriesAndConsentStillRequiredAfterRecovery() async {
        let data = CoreTests().fixture()
        let backend = StubBackend(data)
        var calls = 0; var waits = 0; var failures = 0; var pending: [Bool] = []
        backend.initializeHandler = {
            calls += 1
            if calls < 3 { throw URLError(.notConnectedToInternet) }
            return data
        }
        let sut = make(backend:backend,pause:{ _ in await Task.yield() })
        sut.onView = { event in
            if case .status(.waitingForNetwork) = event { waits += 1 }
            if case .status(.failure) = event { failures += 1 }
            if case .initializationPending(let value) = event { pending.append(value) }
        }
        await sut.start()
        XCTAssertEqual(calls,3); XCTAssertEqual(waits,2); XCTAssertEqual(failures,0)
        XCTAssertEqual(pending,[true,false]); XCTAssertEqual(sut.phase,.privacy)
        XCTAssertEqual(sut.stored.consent,.unknown)
        XCTAssertTrue(backend.requests.allSatisfy { $0["Devicecode"] == "" && $0["Accountoken"] == "" })
    }
    func testBusinessFailureDoesNotRetryOrRemainBehindSplash() async {
        let backend = StubBackend(Data("{\"code\":100,\"msg\":\"业务失败\"}".utf8))
        var pauses = 0; var failure = false; var released = false
        let sut = make(backend:backend,pause:{ _ in pauses += 1 })
        sut.onView = { event in
            if case .status(.failure) = event { failure = true }
            if case .initializationPending(false) = event { released = true }
        }
        await sut.start()
        XCTAssertEqual(pauses,0); XCTAssertTrue(failure); XCTAssertTrue(released)
    }
    func testNewInitializationCancelsOldConnectivityWait() async {
        let data = CoreTests().fixture(); let backend = StubBackend(data)
        var first = true; var sleeping = false; var failures = 0
        backend.initializeHandler = {
            if first { first = false; throw URLError(.dataNotAllowed) }
            return data
        }
        let sut = make(backend:backend,pause:{ _ in
            sleeping = true
            try await Task.sleep(nanoseconds: 60_000_000_000)
        })
        sut.onView = { if case .status(.failure) = $0 { failures += 1 } }
        let old = Task { await sut.start() }
        while !sleeping { await Task.yield() }
        await sut.start(); await old.value
        XCTAssertEqual(sut.phase,.privacy); XCTAssertEqual(failures,0)
    }
}
