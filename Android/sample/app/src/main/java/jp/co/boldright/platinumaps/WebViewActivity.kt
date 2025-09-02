package jp.co.boldright.platinumaps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import jp.co.boldright.platinumaps.sdk.PmMapBeaconOptions
import jp.co.boldright.platinumaps.sdk.PmMapOptions
import jp.co.boldright.platinumaps.sdk.PmWebView

class WebViewActivity : AppCompatActivity(), PmWebView.OnOpenLinkListener {

    private lateinit var webView: PmWebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_view)
        webView = findViewById(R.id.pm_sdk_web_view)
        webView.onOpenLinkListener = this
        webView.openPlatinumaps(
            PmMapOptions(
                mapPath = "demo",
                queryParams = mapOf("key1" to "valueA", "key2" to "value2"),
                safeAreaTop = 0,
                safeAreaBottom = 0,
                beacon = PmMapBeaconOptions(
                    uuid = "XXX-XXX",
                    minSample = 5,
                    maxHistory = 5,
                    memo = "動作確認",
                )
            )
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.web_view_main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onPause() {
        webView.activityPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.activityResume()
    }

    override fun onDestroy() {
        webView.activityDestroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        webView.handlePermissionResult(requestCode, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PmWebView.FILE_CHOOSER_REQUEST_CODE) {
            webView.handleFileChooserResult(requestCode, resultCode, data)
        }
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
