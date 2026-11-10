#if QSM_ALIYUN
import UIKit
import ATAuthSDK

@MainActor final class AliyunAuthGateway: AuthGateway {
    var presenter: (() -> UIViewController?)?
    var available: Bool { true }
    private var handler: TXCommonHandler?
    private var continuation: CheckedContinuation<String, Error>?
    private var flowID: UUID?
    private var timeoutTask: Task<Void, Never>?
    private var deliveredPresentation = false
    private var receivedPresentation = false
    private var pendingToken: String?
    private var presentationModel: TXCustomModel?
    private var closing = false

    func credential(configuration: AuthConfiguration, presented: @escaping @MainActor () async -> Void) async throws -> String {
        guard continuation == nil, !closing, let host = presenter?(), host.viewIfLoaded?.window != nil,
              let secret = configuration.secret else { throw QSMError.invalid("一键登录宿主或配置不可用") }
        let style = try OnekeyAuthStyle(raw: configuration.raw)
        let initialPanel = style.panel(in: host.view.bounds, safeArea: host.view.safeAreaInsets)
        _ = try OnekeyAuthLayout.make(size: initialPanel.size, style: style)
        let id = UUID(); flowID = id; deliveredPresentation = false; receivedPresentation = false; pendingToken = nil
        let sdk = TXCommonHandler.sharedInstance(); handler = sdk
        sdk.getReporter().setConsolePrintLoggerEnable(false)
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                timeoutTask = Task { [weak self] in
                    do { try await Task.sleep(nanoseconds: UInt64(configuration.timeout + 30) * 1_000_000_000) } catch { return }
                    guard self?.flowID == id else { return }
                    self?.finish(.failure(QSMError.invalid("认证流程超时")))
                }
                sdk.setAuthSDKInfo(secret) { [weak self, weak host] result in
                    Task { @MainActor in
                        guard let self = self, self.flowID == id, let host = host else { return }
                        guard (result["resultCode"] as? String) == "600000" else {
                            self.finish(.failure(QSMError.invalid("认证方案初始化失败"))); return
                        }
                        let model: TXCustomModel
                        do {
                            model = try Self.makeModel(style: style, orientations: AuthPresentationPolicy.orientations(),
                                carrierName: TXCommonUtils.getCurrentCarrierName(),
                                viewport: { [weak host] in
                                    guard let host = host else { return (.zero, .zero) }
                                    return (host.view.bounds, host.view.safeAreaInsets)
                                }, onClose: { [weak self] in
                                    guard self?.flowID == id else { return }; self?.finish(.failure(QSMError.cancelled))
                                }, onInvalidLayout: { [weak self] in
                                    guard self?.flowID == id else { return }; self?.finish(.failure(QSMError.invalid("授权窗口无法完整显示，请使用 H5 登录")))
                                })
                            self.presentationModel = model
                        }
                        catch { self.finish(.failure(error)); return }
                        sdk.getLoginToken(withTimeout: configuration.timeout, controller: host, model: model) { [weak self] result in
                            Task { @MainActor in
                                guard let self = self, self.flowID == id else { return }
                                let code = result["resultCode"] as? String ?? ""
                                switch code {
                                case "600001":
                                    guard !self.receivedPresentation else { return }
                                    self.receivedPresentation = true
                                    self.timeoutTask?.cancel()
                                    await presented()
                                    guard self.flowID == id else { return }
                                    self.deliveredPresentation = true
                                    if let token = self.pendingToken { self.finish(.success(token)) }
                                case "600000":
                                    guard self.receivedPresentation, let token = result["token"] as? String, !token.isEmpty else {
                                        self.finish(.failure(QSMError.invalid("认证凭证无效"))); return
                                    }
                                    if self.deliveredPresentation { self.finish(.success(token)) }
                                    else { self.pendingToken = token }
                                case "700000", "700001": self.finish(.failure(QSMError.cancelled))
                                case "700002", "700003", "700004", "700006", "700007", "700008", "700009": break
                                default: self.finish(.failure(QSMError.invalid("运营商认证未完成（\(code)）")))
                                }
                            }
                        }
                    }
                }
            }
        }, onCancel: { [weak self] in Task { @MainActor in self?.cancel() } })
    }
    static func makeModel(style: OnekeyAuthStyle, orientations: UIInterfaceOrientationMask, carrierName: String? = nil,
                          viewport: @escaping () -> (CGRect, UIEdgeInsets), onClose: @escaping () -> Void,
                          onInvalidLayout: @escaping () -> Void) throws -> TXCustomModel {
        guard !orientations.isEmpty else { throw QSMError.invalid("授权页没有可用方向") }
        let (bounds, safe) = viewport()
        let initial = try OnekeyAuthLayout.make(size: style.panel(in: bounds, safeArea: safe).size, style: style)
        func layout(_ size: CGSize) -> OnekeyAuthLayout {
            if let value = try? OnekeyAuthLayout.make(size: size, style: style, fixedNumberSize: initial.numberFont.pointSize) { return value }
            Task { @MainActor in onInvalidLayout() }; return initial
        }
        let model = TXCustomModel()
        model.supportedInterfaceOrientations = orientations
        model.contentViewFrameBlock = { _, size, _ in
            let (current, insets) = viewport()
            return style.panel(in: CGRect(origin: .zero, size: size.width > 0 && size.height > 0 ? size : current.size), safeArea: insets)
        }
        model.alertBlurViewColor = .black; model.alertBlurViewAlpha = 0.45
        model.alertContentViewColor = OnekeyBrand.panel; model.alertContentViewAlpha = 1
        model.alertCornerRadiusArray = [14,14,14,14]
        model.backgroundColor = .clear; model.navIsHidden = true; model.alertBarIsHidden = true
        model.navTitle = NSAttributedString(string: ""); model.alertTitle = NSAttributedString(string: "")
        model.alertCloseItemIsHidden = true; model.logoIsHidden = true
        model.tapAuthPageMaskClosePage = false
        model.prefersStatusBarHidden = true
        model.numberColor = OnekeyBrand.number; model.numberFont = initial.numberFont
        model.numberFrameBlock = { _, size, original in
            let slot = layout(size).number
            return CGRect(x:(size.width-original.width)/2,y:slot.minY+max(0,(slot.height-original.height)/2),width:original.width,height:original.height)
        }
        // 运营商取 SDK 公开查询结果；无法取得时保留 SDK 原生说明，不写固定运营商或空文案。
        model.sloganIsHidden = false
        if let name = carrierName?.trimmingCharacters(in:.whitespacesAndNewlines), !name.isEmpty {
            model.sloganText = NSAttributedString(string:name+"提供认证服务", attributes:[.foregroundColor:OnekeyBrand.secondary,.font:UIFont.systemFont(ofSize:12)])
        }
        model.sloganFrameBlock = { _, size, _ in layout(size).slogan }
        model.loginBtnText = NSAttributedString(string: style.loginText, attributes: [.foregroundColor:UIColor.white,.font:UIFont.systemFont(ofSize:16)])
        model.loginBtnBgImgs = [OnekeyBrand.buttonImage(OnekeyBrand.orange),OnekeyBrand.buttonImage(OnekeyBrand.orange.withAlphaComponent(0.6)),OnekeyBrand.buttonImage(UIColor(red:0.9,green:0.33,blue:0,alpha:1))]
        model.loginBtnFrameBlock = { _, size, _ in layout(size).login }
        model.changeBtnIsHidden = false
        model.changeBtnTitle = NSAttributedString(string: style.otherLoginText, attributes: [.foregroundColor:OnekeyBrand.text,.font:UIFont.systemFont(ofSize:13)])
        model.changeBtnFrameBlock = { _, size, _ in layout(size).change }
        model.checkBoxIsChecked = false; model.checkBoxIsHidden = false
        model.checkBoxWH = 44; model.checkBoxImageEdgeInsets = UIEdgeInsets(top:15,left:15,bottom:15,right:15)
        model.checkBoxVerticalCenter = true
        model.checkBoxImages = [OnekeyBrand.checkbox(checked:false),OnekeyBrand.checkbox(checked:true)]
        model.expandAuthPageCheckedScope = true
        model.privacyOne = [style.protocols[0].name,style.protocols[0].url]
        if style.protocols.count > 1 { model.privacyTwo = [style.protocols[1].name,style.protocols[1].url] }
        model.privacyPreText = "我已阅读并同意"; model.privacySufText = ""
        model.privacyOperatorPreText = "《"; model.privacyOperatorSufText = "》"
        model.privacyConectTexts = ["和","、","、"]
        model.privacyColors = [OnekeyBrand.text,.blue]; model.privacyAlignment = .left
        model.privacyFont = UIFont.systemFont(ofSize:12); model.privacyOperatorFont = UIFont.systemFont(ofSize:12)
        model.privacyLineSpaceDp = 2
        model.privacyFrameBlock = { _, size, _ in layout(size).privacy }
        model.privacyNavColor = OnekeyBrand.red; model.privacyNavTitleColor = .white
        model.privacyNavTitleFont = UIFont.systemFont(ofSize:17); model.privacyNavBackImage = OnekeyBrand.backImage()
        model.privacyVCIsCustomized = false
        let header = OnekeyBrandHeaderView(onClose:onClose)
        model.customViewBlock = { parent in parent.addSubview(header) }
        model.customViewLayoutBlock = { _, content, _, _, _, slogan, number, login, change, privacy in
            header.frame = CGRect(x:0,y:0,width:content.width,height:OnekeyBrand.headerHeight)
            header.superview?.bringSubviewToFront(header)
            // SDK 会按真实运营商协议重新测量；实际内容不适配时退出，禁止裁切或覆盖操作。
            let area = CGRect(origin:.zero,size:content.size).insetBy(dx:-0.5,dy:-0.5)
            let frames = [number,slogan,login,change,privacy]
            guard frames.allSatisfy({ !$0.isEmpty && area.contains($0) }), number.minY >= OnekeyBrand.headerHeight,
                  number.maxY <= slogan.minY+0.5, slogan.maxY <= login.minY+0.5,
                  login.maxY <= change.minY+0.5, change.maxY <= privacy.minY+0.5 else {
                Task { @MainActor in onInvalidLayout() }; return
            }
        }
        let duration: CFTimeInterval = UIAccessibility.isReduceMotionEnabled ? 0 : 0.2
        model.animationDuration = duration
        func fade(_ from: Float, _ to: Float) -> CABasicAnimation {
            let a = CABasicAnimation(keyPath:"opacity"); a.fromValue=from; a.toValue=to; a.duration=duration; return a
        }
        if duration > 0 { model.entryAnimation=fade(0,1); model.exitAnimation=fade(1,0); model.bgEntryAnimation=fade(0,1); model.bgExitAnimation=fade(1,0) }
        return model
    }
    private func finish(_ result: Result<String, Error>) {
        guard continuation != nil else { return }
        let pending = continuation; continuation = nil; flowID = nil
        timeoutTask?.cancel(); timeoutTask = nil; pendingToken = nil
        let model = presentationModel; presentationModel = nil
        closing = true
        let complete: () -> Void = { [weak self] in
            Task { @MainActor in
                model?.customViewBlock = { _ in }
                model?.customViewLayoutBlock = { _,_,_,_,_,_,_,_,_,_ in }
                self?.closing = false; pending?.resume(with: result)
            }
        }
        if receivedPresentation, let handler = handler {
            handler.cancelLoginVC(animated: !UIAccessibility.isReduceMotionEnabled, complete: complete)
        } else {
            handler?.cancelLoginVC(animated:false,complete:nil); complete()
        }
    }
    func cancel() {
        guard continuation != nil else { return }
        finish(.failure(CancellationError()))
    }
}
#endif
