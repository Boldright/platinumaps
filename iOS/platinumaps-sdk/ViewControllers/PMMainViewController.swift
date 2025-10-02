import UIKit
import WebKit
import SafariServices
import CoreLocation

protocol PMMainViewControllerDelegate: AnyObject {
    func openLink(_ url: URL, sharedCookie: Bool)
}

class PMMainViewController: UIViewController {
    
    // クラス内にエラーenumをネストして定義
    private enum ErrorType: Error {
        case gone
    }
    
    enum PMCommand: String {
        case webReady = "web.ready"
        case webWillReload = "web.willreload"
        case locationStatus = "location.status"
        case locationAuthorize = "location.authorize"
        case locationOnce = "location.once"
        case locationWatch = "location.watch"
        case locationClearWatch = "location.clearwatch"
        case stampRallyQrCode = "stamprally.qrcode"
        case browseApp = "browse.app"
        case browseInApp = "browse.inapp"
        case appInfo = "app.info"
        case appDetect = "app.detect"
        case appReview = "app.review"
        case mapNavigate = "map.navigate"
        case searchFocus = "search.focus"
        
        //#region Beacon
        case beaconAuthorize = "beacon.authorize"
        case beaconOnce = "beacon.once"
        case beaconWatch = "beacon.watch"
        case beaconClearWatch = "beacon.clearwatch"
        //#endregion
    }
    
    private weak var mainWebView: PMWebView!
    private weak var coverImageView: UIImageView!
    
    public weak var delegate: PMMainViewControllerDelegate?
    
    /// 表示対象のマップの「URL用文字列」を設定してください。
    public var mapSlug: String? = nil
    
    /// 追加のクエリパラメータを利用する場合に設定してください。
    public var mapQuery: [String: String] = [:]
    
    /// マップの表示言語を明示的に指定したい場合に設定してください。デフォルトでは、アプリの言語（UserDefaults.standard）ないしシステムの言語で表示されます。
    public var mapLocale: PMLocale? = nil
    
    public var appStoreId: String? = nil
    
    public var coverImage: UIImage? = nil
    
    public var userId: String? = nil
    
    public var secretKey: String? = nil
    
    public var offsetBottom: Int = 0
    
    // Universal Linkから起動される時のURL
    public var launchURL: URL? = nil
    
    // 初回画面表示フラグ
    private var isFirstViewAppear = false
    
    // WebView がページを読み込んでいるときにtrueになる
    private var isWebViewLoading = false
    
    // web.ready が呼ばれた
    private var hasWebReady = false
    
    private var _locationManager: CLLocationManager? = nil
    private var locationManager: CLLocationManager {
        if _locationManager == nil {
            _locationManager = CLLocationManager()
            _locationManager?.delegate = self
        }
        return _locationManager!
    }
    private var isMeasuringLocation = false
    
    private var originalUrl = URLComponents()
    
    // WebView のロード処理が行われた時間
    private var webViewLoadingAt: Date? = nil
    
    // この画面が読み込まれた時間
    private let loadAt = Date()
    
    // location.authorize の requestId
    private var locationAuthorizeRequestId: String? = nil
    
    // location.watch が要求された requestId の一覧
    private var locationWatchRequestIds: [String] = []
    
    // location.once が要求された requestId の一覧
    private var locationOnceRequestIds: [String] = []
    
    // didUpdateLocations が呼ばれた時のコールバック処理状態
    // 最初に didUpdateLocations が呼ばれた時に方角の情報が存在していないので
    // 方角の情報が取得できるまでコールバック処理を遅らせたい
    private var locationCallbackStatus = 0
    
    // 方角情報
    private var lastHeading: CLHeading? = nil
    
    /// 位置情報権限が拒否された時のダイアログに対する回答を管理するTask
    private var alertTaskForLocationDenied: Task<Bool, Error>? = nil
    
    /// 位置情報が制限された時のダイアログを表示中かどうか（locationとbeaconで同じダイアログを表示しようとするため、スキップ制御したい）
    private var isAlertPresentedForLocationRestricted = false
    
    // MARK: Beacon Members
    /// 屋内測位に利用する、スキャン対象ビーコンのUUIDを設定してください。
    var beaconUuid: String?
    private var useBeacon = false
    private var beaconRegion: CLBeaconRegion!
    private var beaconWatchRequestIds: [String] = []    {
        didSet {
#if DEBUG
            logBeacon("didSet: beaconWatchRequestIds = \(beaconWatchRequestIds)")
#endif
        }
    }
    private var beaconOnceRequestIds: [String] = []
    private var isMonitoringBeacon = false {
        didSet {
#if DEBUG
            logBeacon("didSet: isMonitoringBeacon = \(isMonitoringBeacon)")
#endif
        }
    }
    private var isRangingBeacon = false {
        didSet {
#if DEBUG
            logBeacon("didSet: isRangingBeacon = \(isRangingBeacon)")
#endif
        }
    }
    /// BG移行時に一時停止した状態（現状ログにしか使っていない）
    private var isBeaconPaused = false
    // MARK: Other Settings
    
