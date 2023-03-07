# Platinumaps 組込手順書(Android)

## プロジェクト設定

### Manifest.xml
以下のように、通信、位置情報、カメラが利用できるように設定してください。

```xml
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <uses-feature android:name="android.hardware.camera" />
    <uses-feature android:name="android.hardware.camera.autofocus" />
```

### bundle.grade(Module: app)
ビュー バインディング を利用していますので、参照元のプロジェクトでも以下のように有効にしてください。

```
android {
    ...
    viewBinding {
        enabled = true
    }
}
```

## 組込手順
1. 以下のページを参考に `platinumaps-sdk-release.aar` をプロジェクトに追加してください。  
https://developer.android.com/studio/projects/android-library?hl=ja#psd-add-library-dependency

2. 以下のように `PmMainActivity` に対して必要な情報を設定してください。
```kotlin
val intent = Intent(this@MainActivity, PmMainActivity::class.java)
intent.putExtra(PmMainActivity.MAP_SLUG, "map_slug")
intent.putExtra(PmMainActivity.MAP_QUERY, "key1=1&key2=abc")
intent.putExtra(PmMainActivity.USER_ID, "test_123456")
intent.putExtra(PmMainActivity.SECRET_KEY, "secret_key")
intent.putExtra(PmMainActivity.CAN_CLOSE, true)
```
  * `MAP_SLUG`  
    必須項目  
    URL用文字列を設定してください。
  * `MAP_QUERY`  
    任意項目  
    クエリパラメーターを設定してください。
  * `USER_ID`  
    任意項目  
    アプリ側で管理してているユーザー識別子を設定してください。
  * `SECRET_KEY`  
    ユーザー識別子を設定する場合は必須  
    ユーザー識別子を暗号化するキーを設定してください。
  * `CAN_CLOSE`  
    任意項目  
    ハードウェアの戻るボタンをクリックされたときに、プラチナマップ画面を閉じる場合に `true` を設定してください。  
    デフォルトでは `false` となり、プラチナマップ画面は閉じられません。
