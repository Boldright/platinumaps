package jp.co.boldright.platinumaps.sdk

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import org.json.JSONObject
import java.util.Date

class PmWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {
    private enum class PMCommand(val rawValue: String) {
        WEB_READY("web.ready"),
        WEB_WILL_RELOAD("web.willreload"),
        BROWSE_APP("browse.app"),
        BROWSE_IN_APP("browse.inapp"),
        APP_INFO("app.info"),
        APP_DETECT("app.detect"),
        APP_REVIEW("app.review"),
        MAP_NAVIGATE("map.navigate"),

        WEB_FILE_CHOOSER("web.filechooser"),
    }

    private val TAG = "platinumap.webview"

    private var originalUrl: Uri? = null

    // WebView がページを読み込んでいるときにtrueになる
    private var isWebViewLoading = false

    // WebView のロード処理が行われた時間
    private var webViewLoadingAt: Date? = null

    // web.ready が呼ばれた
    private var hasWebReady = false

    // ページロードエラーが表示されている
    private var isWebViewLoadErrorVisible = false

    private val parentActivity: Activity?
        get() {
            return context as? Activity
        }

    var playStoreId: String? = null
    var appLinkUri: Uri? = null
    var userId: String? = null
    var secretKey: String? = null

    // Webページ内のリンククリックを処理するためのデリゲート
    var onOpenLinkListener: OnOpenLinkListener? = null

    // パーミッションリクエストのコールバックを一時的に保持
    private var geolocationPermissionsCallback: GeolocationPermissions.Callback? = null
    private var geolocationOrigin: String? = null
    private var activePermissionRequest: PermissionRequest? = null