    /// WebViewのインスペクタを有効にする（デフォルトOFF）
    public var isWebViewInspectable = false
    
    /// Platinumapsサーバ環境
    private var mapOrigin = "https://platinumaps.jp"
    
    /// Bundle
    private var _bundle: Bundle? = nil
    private var bundle: Bundle {
        if _bundle == nil {
            _bundle = Bundle(path: Bundle.main.path(forResource: "Platinumaps", ofType: "bundle")!)
        }
        return _bundle!
    }
    
    // MARK: - Lifecycle
    
    deinit {
        NotificationCenter.default.removeObserver(self)
        stopLocationRequest()
        
        if useBeacon {
            stopRangingBeaconsIfNeeded()
            stopMonitoringBeaconIfNeeded()
        }
    }
    
    override func viewDidLoad() {
        super.viewDidLoad()
        if mapSlug?.isEmpty != false {
            fatalError("MapSlug is empty")
        }
        
        let webViewConfig = WKWebViewConfiguration()
        webViewConfig.applicationNameForUserAgent = "Platinumaps/2.0.0"
        webViewConfig.allowsInlineMediaPlayback = true
        webViewConfig.mediaTypesRequiringUserActionForPlayback = .audio
        let webView = PMWebView(frame: CGRect.zero, configuration: webViewConfig)
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        
        if isWebViewInspectable {
            if #available(iOS 16.4, *) {
                webView.isInspectable = true
            }
        }
        
        mainWebView = webView;
        
        if let image = coverImage {
            let imageView = UIImageView(frame: view.bounds)
            imageView.translatesAutoresizingMaskIntoConstraints = true
            view.addSubview(imageView)
            NSLayoutConstraint.activate([
                imageView.topAnchor.constraint(equalTo: view.topAnchor),
                imageView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                imageView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                imageView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            ])
            imageView.image = image
            coverImageView = imageView;
        }
        
        isFirstViewAppear = true
        
        locationManager.delegate = self
        initBeaconIfNeeded()
    }
    
    @objc private func reloadWebView(_ sender: Any?) {
        showCoverImageView()
        if mainWebView.canGoBack {
            mainWebView.goBack()
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(300)) { [weak self] in
                self?.reloadWebView(nil)
            }
        } else if mainWebView.url != nil {
            mainWebView.reload()
        } else if let url = originalUrl.url {
            mainWebView.load(URLRequest(url: url))
        }
    }
    
    @objc private func openAppSettings(_ sender: Any?) {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }
    
    override func viewDidAppear(_ animated: Bool) {
        
        super.viewDidAppear(animated)
        if isFirstViewAppear {
            let path = "/maps/\(mapSlug!)"
            var urlComp = URLComponents(string: "\(mapOrigin)\(path)")!
            
            var queryItems = [URLQueryItem]();
            
            // Cultureはアプリ言語（UserDefaults.standardのAppleLanguages）を元にWebViewのAccept-Languageが構成されるが、直接指定のI/Fは残しておく。
            if let mapLocale = mapLocale {
                queryItems.append(URLQueryItem(name: "culture", value: mapLocale.rawValue))
            }
            
            queryItems.append(URLQueryItem(name: "native", value: "1"))
            mapQuery.forEach { item in
                queryItems.append(URLQueryItem(name: item.key, value: item.value))
            }
            // view が表示されてからでないと SafeArea の値は設定されない
            let safeAreaTop = self.view.safeAreaInsets.top
            var safeAreaBottom = self.view.safeAreaInsets.bottom
            if (offsetBottom > 0) {
                safeAreaBottom = 0;
            }
            queryItems.append(URLQueryItem(name: "safearea", value: "\(safeAreaTop),\(safeAreaBottom)"));
            urlComp.queryItems = queryItems;
            
            if let url = urlComp.url {
                originalUrl = urlComp
                mainWebView.uiDelegate = self
                mainWebView.navigationDelegate = self
                mainWebView.load(URLRequest(url: url))
            }
            
            NotificationCenter.default.addObserver(self,
                                                   selector: #selector(willEnterForegroundNotification(_:)),
                                                   name: UIApplication.willEnterForegroundNotification,
                                                   object: nil)
            NotificationCenter.default.addObserver(self,
                                                   selector: #selector(didEnterBackgroundNotification(_:)),
                                                   name: UIApplication.didEnterBackgroundNotification,
                                                   object: nil)
        }
        isFirstViewAppear = false
    }
    
    // MARK: - Handlers
    
    @objc private func willEnterForegroundNotification(_ notification: Notification) {
        if !locationWatchRequestIds.isEmpty {
            Task {
                try? await startLocationRequest(isOnce: false, isSilent: true)
            }
        }
        
        if useBeacon {
            beaconWillEnterForeground()
        }
    }
    
    @objc private func didEnterBackgroundNotification(_ notification: Notification) {
        stopLocationRequest()
        
        if useBeacon {
            beaconDidEnterBackground()
        }
    }
    
    override func present(_ viewControllerToPresent: UIViewController, animated flag: Bool, completion: (() -> Void)? = nil) {
        guard var front = self.presentedViewController else {
            super.present(viewControllerToPresent, animated: flag, completion: completion)
            return
        }
        
        while true {
            if let frontOfFront = front.presentedViewController {
                front = frontOfFront
            } else {
                break
            }
        }
        
        front.present(viewControllerToPresent, animated: flag, completion: completion)
    }
}

