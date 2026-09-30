package com.bianhequ.patrolcamera

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * 在线反向地理编码（坐标 → 地名），数据源：**天地图**（国家地理信息公共服务平台）。
 *
 * 启用条件：点位库未命中，且设置页"在线地名"开关为开（默认开）。
 * 未启用 / 无网络 / 解析失败一律静默返回 null，由调用方回退到经纬度，App 始终可用。
 *
 * ── 坐标系（2026-09-30 实测定论，勿改） ─────────────────────────────
 * 天地图 geocoder 接口按 **WGS-84 / CGCS2000** 解析，手机 GPS 坐标**直传，不做任何转换**。
 * 实测（绥芬河边境经济合作区管理局点位）：
 *   · 直传 WGS-84 → 返回"绥芬河市边境经济合作区"，距离 11 米          ✓
 *   · 传 GCJ-02 偏移坐标 → 返回"森鑫木业有限公司东北约307米"，距离 307 米 ✗
 * 本地 WGS-84→GCJ-02 偏移量约 657 米（东 605 / 北 258），一旦误转即整体偏到隔壁企业。
 *
 * ── 名称质量（同批实测） ─────────────────────────────────────────
 * 天地图在绥芬河这类边境小城的 POI 密度有限，会返回 "ZR01"、"S206" 这类无意义编号，
 * 或隔壁企业名。因此 [buildName] 做了清洗：POI 必须含中文字符才算有效、距离过远则降级，
 * 最后回退到行政区。地名主力仍是 App 内置的离线点位库。
 *
 * ── 行政区前缀 ──────────────────────────────────────────────
 * 本路径（点库未命中的兜底）输出的地名**带省、地市、县区前缀**，如
 * `黑龙江省牡丹江市绥芬河市 宽沟路 东南约282米`；库内点位名不加前缀（同县区重复无意义）。
 *
 * 申请/更换 Key：天地图控制台 → 创建应用（**应用类型必须选"服务端"**，移动端/浏览器端 Key 调不了本接口）
 * → 复制 tk 填入设置页"在线地名 Key"，或在下方 TIANDITU_KEY_DEFAULT 内置。
 */
object ReverseGeocoder {

    private const val TAG = "ReverseGeocoder"

    /** 内置天地图"服务端"Key；设置页留空时使用 */
    const val TIANDITU_KEY_DEFAULT = "6d2b68481e45b02598ee7f5e578ba6e4"

    private const val URL_TEMPLATE =
        "https://api.tianditu.gov.cn/geocoder?postStr=%s&type=geocode&tk=%s"

    /** 同一网格内的结果缓存时长：走动巡查时避免反复打接口 */
    private const val CACHE_TTL_MS = 20 * 60 * 1000L

    /** 缓存网格边长（度）：约 90 米（纬度）/ 110 米（经度，本地纬度） */
    private const val CACHE_GRID = 0.001

    /** POI 距离超过此值视为"不是这儿"，降级显示行政区 */
    private const val POI_MAX_RELEVANT_M = 500

    private val cache = LinkedHashMap<String, Pair<String, Long>>()

    /**
     * @param lat 纬度（WGS-84，直传天地图）
     * @param lng 经度（WGS-84，直传天地图）
     * @return 地名字符串；未启用 / 失败返回 null
     */
    fun reverse(context: Context, lat: Double, lng: Double): String? {
        if (!SettingsActivity.isOnlineGeoEnabled(context)) return null
        val key = SettingsActivity.onlineGeoKey(context)
        if (key.isBlank()) return null

        val ck = cacheKey(lat, lng)
        synchronized(cache) {
            cache[ck]?.let { (name, at) ->
                if (System.currentTimeMillis() - at <= CACHE_TTL_MS) return name
                cache.remove(ck)
            }
        }
        // 失败不写缓存，避免网络抖动被记住 20 分钟
        val name = request(key, lat, lng) ?: return null
        synchronized(cache) {
            if (cache.size >= 200) cache.clear()
            cache[ck] = name to System.currentTimeMillis()
        }
        return name
    }

    /** 命中后立刻失效缓存（设置页改 Key 时调用，避免旧结果残留） */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    private fun cacheKey(lat: Double, lng: Double): String {
        val la = Math.round(lat / CACHE_GRID)
        val lo = Math.round(lng / CACHE_GRID)
        return "$la,$lo"
    }

