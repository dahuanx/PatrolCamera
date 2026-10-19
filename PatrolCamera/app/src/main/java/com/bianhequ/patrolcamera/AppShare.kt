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

    /**
     * 下载地址用**安装包直链**，不是发布页。
     * 发发布页（releases/latest）对方还要在页面里再找一次附件，多一步就多一批装不上的人；
     * 直链在浏览器里点开即下载（Gitee 会 302 到附件 CDN，最终直接吐出 apk 文件）。
     */
    private fun apkUrlGitee(): String =
        "https://gitee.com/dahuanxx/PatrolCamera/releases/download/v${BuildConfig.VERSION_NAME}" +
            "/PatrolCamera-v${BuildConfig.VERSION_NAME}.apk"

    private fun apkUrlGithub(): String =
        "https://github.com/dahuanx/PatrolCamera/releases/download/v${BuildConfig.VERSION_NAME}" +
            "/PatrolCamera-v${BuildConfig.VERSION_NAME}.apk"

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
            // 随文件附一段说明：微信可能把文件名改成 xxx.apk.1、或提示无法打开，
            // 对方按这段话就能自己装上（部分客户端只保留文件、不显示这段文字，属正常）
            putExtra(Intent.EXTRA_TEXT, apkTips())
            clipData = ClipData.newRawUri(apkName(), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return startChooser(context, send)
    }

    /** 发送下载链接（纯文本）：直链 + 微信里必须用浏览器打开的说明 */
    fun shareLink(context: Context): Boolean {
        val text = buildString {
            append("巡查水印相机 v").append(BuildConfig.VERSION_NAME).append("（安卓）安装包：\n")
            append(apkUrlGitee()).append("\n\n")
            append("安装方法：\n")
            append("1. 在微信里直接点链接是下不了的，请点右上角「…」→「在浏览器打开」，")
            append("浏览器会直接下载安装包（不会跳到下载页）。\n")
            append("2. 下载完点开安装，首次安装需允许「安装未知应用」；")
            append("部分手机会联网做安全检测，按提示继续即可。\n\n")
            append("备用地址（国内可能打不开）：\n")
            append(apkUrlGithub())
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "巡查水印相机 v${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return startChooser(context, send)
    }

    /** 发安装包时附带的说明：专治微信改名 .apk.1 与"无法打开" */
    private fun apkTips(): String = buildString {
        append("巡查水印相机 v").append(BuildConfig.VERSION_NAME).append(" 安装包（apk）\n")
        append("如果微信里点开提示无法打开、或文件名变成 xxx.apk.1：\n")
        append("① 点文件右上角「…」→「用其他应用打开」，选「文件管理」或「安装器」；\n")
        append("② 还是不行就到「文件管理」→ 微信下载目录，把文件名末尾的 .1 删掉（改成 xxx.apk）再安装；\n")
        append("③ 首次安装需允许「安装未知应用」，部分手机会联网做安全检测，按提示继续即可。")
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
