package jp.co.boldright.platinumaps.sdk

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.appcompat.app.AlertDialog
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.Fragment
import jp.co.boldright.platinumaps.sdk.databinding.FragmentPmMainBinding
import org.json.JSONObject
import java.util.*

class PmMainFragment : Fragment(R.layout.fragment_pm_main) {
    enum class PMCommand(val rawValue: String) {
        WEB_READY("web.ready"),
        WEB_WILL_RELOAD("web.willreload"),
        LOCATION_STATUS("location.status"),
        LOCATION_AUTHORIZE("location.authorize"),
        LOCATION_ONCE("location.once"),
        LOCATION_WATCH("location.watch"),
        LOCATION_CLEAR_WATCH("location.clearwatch"),
        STAMP_RALLY_QR_CODE("stamprally.qrcode"),
        BROWSE_APP("browse.app"),
        BROWSE_IN_APP("browse.inapp"),
        APP_INFO("app.info"),
        APP_DETECT("app.detect"),
        APP_REVIEW("app.review"),
        MAP_NAVIGATE("map.navigate"),
    }

    enum class PmLocationAuthorizationStatus(val rawValue: String) {
        NOT_DETERMINED("notDetermined"),
        AUTHORIZED("authorized"),
        DENIED("denied"),
    }

    private val TAG = "platinumap.main.web"

    private var safeAreaTop: Int = -1
    private var mapSlug: String? = null
    private var mapQuery: String? = null
    private var playStoreId: String? = null
    private var appLinkUri: Uri? = null
    private var userId: String? = null
    private var secretKey: String? = null

    private var mainWebView: WebView? = null
    private var originalUrl: Uri? = null

    // WebView がページを読み込んでいるときにtrueになる
    private var isWebViewLoading = false

    // WebView のロード処理が行われた時間
    private var webViewLoadingAt: Date? = null

    // web.ready が呼ばれた
    private var hasWebReady = false

    // ページロードエラーが表示されている
    private var isWebViewLoadErrorVisible = false

    private var locationAuthorizeRequestId: String? = null
    private var locationOnceRequestIds = mutableListOf<String>()
    private var locationWatchRequestIds = mutableListOf<String>()

    private var pendingQrCodeRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            mapSlug = it.getString(MAP_SLUG)
            mapQuery = it.getString(MAP_QUERY)
            playStoreId = it.getString(PLAY_STORE_ID)
            safeAreaTop = it.getInt(SAFE_AREA_TOP, -1)
            it.getString(APP_LINK_URI)?.let { uriString ->
                appLinkUri = Uri.parse(uriString)
            }
            userId = it.getString(USER_ID)
            secretKey = it.getString(SECRET_KEY)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_pm_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentPmMainBinding.bind(view)

        binding.mainWebView.let {
            if (BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            // JavaScript を有効
            it.settings.javaScriptEnabled = true;
            // ローカルストレージを有効
            it.settings.domStorageEnabled = true;
            val userAgent = "${it.settings.userAgentString} Platinumaps/1.0.0"
            it.settings.userAgentString = userAgent
            // command://xxx を処理したい
            it.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isWebViewLoading = false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    showWebViewLoadErrorMessageIfNeeded()
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    showWebViewLoadErrorMessageIfNeeded()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    request?.url?.let { uri ->
                        // window.open だろうが a タグだろうがここがよばれる。
                        // プラチナマップから別なページに遷移することはないので、
                        // このメソッドの戻り値を true にしてリクエストをキャンセルする。
                        if (openRequest(uri) == 0u) {
                            return true
                        }
                    }
                    return super.shouldOverrideUrlLoading(view, request)
                }
            }

