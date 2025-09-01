package jp.co.boldright.platinumaps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import jp.co.boldright.platinumaps.sdk.PmWebView

class WebViewActivity : AppCompatActivity(), PmWebView.OnOpenLinkListener {

    private lateinit var webView: PmWebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_web_view)
        webView = findViewById(R.id.pm_sdk_web_view)
        webView.onOpenLinkListener = this
        webView.openPlatinumaps(
            "__dev_sr_comb__",
            "preview=bed7ddb0-2643-4d6b-9292-8fa636f7fbb0",
            0,
            0,
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.web_view_main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // ライブラリのメソッドを呼び出してパーミッション結果を処理させる
        webView.handlePermissionResult(requestCode, grantResults)
    }

    override fun onOpenLink(url: Uri, sharedCookie: Boolean) {
        if (sharedCookie) {
            val intent = Intent(this@WebViewActivity, WebBrowserActivity::class.java)
            intent.putExtra(WebBrowserActivity.BROWSING_URL, url.toString())
            startActivity(intent)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, url)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }
}
