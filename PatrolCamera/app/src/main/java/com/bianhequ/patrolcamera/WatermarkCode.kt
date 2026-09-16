package com.bianhequ.patrolcamera

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 防伪码生成器。
 *
 * 用**系统真实时间（秒）** + 坐标 + 标题 + 现场备注做 HMAC-SHA256，
 * 取前 8 字节按模映射到去混淆字符表（去掉 0/O/1/I，避免看错），
 * 输出形如 "A1B2-C3D4" 的 8 位码。
 *
 * 参与运算的是系统真实时间，而非水印上可能被临时校准过的时间；
 * 因此即便有人改动水印时间，防伪码也与真实时间对不上，可用于照片真伪核验。
 */
object WatermarkCode {

    private const val KEY = "BianHeQu@2026#Patrol"
    private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 去 0/O/1/I

    fun generate(
        timestampSec: Long,
        lat: Double?,
        lng: Double?,
        title: String,
        note: String?
    ): String {
        val input = "$timestampSec|${lat ?: ""},${lng ?: ""}|$title|${note ?: ""}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        digest.take(8).forEach { sb.append(CHARS[(it.toInt() and 0xFF) % CHARS.length]) }
        return sb.toString().let { it.substring(0, 4) + "-" + it.substring(4) }
    }
}
