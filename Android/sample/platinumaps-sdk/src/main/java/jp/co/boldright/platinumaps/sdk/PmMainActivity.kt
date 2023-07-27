package jp.co.boldright.platinumaps.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import jp.co.boldright.platinumaps.sdk.databinding.ActivityPmMainBinding
import java.util.*
import kotlin.math.floor

// キーボード表示制御のコールバック
interface OnKeyboardVisibilityListener {
    fun onKeyboardVisibilityChanged(visible: Boolean)
}

class PmMainActivity : AppCompatActivity(), LocationListener, OnKeyboardVisibilityListener {
    companion object {
        const val MAP_SLUG = "mapSlug"
        const val MAP_QUERY = "mapQuery"
        const val PLAY_STORE_ID = "playStoreId"
        const val CAN_CLOSE = "canClose"
        const val APP_LINK_URI = "appLinkUri"
        const val USER_ID = "userId"
        const val SECRET_KEY = "secretKey"
    }

    private val TAG = "platinumaps"
    private var canClose = false

    private val REQUEST_CODE_PERMISSIONS_LOCATION = 3002
    private val REQUEST_CODE_PERMISSIONS_CAMERA = 3003

    // この画面が読み込まれた時間
    private val loadAt = Date()

    private var isMeasuringLocation = false
    private var locationManager: LocationManager? = null
    private var locationCriteria: Criteria? = null
    private var lastLocation: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pm_main)

        val mapSlug = intent.getStringExtra(MAP_SLUG)
        mapSlug ?: throw IllegalArgumentException("map slug is empty.")

        val mapQuery = intent.getStringExtra(MAP_QUERY)

        val playStoreId = intent.getStringExtra(PLAY_STORE_ID)

        canClose = intent.getBooleanExtra(CAN_CLOSE, false)

        val appLinkUri = intent.getStringExtra(APP_LINK_URI)

        val userId = intent.getStringExtra(USER_ID)

        var secretKey = intent.getStringExtra(SECRET_KEY)

        // この Activity の描画が終わるのを待ってからWebView用のフラグメントを追加する
        // こちらを参考に ViewCompat#setOnApplyWindowInsetsListener を利用しようとしたがうまく動かなかったので
        // A-NAvi や CONNECT と同様にある程度待って処理するようにしている。
        // @see: https://mzkii.hatenablog.com/entry/2019/06/18/133551
        Handler(Looper.getMainLooper()).postDelayed({
            val statusBarHeight = getStatusBarHeight()
            setupMainFragment(mapSlug, mapQuery, playStoreId, statusBarHeight, appLinkUri, userId, secretKey)
        }, 1 * 1000)

        setKeyboardVisibilityListener(this)
    }

    override fun onBackPressed() {
        if (!canClose) {
            return
        }
        super.onBackPressed()
    }

    //region Fragment Bridge

    fun getStatusBarHeight(): Int {
        return 0;
    }

    private fun setupMainFragment(mapSlug: String, mapQuery: String?, playStoreId: String?, safeAreaTop: Int, appLinkUri: String?, userId: String?, secretKey: String?) {
        if (supportFragmentManager.fragments.isEmpty()) {
            val binding = ActivityPmMainBinding.inflate(layoutInflater)
            supportFragmentManager.beginTransaction()
                .replace(
                    binding.mainContainer.id,
                    PmMainFragment.newInstance(safeAreaTop, mapSlug, mapQuery, playStoreId, appLinkUri, userId, secretKey)
                )
                .commit()
        }
    }

    private fun mainFragment(): PmMainFragment? {
        return supportFragmentManager.findFragmentById(R.id.main_container) as? PmMainFragment
    }

    fun showSplashView(completion: () -> Unit?) {
        findViewById<ImageView>(R.id.pm_main_splash_view)?.run {
            if (visibility != View.VISIBLE) {
                visibility = View.VISIBLE
                animate().setDuration(300).alpha(1f).withEndAction {
                    completion()
                }
                return
            }
        }
        completion()
    }

    fun hideSplashView(completion: () -> Unit?) {
        findViewById<ImageView>(R.id.pm_main_splash_view)?.run {
            if (visibility == View.VISIBLE) {
                // ちゃんとスプラッシュ画像を見せたいので画面がロードされてから一定時間は待つようにする
                val limitMillis = 1 * 1000L
                var delay = Date().time - loadAt.time
                if (limitMillis < delay) {
                    delay = 0
                } else {
                    delay = limitMillis - delay
                }
                animate().setStartDelay(delay).setDuration(300).alpha(0f).withEndAction {
                    visibility = View.GONE
                    completion()
                }
                return
            }
        }
        completion()
    }

    //region Camera Permission

    fun canUseCamera(): Boolean {
        // check camera permission
        val permissionCheck =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)

        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            return true
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.CAMERA
            )
        ) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_camera_permission))
                .setMessage(getString(R.string.dialog_message_camera_permission))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermissions(
                        arrayOf(Manifest.permission.CAMERA),
                        REQUEST_CODE_PERMISSIONS_CAMERA
                    )
                }
                .create()
                .show()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE_PERMISSIONS_CAMERA
            )
        }
        return false
    }

    private fun updateCameraPermission(isGranted: Boolean) {
        mainFragment()?.updateCameraPermission(isGranted)
    }

    //endregion

    //region Location

    fun locationPermissionStatus(): PmMainFragment.PmLocationAuthorizationStatus {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // 許可されている
            return PmMainFragment.PmLocationAuthorizationStatus.AUTHORIZED
        } else if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        ) {
            // 一度拒否されているが、「今後は確認しない」にチェックが入っていない場合
            return PmMainFragment.PmLocationAuthorizationStatus.DENIED
        }
        // 許可しない & 今後も確認しない の場合、未確認と判別がつかないので、「未認証」とする。
        return PmMainFragment.PmLocationAuthorizationStatus.NOT_DETERMINED
    }

    fun requestLocationPermission() {
        val status = locationPermissionStatus()
        if (status == PmMainFragment.PmLocationAuthorizationStatus.AUTHORIZED) {
            updateLocationPermission(true)
        } else if (status == PmMainFragment.PmLocationAuthorizationStatus.DENIED) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_location_permission))
                .setMessage(getString(R.string.dialog_message_location_permission))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        REQUEST_CODE_PERMISSIONS_LOCATION
                    )
                }
                .create()
                .show()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_CODE_PERMISSIONS_LOCATION
            )
        }
    }

    private fun updateLocationPermission(isGranted: Boolean) {
        mainFragment()?.updateLocationPermission(isGranted)
    }

    @SuppressLint("MissingPermission")
    fun startLocationRequest(isOnce: Boolean) {
        if (locationPermissionStatus() !== PmMainFragment.PmLocationAuthorizationStatus.AUTHORIZED) {
            return
        }
        if (locationManager == null) {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

            val criteria = Criteria()
            criteria.accuracy = Criteria.ACCURACY_FINE
            criteria.powerRequirement = Criteria.POWER_MEDIUM
            criteria.isAltitudeRequired = true
            criteria.isBearingRequired = true
            criteria.isSpeedRequired = false
            criteria.isCostAllowed = true
            locationCriteria = criteria
        }

        locationManager?.let { manager ->
            locationCriteria?.let { criteria ->
                if (isMeasuringLocation) {
                    if (isOnce) {
                        mainFragment()?.updateLocation(lastLocation, false)
                    }
                    return
                }
                manager.requestLocationUpdates(320L, 1F, criteria, this, Looper.myLooper())
                isMeasuringLocation = true
            }
        }
    }

    fun stopLocationRequest() {
        if (!isMeasuringLocation) {
            return
        }
        locationManager?.removeUpdates(this)
        isMeasuringLocation = false
    }

    override fun onLocationChanged(location: Location) {
        this.lastLocation = location
        mainFragment()?.updateLocation(location, false)
    }

    //endregion

    //region Browser

    fun openWebBrowseActivity(uri: Uri) {
        val intent = Intent(this@PmMainActivity, PmWebBrowserActivity::class.java)
        intent.putExtra(PmWebBrowserActivity.BROWSING_URL, uri.toString())
        startActivity(intent)
    }

    //endregion

    //endregion

    //region Activity Result

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissions.isEmpty()) {
            // ダイアログが表示されているのにもかかわらず、このコールバックが呼ばれることがあり、
            // この時は permissions や grantResults は空となっている。
            return
        }
        val allGranted =
            !(grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED })
        when (requestCode) {
            REQUEST_CODE_PERMISSIONS_LOCATION -> {
                updateLocationPermission(allGranted)
            }
            REQUEST_CODE_PERMISSIONS_CAMERA -> {
                updateCameraPermission(allGranted)
            }
        }
    }

    //endregion


    //region Keyboard

    private fun setKeyboardVisibilityListener(onKeyboardVisibilityListener: OnKeyboardVisibilityListener) {
        val parentView: View =
            (findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        parentView.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            private var alreadyOpen = false
            private val defaultKeyboardHeightDP = 100
            private val EstimatedKeyboardDP =
                defaultKeyboardHeightDP + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) 48 else 0
            private val rect = Rect()
            override fun onGlobalLayout() {
                val estimatedKeyboardHeight = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    EstimatedKeyboardDP.toFloat(),
                    parentView.resources.displayMetrics
                ).toInt()
                parentView.getWindowVisibleDisplayFrame(rect)
                val heightDiff = parentView.rootView.height - (rect.bottom - rect.top)
                val isShown = heightDiff >= estimatedKeyboardHeight
                if (isShown == alreadyOpen) {
                    Log.i("Keyboard state", "Ignoring global layout change...")
                    return
                }
                alreadyOpen = isShown
                onKeyboardVisibilityListener.onKeyboardVisibilityChanged(isShown)
            }
        })
    }

    override fun onKeyboardVisibilityChanged(visible: Boolean) {
        var shouldAdjustView = false;
        if (visible) {
            // キーボードが表示された時に入力項目がキーボードに隠れないようにしたい
            mainFragment()?.currentPageUrl()?.let { pageUri ->
                val regex = "(spot=|stamprally=|ews=)".toRegex(RegexOption.IGNORE_CASE)
                pageUri.query?.let { query ->
                    shouldAdjustView = regex.containsMatchIn(query)
                }
            }
        }

        window?.apply {
            if (shouldAdjustView) {
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    //endregion
}
