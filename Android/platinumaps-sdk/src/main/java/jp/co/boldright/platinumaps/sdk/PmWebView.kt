package jp.co.boldright.platinumaps.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Context.BLUETOOTH_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.lang.StringBuilder
import java.util.Date

class PmWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {
    private enum class PMCommand(val rawValue: String) {
        WEB_READY("web.ready"),
        WEB_WILL_RELOAD("web.willreload"),
        LOCATION_STATUS("location.status"),
        LOCATION_AUTHORIZE("location.authorize"),
        LOCATION_ONCE("location.once"),
        LOCATION_WATCH("location.watch"),
        LOCATION_CLEAR_WATCH("location.clearwatch"),

        BROWSE_APP("browse.app"),
        BROWSE_IN_APP("browse.inapp"),
        APP_INFO("app.info"),
        APP_DETECT("app.detect"),
        APP_REVIEW("app.review"),
        MAP_NAVIGATE("map.navigate"),

        WEB_FILE_CHOOSER("web.filechooser"),

        //region Beacon
        BEACON_AUTHORIZE("beacon.authorize"),
        BEACON_ONCE("beacon.once"),
        BEACON_WATCH("beacon.watch"),
        BEACON_CLEAR_WATCH("beacon.clearwatch"),
        //endregion

        //region Heading
        HEADING_WATCH("heading.watch"),
        HEADING_CLEAR_WATCH("heading.clearwatch")
        //endregion
    }

    enum class PmLocationAuthorizationStatus(val rawValue: String) {
        NOT_DETERMINED("notDetermined"),
        AUTHORIZED("authorized"),
        DENIED("denied"),
    }

    private val TAG = "platinumap.webview"
    private val TAG_BEACON = "platinumaps.beacon"
    private val TAG_HEADING = "platinumaps.heading"

    private var originalUrl: Uri? = null

    // Becomes true when the WebView is loading a page
    private var isWebViewLoading = false

    // The time when the WebView loading process was initiated
    private var webViewLoadingAt: Date? = null

    // Becomes true when web.ready is invoked
    private var hasWebReady = false

    private var isMeasuringLocation = false
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastLocation: Location? = null

    /** Receive location updates even if the location has not changed */
    private val minUpdateDistanceMeters = 0F

    /** Minimum update interval for location information in milliseconds */
    private val minUpdateIntervalMillis = 320L

    /** Maximum update interval for location information in milliseconds */
    private val maxUpdateIntervalMillis = 1000L

    private var locationAuthorizeRequestId: String? = null
    private var locationOnceRequestIds = mutableListOf<String>()
    private var locationWatchRequestIds = mutableListOf<String>()