// MARK: - WKUIDelegate
extension PMMainViewController: WKUIDelegate {
    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        let okAction = UIAlertAction(title: "OK", style: .default) { (_) in
            completionHandler()
        }
        alert.addAction(okAction)
        present(alert, animated: true, completion: nil)
    }
    
    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        let okAction = UIAlertAction(title: "OK", style: .default) { (_) in
            completionHandler(true)
        }
        alert.addAction(okAction)
        let cancelAction = UIAlertAction(title: "Cancel", style: .cancel) { (_) in
            completionHandler(false)
        }
        alert.addAction(cancelAction)
        present(alert, animated: true, completion: nil)
    }
}

// MARK: - WKNavigationDelegate
extension PMMainViewController: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if isWebViewLoading {
            // handle error
        }
        isWebViewLoading = false
    }
    
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        if hasWebReady {
            // web.ready が呼ばれていれば以後ページロードが発生しても無視する
            return
        }
        isWebViewLoading = true
        
        let loadingAt = Date()
        webViewLoadingAt = loadingAt
        
        // 一定時間経過によるエラー表示は行わない。
    }
    
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        isWebViewLoading = false
    }
    
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url, let requestUrl = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            decisionHandler(.cancel)
            return
        }
        
        if requestUrl.scheme != "command" {
            switch navigationAction.navigationType {
            case .linkActivated:
                // <a> がタップされた
                if requestUrl.scheme == "https" || requestUrl.scheme == "http" {
                    openSafariViewController(url)
                } else if UIApplication.shared.canOpenURL(url) {
                    UIApplication.shared.open(url, options: [:], completionHandler: nil)
                }
                decisionHandler(.cancel)
                return
            default:
                break
            }
            decisionHandler(.allow)
            return
        }
        
        // command://xxx のようなリクエストは WebView では処理させない。
        decisionHandler(.cancel)
        runCommand(commandUrl: requestUrl)
    }
    private func dictionaryFromUrlQuery(url: URLComponents) -> [String: String] {
        guard let queryItems = url.queryItems else {
            return [:]
        }
        
        var work = [String: String]()
        queryItems.forEach { (item) in
            work[item.name] = item.value ?? ""
        }
        return work
    }
}

