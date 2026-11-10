import SwiftUI
import AppKit
import Security
import UniformTypeIdentifiers
import Darwin

struct GameConfiguration: Codable {
    var game_id = 29
    var display_name = "全民红月"
    var bundle_id = "com.un74meng.yjhcmc"
    var version = "1.0.1"
    var build = "4"
    var orientation = "landscape"
    var icon_path = ""
    var splash_dir = ""
    var game_url = ""
    var channel_code = ""
    var convert_icon = false
    var packager: PackagerSettings? = nil
}

struct PackagerSettings: Codable {
    var source: String
    var python: String
    var profile: String
    var output: String
    var identity: String
    var p12: String
}

@MainActor final class PackagerModel: ObservableObject {
    static let shared = PackagerModel()
    var quitting = false
    @Published var config = GameConfiguration()
    @Published var gameID = "29"
    @Published var source = Bundle.main.object(forInfoDictionaryKey: "QSMSourceRoot") as? String ?? ""
    @Published var python = Bundle.main.object(forInfoDictionaryKey: "QSMPython") as? String ?? "/usr/bin/python3"
    @Published var profile = ""
    @Published var output = NSHomeDirectory() + "/Downloads/iOS出包"
    @Published var identity = ""
    @Published var identityList: [[String:String]] = []
    @Published var p12 = ""
    @Published var password = ""
    @Published var log = "选择游戏配置、资源和签名材料后，先执行预检。"
    @Published var running = false
    private var process: Process?
    private var taskConfig: URL?
    var backend: String { Bundle.main.resourceURL!.appendingPathComponent("Backend/build.py").path }

