# Platinumaps 組込手順書(iOS)

## フォルダー構成
```
./README.md
./ViewControllers
./ViewControllers/PMWebViewController.swift
./ViewControllers/PMMainViewController.swift
./Views
./Views/PMWebView.swift
```

### README.md
このファイル

### PMMainViewController.swift
メイン画面

### PMWebViewController.swift
アプリ内ブラウザ画面

### PMWebView.swift
ウェブブラウザ部品
SafeArea 領域まで表示するためのものです。

## 組込手順
1. 位置情報やカメラ、マイクを利用しますので、これらが利用可能となるようにプロジェクトを設定してください。
2. `/Platinumaps` ごとプロジェクトに追加してください。
3. 以下のように `PMMainViewController` に対して必要な情報を設定してください。

```swift
let vc = PMMainViewController()
vc.mapSlug = "map_slug"
vc.mapQuery["key1"] = "value1"
vc.mapQuery["key2"] = "value2"
```

  * `mapSlug`  
    必須項目  
    URL用文字列を設定してください。
  * `mapQuery`  
    任意項目  
    クエリパラメータをディクショナリー形式で設定してください。
