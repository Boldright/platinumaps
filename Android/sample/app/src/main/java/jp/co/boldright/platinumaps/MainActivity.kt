package jp.co.boldright.platinumaps

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private val TAG = "platinumaps"

    private var safeAreaTop: Int = -1
    private var safeAreaBottom: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_view)) { v, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            safeAreaTop = systemBarsInsets.top
            safeAreaBottom = systemBarsInsets.bottom
            insets
        }
        findViewById<Button>(R.id.btn_open)?.apply {
            setOnClickListener {
                openPlatinumaps()
            }
        }
    }

    fun openPlatinumaps() {
        val intent = Intent(this@MainActivity, WebViewActivity::class.java)
        startActivity(intent)
    }
}