    func select(_ keyPath: ReferenceWritableKeyPath<PackagerModel,String>, directory: Bool = false) {
        let panel = NSOpenPanel(); panel.canChooseDirectories = directory; panel.canChooseFiles = !directory
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url { self[keyPath:keyPath] = url.path }
    }
    func selectResource(_ keyPath: WritableKeyPath<GameConfiguration,String>, directory: Bool = false) {
        let panel = NSOpenPanel(); panel.canChooseDirectories = directory; panel.canChooseFiles = !directory
        if panel.runModal() == .OK, let url = panel.url { config[keyPath:keyPath] = url.path }
    }
    func load() {
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.json]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            var c = try JSONDecoder().decode(GameConfiguration.self, from: Data(contentsOf:url))
            let savedSettings = c.packager
            let gameSource = savedSettings?.source ?? source
            for key in [\GameConfiguration.icon_path, \GameConfiguration.splash_dir] {
                let path = c[keyPath:key]
                if !path.isEmpty && !path.hasPrefix("/") {
                    c[keyPath:key] = URL(fileURLWithPath:gameSource).deletingLastPathComponent().appendingPathComponent(path).path
                }
            }
            c.packager = nil
            config = c; gameID = String(c.game_id); password = ""
            if let settings = savedSettings {
                source = settings.source; python = settings.python; profile = settings.profile
                output = settings.output; identity = settings.identity; p12 = settings.p12
                refreshIdentities()
                log = "已加载游戏配置和打包器设置；证书密码请按需重新输入。"
            } else {
                log = "已加载旧版游戏配置；环境与签名设置保持当前值。"
            }
        } catch { log = "配置加载失败：\(error.localizedDescription)" }
    }
    func save() {
        guard let id = Int(gameID), id > 0 else { log = "游戏 ID 必须是正整数"; return }
        config.game_id = id
        let panel = NSSavePanel(); panel.allowedContentTypes = [.json]; panel.nameFieldStringValue = "游戏配置.json"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            var saved = config
            saved.packager = PackagerSettings(source:source,python:python,profile:profile,output:output,identity:identity,p12:p12)
            let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted,.sortedKeys]
            try encoder.encode(saved).write(to:url,options:.atomic)
            log = "配置已保存；证书密码未写入文件。"
        }
        catch { log = "保存失败：\(error.localizedDescription)" }
    }
    func refreshIdentities() {
        guard !running else { return }
        let p = Process(); p.executableURL = URL(fileURLWithPath:"/usr/bin/security")
        p.arguments = ["find-identity","-v","-p","codesigning"]
        let pipe = Pipe(); p.standardOutput = pipe
        do {
            try p.run(); let data = pipe.fileHandleForReading.readDataToEndOfFile(); p.waitUntilExit()
            let text = String(decoding:data,as:UTF8.self)
            let regex = try NSRegularExpression(pattern:"([0-9A-F]{40}) \"([^\"]+)\"")
            let ns = text as NSString
            identityList = regex.matches(in:text,range:NSRange(location:0,length:ns.length)).map {
                ["sha1":ns.substring(with:$0.range(at:1)),"name":ns.substring(with:$0.range(at:2))]
            }
        } catch { log = "读取钥匙串失败：\(error.localizedDescription)" }
    }
    func importIdentity() {
        guard !p12.isEmpty else { log = "请选择 P12/PFX 文件"; return }
        defer { password = "" }
        do {
            let data = try Data(contentsOf:URL(fileURLWithPath:p12))
            var items: CFArray?
            let status = SecPKCS12Import(data as CFData,[kSecImportExportPassphrase as String:password] as CFDictionary,&items)
            guard status == errSecSuccess else {
                log = "导入失败：\(SecCopyErrorMessageString(status,nil) as String? ?? String(status))"; return
            }
            log = "已导入登录钥匙串。请选择身份并预检描述文件。"; refreshIdentities()
        } catch { log = "导入失败：\(error.localizedDescription)" }
    }
    func execute(_ command: String) {
        guard !running else { return }
        guard let id = Int(gameID), id > 0 else { log = "游戏 ID 必须是正整数"; return }
        guard !profile.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            log = "请先在“环境与签名”页面选择 .mobileprovision 描述文件。"; return
        }
        guard FileManager.default.fileExists(atPath: profile, isDirectory: nil),
              URL(fileURLWithPath: profile).pathExtension.lowercased() == "mobileprovision" else {
            log = "描述文件不存在或格式不正确，请选择有效的 .mobileprovision 文件。"; return
        }
        config.game_id = id
        do {
            let temp = FileManager.default.temporaryDirectory.appendingPathComponent("qsm-config-\(UUID().uuidString).json")
            FileManager.default.createFile(atPath:temp.path,contents:try JSONEncoder().encode(config),attributes:[.posixPermissions:0o600])
            taskConfig = temp
            let p = Process(); p.executableURL = URL(fileURLWithPath:python)
            var args = [backend,command,"--source",source,"--config",temp.path,"--profile",profile]
            if !identity.isEmpty { args += ["--identity",identity] }
            if command == "build" {
                let stamp = DateFormatter(); stamp.dateFormat = "yyyyMMdd-HHmmss"
                let directory = URL(fileURLWithPath:output).appendingPathComponent("\(id)_\(config.bundle_id)_\(config.version)_\(config.build)_\(stamp.string(from:Date()))_\(UUID().uuidString.prefix(6))")
                args += ["--output",directory.path]
            }
            p.arguments = args
            var env = ProcessInfo.processInfo.environment; env["QSM_PACKAGER_PROCESS_GROUP"] = "1"; p.environment = env
            let pipe = Pipe(); p.standardOutput = pipe; p.standardError = pipe
            pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
                let data = handle.availableData
                guard !data.isEmpty else { handle.readabilityHandler = nil; return }
                let value = String(decoding:data,as:UTF8.self)
                Task { @MainActor in self?.log += value }
            }
            p.terminationHandler = { [weak self] process in
                Task { @MainActor in
                    guard let self = self else { return }
                    self.log += "\n任务结束，退出码：\(process.terminationStatus)\n"
                    self.running = false; self.process = nil
                    if self.quitting { NSApp.reply(toApplicationShouldTerminate:true) }
                    if let file = self.taskConfig { try? FileManager.default.removeItem(at:file) }; self.taskConfig = nil
                }
            }
            log = command == "check" ? "正在预检…\n" : "正在出包…\n"
            running = true; process = p
            try p.run()
        } catch {
            running = false; process = nil
            if let file = taskConfig { try? FileManager.default.removeItem(at:file) }; taskConfig = nil
            log += "启动失败：\(error.localizedDescription)"
        }
    }
    func cancel() {
        guard let p = process, p.isRunning else { return }
        // 后端建立独立进程组，同时停止 Xcode 子进程后再恢复临时 profile。
        if getpgid(p.processIdentifier) == p.processIdentifier { kill(-p.processIdentifier,SIGTERM) }
        else { p.interrupt() }
        log += "\n正在取消并清理临时任务…\n"
    }
}

