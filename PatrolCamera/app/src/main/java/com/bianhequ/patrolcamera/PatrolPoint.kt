package com.bianhequ.patrolcamera

import org.json.JSONArray
import java.io.InputStream

/**
 * 巡查点位（离线点位库）。
 *
 * 坐标说明：点位数据来自"图新地图"，为 WGS-84 坐标系；
 * 手机 GPS 返回的也是 WGS-84，二者直接匹配即可，无需转换。
 * 若日后改用高德/百度地图取点（GCJ-02 坐标），
 * 请在 [PointMatcher] 中把 POINT_COORD_SYS 改为 GCJ02，匹配前会自动转换。
 */
data class PatrolPoint(
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double
) {
    companion object {
        fun loadFromAssets(stream: InputStream): List<PatrolPoint> {
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(text)
            val list = ArrayList<PatrolPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    PatrolPoint(
                        name = o.getString("name"),
                        lat = o.getDouble("lat"),
                        lng = o.getDouble("lng"),
                        radiusMeters = o.getDouble("radiusMeters")
                    )
                )
            }
            return list
        }
    }
}
