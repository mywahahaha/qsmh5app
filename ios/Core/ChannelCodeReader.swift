import Foundation

enum ChannelCodeReader {
    // 安装后 IPA 已被系统解包；主 Bundle 的父目录对应 Payload 所在层级。
    // 只枚举直接下级目录，不依赖应用文件名，也不递归进入插件或资源 Bundle。
    static func read(bundle: Bundle = .main) -> String {
        read(payloadDirectory: bundle.bundleURL.deletingLastPathComponent())
    }

    static func read(payloadDirectory: URL, fileManager: FileManager = .default) -> String {
        for app in directories(in: payloadDirectory, using: fileManager) where app.lastPathComponent.hasSuffix(".app") {
            let packCode = app.appendingPathComponent("packCode", isDirectory: true)
            guard isDirectory(packCode) else { continue }
            for channel in directories(in: packCode, using: fileManager) {
                let name = channel.lastPathComponent
                // 不限制渠道格式；仅过滤不能安全放入 HTTP Header 的控制字符与空白名称。
                guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { continue }
                return name
            }
        }
        return ""
    }

    private static func directories(in parent: URL, using fileManager: FileManager) -> [URL] {
        guard isDirectory(parent), let entries = try? fileManager.contentsOfDirectory(
            at: parent, includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey]) else { return [] }
        // 多个候选时固定按目录名排序，避免文件系统枚举顺序导致渠道漂移。
        return entries.filter(isDirectory).sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    private static func isDirectory(_ url: URL) -> Bool {
        guard let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]) else { return false }
        return values.isDirectory == true && values.isSymbolicLink != true
    }
}
