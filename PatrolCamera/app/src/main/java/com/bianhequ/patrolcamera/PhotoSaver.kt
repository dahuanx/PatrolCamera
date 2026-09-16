package com.bianhequ.patrolcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 照片入库：写入系统相册" Pictures/巡查水印相机"目录。
 *
 * Android 10+：MediaStore RELATIVE_PATH，无需存储权限；
 * Android 8~9 ：传统 MediaStore 插入（Manifest 已声明 maxSdkVersion=28 的存储权限）。
 */
object PhotoSaver {

    private const val TAG = "PhotoSaver"
    private const val SUB_DIR = "巡查水印相机"

    data class SavedPhoto(val uri: Uri, val displayName: String)

    fun buildFileName(date: Date): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(date)
        return "PATROL_$ts.jpg"
    }

    fun save(context: Context, bitmap: Bitmap, displayName: String): SavedPhoto {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$SUB_DIR")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert 返回 null")
            resolver.openOutputStream(uri)?.use { os ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)) {
                    throw IllegalStateException("JPEG 压缩失败")
                }
            } ?: throw IllegalStateException("打开输出流失败")
            SavedPhoto(uri, displayName)
        } else {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val dir = File(picturesDir, SUB_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, displayName)
            file.outputStream().use { os ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)) {
                    throw IllegalStateException("JPEG 压缩失败")
                }
            }
            // 登记到媒体库，让相册立刻可见
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            Log.i(TAG, "saved(legacy): ${file.absolutePath}, uri=$uri")
            SavedPhoto(uri ?: Uri.fromFile(file), displayName)
        }
    }
}