    /**
     * Callback to receive location updates from FusedLocationProviderClient
     */
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                // Execute the same process as onLocationChanged
                this@PmWebView.lastLocation = location
                updateLocation(location, false)
            }
        }
    }

    //region Beacon

    private var beaconListeningUuid: String? = null

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanningBle = false
    private var isScanningBlePaused = false

    /** Request ID for beacon.authorize */
    private var beaconAuthorizeRequestId: String? = null

    /** Request IDs for beacon.once */
    private var beaconOnceRequestIds = mutableListOf<String>()

    /** Request IDs for beacon.watch */
    private var beaconWatchRequestIds = mutableListOf<String>()

    /** Beacons are received one by one from the BLE scanner, but they are sent to the web in batches. */
    private val beaconBuffer = mutableListOf<PmBeaconDto>()

    /** The time window in milliseconds for buffering beacon data before sending it to the web. */
    private val beaconBufferingWindow: Long = 500

    /** The last time beacon information (or error information) was sent to the web. */
    private var lastBeaconUpdateTime: Date = Date()

    /** True if a buffer flush is scheduled */
    private var isBeaconBufferFlushReserved = false

    //endregion

    //region Heading

    /** Sensor listener required for heading calculation. */
    private var sensorHeadingListener: SensorEventListener? = null

    /** The last known magnetic heading (integer value). */
    private var lastMagneticHeading: Int? = null

    /** The last time the heading was notified. */
    private var lastMagneticHeadingNotifiedAt: Date = Date()

    /** The interval in milliseconds for pushing heading updates to the web. */
    private val magneticHeadingPushInterval = 100L

    /** Request IDs for heading.watch */
    private val headingRequestIds = mutableListOf<String>()

    //endregion

    private val parentActivity: Activity?
        get() {
            return context as? Activity
        }

    private var playStoreId: String? = null
    private var appLinkUri: Uri? = null
    private var userId: String? = null
    private var secretKey: String? = null

    // Delegate for handling link clicks within the web page
    var onOpenLinkListener: OnOpenLinkListener? = null

    // Temporarily holds permission request callbacks
    private var geolocationPermissionsCallback: GeolocationPermissions.Callback? = null
    private var geolocationOrigin: String? = null
    private var activePermissionRequest: PermissionRequest? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    init {
        if (BuildConfig.DEBUG) {
            setWebContentsDebuggingEnabled(true)
        }

        // Enable JavaScript
        settings.javaScriptEnabled = true
        // Enable DOM Storage
        settings.domStorageEnabled = true
        settings.setGeolocationEnabled(true)
        settings.setSupportMultipleWindows(true)
        val userAgent = "${settings.userAgentString} Platinumaps/2.0.0"
        settings.userAgentString = userAgent
        // To handle command://xxx schemes
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
                onError(request, error?.description?.toString())
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                onError(request, errorResponse?.reasonPhrase)
            }

            private fun onError(request: WebResourceRequest?, message: String?) {
                val host = request?.url?.host
                if (host != null) {
                    Log.e(TAG, "error: url=${request.url} message=$message")
                } else {
                    Log.e(TAG, "error: message=$message")
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                request?.url?.let { uri ->
                    // This is called for both window.open and <a> tags.
                    // Since navigation away from Platinumaps is not expected,
                    // cancel the request by returning true from this method.
                    if (openRequest(uri) == 0u) {
                        return true
                    }
                }
                return false
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                // Display an alert dialog using AlertDialog
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

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                // Display a confirmation dialog using AlertDialog
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

            // Method to handle file selection
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // If there is an existing callback, cancel it
                this@PmWebView.filePathCallback?.onReceiveValue(null)

                this@PmWebView.filePathCallback = filePathCallback

                // Create a file chooser Intent
                val intent = fileChooserParams?.createIntent()
                intent?.let {
                    val activity = context as? Activity
                    if (activity != null) {
                        // Start the Intent from the Activity
                        ActivityCompat.startActivityForResult(
                            activity,
                            it,
                            FILE_CHOOSER_REQUEST_CODE,
                            null
                        )
                    }
                }
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

                // Check the requested permissions
                val requestedPermissions = mutableListOf<String>()

                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    requestedPermissions.add(Manifest.permission.CAMERA)
                }
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    requestedPermissions.add(Manifest.permission.RECORD_AUDIO)
                }

                if (requestedPermissions.isNotEmpty()) {
                    // Check if permissions have already been granted
                    val ungrantedPermissions = requestedPermissions.filter {
                        ContextCompat.checkSelfPermission(
                            activity,
                            it
                        ) != PackageManager.PERMISSION_GRANTED
                    }

                    if (ungrantedPermissions.isNotEmpty()) {
                        // If there are ungranted permissions, show a dialog
                        activePermissionRequest = request
                        ActivityCompat.requestPermissions(
                            activity,
                            ungrantedPermissions.toTypedArray(),
                            PERMISSION_REQUEST_CODE
                        )
                        // Do nothing here to wait for the user's response
                        // Call request.grant() in onRequestPermissionsResult
                    } else {
                        // If all are already granted, grant access immediately
                        request.grant(request.resources)
                    }
                } else {
                    // If no permissions are requested, deny
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

                if (ContextCompat.checkSelfPermission(
                        activity,
                        permission
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(permission),
                        GEOLOCATION_PERMISSION_REQUEST_CODE
                    )
                } else {
                    // If already granted, execute the callback immediately
                    callback.invoke(origin, true, false)
                }
            }
        }

        // Allow cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptThirdPartyCookies(this, true)
        cookieManager.setAcceptCookie(true)
    }

    private fun openPlatinumaps(options: PmMapOptions, queryPrams: String?) {
        // Safely build the URL using Uri.Builder
        val uriBuilder = "https://platinumaps.jp/maps/".toUri().buildUpon()

        // 1. Add the map path
        uriBuilder.appendPath(options.mapPath)

        // 2. Add fixed parameters for the native app
        uriBuilder.appendQueryParameter("native", "1")

        // 3a. [For compatibility] Process the old queryPrams of type String
        queryPrams?.takeIf { it.isNotBlank() }?.let { query ->
            val queryItems = query.split('&')
            for (queryItem in queryItems) {
                // Split only at the first '=', considering cases where the value contains '='
                val parts = queryItem.split('=', limit = 2)
                if (parts.size == 2 && parts[0].isNotEmpty()) {
                    uriBuilder.appendQueryParameter(parts[0], parts[1])
                }
            }
        }

        // 3b. Add parameters from queryParams (Map) in PmMapOptions
        options.queryParams?.forEach { (key, value) ->
            uriBuilder.appendQueryParameter(key, value)
        }

        // 4. Add parameters related to the safe area
        uriBuilder.appendQueryParameter("safearea", "${options.safeAreaTop},${options.safeAreaBottom}")

        // 5. Add parameters related to beacons
        options.beacon?.let { beacon ->
            beaconListeningUuid = beacon.uuid // Set the property here
            beacon.minSample?.let {
                uriBuilder.appendQueryParameter("beaconminsample", it.toString())
            }
            beacon.maxHistory?.let {
                uriBuilder.appendQueryParameter("beaconmaxhistory", it.toString())
            }
            beacon.memo?.let {
                uriBuilder.appendQueryParameter("memo", it)
            }
        }

        // Build the final URL and load it into the WebView
        originalUrl = uriBuilder.build()
        loadWebView()
    }

    /**
     * Loads a Platinumaps map in the WebView using a configuration object.
     * This method constructs the full map URL from the provided options and loads it.
     *
     * @param options An instance of `PmMapOptions` containing all necessary configurations,
     * such as the map path, query parameters, safe area insets, and beacon settings.
     */
    fun openPlatinumaps(options: PmMapOptions) {
        openPlatinumaps(options, null)
    }

    /**
     * Displays the specified Platinumaps map.
     *
     * This function constructs a full URL from the provided page path, query parameters,
     * and device-specific safe area dimensions. It then loads this URL into the WebView.
     * This is an alternative to the `PmMapOptions`-based method.
     *
     * @param pagePath The URL path of the map to display, appended to the base URL "https://platinumaps.jp/maps/".
     * @param mapQuery Optional query parameters for the map, provided as a string (e.g., "key1=value1&key2=value2"). Can be `null`.
     * @param safeAreaTop The height of the top safe area (e.g., status bar or notch) in pixels.
     * @param safeAreaBottom The height of the bottom safe area (e.g., navigation bar) in pixels.
     */
    fun openPlatinumaps(
        pagePath: String,
        mapQuery: String?,
        safeAreaTop: Int,
        safeAreaBottom: Int
    ) {
        openPlatinumaps(PmMapOptions(pagePath, null, safeAreaTop, safeAreaBottom), mapQuery)
    }

    //region WebView

    private fun loadWebView() {
        originalUrl?.let {
            val loadingAt = Date()
            webViewLoadingAt = loadingAt

            loadUrl(it.toString())
            isWebViewLoading = true
        }
    }

    //endregion

    //region Command

    // To handle requests from the WebView
    private fun openRequest(uri: Uri): UInt {
        if (uri.scheme == "command") {
            runCommand(uri)
        } else if (hasWebReady) {
            // Non-command URIs are displayed in an in-app browser
            // It handles schemes other than http as well
            // Since WebViewClient#shouldOverrideUrlLoading is called even for redirects,
            // do not process until web.ready has been called
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

            PMCommand.LOCATION_STATUS -> {
                val status = locationPermissionStatus()
                locationStatusCommandCallback(status, command, requestId)
                return 0u
            }

            PMCommand.LOCATION_AUTHORIZE -> {
                val status = locationPermissionStatus()
                if (status == PmLocationAuthorizationStatus.AUTHORIZED) {
                    locationStatusCommandCallback(status, command, requestId)
                } else {
                    locationAuthorizeRequestId = requestId
                    requestLocationPermission()
                }
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

            //region Beacon
            PMCommand.BEACON_AUTHORIZE -> {
                parentActivity?.let {
                    val status = beaconPermissionStatus()
                    if (status == PmAuthorizationStatus.AUTHORIZED) {
                        // Already has permission
                        beaconStatusCommandCallback(status, command, requestId)
                    } else {
                        // Requesting permission now
                        beaconAuthorizeRequestId = requestId
                        requestBeaconPermission()
                    }
                    return 0u
                }
                // This path is not expected to be taken (Activity should always exist)
                beaconStatusCommandCallback(PmAuthorizationStatus.DENIED, command, requestId)
                return 0u
            }

            PMCommand.BEACON_ONCE -> {
                beaconOnceRequestIds.add(requestId)
                startBeaconRequest(command, requestId)
                return 0u
            }

            PMCommand.BEACON_WATCH -> {
                beaconWatchRequestIds.add(requestId)
                startBeaconRequest(command, requestId)
                return 0u
            }

            PMCommand.BEACON_CLEAR_WATCH -> {
                beaconWatchRequestIds.clear()
                stopBeaconRequestIfNoRequest()
            }
            //endregion

            //region Heading
            PMCommand.HEADING_WATCH -> {
                headingRequestIds.add(requestId)
                startSensorHeadingRequest()
                return 0u
            }

            PMCommand.HEADING_CLEAR_WATCH -> {
                headingRequestIds.clear()
                stopSensorHeadingRequest()
            }
            //endregion

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
        parentActivity?.let {
            CustomTabsIntent.Builder().build().launchUrl(it, uri)
        }
    }

    private fun openWebBrowseActivity(uri: Uri) {
        onOpenLinkListener?.onOpenLink(uri, true)
    }

    private fun openWebBrowseApp(uri: Uri) {
        onOpenLinkListener?.onOpenLink(uri, false)
    }

    //endregion

    //region Location

    private fun requestPermissions(permissions: Array<String>, requestCode: Int) {
        parentActivity?.let {
            ActivityCompat.requestPermissions(
                it,
                permissions,
                requestCode
            )
        }
    }

    private fun locationPermissionStatus(): PmLocationAuthorizationStatus {
        parentActivity?.let {
            if (ContextCompat.checkSelfPermission(
                    it,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // Granted
                return PmLocationAuthorizationStatus.AUTHORIZED
            } else if (ActivityCompat.shouldShowRequestPermissionRationale(
                    it,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            ) {
                // Denied once, but the "Don't ask again" checkbox was not checked
                return PmLocationAuthorizationStatus.DENIED
            }
            // If denied with "Don't ask again", it's indistinguishable from not yet determined, so treat as "notDetermined".
            return PmLocationAuthorizationStatus.NOT_DETERMINED
        }
        return PmLocationAuthorizationStatus.DENIED
    }

    /**
     * Shows a custom dialog to explain the rationale for requiring permissions.
     */
    private fun showPermissionRationaleDialog(
        permissions: Array<String>,
        requestCode: Int,
        title: String,
        message: String
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                requestPermissions(permissions, requestCode)
            }
            .create()
            .show()
    }

    private fun requestLocationPermission() {
        val status = locationPermissionStatus()
        if (status == PmLocationAuthorizationStatus.AUTHORIZED) {
            updateLocationPermission(true)
        } else if (status == PmLocationAuthorizationStatus.DENIED) {
            showPermissionRationaleDialog(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_CODE_PERMISSIONS_LOCATION,
                context.getString(R.string.dialog_title_location_permission),
                context.getString(R.string.dialog_message_location_permission)
            )
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_CODE_PERMISSIONS_LOCATION
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationRequest(isOnce: Boolean) {
        parentActivity?.let {
            if (locationPermissionStatus() !== PmLocationAuthorizationStatus.AUTHORIZED) {
                return
            }

            // Initialize FusedLocationProviderClient (only if not already initialized)
            if (!::fusedLocationClient.isInitialized) {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(it)
            }

            if (isMeasuringLocation) {
                if (isOnce) {
                    lastLocation?.let { updateLocation(it, false) }
                }
                return
            }

            // Create a GMS LocationRequest
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                maxUpdateIntervalMillis
            ).apply {
                setMinUpdateIntervalMillis(minUpdateIntervalMillis)
                setMinUpdateDistanceMeters(minUpdateDistanceMeters)
            }.build()

            // Request location updates
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            isMeasuringLocation = true
        }
    }

    private fun stopLocationRequest() {
        if (!isMeasuringLocation) {
            return
        }
        // Stop after confirming that fusedLocationClient is initialized
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        isMeasuringLocation = false
    }

    private fun startLocationRequest(command: PMCommand, requestId: String) {
        val status = locationPermissionStatus()
        parentActivity?.let {
            if (status != PmLocationAuthorizationStatus.AUTHORIZED) {
                requestLocationPermission()
            } else {
                startLocationRequest(command == PMCommand.LOCATION_ONCE)
            }
            return
        }
        commandCallback(command, requestId, mapOf("status" to status.rawValue))
    }

    private fun stopLocationRequestIfNoRequest() {
        if (locationWatchRequestIds.isEmpty() && locationOnceRequestIds.isEmpty()) {
            stopLocationRequest()
        }
    }

    private fun locationStatusCommandCallback(
        status: PmLocationAuthorizationStatus,
        command: PMCommand,
        requestId: String
    ) {
        commandCallback(command, requestId, mapOf("status" to status.rawValue))
    }

    private fun updateLocationPermission(isGranted: Boolean) {
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

    private fun updateLocation(location: Location?, hasError: Boolean) {
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

    //region Beacon

    /**
     * Requests the necessary permissions for receiving beacon signals.
     * If permissions are already granted, it starts scanning for beacons.
     */
    private fun requestBeaconPermission() {
        parentActivity?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (it.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_DENIED
                    || it.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED
                ) {
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ), REQUEST_CODE_PERMISSIONS_BEACON
                    )
                } else {
                    initBeaconReceiverIfNeeded(it, true)
                }
            } else {
                if ((it.applicationContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED)) {
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ), REQUEST_CODE_PERMISSIONS_BEACON
                    )
                } else {
                    initBeaconReceiverIfNeeded(it, true)
                }
            }
        }
    }

    /**
     * Initializes the BLE scanner.
     * If startScanning is true, it begins the scan.
     */
    private fun initBeaconReceiverIfNeeded(context: Context, startScanning: Boolean) {
        parentActivity?.let {
            if (bluetoothLeScanner == null) {
                val bluetoothManager = it.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
                val bluetoothAdapter = bluetoothManager.adapter
                bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
                Log.d(TAG_BEACON, "initBeaconReceiver: ble scanner is created")
            }

            // Start scanning
            if (startScanning) {
                Handler(Looper.getMainLooper()).post { startScanningBeacon() }
            }
        }
    }

    /**
     * Starts the BLE scan.
     * Does nothing if already scanning.
     */
    @SuppressLint("MissingPermission")
    private fun startScanningBeacon() {
        if (isScanningBle) {
            Log.w(TAG_BEACON, "startScanningBeacon: already scanning")
            return
        }
        synchronized(this) {
            val filter = ScanFilter.Builder()
                .setManufacturerData(0x004C, byteArrayOf()) // Apple
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Low latency mode
                .build()

            bluetoothLeScanner?.startScan(listOf(filter), settings, leScanCallback)
            isScanningBle = true
            Log.d(TAG_BEACON, "startScanningBeacon: ble scan is now started")
        }
    }

    /**
     * Callback for receiving scan results.
     */
    private val leScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            val beacon = parseBeacon(result)
            if (beacon != null) {
                Log.d(TAG_BEACON, "onScanResult: detected $beacon")

                updateBeacon(beacon, false)
            }
        }
    }

    private fun parseBeacon(result: ScanResult): PmBeaconDto? {
        result.scanRecord?.let { scanRecord ->
            val bytes = scanRecord.bytes
            if (bytes.size > 30) {
                val sb = StringBuilder()

                // UUID
                for (i in 9..24) {
                    sb.append(String.format("%02x", bytes[i]))
                    if (i == 12 || i == 14 || i == 16 || i == 18) {
                        sb.append("-")
                    }
                }

                val uuid = sb.toString()
                if (beaconListeningUuid != null && !uuid.equals(
                        beaconListeningUuid,
                        ignoreCase = true
                    )
                ) {
                    // Log.d(TAG_BEACON, "ScanCallback: Ignored: $uuid")
                    return null
                }

                // Log.d(TAG, "Manu " + bytes.map { String.format("%02x ", it) })

                // Major/Minor (Big Endian)
                val major = ((bytes[25].toInt() and 0xFF) shl 8) or (bytes[26].toInt() and 0xFF)
                val minor = ((bytes[27].toInt() and 0xFF) shl 8) or (bytes[28].toInt() and 0xFF)

                val beacon = PmBeaconDto(uuid, major, minor, result.rssi)
                return beacon
            }
        }

        return null
    }

    /**
     * Gets the status of permissions required for beacon reception.
     */
    private fun beaconPermissionStatus(): PmAuthorizationStatus {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothScan = permissionStatus(Manifest.permission.BLUETOOTH_SCAN)
            val accessFineLocation = permissionStatus(Manifest.permission.ACCESS_FINE_LOCATION)

            if (bluetoothScan == PmAuthorizationStatus.AUTHORIZED
                && accessFineLocation == PmAuthorizationStatus.AUTHORIZED
            ) {
                return PmAuthorizationStatus.AUTHORIZED
            }

            if (bluetoothScan == PmAuthorizationStatus.DENIED
                || accessFineLocation == PmAuthorizationStatus.DENIED
            ) {
                return PmAuthorizationStatus.DENIED
            }

            return PmAuthorizationStatus.NOT_DETERMINED

        } else {
            val accessFineLocation = permissionStatus(Manifest.permission.ACCESS_FINE_LOCATION)
            return accessFineLocation
        }
    }

    /**
     * Gets the status of a specific permission.
     */
    private fun permissionStatus(permission: String): PmAuthorizationStatus {
        parentActivity?.let {
            if (ContextCompat.checkSelfPermission(
                    it,
                    permission
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // Granted
                return PmAuthorizationStatus.AUTHORIZED
            } else if (ActivityCompat.shouldShowRequestPermissionRationale(it, permission)) {
                // Denied once, but the "Don't ask again" checkbox was not checked
                return PmAuthorizationStatus.DENIED
            }
            // If denied with "Don't ask again", it's indistinguishable from not yet determined, so treat as "notDetermined".
            return PmAuthorizationStatus.NOT_DETERMINED
        }
        return PmAuthorizationStatus.DENIED
    }

    @SuppressLint("MissingPermission")
    private fun startBeaconRequest(isOnce: Boolean) {
        val permissionStatus = beaconPermissionStatus()
        if (permissionStatus !== PmAuthorizationStatus.AUTHORIZED) {
            Log.w(
                TAG_BEACON,
                "startBeaconRequest: cannot start ble scan because given permission is '${permissionStatus.rawValue}'"
            )
            return
        }

        parentActivity?.let {
            initBeaconReceiverIfNeeded(it, true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBeaconRequest() {
        if (!isScanningBle) {
            return
        }
        bluetoothLeScanner?.stopScan(leScanCallback)
        isScanningBle = false
        Log.d(TAG_BEACON, "stopBeaconRequest: ble scan is now stopped")
    }

    @SuppressLint("MissingPermission")
    private fun pauseScanningBeaconIfNeeded() {
        if (isScanningBle) {
            bluetoothLeScanner?.stopScan(leScanCallback)
            isScanningBle = false
            isScanningBlePaused = true
            Log.d(TAG_BEACON, "pauseScanningBeaconIfNeeded: ble scan is paused")
        }
    }

    private fun resumeScanningBeaconIfNeeded() {
        if (isScanningBlePaused) {
            startScanningBeacon()
            isScanningBlePaused = false
            Log.d(TAG_BEACON, "resumeScanningBeaconIfNeeded: ble scan is resumed")
        }
    }

    /**
     * Requests beacon information (starts BLE scan).
     */
    private fun startBeaconRequest(command: PMCommand, requestId: String) {
        val status = beaconPermissionStatus()
        parentActivity?.let {
            if (status != PmAuthorizationStatus.AUTHORIZED) {
                requestBeaconPermission()
            } else {
                startBeaconRequest(command == PMCommand.BEACON_ONCE)
            }
            return
        }
        commandCallback(command, requestId, mapOf("status" to status.rawValue))
    }

    /**
     * Stops requesting beacon information (stops BLE scan).
     */
    private fun stopBeaconRequestIfNoRequest() {
        if (beaconWatchRequestIds.isEmpty() && beaconOnceRequestIds.isEmpty()) {
            stopBeaconRequest()
        }
    }

    private fun destroyBeacon() {
        beaconAuthorizeRequestId = null
        beaconOnceRequestIds.clear()
        beaconWatchRequestIds.clear()
        beaconBuffer.clear()
        stopBeaconRequest()
    }

    /**
     * Sends the beacon permission status to the web.
     */
    private fun beaconStatusCommandCallback(
        status: PmAuthorizationStatus,
        command: PMCommand,
        requestId: String
    ) {
        commandCallback(command, requestId, mapOf("status" to status.rawValue))
    }

    /**
     * Called when the permission status for beacons has changed.
     */
    private fun updateBeaconPermission(isGranted: Boolean) {
        parentActivity?.let {
            if (isGranted) {
                initBeaconReceiverIfNeeded(it, true)
            }

            val args = mutableMapOf<String, Any>()
            if (isGranted) {
                args["status"] = PmAuthorizationStatus.AUTHORIZED.rawValue
            } else {
                args["status"] = PmAuthorizationStatus.DENIED.rawValue
            }

            // Return an error for the request ID that was pending permission
            beaconAuthorizeRequestId?.let {
                commandCallback(PMCommand.LOCATION_AUTHORIZE, it, args)
            }
            beaconAuthorizeRequestId = null

            if (!isGranted) {
                // Since permission was denied, return an error for beacon-waiting request IDs
                args["hasError"] = true

                for (item in beaconOnceRequestIds) {
                    commandCallback(PMCommand.BEACON_ONCE, item, args)
                }
                for (item in beaconWatchRequestIds) {
                    commandCallback(PMCommand.BEACON_WATCH, item, args)
                }
                beaconOnceRequestIds.clear()
                beaconWatchRequestIds.clear()
            }
        }
    }

    /**
     * Sends information about detected beacons to the web.
     */
    private fun updateBeacon(beacon: PmBeaconDto?, hasError: Boolean) {
        if (hasError) {
            val args = mutableMapOf<String, Any>()
            args["hasError"] = true

            val status = beaconPermissionStatus()
            if (status == PmAuthorizationStatus.AUTHORIZED) {
                args["status"] = PmAuthorizationStatus.AUTHORIZED.rawValue
            } else {
                args["status"] = PmAuthorizationStatus.DENIED.rawValue
            }

            args["beacons"] = mutableListOf<Map<String, Any>>()

            beaconCommandCallback(args)

            beaconBuffer.clear()
            lastBeaconUpdateTime = Date()
            return
        }

        beacon?.let {
            beaconBuffer.add(it)

            if (beaconBufferingWindow > 0) {
                val elapsed = Date().time - lastBeaconUpdateTime.time
                if (elapsed > beaconBufferingWindow) {
                    flushBeaconBuffer()
                } else if (!isBeaconBufferFlushReserved) {
                    reserveFlushBeaconBuffer()
                } else {
                    // Log.d(TAG_BEACON, " - beacon data is buffered(${beaconBuffer.size})")
                }
            } else {
                flushBeaconBuffer()
            }
        }
    }

    private fun reserveFlushBeaconBuffer() {
        if (isBeaconBufferFlushReserved) {
            return
        }
        isBeaconBufferFlushReserved = true
        Handler(Looper.getMainLooper()).postDelayed(
            {
                flushBeaconBuffer()
                isBeaconBufferFlushReserved = false
            }, beaconBufferingWindow
        )
    }

    private fun flushBeaconBuffer() {
        if (beaconBuffer.size > 0) {
            val args = mutableMapOf<String, Any>()
            var beacons = mutableListOf<Map<String, Any>>()

            for (beacon in beaconBuffer) {
                val b = mutableMapOf<String, Any>()
                b["uuid"] = beacon.uuid
                b["major"] = beacon.major
                b["minor"] = beacon.minor
                b["rssi"] = beacon.rssi
                b["timestamp"] = beacon.timestamp.time
                beacons.add(b)
            }

            beaconBuffer.clear()

            args["beacons"] = beacons

            // Log.d(TAG_BEACON, "sending ${beacons.size} beacons at once")

            beaconCommandCallback(args)
        }

        lastBeaconUpdateTime = Date()
    }

    private fun beaconCommandCallback(args: Map<String, Any>) {
        beaconOnceRequestIds.forEach {
            commandCallback(PMCommand.BEACON_ONCE, it, args)
        }

        beaconWatchRequestIds.forEach {
            commandCallback(PMCommand.BEACON_WATCH, it, args)
        }

        beaconOnceRequestIds.clear()
        stopBeaconRequestIfNoRequest()
    }

    //endregion

    //region Heading

    /**
     * Starts requesting heading updates.
     */
    private fun startSensorHeadingRequest() {
        if (sensorHeadingListener != null) {
            return
        }
        parentActivity?.let {

            val sensorManager = it.getSystemService(Context.SENSOR_SERVICE) as SensorManager

            sensorHeadingListener = object : SensorEventListener {
                private val gravity = FloatArray(3)
                private val geomagnetic = FloatArray(3)

                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            System.arraycopy(event.values, 0, gravity, 0, event.values.size)
                        }

                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            System.arraycopy(event.values, 0, geomagnetic, 0, event.values.size)
                        }
                    }

                    if (gravity.isNotEmpty() && geomagnetic.isNotEmpty()) {
                        val rotationMatrix = FloatArray(9)
                        val inclinationMatrix = FloatArray(9)

                        if (SensorManager.getRotationMatrix(
                                rotationMatrix,
                                inclinationMatrix,
                                gravity,
                                geomagnetic
                            )
                        ) {
                            val orientation = FloatArray(3)
                            SensorManager.getOrientation(rotationMatrix, orientation)

                            val azimuthInRadians = orientation[0]
                            val azimuthInDegrees =
                                Math.toDegrees(azimuthInRadians.toDouble()).toFloat()

                            // Device heading (relative to true north)
                            val magneticHeading: Int = Math.round((azimuthInDegrees + 360) % 360)
                            lastMagneticHeading = magneticHeading

                            // Notify at regular intervals
                            val now = Date()
                            if (now.time - lastMagneticHeadingNotifiedAt.time > magneticHeadingPushInterval) {
                                onUpdateHeading(magneticHeading)
                                lastMagneticHeadingNotifiedAt = now
                            }
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            }

            // Register sensor listeners
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            sensorManager.registerListener(
                sensorHeadingListener,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI
            )

            val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            sensorManager.registerListener(
                sensorHeadingListener,
                magnetometer,
                SensorManager.SENSOR_DELAY_UI
            )

            Log.d(TAG_HEADING, "heading sensor is now started")
        }
    }

    /**
     * Stops requesting heading updates.
     */
    private fun stopSensorHeadingRequest() {
        parentActivity?.let {
            if (sensorHeadingListener != null) {
                val sensorManager = it.getSystemService(Context.SENSOR_SERVICE) as SensorManager

                val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                sensorManager.unregisterListener(sensorHeadingListener, accelerometer)

                val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
                sensorManager.unregisterListener(sensorHeadingListener, magnetometer)

                sensorHeadingListener = null

                Log.d(TAG_HEADING, "heading sensor is now stopped")
            }
        }
    }

    /**
     * Pauses heading update requests.
     */
    private fun pauseSensorHeadingRequestIfNeeded() {
        Log.d(TAG_HEADING, "pausing heading sensor")
        stopSensorHeadingRequest()
    }

    /**
     * Resumes heading update requests.
     */
    private fun resumeSensorHeadingRequestIfNeeded() {
        headingWatcherExists().let {
            Log.d(TAG_HEADING, "resuming heading sensor")
            startSensorHeadingRequest()
        }
    }

    private fun headingWatcherExists(): Boolean {
        return headingRequestIds.isNotEmpty()
    }

    /**
     * Notifies the web of the updated device heading.
     */
    private fun onUpdateHeading(heading: Int) {
        val args = mutableMapOf<String, Any>()
        args["heading"] = heading

        headingRequestIds.forEach {
            commandCallback(PMCommand.HEADING_WATCH, it, args)
        }
    }

    //endregion

    /**
     * Handles the result of a runtime permission request.
     *
     * This method must be called from the parent Activity's or Fragment's `onRequestPermissionsResult`
     * callback to forward the result to the WebView. It is used for handling permissions such as
     * geolocation, camera, microphone, and beacons.
     *
     * @param requestCode The integer request code originally supplied to `requestPermissions()`.
     * @param grantResults The grant results for the corresponding permissions, which is either
     * [PackageManager.PERMISSION_GRANTED] or [PackageManager.PERMISSION_DENIED].
     */
    fun handlePermissionResult(requestCode: Int, grantResults: IntArray) {
        val allGranted =
            !(grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED })

        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // If granted
                    activePermissionRequest?.grant(activePermissionRequest?.resources)
                } else {
                    // If denied
                    activePermissionRequest?.deny()
                }
                activePermissionRequest = null
            }

            GEOLOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // If granted
                    geolocationPermissionsCallback?.invoke(geolocationOrigin, true, false)
                } else {
                    // If denied
                    geolocationPermissionsCallback?.invoke(geolocationOrigin, false, false)
                }
                geolocationPermissionsCallback = null
                geolocationOrigin = null
            }

            REQUEST_CODE_PERMISSIONS_LOCATION -> {
                updateLocationPermission(allGranted)
            }

            REQUEST_CODE_PERMISSIONS_BEACON -> {
                updateBeaconPermission(allGranted)
            }
        }
    }

    /**
     * Handles the result from a file chooser intent launched by the WebView.
     *
     * This method must be called from the parent Activity's or Fragment's `onActivityResult` callback
     * (or the modern Activity Result API equivalent). It passes the selected file's URI(s) back to the
     * WebView to complete the file upload process.
     *
     * @param requestCode The integer request code, which should be `FILE_CHOOSER_REQUEST_CODE`.
     * @param resultCode The integer result code returned by the child activity.
     * @param data An `Intent`, which can return result data to the caller.
     */
    fun handleFileChooserResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            var results: Array<Uri>? = null
            if (resultCode == Activity.RESULT_OK) {
                if (data != null) {
                    val dataString = data.dataString
                    if (dataString != null) {
                        results = arrayOf(dataString.toUri())
                    } else {
                        // In case of multiple file selection
                        results = data.clipData?.let {
                            (0 until it.itemCount).map { i -> it.getItemAt(i).uri }.toTypedArray()
                        }
                    }
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 100
        const val GEOLOCATION_PERMISSION_REQUEST_CODE = 101
        const val FILE_CHOOSER_REQUEST_CODE = 102

        // The following constants are ported from a previous version
        const val REQUEST_CODE_PERMISSIONS_LOCATION = 201
        const val REQUEST_CODE_PERMISSIONS_BEACON = 202
    }

    //region lifecycle

    /**
     * Pauses background tasks such as location, beacon, and sensor updates.
     *
     * This method should be called from the parent Activity's or Fragment's `onPause()`
     * lifecycle method to conserve battery and system resources when the app is not in the
     * foreground.
     */
    fun activityPause() {
        if (locationOnceRequestIds.isNotEmpty() || locationWatchRequestIds.isNotEmpty()) {
            stopLocationRequest()
        }
        pauseScanningBeaconIfNeeded()
        pauseSensorHeadingRequestIfNeeded()
    }

    /**
     * Resumes background tasks that were paused by `activityPause()`.
     *
     * This method should be called from the parent Activity's or Fragment's `onResume()`
     * lifecycle method to restart location, beacon, and sensor updates when the app returns
     * to the foreground.
     */
    fun activityResume() {
        if (locationOnceRequestIds.isNotEmpty() || locationWatchRequestIds.isNotEmpty()) {
            startLocationRequest(locationWatchRequestIds.isEmpty())
        }
        resumeScanningBeaconIfNeeded()
        resumeSensorHeadingRequestIfNeeded()
    }

    /**
     * Cleans up all resources used by the WebView.
     *
     * This method must be called from the parent Activity's or Fragment's `onDestroy()`
     * lifecycle method to prevent memory leaks. It stops all running services, clears the
     * WebView's history and state, and calls the underlying `destroy()` method.
     */
    fun activityDestroy() {
        destroyBeacon()
        loadUrl("about:blank")
        clearHistory()
        removeAllViews()
        destroy()
    }

    //endregion

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
