import UIKit
import WebKit
import SafariServices
import CoreLocation
import PromiseKit

class PMMainViewController: UIViewController {
    
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
    }

    private weak var mainWebView: PMWebView!
    private weak var coverImageView: UIImageView!
    
    public var mapSlug: String? = nil
    
    public var mapQuery: [String: String] = [:]
    
    public var appStoreId: String? = nil
    
    public var coverImage: UIImage? = nil
    
    public var userId: String? = nil
    
    public var secretKey: String? = nil

    // Universal Linkから起動される時のURL
    public var launchURL: URL? = nil

    // 初回画面表示フラグ
    private var isFirstViewAppear = false

    // WebView がページを読み込んでいるときにtrueになる
    private var isWebViewLoading = false
    
    // web.ready が呼ばれた
    private var hasWebReady = false
    
    // ページロードエラーが表示されている
    private var isWebViewLoadErrorVisible = false
    
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

    // WebView の表示位置を調整するか
    // メイン画面での検索モードは pullable 部品を上段に引き上げる。
    // iOS12まではこの時に WebView の中身も一緒に引き上がるのでこれを抑制する必要がある。
    private var shouldFixedWebView: Bool = false;

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

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        if mapSlug?.isEmpty != false {
            fatalError("MapSlug is empty")
        }
        
        let webViewConfig = WKWebViewConfiguration()
        webViewConfig.applicationNameForUserAgent = "Platinumaps/1.0.0"
        let webView = PMWebView(frame: CGRect.zero, configuration: webViewConfig)
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        mainWebView = webView;
        
        let imageView = UIImageView(frame: view.bounds)
        view.addSubview(imageView)
        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: view.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            imageView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        coverImageView = imageView;
        
        if let image = coverImage {
            coverImageView.image = image
        }
        
        mainWebView.scrollView.delegate = self

        isFirstViewAppear = true
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
            let origin = "https://platinumaps.jp"
            let path = "/maps/\(mapSlug!)"
            var urlComp = URLComponents(string: "\(origin)\(path)")!

            var queryItems = [URLQueryItem]();
            queryItems.append(URLQueryItem(name: "native", value: "1"))
            mapQuery.forEach { item in
                queryItems.append(URLQueryItem(name: item.key, value: item.value))
            }
            // view が表示されてからでないと SafeArea の値は設定されない
            let safeAreaTop = self.view.safeAreaInsets.top
            let safeAreaBottom = self.view.safeAreaInsets.bottom
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

            NotificationCenter.default.addObserver(self,
                                                   selector: #selector(keyboardEventNotification(_:)),
                                                   name: UIApplication.keyboardWillShowNotification,
                                                   object: nil)
            NotificationCenter.default.addObserver(self,
                                                   selector: #selector(keyboardEventNotification(_:)),
                                                   name: UIApplication.keyboardWillHideNotification,
                                                   object: nil)
        }
        isFirstViewAppear = false
    }

    @objc private func willEnterForegroundNotification(_ notification: Notification) {
        if !locationWatchRequestIds.isEmpty {
            startLocationRequest(isOnce: false)
        }
    }

    @objc private func didEnterBackgroundNotification(_ notification: Notification) {
        stopLocationRequest()
    }
    
    @objc private func keyboardEventNotification(_ notification: Notification) {
        if notification.name == UIApplication.keyboardWillShowNotification {
            var isMainVisible = false
            if let query = mainWebView.url?.query {
                do {
                    // クエリに spot もしくは stamprally が存在している場合、メイン画面の検索バーに触っているはずがないので、スクロール有効にする。
                    // その他にもスクロール有効にしたい場合があり、今後も発生しそうなので、ews (enable webview scroll）というパラメータを汎用的に使う。
                    let targetKeys = try NSRegularExpression(pattern: "(spot=|stamprally=|ews=)", options: [.caseInsensitive])
                    let matches = targetKeys.numberOfMatches(in: query, options: [], range: NSRange(location: 0, length: query.count))
                    if matches == 0 {
                        // いずれも含まれていない場合はマップが表示されている
                        isMainVisible = true
                    }
                } catch let error {
                    dump(error)
                }
            }
            if isMainVisible {
                if mainWebView.scrollView.isScrollEnabled {
                    mainWebView.scrollView.isScrollEnabled = false
                }
                shouldFixedWebView = isMainVisible
            }
        } else if notification.name == UIApplication.keyboardWillHideNotification {
            shouldFixedWebView = false
            if !mainWebView.scrollView.isScrollEnabled {
                mainWebView.scrollView.isScrollEnabled = true
            }
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

extension PMMainViewController: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if isWebViewLoading {
            showWebViewLoadErrorMessage()
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

        // 一定時間経過しても web.ready が呼ばれてなければエラーにする
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(30)) { [weak self] in
            guard let me = self else {
                return
            }
            if loadingAt != me.webViewLoadingAt {
                return
            }
            if !me.hasWebReady {
                me.showWebViewLoadErrorMessage()
            }
            me.isWebViewLoading = false
        }
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
    
    private func showWebViewLoadErrorMessage() {
        if isWebViewLoadErrorVisible {
            return
        }

        if isWebViewLoading {
            mainWebView.stopLoading()
        }
        
        let alertMessage = "データの読み込みに失敗しました。\nリロードをお願いします。"
        let alert = UIAlertController(title: nil, message: alertMessage, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "リロード", style: .default, handler: { [weak self] _ in
            self?.isWebViewLoadErrorVisible = false
            self?.reloadWebView(nil)
        }))
        present(alert, animated: true, completion: nil)
        isWebViewLoadErrorVisible = true
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

extension PMMainViewController: UIScrollViewDelegate {
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        if shouldFixedWebView {
            scrollView.setContentOffset(.zero, animated: false)
        }
    }
}

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
            let status = locationAuthorizationStatus()
            locationStatusCommandCallback(status, command: command, requestId: requestId)
            return
        case .locationAuthorize:
            let status = locationAuthorizationStatus()
            if status == .notDetermined {
                locationAuthorizeRequestId = requestId
                locationManager.requestWhenInUseAuthorization()
            } else {
                locationStatusCommandCallback(status, command: command, requestId: requestId)
            }
            return
        case .locationOnce:
            locationOnceRequestIds.append(requestId)
            startLocationRequest(isOnce: true)
            return
        case .locationWatch:
            locationWatchRequestIds.append(requestId)
            startLocationRequest(isOnce: false)
            return
        case .locationClearWatch:
            locationWatchRequestIds.removeAll()
            stopLocationRequestIfNoRequest()
            break
        case .stampRallyQrCode:
            break
        case .browseApp, .browseInApp:
            if let target = queryItems["url"] {
                var wUrl = URL(string: target)
                if wUrl?.host == nil {
                    // ホストが指定されていない場合はページパス以降が指定されているので
                    // mainWebView に設定した同じドメインのページを表示する
                    wUrl = URL(string: target, relativeTo: originalUrl.url)?.absoluteURL
                }

                if let url = wUrl {
                    if command == .browseApp {
                        if UIApplication.shared.canOpenURL(url) {
                            UIApplication.shared.open(url, options: [:], completionHandler: nil)
                        }
                    } else if queryItems["sharedCookie"] == "true" {
                        let vc = PMWebViewController()
                        vc.pageUrl = url
                        present(vc, animated: true, completion: nil)
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
            shouldFixedWebView = true
            break
        }
        commandCallback(command, requestId: requestId, args: [:])
    }
    
    private func commandCallback(_ command: PMCommand, requestId: String, args: [String: Any]) {
        do {
            let jsonDate = try JSONSerialization.data(withJSONObject: args, options: [])
            guard let jsonString = String(data: jsonDate, encoding: .utf8) else {
                return
            }
            mainWebView.evaluateJavaScript("commandCallback('\(command.rawValue)', '\(requestId)', \(jsonString))", completionHandler: nil)
        } catch {
            dump(error)
        }
    }

    private func showCoverImageView(_ completion: (() -> Void)? = nil) {
        view.bringSubviewToFront(coverImageView)

        if 0 < coverImageView.alpha {
            completion?()
            return
        }

        UIView.animate(withDuration: 0.3) { [weak self] in
            self?.coverImageView.alpha = 1.0
        } completion: { finished in
            completion?()
        }
    }

    private func hideCoverImageView(_ completion: (() -> Void)? = nil) {
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
            self?.coverImageView.alpha = 0.0
        } completion: { [weak self] finished in
            if let imageView = self?.coverImageView {
                self?.view.sendSubviewToBack(imageView)
            }
            completion?()
        }
    }

    private func openSafariViewController(_ url: URL) {
        let vc = SFSafariViewController(url: url)
        present(vc, animated: true, completion: nil)
    }
}

