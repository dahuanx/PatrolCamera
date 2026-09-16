package com.bianhequ.patrolcamera

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自定义地名库：用户手动命名的位置（相机界面点击位置名，或设置页手动添加/修改）。
 *
 * 存储：应用内部目录 custom_places.json，格式：
 *   [ {"lat":44.36123,"lng":131.03950,"name":"老库房大门"}, ... ]
 *
 * 匹配规则：GPS 位置距已保存坐标 ≤ 20 米 → 返回该自定义地名。
 * 去重规则：新增/命名位置 20 米内已有记录时更新其名称，不重复堆积。
 * 管理规则：设置页可按条目索引修改 / 删除。
 */
object CustomPlaceStore {

    private const val TAG = "CustomPlaceStore"
    private const val FILE_NAME = "custom_places.json"
    private const val MATCH_RADIUS_METERS = 20.0

    data class CustomPlace(val lat: Double, val lng: Double, val name: String)

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): List<CustomPlace> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                if (name.isBlank()) null
                else CustomPlace(o.optDouble("lat"), o.optDouble("lng"), name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "自定义地名库读取失败: ${e.message}")
            emptyList()
        }
    }

    private fun save(context: Context, list: List<CustomPlace>) {
        try {
            val arr = JSONArray()
            for (p in list) {
                arr.put(
                    JSONObject()
                        .put("lat", p.lat)
                        .put("lng", p.lng)
                        .put("name", p.name)
                )
            }
            file(context).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "自定义地名库写入失败: ${e.message}")
        }
    }

    /** 20 米内命中则返回自定义地名 */
    fun match(context: Context, lat: Double, lng: Double): String? {
        for (p in load(context)) {
            if (PointMatcher.distanceMeters(lat, lng, p.lat, p.lng) <= MATCH_RADIUS_METERS) {
                return p.name
            }
        }
        return null
    }

    /** 新增命名；20 米内已有记录则更新名称 */
    fun addOrUpdate(context: Context, lat: Double, lng: Double, name: String) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst {
            PointMatcher.distanceMeters(lat, lng, it.lat, it.lng) <= MATCH_RADIUS_METERS
        }
        if (idx >= 0) list[idx] = CustomPlace(list[idx].lat, list[idx].lng, name)
        else list.add(CustomPlace(lat, lng, name))
        save(context, list)
        Log.i(TAG, "自定义地名已保存: $name (共 ${list.size} 条)")
    }

    /** 按条目索引修改（设置页手动编辑） */
    fun updateAt(context: Context, index: Int, lat: Double, lng: Double, name: String) {
        val list = load(context).toMutableList()
        if (index in list.indices) {
            list[index] = CustomPlace(lat, lng, name)
            save(context, list)
        }
    }

    /** 按条目索引删除（设置页单条删除） */
    fun removeAt(context: Context, index: Int) {
        val list = load(context).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            save(context, list)
        }
    }

    fun count(context: Context): Int = load(context).size

    fun clear(context: Context) {
        file(context).delete()
    }
}