    init {
        if (BuildConfig.DEBUG) {
            setWebContentsDebuggingEnabled(true)
        }

        // JavaScript を有効
        settings.javaScriptEnabled = true
        // ローカルストレージを有効
        settings.domStorageEnabled = true
        val userAgent = "${settings.userAgentString} Platinumaps/2.0.0"
        settings.userAgentString = userAgent
        // command://xxx を処理したい
        webViewClient = object : WebViewClient() {
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
                return false
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                // AlertDialogを使ってアラートダイアログを表示
                AlertDialog.Builder(context)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { dialog, which ->
                        result?.confirm()
                    }
                    .setCancelable(false)
                    .create()
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                // AlertDialogを使って確認ダイアログを表示
                AlertDialog.Builder(context)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { dialog, which ->
                        result?.confirm()
                    }
                    .setNegativeButton(android.R.string.cancel) { dialog, which ->
                        result?.cancel()
                    }
                    .setCancelable(false)
                    .create()
                    .show()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) {
                    super.onPermissionRequest(request)
                    return
                }

                val activity = context as? Activity
                if (activity == null) {
                    request.deny()
                    return
                }

                // 要求された権限をチェック
                val requestedPermissions = mutableListOf<String>()

                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    requestedPermissions.add(Manifest.permission.CAMERA)
                }
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    requestedPermissions.add(Manifest.permission.RECORD_AUDIO)
                }

                if (requestedPermissions.isNotEmpty()) {
                    // パーミッションがすでに許可されているか確認
                    val ungrantedPermissions = requestedPermissions.filter {
                        ContextCompat.checkSelfPermission(
                            activity,
                            it
                        ) != PackageManager.PERMISSION_GRANTED
                    }

                    if (ungrantedPermissions.isNotEmpty()) {
                        // 許可されていないパーミッションがあれば、ダイアログを表示
                        activePermissionRequest = request
                        ActivityCompat.requestPermissions(
                            activity,
                            ungrantedPermissions.toTypedArray(),
                            PERMISSION_REQUEST_CODE
                        )
                        // ユーザーの応答を待つため、ここでは何もしない
                        // onRequestPermissionsResultでrequest.grant()を呼び出す
                    } else {
                        // すべて許可済みなら即座にアクセスを許可
                        request.grant(request.resources)
                    }
                } else {
                    // 権限が要求されていなければ拒否
                    request.deny()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (callback == null) {
                    return super.onGeolocationPermissionsShowPrompt(origin, callback)
                }

                val activity = context as? Activity
                if (activity == null) {
                    callback.invoke(origin, false, false)
                    return
                }

                geolocationPermissionsCallback = callback
                geolocationOrigin = origin

                val permission = Manifest.permission.ACCESS_FINE_LOCATION

                if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(activity, arrayOf(permission), GEOLOCATION_PERMISSION_REQUEST_CODE)
                } else {
                    // 既に許可されている場合は、コールバックを即座に実行
                    callback.invoke(origin, true, false)
                }
            }
        }

        // Cookie を許可する
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptThirdPartyCookies(this, true)
        cookieManager.setAcceptCookie(true)
    }

    /**
     * Displays the specified Platinumaps map.
     *
     * This function constructs a full URL from the provided page path, query parameters,
     * and device-specific safe area dimensions. It then loads this URL into a WebView.
     *
     * @param pagePath The URL string of the map to display, appended to the base URL
     * of "https://platinumaps.jp/maps/".
     * @param mapQuery The query parameters for the map, provided as a string. Can be `null`
     * if there are no additional parameters. The function parses this string and
     * appends each key-value pair to the URL.
     * @param safeAreaTop The height of the top safe area (e.g., notch) in a full-screen display.
     * @param safeAreaBottom The height of the bottom safe area (e.g., action bar) in a full-screen display.
     */
    fun openPlatinumaps(pagePath: String, mapQuery: String?, safeAreaTop: Int, safeAreaBottom: Int) {
        val uri = "https://platinumaps.jp/maps/".toUri().buildUpon()
        uri.appendPath(pagePath)
        uri.appendQueryParameter("native", "2")
        mapQuery?.let { query ->
            val queryItems = query.split('&')
            for (queryItem in queryItems) {
                val item = queryItem.split('=')
                if (item.count() == 2) {
                    uri.appendQueryParameter(item[0], item[1])
                }
            }
        }
        uri.appendQueryParameter("safearea", "${safeAreaTop},${safeAreaBottom}")

        originalUrl = uri.build()
        loadWebView()
    }

    //region WebView

    private fun loadWebView() {
        originalUrl?.let {
            val loadingAt = Date()
            webViewLoadingAt = loadingAt

            loadUrl(it.toString())
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
            stopLoading()
        }
        isWebViewLoading = false

        if (isWebViewLoadErrorVisible) {
            // メッセージは多重に表示しない
            return
        }

        parentActivity?.let {
            val alertDialog = AlertDialog.Builder(it).setMessage(R.string.dialog_message_reload)
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
            return PMCommand.entries.firstOrNull { it.rawValue == command }
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
                val args = mutableMapOf<String, String>()
                appLinkUri?.let {
                    args["launchUrl"] = it.toString()
                }
                appLinkUri = null
                commandCallback(command, requestId, args)
                return 0u
            }

            PMCommand.WEB_WILL_RELOAD -> {
                hasWebReady = false
                commandCallback(command, requestId, mapOf())
                return 0u
            }

            PMCommand.BROWSE_APP, PMCommand.BROWSE_IN_APP -> {
                commandWebBrowse(command, commandUri)
            }

            PMCommand.MAP_NAVIGATE -> {
                commandWebBrowse(command, commandUri)
            }

            PMCommand.APP_DETECT -> {
            }

            PMCommand.APP_REVIEW -> {
                playStoreId?.let {
                    val playStoreUri = "https://play.google.com/store/apps/details?id=${it}"
                    playStoreUri.toUri().let {
                        openWebBrowseApp(it)
                    }
                }
            }

            PMCommand.WEB_FILE_CHOOSER -> {
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
        evaluateJavascript(callback) { _ -> }
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
            urlString.toUri().let {
                if (it.authority?.isNotEmpty() == true) {
                    return it
                }
                if (it.scheme == "tel") {
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
        parentActivity?.apply {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
        }
    }

    private fun openWebBrowseActivity(uri: Uri) {
        onOpenLinkListener?.onOpenLink(uri, true)
    }

    private fun openWebBrowseApp(uri: Uri) {
        onOpenLinkListener?.onOpenLink(uri, false)
    }

    //endregion

    /**
     * Handles the result of a permission request.
     *
     * This method should be called from the `onRequestPermissionsResult` method
     * in an Android Activity or Fragment. It processes the user's decision (allow or deny)
     * for a specific permission request code.
     *
     * @param requestCode The request code passed to `requestPermissions()`. This identifies the
     * permission request that was just completed.
     * @param grantResults An array of granted or denied permissions. The results for the
     * corresponding permissions in `requestPermissions()`. A value of
     * [PackageManager.PERMISSION_GRANTED] indicates that the permission
     * was granted.
     */
    fun handlePermissionResult(requestCode: Int, grantResults: IntArray) {
        when (requestCode) {
            GEOLOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 許可された場合
                    geolocationPermissionsCallback?.invoke(geolocationOrigin, true, false)
                } else {
                    // 拒否された場合
                    geolocationPermissionsCallback?.invoke(geolocationOrigin, false, false)
                }
                geolocationPermissionsCallback = null
                geolocationOrigin = null
            }
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // 許可された場合
                    activePermissionRequest?.grant(activePermissionRequest?.resources)
                } else {
                    // 拒否された場合
                    activePermissionRequest?.deny()
                }
                activePermissionRequest = null
            }
        }
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 100
        const val GEOLOCATION_PERMISSION_REQUEST_CODE = 101
    }

    /**
     * Interface definition for a callback to be invoked when a link is opened within the Platinumaps.
     *
     * This listener can be used to handle various types of links, not just web URLs, but also other schemes like 'tel:' and 'mailto:'.
     */
    interface OnOpenLinkListener {

        /**
         * Called when a link is opened.
         *
         * @param url The URI of the link to be opened, which can be an HTTP URL or other URI schemes like 'tel:' and 'mailto:'.
         * @param sharedCookie A boolean flag that is true when user information needs to be passed to the link, such as for temporary download benefits or external link benefits.
         */
        fun onOpenLink(url: Uri, sharedCookie: Boolean)
    }
}
