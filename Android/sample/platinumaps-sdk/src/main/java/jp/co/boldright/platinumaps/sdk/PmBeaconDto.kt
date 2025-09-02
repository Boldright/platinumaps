package jp.co.boldright.platinumaps.sdk

import java.util.Date

class PmBeaconDto(
    var uuid: String,
    var major: Int,
    var minor: Int,
    var rssi: Int,
    var timestamp: Date = Date()
) {

    override fun toString(): String {
        return "Beacon{" +
                // "uuid='" + uuid + '\',' +
                "major=" + major +
                ", minor=" + minor +
                ", rssi=" + rssi +
                '}'
    }
}