// MARK: - Platinumaps Command Interface
extension PMMainViewController {
    private func runCommand(commandUrl: URLComponents) {
        guard let command = PMCommand(rawValue: commandUrl.host ?? "") else {
            return
        }
        let queryItems = dictionaryFromUrlQuery(url: commandUrl)
        guard let requestId = queryItems["requestId"] else {
            return
        }
        switch command {
        case .appInfo:
            var args: [String: Any] = [:]
            if let userId = userId, !userId.isEmpty {
                args["userId"] = userId
            }
            if let secretKey = secretKey, !secretKey.isEmpty {
                args["secretKey"] = secretKey
            }
            args["offsetBottom"] = offsetBottom;
            commandCallback(command, requestId: requestId, args: args)
        case .webReady:
            isWebViewLoading = false
            hasWebReady = true
            var args: [String: Any] = [:]
            hideCoverImageView { [weak self] in
                if let url = self?.launchURL {
                    args["launchUrl"] = url.absoluteString
                    self?.launchURL = nil
                }
                self?.commandCallback(command, requestId: requestId, args: args)
            }
            return
        case .webWillReload:
            hasWebReady = false
            showCoverImageView { [weak self] in
                self?.commandCallback(command, requestId: requestId, args: [:])
            }
            return
        case .locationStatus:
            Task { [weak self] in
                do {
                    let status = try await self?.locationAuthorizationStatusAsync()
                    self?.locationStatusCommandCallback(status ?? .denied, command: command, requestId: requestId)
                } catch {
                    self?.locationStatusCommandCallback(.denied, command: command, requestId: requestId)
                }
            }
            return
        case .locationAuthorize, .beaconAuthorize:
            Task { [weak self] in
                guard let self = self else { return }
                do {
                    let status = try await self.locationAuthorizationStatusAsync()
                    if status == .notDetermined {
                        self.locationAuthorizeRequestId = requestId
                        await self.locationRequestWhenInUseAuthorization()
                    } else {
                        self.locationStatusCommandCallback(status, command: command, requestId: requestId)
                    }
                } catch {
                    self.locationStatusCommandCallback(.denied, command: command, requestId: requestId)
                }
            }
            return
        case .locationOnce:
            locationOnceRequestIds.append(requestId)
            Task {
                do {
                    try await startLocationRequest(isOnce: true, isSilent: false)
                } catch {
                    locationCommandCallback(location: nil, heading: nil, hasError: true)
                }
            }
            return
        case .locationWatch:
            locationWatchRequestIds.append(requestId)
            Task {
                do {
                    try await startLocationRequest(isOnce: false, isSilent: false)
                } catch {
                    locationCommandCallback(location: nil, heading: nil, hasError: true)
                }
            }
            return
        case .locationClearWatch:
            locationWatchRequestIds.removeAll()
            stopLocationRequestIfNoRequest()
            break
        case .beaconOnce:
            logBeacon("command: beacon.once")
            beaconOnceRequestIds.append(requestId)
            startBeaconRequest(isOnce: true, isSilent: false)
            return
        case .beaconWatch:
            logBeacon("command: beacon.watch")
            beaconWatchRequestIds.append(requestId)
            startBeaconRequest(isOnce: false, isSilent: false)
            return
        case .beaconClearWatch:
            logBeacon("command: beacon.clearwatch")
            beaconWatchRequestIds.removeAll()
            stopBeaconRequestIfNoRequest()
            break // common callback flow
        case .stampRallyQrCode:
            break
        case .browseApp, .browseInApp:
            if let target = queryItems["url"] {
                var wUrl = URL(string: target)
                if wUrl?.scheme == nil {
                    // ホストが指定されていない場合はページパス以降が指定されているので
                    // mainWebView に設定した同じドメインのページを表示する
                    wUrl = URL(string: target, relativeTo: originalUrl.url)?.absoluteURL
                }
                
                if let url = wUrl {
                    if let _ = delegate {
                        delegate?.openLink(url, sharedCookie: queryItems["sharedCookie"] == "true")
                        return
                    }
                    if command == .browseApp
                        || (url.scheme != "https" && url.scheme != "http") {
                        if UIApplication.shared.canOpenURL(url) {
                            UIApplication.shared.open(url, options: [:], completionHandler: nil)
                        }
                    } else if queryItems["sharedCookie"] == "true" {
                        let vc = PMWebViewController()
                        vc.pageUrl = url
                        let nc = UINavigationController(rootViewController: vc)
                        present(nc, animated: true, completion: nil)
                    } else {
                        openSafariViewController(url)
                    }
                }
            }
            break
        case .appDetect:
            break
        case .appReview:
            if let appId = appStoreId,
               let url = URL(string: "https://itunes.apple.com/app/id\(appId)?action=write-review") {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
            break
        case .mapNavigate:
            if let target = queryItems["url"], let url = URL(string: target) {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
            break
        case .searchFocus:
            break
        }
        commandCallback(command, requestId: requestId, args: [:])
    }
    
    @MainActor
    private func commandCallbackAsync(_ command: PMCommand, requestId: String, args: [String: Any], completion: ((Any?, Error?) -> Void)? = nil) {
        do {
            let jsonData = try JSONSerialization.data(withJSONObject: args, options: [])
            if let jsonString = String(data: jsonData, encoding: .utf8) {
                mainWebView.evaluateJavaScript("commandCallback('\(command.rawValue)', '\(requestId)', \(jsonString))", completionHandler: {value, error in
                    completion?(value, error)
                })
                return
            }
        } catch {
            dump(error)
        }
        completion?(nil, nil as Error?)
    }
    
    private func commandCallback(_ command: PMCommand, requestId: String, args: [String: Any]) {
        commandCallbackAsync(command, requestId: requestId, args: args)
    }
    
    private func showCoverImageView(_ completion: (() -> Void)? = nil) {
        if let coverImageView = self.coverImageView {
            view.bringSubviewToFront(coverImageView)
            
            if 0 < coverImageView.alpha {
                completion?()
                return
            }
            
            UIView.animate(withDuration: 0.3) { [weak self] in
                self?.coverImageView?.alpha = 1.0
            } completion: { finished in
                completion?()
            }
        } else {
            completion?()
        }
    }
    
    private func hideCoverImageView(_ completion: (() -> Void)? = nil) {
        if let coverImageView = self.coverImageView {
            if coverImageView.alpha != 1 {
                completion?()
                return
            }
            
            let limitSeconds = 1.0
            var delay = Date().timeIntervalSince(loadAt)
            if limitSeconds < delay {
                delay = 0
            } else {
                delay = limitSeconds - delay
            }
            
            UIView.animate(withDuration: 0.3, delay: delay) { [weak self] in
                self?.coverImageView?.alpha = 0.0
            } completion: { [weak self] finished in
                if let imageView = self?.coverImageView {
                    self?.view.sendSubviewToBack(imageView)
                }
                completion?()
            }
        } else {
            completion?()
        }
    }
    
    private func openSafariViewController(_ url: URL) {
        if let _ = delegate {
            delegate?.openLink(url, sharedCookie: false)
        } else {
            let vc = SFSafariViewController(url: url)
            present(vc, animated: true, completion: nil)
        }
    }
}

// MARK: - CLLocationManagerDelegate
extension PMMainViewController: CLLocationManagerDelegate {
    /// 現在の権限状態を取得する。※バックグラウンドスレッドから実行すること
    private func _locationAuthorizationStatusInBackground() -> CLAuthorizationStatus {
        if CLLocationManager.locationServicesEnabled() {
            guard #available(iOS 14.0, *) else {
                return CLLocationManager.authorizationStatus()
            }
            return locationManager.authorizationStatus;
        }
        return .denied
    }
    
    /// 現在の権限状態を取得する。（現在のスレッドを意識しないでOK）
    private func locationAuthorizationStatusAsync() async throws -> CLAuthorizationStatus {
        return try await Task.detached(priority: .background) { [weak self] in
            guard let self = self else {
                throw ErrorType.gone
            }
            return self._locationAuthorizationStatusInBackground()
        }.value
    }
    
    /// 権限状態を3値のテキストに落とし込む
    private func locationAuthorizationStatusText(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .notDetermined:
            return "notDetermined"
        case .authorizedAlways, .authorizedWhenInUse:
            return "authorized"
        default:
            return "denied"
        }
    }
    
