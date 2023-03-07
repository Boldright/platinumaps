package jp.co.boldright.platinumaps

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import jp.co.boldright.platinumaps.sdk.PmMainActivity

class MainActivity : AppCompatActivity() {
    private val TAG = "platinumaps"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn_open)?.apply {
            setOnClickListener {
                openPlatinumaps()
            }
        }
    }

    fun openPlatinumaps() {
        val intent = Intent(this@MainActivity, PmMainActivity::class.java)
        intent.putExtra(PmMainActivity.PLAY_STORE_ID, "com.example")
        intent.putExtra(PmMainActivity.MAP_SLUG, "demo")
        intent.putExtra(PmMainActivity.MAP_QUERY, "key1=1&key2=abc")
        intent.putExtra(PmMainActivity.USER_ID, "test_123456")
        intent.putExtra(PmMainActivity.CAN_CLOSE, true)
        intent.putExtra(PmMainActivity.SECRET_KEY, "secret_key")

        // AppLinkから起動された場合のパラメータを取得
        this.intent.data?.let {
            Log.d(TAG, "intent.data: ${it}")
            intent.putExtra(PmMainActivity.APP_LINK_URI, it.toString())
        }

        startActivity(intent)
    }
}