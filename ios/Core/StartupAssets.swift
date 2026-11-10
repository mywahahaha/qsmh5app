import Foundation

struct SplashAsset: Codable, Equatable {
    let file: String
    let width: Int
    let height: Int
    static func select(_ assets: [SplashAsset], width: Double, height: Double) -> SplashAsset? {
        guard width > 0, height > 0 else { return nil }
        let valid = assets.filter { $0.width > 0 && $0.height > 0 && !$0.file.contains("/") && !$0.file.contains("..") }
        let matching = valid.filter { ($0.width > $0.height) == (width > height) }
        return (matching.isEmpty ? valid : matching).min {
            let a = abs(log((Double($0.width) / Double($0.height)) / (width / height)))
            let b = abs(log((Double($1.width) / Double($1.height)) / (width / height)))
            if abs(a - b) > 1e-10 { return a < b }
            return Double($0.width) * Double($0.height) > Double($1.width) * Double($1.height)
        }
    }
}

struct SplashTiming {
    let started: TimeInterval
    func remaining(now: TimeInterval, ready: Bool) -> TimeInterval {
        max(0, (ready ? 1.2 : 5) - (now - started))
    }
}