    /// デバッグ用
    private func locationAuthorizationStatusTextFull(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .notDetermined: return "notDetermined"
        case .denied: return "denied"
        case .restricted: return "restricted"
        case .authorizedAlways: return "authorizedAlways"
        case .authorizedWhenInUse: return "authorizedWhenInUse"
        default: return status.rawValue.description
        }
    }
    
    /// 権限状態をWebに返却する
    @MainActor
    private func locationStatusCommandCallback(_ status: CLAuthorizationStatus, command: PMCommand, requestId: String) {
        let statusText = locationAuthorizationStatusText(status)
        commandCallback(command, requestId: requestId, args: ["status": statusText])
    }
    
    private func startLocationRequest(isOnce: Bool, isSilent: Bool) async throws {
        let status = try await locationAuthorizationStatusAsync()
        
        switch status {
        case .notDetermined:
            await locationRequestWhenInUseAuthorization()
            return
        case .restricted:
            if !isSilent {
                await presentAlertForLocationRestricted()
            }
            locationCommandCallback(location: nil, heading: nil)
            return
        case .denied:
            if !isSilent {
                let okOrCancel = try await presentAlertForLocationDenied()
                if okOrCancel == false {
                    locationCommandCallback(location: nil, heading: nil)
                }
            } else {
                locationCommandCallback(location: nil, heading: nil)
            }
            return
        case .authorizedAlways, .authorizedWhenInUse:
            if isMeasuringLocation {
                if isOnce {
                    // watch していても once はすぐに処理したい
                    locationManager.requestLocation()
                }
                return
            }
            locationCallbackStatus = 0
            lastHeading = nil
            
            locationManager.startUpdatingLocation()
            locationManager.startUpdatingHeading()
            locationManager.startMonitoringSignificantLocationChanges()
            isMeasuringLocation = true
            return
        @unknown default:
            locationCommandCallback(location: nil, heading: nil)
            return
        }
    }
    
    /// locationManager.requestWhenInUseAuthorization() をメインスレッドで実行する
    @MainActor
    private func locationRequestWhenInUseAuthorization() {
        locationManager.requestWhenInUseAuthorization()
    }
    
    private func localizedString(forKey key: String) -> String {
        return NSLocalizedString(key, bundle: bundle, comment: "");
    }
    
    /// 位置情報権限が拒否されている旨のアラートを表示する。（OK押下でiOSの設定画面を開く）
    @MainActor
    private func presentAlertForLocationDenied() async throws -> Bool {
        if let existingTask = alertTaskForLocationDenied {
            return try await existingTask.value
        }
        
        let task = Task<Bool, Error> {
            try await withCheckedThrowingContinuation { [weak self] continuation in
                guard let self = self else {
                    continuation.resume(throwing: ErrorType.gone)
                    return
                }
                
                let alertTitle = self.localizedString(forKey: "PMDeniedTitle")
                let alertMessage = self.localizedString(forKey: "PMDeniedMessage")
                let okTitle = self.localizedString(forKey: "PMDeniedOk")
                let cancelTitle = self.localizedString(forKey: "PMDeniedCancel")
                
                let alert = UIAlertController(title: alertTitle, message: alertMessage, preferredStyle: .alert)
                
                let okAction = UIAlertAction(title: okTitle, style: .default) { _ in
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url, options: [:], completionHandler: nil)
                    }
                    continuation.resume(returning: true)
                    self.alertTaskForLocationDenied = nil
                }
                alert.addAction(okAction)
                
                let cancelAction = UIAlertAction(title: cancelTitle, style: .cancel) { _ in
                    continuation.resume(returning: false)
                    self.alertTaskForLocationDenied = nil
                }
                alert.addAction(cancelAction)
                
                self.present(alert, animated: true, completion: nil)
            }
        }
        
        self.alertTaskForLocationDenied = task
        return try await task.value
    }
    
    /// 権限が制限されている旨のアラートを表示する。（OK押下でアラートを閉じるだけ）
    @MainActor
    private func presentAlertForLocationRestricted() {
        if isAlertPresentedForLocationRestricted {
            return
        }
        isAlertPresentedForLocationRestricted = true
        
        let alertTitle = localizedString(forKey: "PMRestrictedTitle")
        let alertMessage = localizedString(forKey: "PMRestrictedMessage")
        
        let alert = UIAlertController(title: alertTitle, message: alertMessage, preferredStyle: .alert)
        
        let okAction = UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.isAlertPresentedForLocationRestricted = false
        }
        alert.addAction(okAction)
        
        present(alert, animated: true, completion: nil)
    }
    
    private func stopLocationRequestIfNoRequest() {
        if locationOnceRequestIds.isEmpty && locationWatchRequestIds.isEmpty {
            stopLocationRequest()
        }
    }
    
    private func stopLocationRequest() {
        guard isMeasuringLocation else {
            return
        }
        locationManager.stopUpdatingLocation()
        locationManager.stopUpdatingHeading()
        locationManager.stopMonitoringSignificantLocationChanges()
        isMeasuringLocation = false
    }
    
    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        if !locationOnceRequestIds.isEmpty || !locationWatchRequestIds.isEmpty {
            // 位置情報を要求するコマンドを受け取っていれば位置情報を取得する
            switch status {
            case .authorizedAlways, .authorizedWhenInUse:
                Task {
                    try? await startLocationRequest(isOnce: !locationWatchRequestIds.isEmpty, isSilent: true)
                }
            case .notDetermined:
                break
            default:
                stopLocationRequest()
                locationCommandCallback(location: nil, heading: nil)
                locationWatchRequestIds.removeAll()
            }
        }
        
        //#region Beacon
        if !beaconOnceRequestIds.isEmpty || !beaconWatchRequestIds.isEmpty {
            switch status {
            case .authorizedAlways, .authorizedWhenInUse:
                startMonitoringBeaconIfNeeded()
                break
            case .notDetermined:
                break
            default: // rejected, restricted
                stopRangingBeaconsIfNeeded()
                stopMonitoringBeaconIfNeeded()
                beaconCommandCallback(beacons: nil, hasError: true)
            }
        }
        //#endregion
        
        if status != .notDetermined {
            // 権限を要求すると、まず .notDetermined が返ってくるのでこれは無視する。
            if let requestId = locationAuthorizeRequestId {
                Task {
                    await locationStatusCommandCallback(status, command: .locationAuthorize, requestId: requestId)
                }
                locationAuthorizeRequestId = nil
            }
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if locationCallbackStatus == 0 {
            locationCallbackStatus = 1
            // didUpdateLocations -> didUpdateHeading の順に呼ばれるので
            // 最初はちょっと待ってコールバックを処理する
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(120)) { [weak self] in
                let location = self?.locationManager.location
                let heading = self?.locationManager.heading ?? self?.lastHeading
                self?.locationCommandCallback(location: location, heading: heading)
                self?.locationCallbackStatus = 2
            }
        } else if locationCallbackStatus == 2 {
            // 最初のコールバックが終わるまでは処理しない
            let location = manager.location
            let heading = manager.heading ?? lastHeading
            locationCommandCallback(location: location, heading: heading)
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        // didUpdateLocations はたまに heading が欠落しているので保持しておく
        lastHeading = newHeading
        if locationCallbackStatus == 2 {
            // 最初のコールバックが終わるまでは処理しない
            let location = manager.location
            let heading = newHeading
            locationCommandCallback(location: location, heading: heading)
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        stopLocationRequest()
        locationCommandCallback(location: nil, heading: nil, hasError: true)
        locationWatchRequestIds.removeAll()
    }
    
    private func locationCommandCallback(location: CLLocation?, heading: CLHeading?, hasError: Bool = false) {
        var args: [String: Any] = [:];
        if let location = location {
            args["lat"] = location.coordinate.latitude
            args["lng"] = location.coordinate.longitude
        }
        if let heading = heading {
            args["heading"] = heading.magneticHeading
        }
        if hasError {
            args["hasError"] = true
        }
        
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            
            do {
                let status = try await self.locationAuthorizationStatusAsync()
                args["status"] = self.locationAuthorizationStatusText(status)
            } catch {
                args["status"] = "denied"
            }
            
            self.locationOnceRequestIds.forEach { id in
                self.commandCallback(.locationOnce, requestId: id, args: args)
            }
            
            self.locationWatchRequestIds.forEach { id in
                self.commandCallback(.locationWatch, requestId: id, args: args)
            }
            
            self.locationOnceRequestIds.removeAll()
            self.stopLocationRequestIfNoRequest()
        }
    }
}

