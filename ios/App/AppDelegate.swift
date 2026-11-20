import UIKit

@main @MainActor final class AppDelegate: UIResponder, UIApplicationDelegate {
    private let launchOrientation = ScreenOrientationController()
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool { true }
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        (window?.windowScene?.delegate as? SceneDelegate)?.supportedInterfaceOrientations
            ?? launchOrientation.supportedInterfaceOrientations
    }
}

@MainActor final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private weak var root: RootViewController?
    private let orientation = ScreenOrientationController()
    var supportedInterfaceOrientations: UIInterfaceOrientationMask { orientation.supportedInterfaceOrientations }

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let controller: UIViewController
        do {
            let config = try RuntimeConfiguration.load()
            let folder = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                .appendingPathComponent("QSMH5App", isDirectory: true)
            let state = FileStateRepository(url: folder.appendingPathComponent("state.json"))
            let vault = KeychainTokenVault(service: "\(Bundle.main.bundleIdentifier ?? "qsm").\(config.identity.gameID)")
            let gateway: AuthGateway
            #if QSM_ALIYUN
            gateway = AliyunAuthGateway()
            #else
            gateway = UnavailableAuthGateway()
            #endif
            let transport = URLSessionTransport(onWaitingForConnectivity: { [weak self] in
                Task { @MainActor [weak self] in self?.root?.coordinator.initializationWaitingForConnectivity() }
            })
            let backend = APIClient(baseURL: config.baseURL, policy: config.policy, transport: transport)
            let coordinator = AppCoordinator(identity: config.identity, policy: config.policy, backend: backend,
                repository: state, vault: vault, gateway: gateway, initialGameURL: config.initialGameURL,
                notchScreen: { [weak self] in NotchScreenDetector.isNotched(window: self?.window) },
                screenMode: { [weak self] in self?.orientation.currentMode(in: self?.window) ?? .vertical },
                packageMode: { [weak self] in self?.orientation.packageMode ?? .vertical },
                changeScreenMode: { [weak self] mode in
                    guard let self else { return }
                    self.orientation.change(to: mode, in: self.window, root: self.root)
                },
                stageScreenMode: { [weak self] mode in self?.orientation.stage(to: mode) },
                applyStagedScreenMode: { [weak self] in
                    guard let self else { return }
                    self.orientation.applyStaged(in: self.window, root: self.root)
                },
                waitForScreenMode: { [weak self] mode in
                    guard let self else { return false }
                    return await self.root?.waitForLayout(mode: mode) ?? false
                },
                clearWebData: RootViewController.clearWebData)
            let reset: (() async throws -> Void)? = config.resetLocalState ? {
                try vault.write(nil); try state.save(StoredState()); await RootViewController.clearWebData()
            } : nil
            let root = RootViewController(coordinator: coordinator, orientation: orientation, resetLocalState: reset)
            #if QSM_ALIYUN
            (gateway as? AliyunAuthGateway)?.presenter = { [weak root] in root }
            #endif
            self.root = root; controller = root
        } catch {
            let errorController = UIViewController(); errorController.view.backgroundColor = .systemBackground
            let label = UILabel(); label.text = error.localizedDescription; label.numberOfLines = 0; label.textAlignment = .center
            label.accessibilityIdentifier = "configuration.error"; label.translatesAutoresizingMaskIntoConstraints = false
            errorController.view.addSubview(label)
            NSLayoutConstraint.activate([label.centerXAnchor.constraint(equalTo: errorController.view.centerXAnchor),
                label.centerYAnchor.constraint(equalTo: errorController.view.centerYAnchor), label.widthAnchor.constraint(lessThanOrEqualTo: errorController.view.widthAnchor, constant: -40)])
            controller = errorController
        }
        let window = UIWindow(windowScene: windowScene); window.rootViewController = controller; window.makeKeyAndVisible()
        self.window = window
    }
    func sceneDidEnterBackground(_ scene: UIScene) { root?.coordinator.enteredBackground() }
    func sceneWillEnterForeground(_ scene: UIScene) { root?.coordinator.enteredForeground() }
    func sceneWillResignActive(_ scene: UIScene) {
        root?.applicationWillResignActive()
        root?.setApplicationActive(false)
    }
    func sceneDidBecomeActive(_ scene: UIScene) {
        root?.setApplicationActive(true)
        root?.applicationDidBecomeActive()
    }
    func windowScene(_ windowScene: UIWindowScene, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        supportedInterfaceOrientations
    }
}

@MainActor final class ScreenOrientationController {
    let packageMode: ScreenMode
    private(set) var requestedMode: ScreenMode
    var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        requestedMode == .horizontal ? .landscape : .portrait
    }
    init(bundle: Bundle = .main) {
        if let value = bundle.object(forInfoDictionaryKey: "QSMInitialScreenMode") as? String,
           let mode = ScreenMode(rawValue: value) {
            packageMode = mode
        } else {
            let first = (bundle.object(forInfoDictionaryKey: "UISupportedInterfaceOrientations") as? [String])?.first
            packageMode = first == "UIInterfaceOrientationPortrait" ? .vertical : .horizontal
        }
        requestedMode = packageMode
    }
    static func mode(interfaceOrientation: UIInterfaceOrientation, bounds: CGSize, fallback: ScreenMode) -> ScreenMode {
        if interfaceOrientation.isLandscape { return .horizontal }
        if interfaceOrientation.isPortrait { return .vertical }
        if bounds.width > bounds.height { return .horizontal }
        if bounds.height > bounds.width { return .vertical }
        return fallback
    }
    func currentMode(in window: UIWindow?) -> ScreenMode {
        // 转屏动画期间仍使用已请求的业务方向，供 Bridge 和请求 Header 同步读取。
        requestedMode
    }
    func change(to mode: ScreenMode, in window: UIWindow?, root: UIViewController?) {
        guard stage(to: mode) else { return }
        applyStaged(in: window, root: root)
    }
    @discardableResult func stage(to mode: ScreenMode) -> Bool {
        guard requestedMode != mode else { return false }
        requestedMode = mode
        return true
    }
    func applyStaged(in window: UIWindow?, root: UIViewController?) {
        applyOrientation(in: window, root: root)
    }
    private func applyOrientation(in window: UIWindow?, root: UIViewController?) {
        guard let window else { return }
        if #available(iOS 16.0, *), let windowScene = window.windowScene {
            root?.setNeedsUpdateOfSupportedInterfaceOrientations()
            let preferences = UIWindowScene.GeometryPreferences.iOS(interfaceOrientations: supportedInterfaceOrientations)
            windowScene.requestGeometryUpdate(preferences) { _ in }
        } else {
            let target: UIInterfaceOrientation = requestedMode == .horizontal ? .landscapeRight : .portrait
            UIDevice.current.setValue(target.rawValue, forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }
}

enum NotchScreenDetector {
    static func isNotched(window: UIWindow?) -> Bool {
        guard let window, UIDevice.current.userInterfaceIdiom == .phone else { return false }
        return hasCutout(insets: window.safeAreaInsets, orientation: window.windowScene?.interfaceOrientation ?? .unknown)
    }
    static func hasCutout(insets: UIEdgeInsets, orientation: UIInterfaceOrientation) -> Bool {
        if orientation.isLandscape { return max(insets.left, insets.right) > 20 }
        return insets.top > 20
    }
}
