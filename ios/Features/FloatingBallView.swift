import UIKit

// 空白部分透传给 WebView，只有球体自身接收触摸。
@MainActor final class FloatingBallLayer: UIView {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        return hit === self ? nil : hit
    }
}

@MainActor final class FloatingBallView: UIControl {
    typealias ImageLoader = @MainActor (URL) async -> UIImage?
    let configuration: FloatingBallConfiguration
    private let imageView = UIImageView()
    private let imageLoader: ImageLoader
    private var imageTask: Task<Void, Never>?
    private var images: [URL: UIImage] = [:]
    private(set) var requestedURL: URL?
    private var containerSize: CGSize = .zero
    private var touchStart: (y: CGFloat, top: CGFloat)?
    private var largestDrag: CGFloat = 0

    init(configuration: FloatingBallConfiguration, imageLoader: @escaping ImageLoader = FloatingBallView.loadImage) {
        self.configuration = configuration; self.imageLoader = imageLoader
        super.init(frame: .zero)
        imageView.contentMode = .scaleAspectFit; imageView.isUserInteractionEnabled = false
        imageView.isAccessibilityElement = false; imageView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(imageView)
        isAccessibilityElement = true; accessibilityIdentifier = "floating.ball"; accessibilityLabel = "游戏悬浮球"
        accessibilityTraits = .button
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    func layout(in size: CGSize) {
        guard size != containerSize else { return }
        containerSize = size
        frame = FloatingBallLayout.frame(configuration: configuration, width: size.width, height: size.height)
        imageView.frame = bounds
    }
    func setActive(_ active: Bool) {
        accessibilityValue = active ? "active" : "default"
        let url = active ? configuration.imageActive : configuration.imageDefault
        guard requestedURL != url else { return }
        requestedURL = url; imageTask?.cancel()
        if let cached = images[url] { imageView.image = cached; return }
        let loader = imageLoader
        imageTask = Task { [weak self] in
            let image = await loader(url)
            guard !Task.isCancelled, let self = self, self.requestedURL == url, let image = image else { return }
            self.images[url] = image; self.imageView.image = image
        }
    }
    private static func loadImage(_ url: URL) async -> UIImage? {
        let transport = URLSessionTransport()
        var request = URLRequest(url: url); request.timeoutInterval = 5
        guard let (data, status) = try? await transport.send(request), (200..<300).contains(status), data.count <= 10_000_000 else { return nil }
        // 与 Android BitmapFactory 一致，GIF 使用静态首帧；不额外下载或重建动画视图。
        return UIImage(data: data)
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let parent = superview, let touch = touches.first else { return }
        touchStart = (touch.location(in: parent).y, frame.minY); largestDrag = 0
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let parent = superview, let touch = touches.first, let start = touchStart else { return }
        let delta = touch.location(in: parent).y - start.y
        largestDrag = max(largestDrag, abs(delta))
        frame.origin.y = floor(min(max(0, start.top + delta), max(0, parent.bounds.height - frame.height)))
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard touchStart != nil else { return }
        defer { touchStart = nil }
        if largestDrag < 8 { sendActions(for: .touchUpInside) }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { touchStart = nil }
    func destroy() { imageTask?.cancel(); imageTask = nil; removeFromSuperview() }
    deinit { imageTask?.cancel() }
}
