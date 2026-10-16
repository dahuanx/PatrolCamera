package com.bianhequ.patrolcamera

import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 软件内更新的交互流程（拍照页与设置页共用一套）：
 *
 *   检查清单 → 有新版本则弹窗 → 应用内下载（带进度）→ 三重校验 → 交系统安装器
 *
 * 两个入口：
 *   - 拍照页启动时静默检查（一天最多一次，见 [UpdateChecker.shouldAutoCheck]）；
 *   - 设置页「检查更新」按钮手动检查（无论结果都给提示）。
 */
object UpdateUi {

    /** 已下载完、但卡在"允许安装未知应用"授权的待安装版本；从授权页回来时自动续上 */
    private var pendingInstall: UpdateInfo? = null

    /**
     * 检查并提示。
     * @param manual 手动检查时，无论"已是最新"还是"检查失败"都给出明确提示；静默检查则只在有新版本时打扰用户
     */
    fun checkAndPrompt(activity: AppCompatActivity, manual: Boolean) {
        if (manual) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val info = UpdateChecker.fetch()
            when {
                info == null -> if (manual) {
                    Toast.makeText(
                        activity,
                        "检查失败：网络不可用，或尚未配置更新清单",
                        Toast.LENGTH_LONG
                    ).show()
                }

                !UpdateChecker.hasUpdate(info) -> if (manual) {
                    Toast.makeText(
                        activity,
                        "已是最新版本（v" + BuildConfig.VERSION_NAME + "）",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                else -> showUpdateDialog(activity, info)
            }
        }
    }

    /** 从"允许安装未知应用"授权页返回时调用：已授权且有待装包，则直接续上安装 */
    fun resumePendingInstall(activity: AppCompatActivity) {
        val info = pendingInstall ?: return
        if (!UpdateManager.canInstall(activity)) return
        val apk = UpdateManager.cachedApk(activity, info)
        if (apk == null) {
            pendingInstall = null
            return
        }
        pendingInstall = null
        try {
            UpdateManager.install(activity, apk)
        } catch (e: Exception) {
            Toast.makeText(activity, "调起安装器失败：" + (e.message ?: "未知原因"), Toast.LENGTH_LONG).show()
        }
    }

    // ---- 内部实现 ----

    private fun showUpdateDialog(activity: AppCompatActivity, info: UpdateInfo) {
        val forced = UpdateChecker.isForced(info)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val body = TextView(activity).apply {
            text = buildString {
                append("当前版本：v").append(BuildConfig.VERSION_NAME).append('\n')
                append("最新版本：v").append(info.versionName)
                if (info.size > 0L) append("（").append(fmtSize(info.size)).append("）")
                append("\n\n更新内容：\n")
                append(info.notes.ifBlank { "（发布方未填写说明）" })
            }
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
            addView(body)
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (forced) "需要更新后才能继续使用" else "发现新版本 v" + info.versionName)
            .setView(container)
            .setPositiveButton("立即更新", null)
        if (forced) {
            // 强制更新：不可取消、不可跳过（发布方用于修严重缺陷时兜底）
            builder.setCancelable(false)
        } else {
            builder.setNegativeButton("稍后再说", null)
        }
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(!forced)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                startDownload(activity, info)
            }
        }
        dialog.show()
    }

    private fun startDownload(activity: AppCompatActivity, info: UpdateInfo) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val tvState = TextView(activity).apply {
            text = "正在连接下载地址…"
            textSize = 14f
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(tvState)
            addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("正在下载 v" + info.versionName)
            .setView(container)
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create()

        var job: Job? = null
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { job?.cancel() }
        }
        dialog.show()

        job = activity.lifecycleScope.launch {
            try {
                val apk = UpdateManager.obtain(activity, info) { done, total ->
                    activity.runOnUiThread {
                        if (total > 0L) {
                            val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
                            bar.progress = percent
                            tvState.text = "已下载 " + percent + "%　" + fmtSize(done) + " / " + fmtSize(total)
                        } else {
                            tvState.text = "已下载 " + fmtSize(done)
                        }
                    }
                }
                dialog.dismiss()
                afterDownload(activity, info, apk)
            } catch (e: CancellationException) {
                dialog.dismiss()
                Toast.makeText(activity, "已取消下载", Toast.LENGTH_SHORT).show()
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                AlertDialog.Builder(activity)
                    .setTitle("更新失败")
                    .setMessage(
                        "下载或校验没通过：" + (e.message ?: "未知原因") +
                            "\n\n可稍后再试，或到发布页手动下载安装。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun afterDownload(activity: AppCompatActivity, info: UpdateInfo, apk: File) {
        if (!UpdateManager.canInstall(activity)) {
            pendingInstall = info
            AlertDialog.Builder(activity)
                .setTitle("还差一步：允许安装应用")
                .setMessage(
                    "安装包已下载并校验通过。\n\n" +
                        "系统需要你允许本应用「安装未知应用」，请在接下来的设置页打开该开关，" +
                        "然后返回本应用——会自动继续安装，不会重复下载。"
                )
                .setPositiveButton("去设置") { _, _ ->
                    UpdateManager.openInstallPermissionSettings(activity)
                }
                .setNegativeButton("稍后", null)
                .show()
            return
        }
        try {
            UpdateManager.install(activity, apk)
        } catch (e: Exception) {
            Toast.makeText(activity, "调起安装器失败：" + (e.message ?: "未知原因"), Toast.LENGTH_LONG).show()
        }
    }

    private fun fmtSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.CHINA, "%.1f MB", mb)
        } else {
            String.format(Locale.CHINA, "%d KB", bytes / 1024)
        }
    }
}