// MARK: - Push
extension PMMainViewController {
    
    func pushLaunchURL(_ url: URL) {
        guard hasWebReady else {
            self.launchURL = url
            return
        }
        
        let commandPushAction: () -> Void = { [weak self] in
            self?.commandPush("app.link", args: ["url": url.absoluteString])
        }
        
        if presentedViewController == nil {
            // 何も画面を表示していなければそのまま処理する
            commandPushAction()
            return
        }
        
        // 何か画面が表示されていればそれらを閉じてから処理する
        var presentedVC = presentedViewController
        while true {
            if presentedVC == nil {
                break
            }
            presentedVC = presentedVC?.presentedViewController
        }
        dismiss(animated: true) {
            commandPushAction()
        }
    }
    
    /// Web 側にコマンドを送る。
    @MainActor
    private func commandPush(_ command: String, args: [String: Any]) {
        do {
            let jsonData = try JSONSerialization.data(withJSONObject: args, options: [])
            guard let jsonString = String(data: jsonData, encoding: .utf8) else {
                return
            }
            
            let commandPushFunction = "commandPush('\(command)', \(jsonString))"
            mainWebView.evaluateJavaScript(commandPushFunction, completionHandler: nil)
            print(#function, commandPushFunction)
        } catch let error {
            dump(error)
        }
    }
}

// MARK: - Beacon
extension PMMainViewController {
    
