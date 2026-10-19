package com.bianhequ.patrolcamera

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * 分享**软件本身**：把本机正在运行的这一版安装包（APK）转给同事，或发下载链接让对方自己下。
 *
 * 为什么两种方式都要有：
 *   - 发安装包：不依赖网络、版本与分享者完全一致，适合工地 / 无网络环境，走微信、QQ 的"文件"通道；
 *   - 发链接  ：微信对 .apk 文件时有限制（可能提示不支持或直接拦截），此时改用下载页更稳。
 *
 * 安装包来源 = `applicationInfo.sourceDir`（已安装的 base.apk，应用可读自己），
 * 复制到 cache/share 后经 FileProvider 以 content:// 交出，Android 7.0+ 不会抛 FileUriExposedException。
 * 复制是必要的：sourceDir 在 /data/app 下，其它应用无权直接读。
 */
object AppShare {

    private const val TAG = "AppShare"
    private const val DIR = "share"

    /** 下载页：国内优先 Gitee，GitHub 作备用 */
    private const val GITEE_URL = "https://gitee.com/dahuanxx/PatrolCamera/releases/latest"
    private const val GITHUB_URL = "https://github.com/dahuanx/PatrolCamera/releases/latest"

    private const val MIME_APK = "application/vnd.android.package-archive"

    /** 分享用的文件名：带版本号，对方收到后一眼知道是哪版 */
    private fun apkName(): String = "巡查水印相机-v${BuildConfig.VERSION_NAME}.apk"

    /**
     * 把当前安装包复制到 cache/share，返回可分享的 content:// uri。
     * **IO 线程调用**；失败返回 null（调用方回退到发链接）。
     */
    fun prepareApkUri(context: Context): Uri? {
        // split APK 安装的应用只拿 base.apk 会装不全，直接放弃发文件、改发链接
        val splits = context.applicationInfo.splitSourceDirs
        if (splits != null && splits.isNotEmpty()) {
            Log.w(TAG, "应用为 split 安装（${splits.size} 个分包），不发送安装包")
            return null
        }
        val src = File(context.applicationInfo.sourceDir ?: return null)
        if (!src.isFile) {
            Log.w(TAG, "读不到安装包: ${src.absolutePath}")
            return null
        }
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        // 清掉上次留下的旧版本包，避免 cache 里越堆越多
        dir.listFiles()?.forEach { if (it.name != apkName()) it.delete() }
        val dst = File(dir, apkName())
        return try {
            src.inputStream().use { ins ->
                dst.outputStream().use { out -> ins.copyTo(out, 64 * 1024) }
            }
            if (dst.length() != src.length()) {
                Log.w(TAG, "安装包复制不完整: ${dst.length()} / ${src.length()}")
                dst.delete()
                return null
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dst)
        } catch (e: Exception) {
            Log.e(TAG, "准备安装包失败: ${e.message}")
            dst.delete()
            null
        }
    }

    /** 把准备好的安装包交给系统分享面板 */
    fun shareApk(context: Context, uri: Uri): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_APK
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, apkName())
            clipData = ClipData.newRawUri(apkName(), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return startChooser(context, send)
    }

    /** 发送下载链接（纯文本） */
    fun shareLink(context: Context): Boolean {
        val text = buildString {
            append("巡查水印相机 v").append(BuildConfig.VERSION_NAME).append("（安卓）\n")
            append("下载安装：").append(GITEE_URL).append('\n')
            append("备用地址：").append(GITHUB_URL).append('\n')
            append("安装时如提示「未知来源」，允许一次即可。")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "巡查水印相机 v${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return startChooser(context, send)
    }

    private fun startChooser(context: Context, send: Intent): Boolean {
        val chooser = Intent.createChooser(send, "分享巡查水印相机").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            Log.e(TAG, "调起分享面板失败: ${e.message}")
            false
        }
    }
}
