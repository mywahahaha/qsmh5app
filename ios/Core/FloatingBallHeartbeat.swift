import Foundation

@MainActor final class FloatingBallHeartbeat {
    typealias Sleep = @MainActor (Int) async throws -> Void
    private let interval: Int
    private let sleep: Sleep
    private let poll: @MainActor () async throws -> Data
    private let deliver: @MainActor (Data) -> Void
    private var task: Task<Void, Never>?
    private var generation: UInt64 = 0
    private var started = false

    init(interval: Int, sleep: @escaping Sleep = { try await ContinuousClock().sleep(for: .seconds($0)) },
         poll: @escaping @MainActor () async throws -> Data, deliver: @escaping @MainActor (Data) -> Void) {
        self.interval = interval; self.sleep = sleep; self.poll = poll; self.deliver = deliver
    }
    func update(sessionReady: Bool, visible: Bool, foreground: Bool) {
        guard sessionReady else { stop(); return }
        guard foreground else { cancelTask(); return }
        guard visible || started, task == nil else { return }
        started = true
        let stamp = generation, sleep = self.sleep, poll = self.poll, deliver = self.deliver, interval = self.interval
        task = Task { [weak self] in
            while !Task.isCancelled {
                do { try await sleep(interval) } catch { return }
                guard !Task.isCancelled, self?.generation == stamp else { return }
                do {
                    let data = try await poll()
                    guard !Task.isCancelled, self?.generation == stamp else { return }
                    deliver(data)
                } catch { /* 失败不改变状态，下个完整周期继续。 */ }
            }
        }
    }
    private func cancelTask() { generation &+= 1; task?.cancel(); task = nil }
    func stop() { started = false; cancelTask() }
    deinit { task?.cancel() }
}