    private fun request(key: String, lat: Double, lng: Double): String? {
        var conn: HttpURLConnection? = null
        return try {
            // 注意：天地图要求 lon 在前、lat 在后，postStr 为 JSON 且需 URL 编码
            val postStr = String.format(Locale.US, "{\"lon\":%.7f,\"lat\":%.7f,\"ver\":1}", lng, lat)
            val url = URL(
                String.format(
                    URL_TEMPLATE,
                    URLEncoder.encode(postStr, "UTF-8"),
                    URLEncoder.encode(key, "UTF-8")
                )
            )
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) {
                Log.w(TAG, "geocoder http ${conn.responseCode}")
                return null
            }
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                .use { it.readText() }
            val json = JSONObject(body)
            // 天地图约定：status "0" 为成功
            if (json.optString("status") != "0") {
                Log.w(TAG, "geocoder fail: ${json.optString("msg")}")
                return null
            }
            val result = json.optJSONObject("result") ?: return null
            buildName(result)
        } catch (e: Exception) {
            Log.w(TAG, "geocoder error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 从天地图返回结果中挑一个适合进水印的地名，**带省、地市、县区前缀**。
     *
     * 输出规则：
     *   1. POI 名含中文且距离 ≤ 500m → 近距离直接用名、略远带方位距离
     *   2. 最近地点 address（含中文）
     *   3. 都没有时只报行政区（省 + 地市 + 县区 + 乡镇）
     * 例：`黑龙江省牡丹江市绥芬河市 宽沟路 东南约282米`
     *
     * 注意（2026-09-30 洲哥明确要求）：**只有在线这一路加行政区，库内点位名保持原样**——
     * 51 个离线点位全在同一县区，逐张拼省市区纯属重复；库外（点位未命中）才需要属地信息。
     */
    private fun buildName(result: JSONObject): String? {
        val comp = result.optJSONObject("addressComponent")
        val province = comp?.optString("province").orEmpty().trim()
        val city = comp?.optString("city").orEmpty().trim()
        val county = comp?.optString("county").orEmpty().trim()
        val town = comp?.optString("town").orEmpty().trim()
        val poi = comp?.optString("poi").orEmpty().trim()
        // 官方文档拼写不一致（poi_distince / poi_distance），两种都兼容
        val poiDist = if (comp?.has("poi_distance") == true) {
            comp.optInt("poi_distance", -1)
        } else {
            comp?.optInt("poi_distince", -1) ?: -1
        }
        val poiPos = comp?.optString("poi_position").orEmpty().trim()
        val address = comp?.optString("address").orEmpty().trim()
        val formatted = result.optString("formatted_address").trim()

        val admin = joinAdmin(province, city, county)

        val place = when {
            isMeaningful(poi) && (poiDist < 0 || poiDist <= POI_MAX_RELEVANT_M) ->
                // 注意：中文字符在 Kotlin 里属于合法标识符字符，紧跟变量的中文必须用 ${} 包住，
                // 否则 "$poiPos约" 会被当成变量名 poiPos约 → Unresolved reference
                if (poiDist in 0..60 || poiPos.isBlank()) poi else "$poi ${poiPos}约${poiDist}米"
            isMeaningful(address) && address != poi -> address
            else -> null
        }

        if (place == null) {
            // 连具体地点都没有 → 只报行政区（细到乡镇），再不行用天地图原始地址
            return joinAdmin(province, city, county, town)
                .ifBlank { formatted.takeIf { it.isNotBlank() } }
        }
        val clean = stripAdminPrefix(place, county)
        return listOf(admin, clean)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
    }

    /** 拼接行政区名：跳过空值、去掉相邻重复（如直辖市"北京市北京市"只留一个） */
    private fun joinAdmin(vararg parts: String): String {
        var out = ""
        for (p in parts) {
            val s = p.trim()
            if (s.isEmpty() || s == out) continue
            out += s
        }
        return out
    }

    /**
     * 地名开头若与县区名重复则去掉该前缀：
     * "绥芬河市边境经济合作区"（天地图常这么返回）→ "边境经济合作区"。
     * 剩余不足 4 字则保留原样，避免把"绥芬河市人民政府"裁成"人民政府"。
     */
    private fun stripAdminPrefix(place: String, county: String): String {
        if (county.isBlank() || !place.startsWith(county)) return place
        val rest = place.removePrefix(county).trim()
        return if (rest.length >= 4) rest else place
    }

    /** 有效地名：至少含一个中文字符（用于滤掉 "ZR01"、"S206" 这类无意义编号） */
    private fun isMeaningful(s: String): Boolean =
        s.any { it.code in 0x4E00..0x9FFF }
}
