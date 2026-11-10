import UIKit
import ImageIO

// 图片请求使用独立无缓存会话，不发送业务 Header、Cookie 或认证凭据。
private final class BoundedImageDownload: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    let policy: URLPolicy
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Data?, Never>?
    private var task: URLSessionDataTask?
    private var data = Data()
    private var accepted = false
    private var cancelled = false
    init(_ policy: URLPolicy) { self.policy = policy }
    func load(_ url: URL, session: URLSession) async -> Data? {
        await withTaskCancellationHandler(operation: {
            await withCheckedContinuation { continuation in
                lock.lock()
                guard !cancelled else {
                    lock.unlock(); continuation.resume(returning: nil); return
                }
                self.continuation = continuation
                let request = session.dataTask(with: URLRequest(url: url))
                task = request
                lock.unlock()
                request.resume()
            }
        }, onCancel: {
            lock.lock()
            cancelled = true
            let request = task, pending = continuation
            continuation = nil
            lock.unlock()
            request?.cancel()
            pending?.resume(returning: nil)
        })
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(request.url.flatMap { policy.allows($0) ? request : nil })
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let valid = (response as? HTTPURLResponse).map { (200..<300).contains($0.statusCode) } == true &&
            response.expectedContentLength <= 16_000_000 && response.url.map(policy.allows) == true
        lock.lock(); accepted = valid && !cancelled; lock.unlock()
        completionHandler(valid ? .allow : .cancel)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        lock.lock()
        let overLimit = chunk.count > 16_000_000 - data.count
        if accepted && !cancelled && !overLimit { data.append(chunk) }
        lock.unlock()
        if overLimit { dataTask.cancel() }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let pending = continuation
        continuation = nil
        let result: Data? = error == nil && accepted && !cancelled ? data : nil
        lock.unlock()
        pending?.resume(returning: result)
    }
}

enum StartupImageDecoder {
    static func decode(_ data: Data, pixels: Int) -> UIImage? {
        guard data.count <= 16_000_000,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let w = properties[kCGImagePropertyPixelWidth] as? Double,
              let h = properties[kCGImagePropertyPixelHeight] as? Double,
              w > 0, h > 0, w * h <= 60_000_000,
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: min(4096, max(1, pixels)),
                kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }
    static func load(_ url: URL, pixels: Int, policy: URLPolicy) async -> UIImage? {
        guard policy.allows(url) else { return nil }
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false; config.httpCookieStorage = nil; config.urlCredentialStorage = nil
        config.urlCache = nil; config.timeoutIntervalForRequest = 10; config.timeoutIntervalForResource = 15
        let download = BoundedImageDownload(policy)
        let session = URLSession(configuration: config, delegate: download, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        guard let data = await download.load(url, session: session), !Task.isCancelled else { return nil }
        return decode(data, pixels: pixels)
    }
}

@MainActor final class StartupBackgroundView: UIImageView {
    typealias Loader = (URL, Int, URLPolicy) async -> UIImage?
    private var task: Task<Void, Never>?
    private var generation = UUID()
    private(set) var requestedURL: URL?
    func load(_ url: URL?, policy: URLPolicy, loader: @escaping Loader = StartupImageDecoder.load) {
        task?.cancel(); generation = UUID(); requestedURL = url; image = nil
        #if LOCAL_TESTING
        isAccessibilityElement = true; accessibilityValue = "empty"
        #endif
        guard let url = url else { return }
        let stamp = generation
        let pixels = Int(max(UIScreen.main.bounds.width, UIScreen.main.bounds.height) * UIScreen.main.scale)
        task = Task { [weak self] in
            let image = await loader(url, pixels, policy)
            guard !Task.isCancelled, let self = self, self.generation == stamp else { return }
            self.image = image
            #if LOCAL_TESTING
            self.accessibilityValue = image == nil ? "empty" : "loaded"
            #endif
        }
    }
    deinit { task?.cancel() }
}

@MainActor final class StartupSplashView: UIView {
    private let imageView = UIImageView()
    private let assets: [SplashAsset]
    private let bundle: Bundle
    private var timing: SplashTiming?
    private var dismissal: Task<Void, Never>?
    private var selected: SplashAsset?
    private var finished = false
    private var initializationPending = true
    private var ready = false
    init(bundle: Bundle = .main) {
        self.bundle = bundle
        assets = bundle.url(forResource: "startup-splashes", withExtension: "json")
            .flatMap { try? Data(contentsOf: $0) }.flatMap { try? JSONDecoder().decode([SplashAsset].self, from: $0) } ?? []
        super.init(frame: .zero)
        backgroundColor = .black; accessibilityIdentifier = "startup.splash"; isAccessibilityElement = true; accessibilityLabel = "游戏启动画面"
        imageView.contentMode = .scaleAspectFill; imageView.clipsToBounds = true; addSubview(imageView)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil, timing == nil, !finished {
            timing = SplashTiming(started: ProcessInfo.processInfo.systemUptime); schedule(ready: false)
        } else if window == nil { dismissal?.cancel() }
    }
    override func layoutSubviews() {
        super.layoutSubviews(); imageView.frame = bounds
        guard !finished else { return }
        let valid = assets.filter { bundle.url(forResource: $0.file, withExtension: nil, subdirectory: "StartupImages") != nil }
        guard let asset = SplashAsset.select(valid, width: bounds.width, height: bounds.height) else { dismiss(); return }
        guard selected != asset else { return }
        selected = asset
        if let url = bundle.url(forResource: asset.file, withExtension: nil, subdirectory: "StartupImages"), let data = try? Data(contentsOf: url),
           let image = StartupImageDecoder.decode(data, pixels: Int(max(bounds.width, bounds.height) * UIScreen.main.scale)) {
            imageView.image = image
        } else { dismiss() }
    }
    func setInitializationPending(_ pending: Bool) {
        guard !finished else { return }
        initializationPending = pending
        if pending { dismissal?.cancel() }
        else {
            // 等待联网授权期间不计入闪图的正常 5 秒上限。
            timing = SplashTiming(started: ProcessInfo.processInfo.systemUptime)
            schedule(ready: ready)
        }
    }
    func contentReady() { ready = true; schedule(ready: true) }
    private func schedule(ready: Bool) {
        guard !finished, !initializationPending, let timing = timing else { return }
        dismissal?.cancel()
        let delay = timing.remaining(now: ProcessInfo.processInfo.systemUptime, ready: ready)
        dismissal = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) } catch { return }
            self?.dismiss()
        }
    }
    func dismiss() { finished = true; dismissal?.cancel(); dismissal = nil; removeFromSuperview() }
    deinit { dismissal?.cancel() }
}