extension PMMainViewController: CLLocationManagerDelegate {
    private func locationAuthorizationStatus() -> CLAuthorizationStatus {
        if CLLocationManager.locationServicesEnabled() {
            guard #available(iOS 14.0, *) else {
                return CLLocationManager.authorizationStatus()
            }
            return locationManager.authorizationStatus;
        }
        return .denied
    }
    
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

    private func locationStatusCommandCallback(_ status: CLAuthorizationStatus, command: PMCommand, requestId: String) {
        let statusText = locationAuthorizationStatusText(status)
        commandCallback(command, requestId: requestId, args: ["status": statusText])
    }
    
    private func startLocationRequest(isOnce: Bool) {
        let status = locationAuthorizationStatus()
        switch status {
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
            return
        case .restricted:
            break
        case .denied:
            let alertTitle = "確認";
            let alertMessage = "位置情報の使用を許可してください。";
            let alert = UIAlertController(title: alertTitle, message: alertMessage, preferredStyle: .alert)
            
            let okAction = UIAlertAction(title: "OK", style: .default) { (action) in
                guard let url = URL(string: UIApplication.openSettingsURLString) else {
                    return
                }
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
            alert.addAction(okAction)
            
            let cancelAction = UIAlertAction(title: "Cancel", style: .cancel, handler: nil)
            alert.addAction(cancelAction)
            
            present(alert, animated: true, completion: nil)
            break
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
            break
        }
        locationCommandCallback(location: nil, heading: nil)
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
                startLocationRequest(isOnce: !locationWatchRequestIds.isEmpty)
            case .notDetermined:
                break
            default:
                stopLocationRequest()
                locationCommandCallback(location: nil, heading: nil)
                locationWatchRequestIds.removeAll()
            }
        }
        
        if status != .notDetermined {
            // 権限を要求すると、まず .notDetermined が返ってくるのでこれは無視する。
            if let requestId = locationAuthorizeRequestId {
                locationStatusCommandCallback(status, command: .locationAuthorize, requestId: requestId)
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
        let status = locationAuthorizationStatus()
        args["status"] = locationAuthorizationStatusText(status)

        var callbacks = locationOnceRequestIds.map { id in
            return Promise<String> { [weak self] resolver in
                self?.commandCallback(.locationOnce, requestId: id, args: args)
                resolver.fulfill(id)
            }
        }

        let watchCallbacks = locationWatchRequestIds.map { id in
            return Promise<String> { [weak self] resolver in
                self?.commandCallback(.locationWatch, requestId: id, args: args)
                resolver.fulfill(id)
            }
        }
        callbacks.append(contentsOf: watchCallbacks)

        if !callbacks.isEmpty {
            _ = PromiseKit.when(resolved: callbacks)
        }

        locationOnceRequestIds.removeAll()
        stopLocationRequestIfNoRequest()
    }
}

// MARK: Push
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
    private func commandPush(_ command: String, args: [String: Any]) {
        do {
            let jsonDate = try JSONSerialization.data(withJSONObject: args, options: [])
            guard let jsonString = String(data: jsonDate, encoding: .utf8) else {
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
