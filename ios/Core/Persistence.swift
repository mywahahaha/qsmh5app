import Foundation
import Security
import CryptoKit

enum Consent: String, Codable { case unknown, agreed, revoked }

struct StoredState: Codable, Equatable {
    var consent: Consent = .unknown
    var installationID: String?
    var cleanupPending = false
    var values: [String: String] = [:]
    var deviceCode: String {
        guard consent == .agreed, let installationID = installationID else { return "" }
        return Insecure.MD5.hash(data: Data(installationID.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

// 状态、Token 提交与会话代次均在 MainActor 排序。首期小文件同步原子落盘，
// 回调只在保存成功后发出，避免检查代次后异步写入被 logout 插入。
@MainActor protocol StateRepository: AnyObject {
    func load() throws -> StoredState?
    func save(_ state: StoredState) throws
}

@MainActor final class FileStateRepository: StateRepository {
    let url: URL
    init(url: URL) { self.url = url }
    func load() throws -> StoredState? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try JSONDecoder().decode(StoredState.self, from: Data(contentsOf: url))
    }
    func save(_ state: StoredState) throws {
        let folder = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var marked = folder; try marked.setResourceValues(values)
        try JSONEncoder().encode(state).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

@MainActor protocol TokenVault: AnyObject {
    func read() throws -> String?
    func write(_ token: String?) throws
}

@MainActor final class KeychainTokenVault: TokenVault {
    private let service: String
    init(service: String) { self.service = service }
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: "accountToken"]
    }
    func read() throws -> String? {
        var item = query; item[kSecReturnData as String] = true; item[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(item as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data, let token = String(data: data, encoding: .utf8) else {
            throw QSMError.storage("无法读取安全登录信息（\(status)）")
        }
        return token
    }
    func write(_ token: String?) throws {
        guard let token = token else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw QSMError.storage("无法清除安全登录信息（\(status)）") }
            return
        }
        let attrs: [String: Any] = [kSecValueData as String: Data(token.utf8), kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(query.merging(attrs) { _, new in new } as CFDictionary, nil) }
        guard status == errSecSuccess else { throw QSMError.storage("无法保存安全登录信息（\(status)）") }
    }
}
