import UIKit

@main @MainActor final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private weak var root: RootViewController?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
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
            let backend = APIClient(baseURL: config.baseURL, policy: config.policy)
            let coordinator = AppCoordinator(identity: config.identity, policy: config.policy, backend: backend,
                repository: state, vault: vault, gateway: gateway, initialGameURL: config.initialGameURL, clearWebData: RootViewController.clearWebData)
            let reset: (() async throws -> Void)? = config.resetLocalState ? {
                try vault.write(nil); try state.save(StoredState()); await RootViewController.clearWebData()
            } : nil
            let root = RootViewController(coordinator: coordinator, resetLocalState: reset)
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
        let window = UIWindow(frame: UIScreen.main.bounds); window.rootViewController = controller; window.makeKeyAndVisible()
        self.window = window
        return true
    }
    func applicationDidEnterBackground(_ application: UIApplication) { root?.coordinator.enteredBackground() }
    func applicationWillEnterForeground(_ application: UIApplication) { root?.coordinator.enteredForeground() }
}
