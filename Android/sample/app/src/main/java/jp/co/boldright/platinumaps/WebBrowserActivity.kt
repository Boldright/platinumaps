package jp.co.boldright.platinumaps

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class WebBrowserActivity : AppCompatActivity() {
    companion object {
        const val BROWSING_URL = "browsingUrl"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_browser)

        val browsingUrl = intent.getStringExtra(BROWSING_URL)
        findViewById<WebView>(R.id.web_view)?.let {
            if (BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            // JavaScript を有効
            it.settings.javaScriptEnabled = true;
            // ローカルストレージを有効
            it.settings.domStorageEnabled = true
            it.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false
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

            browsingUrl?.let { url ->
                it.loadUrl(url)
            }
        }
    }

    private fun showAlertDialog(message: String?) {
        val alertDialog = AlertDialog.Builder(this).setMessage(message)
        alertDialog.setNeutralButton(android.R.string.ok) { _, _ -> }
        alertDialog.show()
    }

    private fun showConfirmDialog(message: String?, result: JsResult?) {
        val alertDialog = AlertDialog.Builder(this).setMessage(message)
        alertDialog.setPositiveButton(android.R.string.ok) { _, _ ->
            result?.confirm()
        }
        alertDialog.setNegativeButton(android.R.string.cancel) { _, _ ->
            result?.cancel()
        }
        alertDialog.show()
    }
}