    /// UUIDを指定してBeaconRegionを初期化する。
    private func initBeaconIfNeeded() {
        guard let beaconUuid = beaconUuid,
              let uuid = UUID.init(uuidString: beaconUuid) else {
            useBeacon = false
            logBeacon("beacon is disabled: beaconUuid is not given")
            return
        }
        beaconRegion = CLBeaconRegion(uuid: uuid, identifier: "Platinumaps")
        useBeacon = true
        logBeacon("beacon is enabled: region=\(beaconRegion!)")
    }
    
    /// ビーコン情報取得要求をうけて、ビーコンのモニタリングを開始する。
    private func startBeaconRequest(isOnce: Bool, isSilent: Bool) {
        Task { [weak self] in
            guard let self = self else { return }
            
            do {
                let status = try await self.locationAuthorizationStatusAsync()
                logBeacon("startBeaconRequest(\(isOnce)): authorization status = \(locationAuthorizationStatusTextFull(status))")
                
                switch status {
                case .notDetermined:
                    await self.locationRequestWhenInUseAuthorization()
                    return
                case .restricted:
                    // Parental Control, MDM, etc.
                    if !isSilent {
                        await self.presentAlertForLocationRestricted()
                    }
                    self.beaconCommandCallback(beacons: nil, hasError: true)
                    return
                case .denied:
                    if !isSilent {
                        // 設定を開いて権限を変えるかどうかのダイアログで、「変えない」と回答された → エラー通知
                        let okOrCancel = try await self.presentAlertForLocationDenied()
                        if okOrCancel == false {
                            self.beaconCommandCallback(beacons: nil, hasError: true)
                        }
                    } else {
                        self.beaconCommandCallback(beacons: nil, hasError: true)
                    }
                    return
                case .authorizedAlways, .authorizedWhenInUse:
                    self.startMonitoringBeaconIfNeeded()
                    return
                @unknown default:
                    // New enum may be added in future
                    self.beaconCommandCallback(beacons: nil, hasError: true)
                    return
                }
            } catch {
                self.beaconCommandCallback(beacons: nil, hasError: true)
            }
        }
    }
    
    private func startMonitoringBeaconIfNeeded() {
        guard useBeacon == true else {
            return
        }
        if !isMonitoringBeacon {
            isMonitoringBeacon = true
            locationManager.startMonitoring(for: beaconRegion)
            logBeacon("startMonitoringBeaconIfNeeded: beacon monitoring is started")
        }
    }
    
    private func stopMonitoringBeaconIfNeeded() {
        guard useBeacon == true else {
            return
        }
        if isMonitoringBeacon {
            isMonitoringBeacon = false
            locationManager.stopMonitoring(for: self.beaconRegion)
            logBeacon("stopMonitoringBeaconIfNeeded: beacon monitoring is stopped")
        }
    }
    
    /// ビーコン情報待ちのリクエストがなくなったら、ビーコンのモニタリングを終了する
    private func stopBeaconRequestIfNoRequest() {
        guard useBeacon == true else {
            return
        }
        if beaconOnceRequestIds.isEmpty && beaconWatchRequestIds.isEmpty {
            stopRangingBeaconsIfNeeded()
            stopMonitoringBeaconIfNeeded()
        }
    }
    
    /// ビーコンのモニタリングが開始された → 状態確認
    func locationManager(_ manager: CLLocationManager, didStartMonitoringFor region: CLRegion) {
        guard useBeacon == true,
              let beaconRegion = self.beaconRegion else {
            return
        }
        logBeacon("fucn: didStartMonitoringFor")
        self.locationManager.requestState(for: beaconRegion)
    }
    
    /// 状態を取得した → 領域内のレンジングを開始
    func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for inRegion: CLRegion) {
        guard useBeacon == true else {
            return
        }
        logBeacon("func: didDetermineState: state = \(state)")
        switch (state) {
        case .inside:
            startRangingBeaconsIfNeeded()
            break
        case .outside:
            break
        case .unknown:
            break
        }
    }
    
