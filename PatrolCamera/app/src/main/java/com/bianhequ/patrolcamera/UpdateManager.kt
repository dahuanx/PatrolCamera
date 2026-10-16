package com.bianhequ.patrolcamera

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 更新包下载与安装：应用内下载 → 三重校验 → 交系统安装器。
 *
 * 存放位置：`getExternalFilesDir(null)/update/`（应用外部私有目录）
 *   - 免存储权限（Android 10+ 分区存储下也不受限）；
 *   - 卸载 App 时随目录一并清除，不污染用户相册。
 *
 * 校验三重（任一不过即判定为坏包，直接删除重下）：
 *   1. 体积比对（清单给了 size 时）；
 *   2. SHA-256 比对（清单给了 sha256 时）；
 *   3. 读包内 versionCode / 包名（最硬的证据，能挡住半截包与被掉包的 APK）。
 */
object UpdateManager {

    private const val TAG = "PatrolCamera"

    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 15000

    /** 下载缓冲区：32KB，兼顾速度与进度回调频率 */
    private const val BUF_SIZE = 32 * 1024

    private fun updateDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "update")

    private fun apkName(info: UpdateInfo) = "PatrolCamera-v${info.versionName}.apk"

    /**
     * 本地已下好且校验通过的更新包；不存在或校验不过（已被删除）返回 null。
     * 有了它，用户在"允许安装未知应用"授权页回来后再点一次更新就不会重复下载 3MB。
     */
    fun cachedApk(context: Context, info: UpdateInfo): File? {
        val f = File(updateDir(context), apkName(info))
        if (!f.isFile || f.length() <= 0L) return null
        if (verify(context, f, info)) return f
        f.delete()
        return null
    }

    /**
     * 取更新包：优先复用本地已校验通过的缓存，否则联网下载。
     * @param onProgress (已下载字节, 总字节)；总字节未知时为 0
     */
    suspend fun obtain(
        context: Context,
        info: UpdateInfo,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
        cachedApk(context, info)?.let {
            Log.i(TAG, "复用已下载的更新包: ${it.name}")
            return it
        }
        return withContext(Dispatchers.IO) {
            val dir = updateDir(context)
            dir.mkdirs()
            // 清掉旧版本残留，避免反复更新后堆积
            dir.listFiles()?.forEach { if (it.name != apkName(info)) it.delete() }

            val out = File(dir, apkName(info))
            if (out.exists()) out.delete()

            val sources = listOfNotNull(info.url, info.urlBackup)
            var lastError: Exception? = null
            for (src in sources) {
                try {
                    downloadFrom(src, out, onProgress)
                    if (!verify(context, out, info)) {
                        throw IllegalStateException("安装包校验未通过")
                    }
                    return@withContext out
                } catch (e: CancellationException) {
                    out.delete()
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "从 $src 下载失败: ${e.message}")
                    lastError = e
                    out.delete()
                }
            }
            throw lastError ?: IllegalStateException("没有可用的下载地址")
        }
    }

    private fun downloadFrom(urlStr: String, out: File, onProgress: (Long, Long) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                // Gitee 附件下载会 302 跳到 foruda.gitee.com 的临时地址，必须跟随
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PatrolCamera/" + BuildConfig.VERSION_NAME)
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("下载地址返回 HTTP $code")

            val total = conn.contentLengthLong.takeIf { it > 0L } ?: 0L
            var done = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(BUF_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    output.flush()
                }
            }
            Log.i(TAG, "更新包下载完成: ${out.name}（$done 字节）")
        } finally {
            conn?.disconnect()
        }
    }

    /** 三重校验：体积 → SHA-256 → 包内 versionCode / 包名 */
    @Suppress("DEPRECATION")
    private fun verify(context: Context, apk: File, info: UpdateInfo): Boolean {
        if (!apk.isFile || apk.length() <= 0L) {
            Log.w(TAG, "更新包不存在或为空")
            return false
        }
        if (info.size > 0L && apk.length() != info.size) {
            Log.w(TAG, "更新包体积不符：期望 ${info.size}，实际 ${apk.length()}")
            return false
        }
        val expect = info.sha256?.trim()?.lowercase()?.replace(" ", "")
        if (!expect.isNullOrEmpty() && sha256Of(apk) != expect) {
            Log.w(TAG, "更新包 SHA-256 校验失败")
            return false
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        if (archive == null) {
            Log.w(TAG, "更新包无法解析（可能已损坏或不是 APK）")
            return false
        }
        if (archive.packageName != context.packageName) {
            Log.w(TAG, "更新包包名不符：${archive.packageName}")
            return false
        }
        @Suppress("DEPRECATION")
        val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode.toInt()
        } else {
            archive.versionCode
        }
        if (vc != info.versionCode) {
            Log.w(TAG, "更新包版本号不符：期望 ${info.versionCode}，实际 $vc")
            return false
        }
        return true
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 系统是否已允许本应用安装未知来源应用（Android 8.0+） */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 调起系统安装器（必须走 content://，Android 7+ 禁止 file:// 外泄） */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 跳到系统"安装未知应用"授权页（授权后回到 App 可自动续上安装） */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "无法打开安装权限设置页: ${e.message}")
        }
    }
}
