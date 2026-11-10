import Foundation

enum ChannelCodeReader {
    // 真机允许访问自身 Bundle，但不保证可以枚举安装容器父目录。
    // Bundle 已确定当前 .app 的位置，直接读取其中的渠道目录即可，不访问同级应用。
    static func read(bundle: Bundle = .main, fileManager: FileManager = .default) -> String {
        read(applicationDirectory: bundle.bundleURL, fileManager: fileManager)
    }

    static func read(payloadDirectory: URL, fileManager: FileManager = .default) -> String {
        for app in directories(in: payloadDirectory, using: fileManager) where app.lastPathComponent.hasSuffix(".app") {
            let code = read(applicationDirectory: app, fileManager: fileManager)
            if !code.isEmpty { return code }
        }
        return ""
    }

    private static func read(applicationDirectory: URL, fileManager: FileManager) -> String {
        guard isDirectory(applicationDirectory) else { return "" }
        let packCode = applicationDirectory.appendingPathComponent("packCode", isDirectory: true)
        for channel in directories(in: packCode, using: fileManager) {
            let name = channel.lastPathComponent
            // 不限制渠道格式；仅过滤不能安全放入 HTTP Header 的控制字符与空白名称。
            guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { continue }
            return name
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
