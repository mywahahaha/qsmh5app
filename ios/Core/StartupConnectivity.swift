import Foundation

// 仅重试网络可恢复错误；业务、TLS、配置和持久化失败仍立即展示。
enum StartupConnectivity {
    static func shouldWait(for error: Error) -> Bool {
        let value = error as NSError
        guard value.domain == NSURLErrorDomain else { return false }
        return [URLError.notConnectedToInternet, .networkConnectionLost, .timedOut, .cannotConnectToHost,
                .cannotFindHost, .dnsLookupFailed, .dataNotAllowed, .internationalRoamingOff, .callIsActive]
            .contains { $0.rawValue == value.code }
    }
    static func retryDelay(attempt: Int) -> TimeInterval { min(10, Double(max(1, attempt)) * 2) }
}
