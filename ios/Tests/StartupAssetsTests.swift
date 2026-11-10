import XCTest
import UIKit
@testable import QSMH5App

@MainActor final class StartupAssetsTests: XCTestCase {
    func asset(_ w: Int, _ h: Int) -> SplashAsset { SplashAsset(file: "\(w)x\(h).png", width: w, height: h) }
    func testSelectionDirectionRatioResolutionAndFallback() {
        let a = asset(1280,768), b = asset(2560,1536), portrait = asset(750,1334)
        XCTAssertEqual(SplashAsset.select([a,b,portrait], width: 1280, height: 768), b)
        XCTAssertEqual(SplashAsset.select([a,b,portrait], width: 800, height: 1400), portrait)
        XCTAssertEqual(SplashAsset.select([a], width: 750, height: 1334), a)
        XCTAssertNil(SplashAsset.select([asset(0,10)], width: 100, height: 200))
        XCTAssertNil(SplashAsset.select([SplashAsset(file: "../bad", width: 1, height: 2)], width: 100, height: 200))
    }
    func testTimingFastSlowAndMaximum() {
        let timing = SplashTiming(started: 10)
        XCTAssertEqual(timing.remaining(now: 10.1, ready: true), 1.1, accuracy: 0.001)
        XCTAssertEqual(timing.remaining(now: 12, ready: true), 0)
        XCTAssertEqual(timing.remaining(now: 12, ready: false), 3)
        XCTAssertEqual(timing.remaining(now: 16, ready: false), 0)
    }
    func testBackgroundParsingIsOptionalAndCannotBreakInitialization() throws {
        let fixture = CoreTests().fixture()
        var root = try JSONSerialization.jsonObject(with: fixture) as! [String:Any]
        var body = root["data"] as! [String:Any]
        for value: Any in [NSNull(), 17, "", "  ", "http://example.test/a.png", "file:///tmp/a.png"] {
            body["bgImage"] = value; root["data"] = body
            let parsed = try InitConfiguration.parse(JSONSerialization.data(withJSONObject: root), policy: URLPolicy(localOrigin: nil))
            XCTAssertNil(parsed.backgroundURL)
        }
        body["bgImage"] = " https://example.test/a.png "; root["data"] = body
        let parsed = try InitConfiguration.parse(JSONSerialization.data(withJSONObject: root), policy: URLPolicy(localOrigin: nil))
        XCTAssertEqual(parsed.backgroundURL?.absoluteString, "https://example.test/a.png")
    }
    func testStaleDownloadCannotReplaceNewBackgroundAndClearCancels() async {
        let view = StartupBackgroundView()
        var completions: [String: CheckedContinuation<UIImage?, Never>] = [:]
        let loader: StartupBackgroundView.Loader = { url, _, _ in
            await withCheckedContinuation { completions[url.lastPathComponent] = $0 }
        }
        let policy = URLPolicy(localOrigin: nil)
        view.load(URL(string: "https://fixture.test/old")!, policy: policy, loader: loader)
        await Task.yield()
        view.load(URL(string: "https://fixture.test/new")!, policy: policy, loader: loader)
        await Task.yield()
        let new = UIImage()
        completions["new"]?.resume(returning: new); await Task.yield(); await Task.yield()
        XCTAssertTrue(view.image === new)
        completions["old"]?.resume(returning: UIImage()); await Task.yield(); await Task.yield()
        XCTAssertTrue(view.image === new)
        view.load(nil, policy: policy); XCTAssertNil(view.image)
    }
    func testCorruptImageAndDownsample() throws {
        XCTAssertNil(StartupImageDecoder.decode(Data("broken".utf8), pixels: 100))
        let data = try Data(contentsOf: Bundle.main.url(forResource: "splash_landscape_1280x768", withExtension: "png", subdirectory: "StartupImages")!)
        let image = try XCTUnwrap(StartupImageDecoder.decode(data, pixels: 200))
        XCTAssertLessThanOrEqual(max(image.size.width,image.size.height),200)
    }
}
