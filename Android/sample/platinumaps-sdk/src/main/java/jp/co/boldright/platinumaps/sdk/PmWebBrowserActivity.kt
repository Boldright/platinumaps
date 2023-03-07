package jp.co.boldright.platinumaps.sdk

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AlertDialog

class PmWebBrowserActivity : AppCompatActivity() {
    companion object {
        const val BROWSING_URL = "browsingUrl"
    }

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pm_web_browser)

        val browsingUrl = intent.getStringExtra(BROWSING_URL)
        findViewById<WebView>(R.id.pm_web_view)?.let {
            if (BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            // JavaScript を有効
            it.settings.javaScriptEnabled = true;
            // ローカルストレージを有効
            it.settings.domStorageEnabled = true;
            val userAgent = "${it.settings.userAgentString} Platinumaps/1.0.0"
            it.settings.userAgentString = userAgent
            it.webViewClient = PmWebBrowserView()
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

            browsingUrl?.let { url ->
                it.loadUrl(url)
            }
        }
    }

    private fun pmAlertDialog(context: Context): AlertDialog.Builder {
        return AlertDialog.Builder(context)
    }

    private fun showAlertDialog(message: String?) {
        val alertDialog = pmAlertDialog(this).setMessage(message)
        alertDialog.setNeutralButton(R.string.alert_ok) { _, _ -> }
        alertDialog.show()
    }

    private fun showConfirmDialog(message: String?, result: JsResult?) {
        val alertDialog = pmAlertDialog(this).setMessage(message)
        alertDialog.setPositiveButton(R.string.alert_ok) { _, _ ->
            result?.confirm()
        }
        alertDialog.setNegativeButton(R.string.alert_cancel) { _, _ ->
            result?.cancel()
        }
        alertDialog.show()
    }

    private class PmWebBrowserView() : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            return super.shouldOverrideUrlLoading(view, request)
        }
    }
}
