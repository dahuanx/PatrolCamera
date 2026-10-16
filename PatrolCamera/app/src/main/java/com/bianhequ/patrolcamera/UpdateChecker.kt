package com.bianhequ.patrolcamera

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新清单内容（由发布方维护的 version.json 解析而来）。
 *
 * 权威版本依据是 [versionCode]（整数），不是 versionName 字符串——
 * 字符串比版本号在 "1.3.10" vs "1.3.9" 这类情况下必然出错。
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** 最低可用版本号：本机低于它就强制更新（发布方用于修严重缺陷时兜底） */
    val minVersionCode: Int,
    /** 发布方显式要求强制更新 */
    val force: Boolean,
    val url: String,
    val urlBackup: String?,
    /** APK 字节数，0 表示清单未提供（跳过体积校验） */
    val size: Long,
    /** APK 的 SHA-256（小写十六进制），null 表示清单未提供（跳过散列校验） */
    val sha256: String?,
    val notes: String
)

/**
 * 版本更新检查。
 *
 * 清单地址（App 只读）：仓库根目录的 `version.json`
 *   - 主源 Gitee raw（国内直连，快）
 *   - 备源 GitHub raw（Gitee 抽风时兜底）
 *
 * 与拍照主流程完全解耦：任何网络异常都只记日志、返回 null，
 * 绝不让"检查更新失败"影响取景、拍照与保存。
 */
object UpdateChecker {

    private const val TAG = "PatrolCamera"
    private const val PREFS = "settings"
    private const val KEY_LAST_CHECK = "update_last_check"

    /** 自动检查间隔：一天（单位多人共用出口 IP，避免触发 Gitee 未登录限流） */
    private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private const val CONNECT_TIMEOUT = 5000
    private const val READ_TIMEOUT = 6000

    /** 清单体积上限，防止异常响应拖垮解析 */
    private const val MAX_BODY_CHARS = 128 * 1024

    private val MANIFEST_URLS = listOf(
        "https://gitee.com/dahuanxx/PatrolCamera/raw/master/version.json",
        "https://raw.githubusercontent.com/dahuanx/PatrolCamera/main/version.json"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 距上次自动检查是否已满一天 */
    fun shouldAutoCheck(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS
    }

    /**
     * 记录本次自动检查时间。
     * 失败也记——否则断网时每次启动都要白等一次超时。
     */
    fun markChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    /** 是否有可用新版本 */
    fun hasUpdate(info: UpdateInfo): Boolean = info.versionCode > BuildConfig.VERSION_CODE

    /** 是否强制更新（发布方显式标记，或本机版本已低于最低支持版本） */
    fun isForced(info: UpdateInfo): Boolean {
        return info.force || BuildConfig.VERSION_CODE < info.minVersionCode
    }

    /** 按顺序试各清单源，全部失败返回 null（不抛异常） */
    suspend fun fetch(): UpdateInfo? = withContext(Dispatchers.IO) {
        for (u in MANIFEST_URLS) {
            val body = try {
                httpGet(u)
            } catch (e: Exception) {
                Log.w(TAG, "更新清单请求异常（$u）: ${e.message}")
                null
            } ?: continue
            parse(body)?.let { return@withContext it }
        }
        Log.i(TAG, "未取到可用的更新清单，跳过本次检查")
        null
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                // Gitee raw 会 302 跳到 raw.gitee.com，必须跟随
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PatrolCamera/" + BuildConfig.VERSION_NAME)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "更新清单 HTTP $code: $urlStr")
                return null
            }
            val sb = StringBuilder()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    if (sb.length + n > MAX_BODY_CHARS) break
                    sb.append(buf, 0, n)
                }
            }
            sb.toString()
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(body: String): UpdateInfo? = try {
        val o = JSONObject(body)
        val code = o.optInt("versionCode", 0)
        val url = o.optString("url").trim()
        if (code <= 0 || url.isEmpty()) {
            Log.w(TAG, "更新清单字段不完整（versionCode=$code）")
            null
        } else {
            UpdateInfo(
                versionCode = code,
                versionName = o.optString("versionName").trim().ifEmpty { code.toString() },
                minVersionCode = o.optInt("minVersionCode", 0),
                force = o.optBoolean("force", false),
                url = url,
                urlBackup = o.optString("urlBackup").trim().ifEmpty { null },
                size = o.optLong("size", 0L),
                sha256 = o.optString("sha256").trim().ifEmpty { null },
                notes = o.optString("notes").trim()
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "更新清单解析失败: ${e.message}")
        null
    }
}
