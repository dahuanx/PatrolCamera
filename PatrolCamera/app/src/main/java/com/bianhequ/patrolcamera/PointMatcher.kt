package com.bianhequ.patrolcamera

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 点位匹配器：GPS（WGS-84）坐标 → 最近巡查点位。
 *
 * 命中规则：落在点位 [PatrolPoint.radiusMeters] 半径内的点里取距离最近的一个；
 * 没有任何点位半径覆盖时返回 null（由调用方走在线地名或经纬度兜底）。
 */
object PointMatcher {

    /** 点位库坐标系：WGS84（图新地图/GPS）或 GCJ02（高德/百度取点） */
    const val POINT_COORD_SYS = "WGS84"

    private const val EARTH_RADIUS = 6378137.0

    /** Haversine 球面距离，单位米 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val radLat1 = Math.toRadians(lat1)
        val radLat2 = Math.toRadians(lat2)
        val dLat = radLat1 - radLat2
        val dLng = Math.toRadians(lng1) - Math.toRadians(lng2)
        val s = 2 * asin(
            sqrt(
                sin(dLat / 2).pow(2) + cos(radLat1) * cos(radLat2) * sin(dLng / 2).pow(2)
            )
        )
        return s * EARTH_RADIUS
    }

    /**
     * 匹配最近的巡查点位。
     * @return 命中的点位与实际距离；未命中返回 null
     */
    fun match(lat: Double, lng: Double, points: List<PatrolPoint>): Pair<PatrolPoint, Double>? {
        if (points.isEmpty()) return null

        // 若点位库是 GCJ-02 坐标，先把 GPS(WGS-84) 转成 GCJ-02 再比
        val latToMatch: Double
        val lngToMatch: Double
        if (POINT_COORD_SYS == "GCJ02") {
            val g = CoordinateConverter.wgs84ToGcj02(lat, lng)
            latToMatch = g[0]
            lngToMatch = g[1]
        } else {
            latToMatch = lat
            lngToMatch = lng
        }

        var best: PatrolPoint? = null
        var bestDist = Double.MAX_VALUE
        for (p in points) {
            val d = distanceMeters(latToMatch, lngToMatch, p.lat, p.lng)
            if (d <= p.radiusMeters && d < bestDist) {
                best = p
                bestDist = d
            }
        }
        return best?.let { it to min(bestDist, it.radiusMeters) }
    }
}

/**
 * WGS-84 ↔ GCJ-02 坐标转换（标准偏移算法）。
 * 仅在点位库为高德/百度坐标时启用。
 */
object CoordinateConverter {

    private const val PI = Math.PI
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun wgs84ToGcj02(wgsLat: Double, wgsLng: Double): DoubleArray {
        if (outOfChina(wgsLat, wgsLng)) return doubleArrayOf(wgsLat, wgsLng)
        val dLat = transformLat(wgsLng - 105.0, wgsLat - 35.0)
        val dLng = transformLng(wgsLng - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val offsetLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val offsetLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return doubleArrayOf(wgsLat + offsetLat, wgsLng + offsetLng)
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(x.abs())
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(x.abs())
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private fun Double.abs(): Double = if (this < 0) -this else this
}
