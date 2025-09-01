### 🗺️ Platinumaps Android SDK 組み込み手順書

このドキュメントでは、Platinumaps SDKをAndroidアプリケーションに組み込む手順を説明します。

-----

### プロジェクト設定

Platinumaps SDKを使用するには、まずアプリの **`AndroidManifest.xml`** ファイルに以下のパーミッションと機能を設定する必要があります。これにより、SDKが通信、位置情報、カメラ、マイクを利用できるようになります。

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<uses-feature android:name="android.hardware.camera" />
<uses-feature android:name="android.hardware.camera.autofocus" />
```

-----

### 組み込み手順

#### 1. SDKの追加

`platinumaps-sdk-release.aar`ファイルをプロジェクトに追加します。具体的な手順については、Android Studioの公式ドキュメント「[Add a library dependency](https://developer.android.com/studio/projects/android-library?hl=ja#psd-add-library-dependency)」を参照してください。

#### 2. レイアウトの設定

マップを表示したいActivityのレイアウトファイルに **`PmWebView`** コンポーネントを追加します。このコンポーネントが、マップの表示領域となります。

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

#### 3. Activityへの実装

レイアウトファイルに対応するKotlinファイルに、 **`PmWebView`** コンポーネントを初期化し、マップを表示するためのロジックを実装します。

**`WebViewActivity.kt`**

```kotlin
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
            "demo", // 表示したいマップのURL文字列を指定してください
            "key1=value1&key2=value2", // 表示したいマップのクエリパラメータを指定してください
            0, // 全画面表示する場合に端末上部のノッチ領域などの高さを指定してください
            0, // 全画面表示する場合に端末下部のアクションバーなどの高さを指定してください
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
        // SDKのメソッドを呼び出してパーミッション結果を処理させる
        webView.handlePermissionResult(requestCode, grantResults)
    }

    override fun onOpenLink(url: Uri, sharedCookie: Boolean) {
        if (sharedCookie) {
            // スタンプラリーのダウンロード特典や外部リンク特典などユーザー情報を引き継ぐ必要がある場合に
            // sharedCookie=true でイベントが呼ばれますのでアプリ内ブラウザで表示するようにしてください
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
```

このコードでは、以下の処理を行っています。

  * **`onCreate`**: `PmWebView`を初期化し、 **`onOpenLinkListener`**を設定した後、**`openPlatinumaps`** メソッドを呼び出してマップを表示します。
  * **`onRequestPermissionsResult`**: ユーザーがパーミッションを許可または拒否した結果を、SDKの **`handlePermissionResult`** メソッドに渡します。
  * **`onOpenLink`**: マップ内のリンクがタップされた際に呼び出されます。 **`sharedCookie`** が`true`の場合はユーザー情報を引き継いでアプリ内ブラウザで開き、それ以外の場合は外部ブラウザで開くように実装されています。

この手順に従うことで、AndroidアプリケーションにPlatinumapsの地図機能を簡単に組み込むことができます。
