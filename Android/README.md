### Platinumaps Android SDK Integration Guide

This document explains the procedure for integrating the Platinumaps SDK into an Android application.

-----

### Directory Structure

```
./README.md
./platinumaps-sdk
./platinumaps-sdk-release.aar
./sample
```

#### README.md

This file

#### platinumaps-sdk

The SDK's project folder

#### platinumaps-sdk-release.aar

The SDK library file

#### sample

The sample project folder

-----

### Project Setup

To use the Platinumaps SDK, you first need to configure the following permissions and features in your app's **`AndroidManifest.xml`** file.

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
```

**Note**: Setting the `android:required` attribute in `uses-feature` to `false` allows your app to be installed on devices that do not have camera hardware.

-----

### Integration Steps

#### 1\. Add the SDK

Add the `platinumaps-sdk-release.aar` file to your project. For specific instructions, please refer to the official Android Studio documentation: "[Add a library dependency](https://www.google.com/search?q=https://developer.android.com/studio/projects/android-library%3Fhl%3Den%23psd-add-library-dependency)".
Alternatively, you can include the `platinumaps-sdk` folder in your project as a module.

#### 2\. Set up the Layout

Add the **`PmWebView`** component to the layout file of the Activity where you want to display the map.

**`layout/activity_web_view.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/web_view_main"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    tools:context=".WebViewActivity">

    <jp.co.boldright.platinumaps.sdk.PmWebView
        android:id="@+id/pm_sdk_web_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:focusable="true"
        android:focusableInTouchMode="true"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

#### 3\. Implement in your Activity

In the corresponding Activity class, initialize the **`PmWebView`** component and implement the logic to integrate the SDK's features.

**`WebViewActivity.kt`**

```kotlin
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

        // Configure the map display settings using PmMapOptions
        webView.openPlatinumaps(
            PmMapOptions(
                mapPath = "demo", // Specify the path of the map to display
                queryParams = mapOf("key1" to "valueA", "key2" to "value2"), // Specify query parameters as a Map
                safeAreaTop = 0,    // Height of the top safe area (e.g., notch) for full-screen displays
                safeAreaBottom = 0, // Height of the bottom safe area (e.g., navigation bar) for full-screen displays
                beacon = PmMapBeaconOptions( // Settings for using beacons
                    uuid = "B9407F30-F5F8-466E-AFF9-25556B57FE6D", // Target beacon UUID
                    minSample = 5,
                    maxHistory = 5,
                    memo = "Operation check",
                )
            )
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.web_view_main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    // Link the Activity's lifecycle with the SDK
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

    // Pass the permission request result to the SDK
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        webView.handlePermissionResult(requestCode, grantResults)
    }

    // Pass the file chooser result to the SDK
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Handle the result from the intent started by onShowFileChooser
        if (requestCode == PmWebView.FILE_CHOOSER_REQUEST_CODE) {
            webView.handleFileChooserResult(requestCode, resultCode, data)
        }
    }

    // Handle link clicks within the map
    override fun onOpenLink(url: Uri, sharedCookie: Boolean) {
        if (sharedCookie) {
            // This is called with sharedCookie=true when user information needs to be carried over,
            // such as for stamp rally download rewards. Please display in an in-app browser.
            val intent = Intent(this@WebViewActivity, WebBrowserActivity::class.java)
            intent.putExtra(WebBrowserActivity.BROWSING_URL, url.toString())
            startActivity(intent)
            return
        }
        // Open other regular links in an external browser
        val intent = Intent(Intent.ACTION_VIEW, url)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }
}
```

This code performs the following integrations:

  * **`onCreate`**: Initializes `PmWebView` and calls the **`openPlatinumaps`** method using a **`PmMapOptions`** object to specify the map path, query parameters, and beacon settings.
  * **Lifecycle Integration**: Calls the corresponding SDK methods (`activityPause`, `activityResume`, `activityDestroy`) in `onPause`, `onResume`, and `onDestroy`. This ensures that location and beacon scanning are properly stopped and resumed when the app moves to the background, managing resources efficiently.
  * **`onRequestPermissionsResult`**: Passes the result of a user's permission decision (e.g., for location) to the SDK's **`handlePermissionResult`** method.
  * **`onActivityResult`**: Passes the result of a user's file selection from a file chooser dialog to the SDK's **`handleFileChooserResult`** method.
  * **`onOpenLink`**: Called when a link within the map is tapped. If **`sharedCookie`** is `true`, it opens the link in an in-app browser because user information needs to be preserved. Otherwise, it is opened in an external browser.

By following these steps, you can easily integrate the Platinumaps map functionality into your Android application.
