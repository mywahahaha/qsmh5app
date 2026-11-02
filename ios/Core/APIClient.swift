import Foundation

protocol HTTPTransport {
    func send(_ request: URLRequest) async throws -> (Data, Int)
}

private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

final class URLSessionTransport: HTTPTransport {
    private let session: URLSession
    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 30
        configuration.httpCookieStorage = nil
        session = URLSession(configuration: configuration, delegate: NoRedirectDelegate(), delegateQueue: nil)
    }
    func send(_ request: URLRequest) async throws -> (Data, Int) {
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw QSMError.invalid("接口响应无效") }
        return (data, response.statusCode)
    }
    deinit { session.invalidateAndCancel() }
}

@MainActor protocol Backend {
    func initialize(headers: [String: String]) async throws -> Data
    func login(token: String, headers: [String: String]) async throws -> String
    func exchange(credential: String, url: URL, headers: [String: String]) async throws -> String
    func floatingBallHeartbeat(url: URL, headers: [String: String]) async throws -> Data
}

final class APIClient: Backend {
    let baseURL: URL
    let policy: URLPolicy
    private let transport: HTTPTransport
    init(baseURL: URL, policy: URLPolicy, transport: HTTPTransport = URLSessionTransport()) {
        self.baseURL = baseURL; self.policy = policy; self.transport = transport
    }
    func post(url: URL, body: [String: Any], headers: [String: String]) async throws -> Data {
        guard policy.allows(url) else { throw QSMError.invalid("接口地址未获准") }
        guard headers.values.allSatisfy({ !$0.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) }) else {
            throw QSMError.invalid("请求 Header 包含非法字符")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        let (data, status) = try await transport.send(request)
        guard (200..<300).contains(status) else { throw QSMError.invalid("请求失败 HTTP \(status)") }
        return data
    }
    func initialize(headers: [String: String]) async throws -> Data {
        try await post(url: baseURL.appendingPathComponent("h5_app/init"), body: [:], headers: headers)
    }
    func login(token: String, headers: [String: String]) async throws -> String {
        let data = try await post(url: baseURL.appendingPathComponent("h5_app/login_by_token"), body: [:], headers: headers)
        return try tokenFrom(data)
    }
    func exchange(credential: String, url: URL, headers: [String: String]) async throws -> String {
        try tokenFrom(await post(url: url, body: ["auth_token": credential], headers: headers))
    }
    func floatingBallHeartbeat(url: URL, headers: [String: String]) async throws -> Data {
        try await post(url: url, body: [:], headers: headers)
    }
    private func tokenFrom(_ data: Data) throws -> String {
        let root = try JSON.envelope(data)
        return try JSON.text(JSON.object(root["data"], "data")["token"], "token")
    }
}
