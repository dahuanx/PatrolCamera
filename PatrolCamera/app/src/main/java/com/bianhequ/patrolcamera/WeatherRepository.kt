package com.bianhequ.patrolcamera

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** 天气快照：气温（取整）、WMO 天气代码、昼夜标记 */
data class WeatherInfo(
    val temperature: Int,
    val code: Int,
    val isDay: Boolean,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    /** 天气图标 */
    val iconRes: Int get() = WeatherRepository.iconOf(code, isDay)

    /** 中文简述，如“多云” */
    val label: String get() = WeatherRepository.labelOf(code)
}

/**
 * 天气数据源：Open-Meteo 免费接口（https://open-meteo.com）。
 *
 * 特点：
 *   - 无需 API Key，HTTPS；
 *   - 直接吃 WGS-84 经纬度（与 GPS 同系，无需坐标转换），返回 WMO 天气代码 + 气温；
 *   - 缓存策略：20 分钟内且位移 < 2km 直接复用；网络失败时沿用旧数据（不报错、不闪断）；
 *   - 若日后想换国内数据源（和风天气 / 高德天气），只改本文件即可。
 */
object WeatherRepository {

    private const val TAG = "PatrolCamera"
    private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"
    private const val CACHE_MS = 20 * 60 * 1000L      // 缓存有效期：20 分钟
    private const val MIN_MOVE_M = 2000.0             // 位置变化超过 2km 才重新请求
    private const val CONNECT_TIMEOUT = 6000
    private const val READ_TIMEOUT = 8000

    @Volatile private var cache: WeatherInfo? = null
    @Volatile private var cacheLat = Double.NaN
    @Volatile private var cacheLng = Double.NaN

    /** 当前缓存的天气（无则 null） */
    fun cached(): WeatherInfo? = cache

    /**
     * 获取天气。有新鲜缓存则直接返回，不联网；
     * 请求失败时返回旧缓存（可能过期）或 null。
     */
    suspend fun fetch(lat: Double, lng: Double, force: Boolean = false): WeatherInfo? =
        withContext(Dispatchers.IO) {
            val c = cache
            if (!force && c != null && isFresh(c, lat, lng)) return@withContext c

            val fresh = try {
                request(lat, lng)
            } catch (e: Exception) {
                Log.w(TAG, "天气获取失败: ${e.message}")
                null
            }
            if (fresh != null) {
                cache = fresh
                cacheLat = lat
                cacheLng = lng
                Log.i(TAG, "天气更新：${fresh.label} ${fresh.temperature}℃")
                fresh
            } else {
                c
            }
        }

    private fun isFresh(c: WeatherInfo, lat: Double, lng: Double): Boolean {
        if (System.currentTimeMillis() - c.fetchedAt > CACHE_MS) return false
        if (cacheLat.isNaN() || cacheLng.isNaN()) return true
        return PointMatcher.distanceMeters(lat, lng, cacheLat, cacheLng) < MIN_MOVE_M
    }

    private fun request(lat: Double, lng: Double): WeatherInfo? {
        val url = URL(
            String.format(
                Locale.US,
                "%s?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&timezone=Asia/Shanghai",
                ENDPOINT, lat, lng
            )
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "天气接口 HTTP ${conn.responseCode}")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val cur = JSONObject(body).getJSONObject("current")
            WeatherInfo(
                temperature = cur.getDouble("temperature_2m").roundToInt(),
                code = cur.optInt("weather_code", 3),
                isDay = cur.optInt("is_day", 1) == 1
            )
        } finally {
            conn.disconnect()
        }
    }

    // ---- WMO 天气代码 → 图标 / 中文简述 ----

    fun iconOf(code: Int, isDay: Boolean): Int = when (code) {
        0, 1 -> if (isDay) R.drawable.ic_w_sunny else R.drawable.ic_w_night
        2 -> if (isDay) R.drawable.ic_w_partly else R.drawable.ic_w_night
        3 -> R.drawable.ic_w_cloudy
        45, 48 -> R.drawable.ic_w_fog
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_w_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_w_snow
        95, 96, 99 -> R.drawable.ic_w_thunder
        else -> R.drawable.ic_w_cloudy
    }

    fun labelOf(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "晴间多云"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51 -> "毛毛雨"
        53, 55 -> "小雨"
        56, 57, 66, 67 -> "冻雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "米雪"
        80 -> "小阵雨"
        81 -> "阵雨"
        82 -> "强阵雨"
        85, 86 -> "阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷暴冰雹"
        else -> "天气"
    }
}