struct ContentView: View {
    @StateObject var model = PackagerModel.shared
    @State private var tab = 0
    func field(_ title: String, _ value: Binding<String>) -> some View {
        HStack { Text(title).frame(width:100,alignment:.trailing); TextField(title,text:value).textFieldStyle(.roundedBorder) }
    }
    func path(_ title: String, _ value: Binding<String>, action: @escaping () -> Void) -> some View {
        HStack { field(title,value); Button("选择",action:action) }
    }
    var body: some View {
        VStack(alignment:.leading,spacing:12) {
            HStack { Text("iOS 游戏签名打包器").font(.title2); Spacer(); Button("加载配置",action:model.load); Button("保存配置",action:model.save) }.disabled(model.running)
            VStack {
                Picker("配置页面",selection:$tab) {
                    Text("游戏与图片").tag(0); Text("环境与签名").tag(1)
                }.pickerStyle(.segmented)
                if tab == 0 {
                ScrollView {
                    VStack(spacing:10) {
                        field("游戏 ID",$model.gameID); field("桌面名称",$model.config.display_name)
                        field("Bundle ID",$model.config.bundle_id)
                        HStack { field("版本",$model.config.version); field("构建号",$model.config.build) }
                        Picker("游戏方向",selection:$model.config.orientation) { Text("横屏").tag("landscape"); Text("竖屏").tag("portrait") }.pickerStyle(.segmented)
                        path("游戏图标",$model.config.icon_path) { model.selectResource(\GameConfiguration.icon_path) }
                        Toggle("兼容旧图标：缩放到 1024 并铺黑底（转换写入摘要）",isOn:$model.config.convert_icon)
                        if let image = NSImage(contentsOfFile:model.config.icon_path) { Image(nsImage:image).resizable().scaledToFit().frame(width:88,height:88).background(Color.black) }
                        path("闪图目录",$model.config.splash_dir) { model.selectResource(\GameConfiguration.splash_dir,directory:true) }
                        SplashPreview(folder:model.config.splash_dir,landscape:model.config.orientation == "landscape")
                        field("游戏地址覆盖",$model.config.game_url)
                        Text("留空使用初始化返回的游戏地址；覆盖地址仍需与接口游戏来源一致。").font(.caption).foregroundColor(.secondary)
                        field("渠道码（可选）",$model.config.channel_code)
                    }.padding()
                }
                } else {
                ScrollView {
                    VStack(spacing:10) {
                        path("iOS 工程目录",$model.source) { model.select(\PackagerModel.source,directory:true) }
                        path("Python 3.11+",$model.python) { model.select(\PackagerModel.python) }
                        path("描述文件",$model.profile) { model.select(\PackagerModel.profile) }
                        Picker("签名身份",selection:$model.identity) {
                            Text("自动匹配描述文件").tag("")
                            ForEach(model.identityList,id:\.self) { item in Text(item["name"] ?? "").tag(item["sha1"] ?? "") }
                        }
                        Button("刷新钥匙串身份",action:model.refreshIdentities)
                        Divider()
                        path("P12 / PFX",$model.p12) { model.select(\PackagerModel.p12) }
                        SecureField("私钥密码（仅本次导入使用）",text:$model.password).textFieldStyle(.roundedBorder)
                        Button("导入到登录钥匙串",action:model.importIdentity)
                        Text("已有匹配身份时无需导入；系统可能提示授权。密码不写配置或日志。").font(.caption).foregroundColor(.secondary)
                        Divider()
                        path("输出父目录",$model.output) { model.select(\PackagerModel.output,directory:true) }
                        Text("预检会展示分发类型、团队、有效期和设备数量。Ad Hoc 仅限已登记设备；实际构建需要完整 Xcode。").font(.caption).foregroundColor(.secondary)
                    }.padding()
                }
                }
            }.disabled(model.running)
            HStack {
                Button("预检") { model.execute("check") }.disabled(model.running)
                Button("开始签名出包") { model.execute("build") }.disabled(model.running).keyboardShortcut(.defaultAction)
                if model.running { ProgressView().controlSize(.small); Button("取消",action:model.cancel) }
                Spacer(); Button("打开输出目录") { NSWorkspace.shared.open(URL(fileURLWithPath:model.output)) }
            }
            ScrollView { Text(model.log).font(.system(.caption,design:.monospaced)).textSelection(.enabled).frame(maxWidth:.infinity,alignment:.leading).padding(8) }
                .frame(height:170).background(Color(nsColor:.textBackgroundColor)).border(Color.secondary.opacity(0.3))
        }.padding(18).frame(minWidth:800,minHeight:760).onAppear { model.refreshIdentities() }
    }
}

struct SplashPreview: View {
    let folder: String
    let landscape: Bool
    var files: [URL] {
        (try? FileManager.default.contentsOfDirectory(at:URL(fileURLWithPath:folder),includingPropertiesForKeys:nil))?
            .filter { $0.pathExtension.lowercased() == "png" }.sorted { $0.lastPathComponent < $1.lastPathComponent } ?? []
    }
    @State private var selected = ""
    var body: some View {
        VStack {
            if !files.isEmpty {
                Picker("闪图预览",selection:$selected) {
                    Text("首张素材").tag("")
                    ForEach(files,id:\.path) { Text($0.lastPathComponent).tag($0.path) }
                }
                if let image = NSImage(contentsOfFile:selected.isEmpty ? files[0].path : selected) {
                    Image(nsImage:image).resizable().scaledToFill().frame(width:landscape ? 260 : 120,height:landscape ? 120 : 220).clipped().background(Color.black)
                    Text("按所选方向预览铺满裁切；运行时按实际屏幕比例选图。").font(.caption).foregroundColor(.secondary)
                }
            }
        }
    }
}

final class AppLifecycle: NSObject, NSApplicationDelegate {
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        let model = PackagerModel.shared
        if model.running { model.quitting = true; model.cancel(); return .terminateLater }
        return .terminateNow
    }
}

@main struct PackagerApp: App {
    @NSApplicationDelegateAdaptor(AppLifecycle.self) var lifecycle
    var body: some Scene { WindowGroup { ContentView() }.windowStyle(.titleBar) }
}
