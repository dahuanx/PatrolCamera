package com.bianhequ.patrolcamera

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 位置文本解析：GPS 状态 → 水印上的"所在位置"文字。
 *
 * 解析优先级（在 IO 线程执行，结果缓存供拍照瞬间直接取用）：
 *   1. 自定义地名库命中（20 米内，用户手动命名过）→ 自定义地名
 *   2. 离线点位库命中 → 规范点位名（如"万鸿利通北门"）
 *   3. 点位库未命中且配置了高德 Key → 在线逆地理地名
 *   4. 均不可用 → 经纬度兜底
 */
class LocationResolver(
    scope: CoroutineScope,
    private val context: Context,
    private val gps: GpsProvider,
    private val points: List<PatrolPoint>
) {

    private val _text = MutableStateFlow("定位中…")
    val text: StateFlow<String> = _text

    init {
        scope.launch(Dispatchers.IO) {
            gps.state.collectLatest { st ->
                // 防抖：定位回调较密，等 600ms 静止后再解析，避免频繁网络请求
                delay(600)
                _text.value = resolve(st)
            }
        }
    }

    private suspend fun resolve(state: GpsProvider.GpsState): String = withContext(Dispatchers.IO) {
        when (state) {
            is GpsProvider.GpsState.Waiting -> "定位中…"
            is GpsProvider.GpsState.Fixed -> {
                // 1) 自定义地名（用户手动命名，优先级最高）
                CustomPlaceStore.match(context, state.lat, state.lng)?.let { return@withContext it }
                // 2) 离线点位库
                PointMatcher.match(state.lat, state.lng, points)?.let { (p, _) ->
                    return@withContext p.name
                }
                // 3) 在线逆地理（未配置 Key 时内部直接返回 null）
                ReverseGeocoder.reverse(state.lat, state.lng)?.let { return@withContext it }
                // 4) 经纬度兜底
                String.format(Locale.CHINA, "%.5f, %.5f", state.lat, state.lng)
            }
        }
    }
}
