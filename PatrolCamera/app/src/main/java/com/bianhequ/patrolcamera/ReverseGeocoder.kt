package com.bianhequ.patrolcamera

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 在线反向地理编码（坐标 → 地名）。
 *
 * 说明：
 * 1. 仅当点位库未命中且已配置 AMAP_KEY 时才发起网络请求（高德逆地理 API，免费额度充足）；
 * 2. 高德接口要求 GCJ-02 坐标，传入 GPS(WGS-84) 坐标时用 coordsys=gps 参数由高德端转换；
 * 3. AMAP_KEY 为空时直接返回 null，App 完全离线可用。
 *
 * 申请 Key 的方法：https://lbs.amap.com → 注册 → 控制台 → 应用管理 → 创建应用 →
 * 添加 Key（服务平台选"Web服务"）→ 把得到的 Key 填到下方 AMAP_KEY。
 */
object ReverseGeocoder {

    private const val TAG = "ReverseGeocoder"

    /** 高德开放平台 Web服务 Key；为空表示不启用在线地名 */
    const val AMAP_KEY = ""

    private const val REgeo_URL =
        "https://restapi.amap.com/v3/geocode/regeo?key=%s&location=%s&coordsys=gps&poiid=false&radius=300&extensions=base"

    /**
     * @param lat 纬度（WGS-84）
     * @param lng 经度（WGS-84）
     * @return 地名字符串；失败/未启用返回 null
     */
    fun reverse(lat: Double, lng: Double): String? {
        if (AMAP_KEY.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            val loc = String.format("%.6f,%.6f", lng, lat)
            val url = URL(String.format(REgeo_URL, URLEncoder.encode(AMAP_KEY, "UTF-8"), loc))
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) {
                Log.w(TAG, "regeo http ${conn.responseCode}")
                return null
            }
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                .use { it.readText() }
            val json = JSONObject(body)
            if (json.optString("status") != "1") {
                Log.w(TAG, "regeo fail: ${json.optString("info")}")
                return null
            }
            json.getJSONObject("regeocode").optString("formatted_address").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "regeo error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
