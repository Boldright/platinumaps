package jp.co.boldright.platinumaps.sdk

/**
 * プラチナマップ表示用の設定を保持するクラス。
 *
 * @property mapPath 表示したいマップのパス (例: "demo/sr999")。
 * @property queryParams URLのクエリ文字列に追加するパラメータのMap。
 * @property safeAreaTop 上部のセーフエリア（ステータスバーなど）の高さをピクセル単位で指定します。デフォルトは `0` です。
 * @property safeAreaBottom 下部のセーフエリア（ナビゲーションバーなど）の高さをピクセル単位で指定します。デフォルトは `0` です。
 * @property beacon ビーコン機能を利用する場合の設定。利用しない場合は `null` を指定します。
 */
data class PmMapOptions(
    val mapPath: String,
    val queryParams: Map<String, String>? = null,
    val safeAreaTop: Int = 0,
    val safeAreaBottom: Int = 0,
    val beacon: PmMapBeaconOptions? = null
)

/**
 * マップのビーコン機能に関する設定を保持するクラス。
 *
 * @property uuid ビーコンを識別するための Proximity UUID。
 * @property minSample （任意）ビーコン電波の強度を安定させるためのサンプル数。デフォルト値はマップ側の設定に従います。
 * @property maxHistory （任意）ビーコン情報の履歴を保持する最大件数。デフォルト値はマップ側の設定に従います。
 * @property memo （任意）その他、マップ側に渡したい任意の情報を格納します。
 */
data class PmMapBeaconOptions(
    val uuid: String,
    val minSample: Int? = null,
    val maxHistory: Int? = null,
    val memo: String? = null
)
