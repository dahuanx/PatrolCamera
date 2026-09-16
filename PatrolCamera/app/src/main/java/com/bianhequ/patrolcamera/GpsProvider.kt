package com.bianhequ.patrolcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 定位提供器：基于系统 LocationManager（不依赖谷歌服务框架，国产手机均可使用）。
 *
 * 策略：同时监听 GPS 与网络定位，先到先用（网络快、精度低），
 * GPS 回调到达且精度更优时自动替换，持续向 UI 推送最新位置。
 */
class GpsProvider(private val context: Context) {

    companion object {
        /** 当前显示定位超过该时长未更新，则无条件接受下一条新定位（保证位置流动） */
        private const val STALE_MS = 15_000L

        /** 两次定位间实际移动超过该距离即接受更新（米） */
        private const val MOVE_MIN_METERS = 5f

        /** 系统缓存"最后已知位置"超过该时长则丢弃，避免开门就显示陈旧位置 */
        private const val LAST_KNOWN_MAX_AGE_MS = 5 * 60_000L
    }

    sealed class GpsState {
        /** 尚未获得任何定位 */
        object Waiting : GpsState()

        /** 已获得定位（可能被更高精度的结果持续更新） */
        data class Fixed(
            val lat: Double,
            val lng: Double,
            val accuracyMeters: Float,
            val provider: String
        ) : GpsState() {
            val isGps: Boolean get() = provider == LocationManager.GPS_PROVIDER
        }
    }

    private val _state = MutableStateFlow<GpsState>(GpsState.Waiting)
    val state: StateFlow<GpsState> = _state

    private var locationManager: LocationManager? = null
    private var running = false

    /** 最近一次被接受定位的时间戳（用于过期判断） */
    private var lastAcceptAt = 0L

    private val listener = LocationListener { location -> onLocation(location) }

    private fun onLocation(location: Location) {
        val now = System.currentTimeMillis()
        val current = _state.value
        val accept = when (current) {
            is GpsState.Waiting -> true
            is GpsState.Fixed -> {
                val gpsFix = location.provider == LocationManager.GPS_PROVIDER
                val dist = FloatArray(1)
                Location.distanceBetween(
                    current.lat, current.lng, location.latitude, location.longitude, dist
                )
                val moved = dist[0]
                val stale = now - lastAcceptAt > STALE_MS
                when {
                    // GPS 结果升级替换网络定位（精度天然更优）
                    gpsFix && !current.isGps -> true
                    // 距上次接受定位超过阈值，无论精度如何都接受，保证位置持续流动
                    stale -> true
                    // 实际移动超过阈值（巡查走动的核心更新路径）
                    moved >= MOVE_MIN_METERS -> true
                    // 精度明显提升（< 0.8 倍）才接受，避免微小波动刷屏
                    location.hasAccuracy() && location.accuracy < current.accuracyMeters * 0.8f -> true
                    else -> false
                }
            }
        }
        if (accept) {
            lastAcceptAt = now
            _state.value = GpsState.Fixed(
                lat = location.latitude,
                lng = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else 999f,
                provider = location.provider ?: ""
            )
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /** 开始持续定位；重复调用安全 */
    fun start() {
        if (running || !hasPermission()) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm
        running = true

        // 先取最后一次已知位置，立刻给出粗略结果（仅接受 5 分钟内的缓存，过旧则弃用）
        try {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null && System.currentTimeMillis() - last.time <= LAST_KNOWN_MAX_AGE_MS) {
                onLocation(last)
            }
        } catch (_: SecurityException) {
            // 权限已检查，理论上不会发生
        }

        val minTime = 1000L
        val minDistance = 0f
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, minTime, minDistance, listener, Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) { /* 忽略单个 provider 失败 */ }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, minTime, minDistance, listener, Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) { /* 忽略单个 provider 失败 */ }
    }

    fun stop() {
        if (!running) return
        locationManager?.removeUpdates(listener)
        locationManager = null
        running = false
    }
}
