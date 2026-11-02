import XCTest
@testable import QSMH5App

final class ChannelCodeTests: XCTestCase {
    private var payload: URL!
    override func setUpWithError() throws {
        payload = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("Payload")
        try FileManager.default.createDirectory(at: payload, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: payload.deletingLastPathComponent())
    }
    @discardableResult private func directory(_ name: String) throws -> URL {
        let url = payload.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
    func testEveryMissingLayerReturnsEmptyWithoutThrowing() throws {
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload.appendingPathComponent("absent")), "")
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload), "")
        try directory("QSMH5App.app")
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload), "")
        try directory("QSMH5App.app/packCode")
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload), "")
    }
    func testReadsFolderNameAndDoesNotDependOnApplicationName() throws {
        try directory("RenamedGame.app/packCode/1-218")
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload), "1-218")
    }
    func testScansAllApplicationsWhenEarlierApplicationHasNoChannel() throws {
        try directory("A.app/packCode")
        try directory("Z.app/packCode/1-218")
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload), "1-218")
    }
    func testFilesAndNestedNonApplicationDirectoriesAreIgnored() throws {
        try Data().write(to: payload.appendingPathComponent("NotDirectory.app"))
        try directory("Other/Game.app/packCode/wrong")
        let app = try directory("Real.app")
        try Data().write(to: app.appendingPathComponent("packCode"))
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload), "")
        try FileManager.default.removeItem(at: app.appendingPathComponent("packCode"))
        let pack = try directory("Real.app/packCode")
        try Data().write(to: pack.appendingPathComponent("file-channel"))
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload), "")
    }
    func testUsesImmediateChannelDirectoryAndDeterministicOrdering() throws {
        try directory("B.app/packCode/z-code")
        try directory("B.app/packCode/a-code/deeper-code")
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload), "a-code")
        try directory("A.app/packCode/first-code")
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload), "first-code")
    }
    func testSymbolicLinksDoNotEscapeDirectoryTree() throws {
        let outside = payload.deletingLastPathComponent().appendingPathComponent("outside")
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        let app = try directory("Real.app")
        try FileManager.default.createSymbolicLink(at: app.appendingPathComponent("packCode"), withDestinationURL: outside)
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload), "")
        try FileManager.default.removeItem(at: app.appendingPathComponent("packCode"))
        let pack = try directory("Real.app/packCode")
        try FileManager.default.createSymbolicLink(at: pack.appendingPathComponent("link-channel"), withDestinationURL: outside)
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload), "")
    }
    func testUnsafeHeaderNameFallsBackToEmpty() throws {
        try directory("Game.app/packCode/bad\nchannel")
        XCTAssertEqual(ChannelCodeReader.read(payloadDirectory: payload), "")
    }
    func testRuntimeIgnoresLegacyPlistValueAndUsesChannelDirectory() throws {
        let app = try directory("Game.app")
        let info: [String: Any] = ["CFBundleIdentifier": "test.channel", "CFBundlePackageType": "APPL",
            "QSMPackageCode": "stale-plist-code", "QSMGameID": "29", "CFBundleShortVersionString": "1.0.1"]
        try PropertyListSerialization.data(fromPropertyList: info, format: .xml, options: 0).write(to: app.appendingPathComponent("Info.plist"))
        let bundle = try XCTUnwrap(Bundle(url: app))
        XCTAssertEqual(try RuntimeConfiguration.load(bundle: bundle).identity.packageCode, "")
        try directory("Game.app/packCode/1-218")
        XCTAssertEqual(try RuntimeConfiguration.load(bundle: bundle).identity.packageCode, "1-218")
    }
}