    private func startRangingBeaconsIfNeeded() {
        if !isRangingBeacon {
            self.locationManager.startRangingBeacons(satisfying: self.beaconRegion.beaconIdentityConstraint)
            isRangingBeacon = true
            logBeacon("beacon ranging is now started")
        }
    }
    
    /// ビーコン領域に入った（ビーコンを発見した） → レンジングを開始
    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        guard useBeacon == true else {
            return
        }
        logBeacon("func: didEnterRegion")
        startRangingBeaconsIfNeeded()
    }
    
    /// ビーコン領域から出た（すべてのビーコンから離れた） → レンジングを終了
    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        guard useBeacon == true else {
            return
        }
        logBeacon("func: didExitRegion")
        stopRangingBeaconsIfNeeded()
    }
    
    private func stopRangingBeaconsIfNeeded() {
        guard useBeacon == true else {
            return
        }
        if isRangingBeacon {
            self.locationManager.stopRangingBeacons(satisfying: self.beaconRegion.beaconIdentityConstraint)
            isRangingBeacon = false
            logBeacon("beacon ranging is now stopped")
        }
    }
    
    /// レンジングできた → Webにデータを返却
    func locationManager(_ manager: CLLocationManager, didRangeBeacons beacons: [CLBeacon], in region: CLBeaconRegion){
        guard isRangingBeacon && isMonitoringBeacon else {
            // 停止要求後に時間差で来たものは捨てる
            return
        }
        
#if DEBUG
        // 呼び出しが頻繁なので、DEBUGビルド時のみとする
        logBeacon("func: didRangeBeacons")
        for beacon in beacons {
            logBeacon("major:\(beacon.major) minor:\(beacon.minor) rssi:\(beacon.rssi) timestamp:\(beacon.timestamp) accuracy:\(beacon.accuracy)")
        }
#endif
        
        beaconCommandCallback(beacons: beacons, hasError: false)
    }
    
    /// ビーコン情報をWebに返却する
    private func beaconCommandCallback(beacons: [CLBeacon]?, hasError: Bool = false) {
        var args: [String: Any] = [:];
        
        // 正常
        if let beacons = beacons {
            var beaconsArray = [[String: Any]]()
            for beacon in beacons {
                if beacon.accuracy > 0 {
                    beaconsArray.append([
                        "uuid": beacon.uuid.uuidString,
                        "major": beacon.major,
                        "minor": beacon.minor,
                        "rssi": beacon.rssi,
                        "timestamp": Int64(beacon.timestamp.timeIntervalSince1970 * 1000),
                        "accuracy": beacon.accuracy,
                        "proximity": beacon.proximity.rawValue
                    ])
                }
            }
            args["beacons"] = beaconsArray
            _beaconCommandCallback(args: args)
            return
        }
        
        guard hasError else {
            args["beacons"] = [[String: Any]]()
            _beaconCommandCallback(args: args)
            return
        }
        
        // 異常
        args["hasError"] = true
        
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            do {
                let status = try await self.locationAuthorizationStatusAsync()
                args["status"] = self.locationAuthorizationStatusText(status)
            } catch {
                args["status"] = "denied"
            }
            
            self._beaconCommandCallback(args: args)
            
            // エラー通知したらWatch IDをクリアする
            self.beaconWatchRequestIds.removeAll()
        }
    }
    
    @MainActor
    private func _beaconCommandCallback(args: [String: Any]) {
        beaconOnceRequestIds.forEach { id in
            commandCallback(.beaconOnce, requestId: id, args: args)
        }
        
        beaconWatchRequestIds.forEach { id in
            commandCallback(.beaconWatch, requestId: id, args: args)
        }
        
        beaconOnceRequestIds.removeAll()
        stopBeaconRequestIfNoRequest()
    }
    
    private func beaconDidEnterBackground() {
        if isRangingBeacon || isMonitoringBeacon {
            isBeaconPaused = true
            stopRangingBeaconsIfNeeded()
            stopMonitoringBeaconIfNeeded()
            logBeacon("beacon monitoring/ranging is paused when in background")
        }
    }
    
    private func beaconWillEnterForeground() {
        if isBeaconPaused {
            logBeacon("resuming beacon monitoring/ranging")
            isBeaconPaused = false
        }
        
        // FGに復帰するのは、BG前からビーコンを動作させていた場合と、BG前は権限がなく、権限を変えて（あるいは変えないで）戻ってきた場合とある
        
        if !beaconWatchRequestIds.isEmpty {
            startBeaconRequest(isOnce: false, isSilent: true)
        } else if !beaconOnceRequestIds.isEmpty {
            startBeaconRequest(isOnce: true, isSilent: true)
        }
    }
    
    private func logBeacon(_ text: String) -> Void {
#if DEBUG
        print("[Beacon] \(text)")
#endif
    }
}