            it.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    showAlertDialog(message)
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    showConfirmDialog(message, result)
                    return true
                }
            }

            // Cookie を許可する
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptThirdPartyCookies(it, true)
            cookieManager.setAcceptCookie(true)

            val uri = Uri.parse("https://platinumaps.jp/maps/").buildUpon()
            if (BuildConfig.DEBUG) {
                // uri.authority("4b008f98d1ee.jp.ngrok.io")
            }
            uri.appendPath(mapSlug)
            uri.appendQueryParameter("native", "1")
            mapQuery?.let { query ->
                val queryItems = query.split('&')
                for (queryItem in queryItems) {
                    val item = queryItem.split('=')
                    if (item.count() == 2) {
                        uri.appendQueryParameter(item[0], item[1])
                    }
                }
            }
            if (safeAreaTop <= 0) {
                safeAreaTop = getStatusBarHeight()
            }
            uri.appendQueryParameter("safearea", "${safeAreaTop},0")

            originalUrl = uri.build()

            this.mainWebView = it
            loadWebView()
        }
    }

    override fun onResume() {
        super.onResume()
        if (locationOnceRequestIds.isNotEmpty() || locationWatchRequestIds.isNotEmpty()) {
            mainActivity()?.startLocationRequest(locationWatchRequestIds.isEmpty())
        }
    }

    override fun onStop() {
        if (locationOnceRequestIds.isNotEmpty() || locationWatchRequestIds.isNotEmpty()) {
            mainActivity()?.stopLocationRequest()
        }
        super.onStop()
    }

    private fun getStatusBarHeight(): Int {
        mainActivity()?.let {
            return it.getStatusBarHeight()
        }
        return 0;
    }

    private fun mainActivity(): PmMainActivity? {
        return activity as? PmMainActivity
    }

    fun currentPageUrl(): Uri? {
        mainWebView?.url?.let {
            Uri.parse(it)?.let { pageUrl ->
                return pageUrl
            }
        }

        return null
    }

    override fun onDestroyView() {
        // PMMainActivity を閉じても、WebViewのインスタンスが残っていて、
        // 待ち時間などのリクエストが処理されていた
        // @see: https://stackoverflow.com/questions/17418503/destroy-webview-in-android
        // あと、こっちも
        // https://developer.android.com/topic/libraries/view-binding?hl=ja
        // 注: フラグメントはビューよりも持続します。フラグメントの onDestroyView() メソッドでバインディング クラスのインスタンスへの参照をすべてクリーンアップしてください。
        mainWebView?.let {
            it.loadUrl("about:blank")
            it.onPause()
            it.removeAllViews()
            it.pauseTimers()
            it.destroy()
        }
        mainWebView = null

        super.onDestroyView()
    }

    //region WebView

    private fun loadWebView() {
        originalUrl?.let {
            val loadingAt = Date()
            webViewLoadingAt = loadingAt

            mainWebView?.loadUrl(it.toString())
            isWebViewLoading = true

            // Android の WebView はいい感じにタイムアウト処理をしてくれないので
            // 自前でタイムアウト処理を実装する必要がある
            val timeoutMillis = 120 * 1000L
            Handler(Looper.getMainLooper()).postDelayed({
                if (loadingAt != webViewLoadingAt) {
                    return@postDelayed
                }
                showWebViewLoadErrorMessageIfNeeded()
            }, timeoutMillis)
        }
    }

    private fun showWebViewLoadErrorMessageIfNeeded() {
        if (hasWebReady) {
            // web.ready が呼ばれていれば表示しない
            return
        }

        if (isWebViewLoading) {
            // 読み込み中であればキャンセルする
            mainWebView?.stopLoading();
        }
        isWebViewLoading = false

        if (isWebViewLoadErrorVisible) {
            // メッセージは多重に表示しない
            return
        }

        activity?.let {
            val alertDialog = pmAlertDialog(it).setMessage(R.string.dialog_message_reload)
            alertDialog.setPositiveButton(R.string.alert_reload) { _, _ ->
                isWebViewLoadErrorVisible = false
                loadWebView()
            }
            alertDialog.show()
            isWebViewLoadErrorVisible = true
        }
    }

    //endregion

    //region Command

    // WebView からのリクエスを処理したい
    private fun openRequest(uri: Uri): UInt {
        if (uri.scheme == "command") {
            runCommand(uri)
        } else if (hasWebReady) {
            // コマンド以外はアプリ内ブラウザで表示する
            // http 以外もいい感じに処理してくれる
            // リダイレクトの場合でも WebViewClient#shouldOverrideUrlLoading が呼ばれるので
            // web.ready が呼ばれるまでは処理させない
            openWebBrowseInApp(uri)
        } else {
            return 1u;
        }
        return 0u
    }

    private fun getCommand(commandUri: Uri): PMCommand? {
        commandUri.host?.let { command ->
            return PMCommand.values().firstOrNull { it.rawValue == command }
        }
        return null
    }

    private fun runCommand(commandUri: Uri): UInt {
        val command = getCommand(commandUri)
        command ?: return 1u
        val requestId = commandUri.getQueryParameter("requestId")
        requestId ?: return 1u
        when (command) {
            PMCommand.APP_INFO -> {
                val args = mutableMapOf<String, String>()
                userId?.let {
                    if (it.isNotEmpty()) {
                        args["userId"] = it
                    }
                }
                secretKey?.let {
                    if (it.isNotEmpty()) {
                        args["secretKey"] = it
                    }
                }
                commandCallback(command, requestId, args)
                return 0u
            }
            PMCommand.WEB_READY -> {
                hasWebReady = true
                mainActivity()?.let {
                    it.hideSplashView {
                        val args = mutableMapOf<String, String>()
                        appLinkUri?.let {
                            args["launchUrl"] = it.toString()
                        }
                        appLinkUri = null
                        commandCallback(command, requestId, args)
                    }
                    return 0u
                }
            }
            PMCommand.WEB_WILL_RELOAD -> {
                hasWebReady = false
                mainActivity()?.let {
                    it.showSplashView {
                        commandCallback(command, requestId, mapOf())
                    }
                    return 0u
                }
            }
            PMCommand.LOCATION_STATUS -> {
                val status = locationPermissionStatus()
                locationStatusCommandCallback(status, command, requestId)
                return 0u
            }
            PMCommand.LOCATION_AUTHORIZE -> {
                mainActivity()?.let {
                    val status = it.locationPermissionStatus()
                    if (status == PmLocationAuthorizationStatus.AUTHORIZED) {
                        locationStatusCommandCallback(status, command, requestId)
                    } else {
                        locationAuthorizeRequestId = requestId
                        it.requestLocationPermission()
                    }
                    return 0u
                }
                locationStatusCommandCallback(
                    PmLocationAuthorizationStatus.DENIED,
                    command,
                    requestId
                )
                return 0u
            }
            PMCommand.LOCATION_ONCE -> {
                locationOnceRequestIds.add(requestId)
                startLocationRequest(command, requestId)
                return 0u
            }
            PMCommand.LOCATION_WATCH -> {
                locationWatchRequestIds.add(requestId)
                startLocationRequest(command, requestId)
                return 0u
            }
            PMCommand.LOCATION_CLEAR_WATCH -> {
                locationWatchRequestIds.clear()
                stopLocationRequestIfNoRequest()
            }
            PMCommand.BROWSE_APP, PMCommand.BROWSE_IN_APP -> {
                commandWebBrowse(command, commandUri)
            }
            PMCommand.STAMP_RALLY_QR_CODE -> {
                if (canUseCamera(command, requestId)) {
                    openQrCodeReader(command, requestId)
                    return 0u
                } else if (pendingQrCodeRequestId != null) {
                    return 0u
                }
            }
            PMCommand.MAP_NAVIGATE -> {
                commandWebBrowse(command, commandUri)
            }
            PMCommand.APP_DETECT -> {
            }
            PMCommand.APP_REVIEW -> {
                playStoreId?.let {
                    val playStoreUri = "https://play.google.com/store/apps/details?id=${it}"
                    Uri.parse(playStoreUri)?.let {
                        openWebBrowseApp(it)
                    }
                }
            }
        }

        commandCallback(command, requestId, mapOf())
        return 0u
    }

    private fun commandCallback(command: PMCommand, requestId: String, args: Map<String, Any>) {
        val json = JSONObject(args)
        val callback = String.format(
            "commandCallback('%s','%s',%s)",
            command.rawValue,
            requestId,
            json.toString()
        )
        this.mainWebView?.evaluateJavascript(callback) { _ -> }
    }

    //endregion

    //region Web Browse

    private fun commandWebBrowse(command: PMCommand, commandUri: Uri) {
        commandUri.getQueryParameter("url")?.let { uriString ->
            parseBrowseUrl(uriString)?.let { uri ->
                when (command) {
                    PMCommand.BROWSE_APP,
                    PMCommand.MAP_NAVIGATE -> {
                        openWebBrowseApp(uri)
                    }
                    PMCommand.BROWSE_IN_APP -> {
                        val sharedCookie = commandUri.getQueryParameter("sharedCookie") == "true"
                        if (sharedCookie) {
                            openWebBrowseActivity(uri)
                        } else {
                            openWebBrowseInApp(uri)
                        }
                    }
                    else -> {
                        // ignore
                    }
                }
            }
        }
        // --
    }

    private fun parseBrowseUrl(urlString: String): Uri? {
        try {
            Uri.parse(urlString)?.let {
                if (it.authority?.isNotEmpty() == true) {
                    return it
                }
                originalUrl?.let { originalUrl ->
                    val workUri = it.buildUpon()
                    originalUrl.scheme?.let { scheme ->
                        workUri.scheme(scheme)
                    }
                    originalUrl.authority?.let { authority ->
                        workUri.authority(authority)
                    }
                    return workUri.build()
                }
            }
        } catch (ex: Exception) {
            ex.message?.let {
                Log.d(TAG, it)
            }
        }
        return null
    }

    private fun openWebBrowseInApp(uri: Uri) {
        mainActivity()?.apply {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
        }
    }

    private fun openWebBrowseActivity(uri: Uri) {
        mainActivity()?.openWebBrowseActivity(uri)
    }

    private fun openWebBrowseApp(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    //endregion

    //region QR Code Reader

    private fun canUseCamera(command: PMCommand, requestId: String): Boolean {
        mainActivity()?.let {
            if (it.canUseCamera()) {
                return true
            }
            pendingQrCodeRequestId = requestId
        }
        return false
    }

    private fun openQrCodeReader(command: PMCommand, requestId: String) {
        val dialog = PmQrCodeReaderDialog.Builder(this)
            .setReadQrCodeListener { result ->
                val args = mutableMapOf<String, String>()
                result?.let {
                    if (it.isNotEmpty()) {
                        args["value"] = it
                    }
                }
                commandCallback(command, requestId, args)
            }
            .setCancelButton(null) {
                commandCallback(command, requestId, mapOf())
            }
            .build()
        dialog.show(childFragmentManager, PmQrCodeReaderDialog::class.simpleName)
    }

    fun updateCameraPermission(isGranted: Boolean) {
        pendingQrCodeRequestId?.let {
            if (isGranted) {
                openQrCodeReader(PMCommand.STAMP_RALLY_QR_CODE, it)
            } else {
                commandCallback(PMCommand.STAMP_RALLY_QR_CODE, it, mapOf())
            }
        }
        pendingQrCodeRequestId = null
    }

    //endregion

    //region Location

    private fun startLocationRequest(command: PMCommand, requestId: String) {
        val status = locationPermissionStatus()
        mainActivity()?.let {
            if (status != PmLocationAuthorizationStatus.AUTHORIZED) {
                it.requestLocationPermission()
            } else {
                it.startLocationRequest(command == PMCommand.LOCATION_ONCE)
            }
            return
        }
        commandCallback(command, requestId, mapOf("status" to status.rawValue))
    }

    private fun stopLocationRequestIfNoRequest() {
        if (locationWatchRequestIds.isEmpty() && locationOnceRequestIds.isEmpty()) {
            mainActivity()?.stopLocationRequest()
        }
    }

    private fun locationPermissionStatus(): PmLocationAuthorizationStatus {
        mainActivity()?.let {
            return it.locationPermissionStatus()
        }
        return PmLocationAuthorizationStatus.DENIED
    }

    private fun locationStatusCommandCallback(
        status: PmLocationAuthorizationStatus,
        command: PMCommand,
        requestId: String
    ) {
        commandCallback(command, requestId, mapOf("status" to status.rawValue))
    }

    fun updateLocationPermission(isGranted: Boolean) {
        val args = mutableMapOf<String, Any>()
        if (isGranted) {
            args["status"] = PmLocationAuthorizationStatus.AUTHORIZED.rawValue
        } else {
            args["status"] = PmLocationAuthorizationStatus.DENIED.rawValue
        }
        if (!isGranted) {
            for (item in locationOnceRequestIds) {
                commandCallback(PMCommand.LOCATION_ONCE, item, args)
            }
            for (item in locationWatchRequestIds) {
                commandCallback(PMCommand.LOCATION_WATCH, item, args)
            }
            locationOnceRequestIds.clear()
            locationWatchRequestIds.clear()
        }

        locationAuthorizeRequestId?.let {
            commandCallback(PMCommand.LOCATION_AUTHORIZE, it, args)
        }
        locationAuthorizeRequestId = null
    }

    fun updateLocation(location: Location?, hasError: Boolean) {
        val args = mutableMapOf<String, Any>()
        val status = locationPermissionStatus()
        if (status == PmLocationAuthorizationStatus.AUTHORIZED) {
            args["status"] = PmLocationAuthorizationStatus.AUTHORIZED.rawValue
        } else {
            args["status"] = PmLocationAuthorizationStatus.DENIED.rawValue
        }

        location?.let {
            args["lat"] = it.latitude
            args["lng"] = it.longitude
            if (it.hasBearing()) {
                args["heading"] = it.bearing
            }
        }

        if (hasError) {
            args["hasError"] = true
        }

        locationOnceRequestIds.forEach {
            commandCallback(PMCommand.LOCATION_ONCE, it, args)
        }

        locationWatchRequestIds.forEach {
            commandCallback(PMCommand.LOCATION_WATCH, it, args)
        }

        locationOnceRequestIds.clear()
        stopLocationRequestIfNoRequest()
    }

    //endregion

    //region Alert Dialog

    private fun pmAlertDialog(context: Context): AlertDialog.Builder {
        return AlertDialog.Builder(context)
    }

    private fun showAlertDialog(message: String?) {
        activity?.let {
            val alertDialog = pmAlertDialog(it).setMessage(message)
            alertDialog.setNeutralButton(R.string.alert_ok) { _, _ -> }
            alertDialog.show()
        }
    }

    private fun showConfirmDialog(message: String?, result: JsResult?) {
        activity?.let {
            val alertDialog = pmAlertDialog(it).setMessage(message)
            alertDialog.setPositiveButton(R.string.alert_ok) { _, _ ->
                result?.confirm()
            }
            alertDialog.setNegativeButton(R.string.alert_cancel) { _, _ ->
                result?.cancel()
            }
            alertDialog.show()
            return
        }
        result?.cancel()
    }

    //endregion

    companion object {
        const val MAP_SLUG = "mapSlug"
        const val MAP_QUERY = "mapQuery"
        const val PLAY_STORE_ID = "playStoreId"
        const val SAFE_AREA_TOP = "safeAreaTop"
        const val APP_LINK_URI = "appLinkUri"
        const val USER_ID = "userId"
        const val SECRET_KEY = "secretKey"

        @JvmStatic
        fun newInstance(safeAreaTop: Int, mapSlug: String, mapQuery: String?, playStoreId: String?, appLinkUri: String?, userId: String?, secretKey: String?) =
            PmMainFragment().apply {
                arguments = Bundle().apply {
                    putInt(SAFE_AREA_TOP, safeAreaTop)
                    putString(MAP_SLUG, mapSlug)
                    mapQuery?.let {
                        putString(MAP_QUERY, it)
                    }
                    playStoreId?.let {
                        putString(PLAY_STORE_ID, it)
                    }
                    appLinkUri?.let {
                        putString(APP_LINK_URI, it)
                    }
                    userId?.let {
                        putString(USER_ID, it)
                    }
                    secretKey?.let {
                        putString(SECRET_KEY, it)
                    }
                }
            }
    }
}
