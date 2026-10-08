package com.bianhequ.patrolcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页：
 *   1. 巡查标题名称编辑（框线输入框，黑色文字，持久化 SharedPreferences）
 *   2. 天气显示开关（水印卡片内的天气行）
 *   3. 经纬度坐标开关（水印卡片内的 GPS 坐标行，默认开启）
 *   4. 在线地名：开关 + 天地图服务端 Key（留空用内置 Key），点位库未命中时取真实地名
 *   5. 自定义位置名列表（显示名称+坐标，可手动添加 / 修改 / 单条删除 / 一键清空；
 *      添加时自动预填当前位置坐标）
 *   6. 版本说明（"2025组团式援边绥芬河工作队"连续点击 5 次 → 开 / 关"隐藏功能模式"）：
 *      开启时：① 拍照页点击水印上的时间/日期可临时校准巡查时间；
 *              ② 点击位置名称可手动填写 / 修改坐标、选择自定义位置（正常模式下只能改名，改不了坐标）；
 *              ③ 拍照页右下角"真实时间"上方出现「导入照片」图标，可给相册里已有的照片按当前设定补上水印；
 *      关闭时：一并清除已设置的临时校准时间，并放弃手填坐标 / 自定义位置
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "settings"
        const val KEY_WATERMARK_TITLE = "watermark_title"
        const val KEY_TIME_EDIT_UNLOCKED = "time_edit_unlocked"
        const val KEY_MANUAL_DATETIME = "manual_datetime"
        const val KEY_SHOW_WEATHER = "show_weather"
        const val KEY_SHOW_COORDINATE = "show_coordinate"
        const val KEY_SHOW_ONLINE_GEO = "show_online_geo"
        const val KEY_ONLINE_GEO_KEY = "online_geo_key"
        const val DEFAULT_TITLE = "巡检工作记录"

        fun loadTitle(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_WATERMARK_TITLE, DEFAULT_TITLE) ?: DEFAULT_TITLE

        fun isTimeEditUnlocked(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TIME_EDIT_UNLOCKED, false)

        /** 开 / 关"隐藏功能模式"（拍照页时间校准 + 位置名称手动改坐标 / 选自定义位置的解锁状态） */
        fun setTimeEditUnlocked(context: Context, unlocked: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_TIME_EDIT_UNLOCKED, unlocked).apply()
        }

        private val manualFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

        /** 读取拍照页临时校准时间；未设置时返回 null（表示使用系统时间） */
        fun getManualDateTime(context: Context): Date? {
            val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MANUAL_DATETIME, null) ?: return null
            return try {
                manualFmt.parse(s)
            } catch (e: Exception) {
                null
            }
        }

        /** 保存临时校准时间；传 null 则清除，恢复系统时间 */
        fun setManualDateTime(context: Context, date: Date?) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (date == null) {
                prefs.edit().remove(KEY_MANUAL_DATETIME).apply()
            } else {
                prefs.edit().putString(KEY_MANUAL_DATETIME, manualFmt.format(date)).apply()
            }
        }

        /** 是否在拍照页右下角显示天气（默认开启） */
        fun isWeatherEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_WEATHER, true)

        fun setWeatherEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SHOW_WEATHER, enabled).apply()
        }

        /** 是否在水印中显示经纬度坐标（默认开启） */
        fun isCoordinateEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_COORDINATE, true)

        fun setCoordinateEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SHOW_COORDINATE, enabled).apply()
        }

        /** 是否启用在线地名（天地图逆地理，默认开启）；关闭后点位库未命中时直接显示经纬度 */
        fun isOnlineGeoEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_ONLINE_GEO, true)

        fun setOnlineGeoEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SHOW_ONLINE_GEO, enabled).apply()
        }

        /** 用户在设置页自定义的天地图 Key（可能为空；为空表示用内置 Key） */
        fun customGeoKey(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ONLINE_GEO_KEY, null)?.trim().orEmpty()

        /** 实际生效的天地图 Key：自定义优先，否则回退内置默认 */
        fun onlineGeoKey(context: Context): String =
            customGeoKey(context).ifBlank { ReverseGeocoder.TIANDITU_KEY_DEFAULT }

        /** 保存自定义 Key；传空字符串则清除（恢复使用内置 Key） */
        fun setCustomGeoKey(context: Context, key: String) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val k = key.trim()
            if (k.isEmpty()) prefs.edit().remove(KEY_ONLINE_GEO_KEY).apply()
            else prefs.edit().putString(KEY_ONLINE_GEO_KEY, k).apply()
        }
    }

    private lateinit var edtTitleTag: EditText
    private lateinit var edtTitleRest: EditText
    private lateinit var tvCustomCount: TextView
    private lateinit var llCustomList: LinearLayout
    private lateinit var tvCustomEmpty: TextView
    private lateinit var tvAdvancedState: TextView

    private var creditTaps = 0
    private var lastTapAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        edtTitleTag = findViewById(R.id.edt_title_tag)
        edtTitleRest = findViewById(R.id.edt_title_rest)
        tvCustomCount = findViewById(R.id.tv_custom_count)
        llCustomList = findViewById(R.id.ll_custom_list)
        tvCustomEmpty = findViewById(R.id.tv_custom_empty)
        tvAdvancedState = findViewById(R.id.tv_advanced_state)
        refreshAdvancedState()

        // 标题拆成两框显示：前两字（黄色标签）+ 其余（白色标题）
        val title = loadTitle(this)
        edtTitleTag.setText(title.take(2))
        edtTitleRest.setText(title.drop(2))
        refreshCustomList()

        // 天气显示开关
        val swWeather = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_weather)
        swWeather.isChecked = isWeatherEnabled(this)
        swWeather.setOnCheckedChangeListener { _, checked ->
            setWeatherEnabled(this, checked)
            Toast.makeText(
                this,
                if (checked) "已在水印中显示天气" else "已关闭天气显示",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 经纬度坐标开关：控制水印卡片内是否显示系统真实 GPS 坐标
        val swCoordinate = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_coordinate)
        swCoordinate.isChecked = isCoordinateEnabled(this)
        swCoordinate.setOnCheckedChangeListener { _, checked ->
            setCoordinateEnabled(this, checked)
            Toast.makeText(
                this,
                if (checked) "已显示经纬度坐标" else "已隐藏经纬度坐标",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 在线地名：点位库未命中时调天地图逆地理接口取真实地名
        val edtGeoKey = findViewById<EditText>(R.id.edt_geo_key)
        edtGeoKey.setText(customGeoKey(this))
        val swOnlineGeo = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_online_geo)
        swOnlineGeo.isChecked = isOnlineGeoEnabled(this)
        swOnlineGeo.setOnCheckedChangeListener { _, checked ->
            setOnlineGeoEnabled(this, checked)
            Toast.makeText(
                this,
                if (checked) "已启用在线地名（需联网）" else "已关闭在线地名，将只显示点位或经纬度",
                Toast.LENGTH_SHORT
            ).show()
        }
        findViewById<Button>(R.id.btn_save_geo_key).setOnClickListener {
            setCustomGeoKey(this, edtGeoKey.text.toString())
            ReverseGeocoder.clearCache()
            Toast.makeText(
                this,
                if (edtGeoKey.text.toString().isBlank()) "已恢复使用内置 Key" else "已保存自定义 Key",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<Button>(R.id.btn_save_title).setOnClickListener {
            val t = (edtTitleTag.text.toString().trim() + edtTitleRest.text.toString().trim())
                .ifBlank { DEFAULT_TITLE }
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_WATERMARK_TITLE, t).apply()
            Toast.makeText(this, "标题已保存：$t", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btn_add_custom).setOnClickListener {
            showPlaceDialog(index = null, place = null)
        }

        findViewById<Button>(R.id.btn_clear_custom).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空自定义位置名")
                .setMessage("将删除全部手动保存的位置名记录，确定吗？")
                .setPositiveButton("清空") { _, _ ->
                    CustomPlaceStore.clear(this)
                    refreshCustomList()
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 隐藏功能模式开关（彩蛋）：版本说明连续点击 5 次（2 秒内连续）→ 开 / 关
        //   开启：拍照页点击水印上的时间/日期可临时校准巡查时间；
        //         点击位置名称可手动修改坐标、选择自定义位置（正常模式下只能改名）
        //   关闭：一并清除已设置的临时校准时间，避免留下一个再也改不回来的假时间
        findViewById<TextView>(R.id.tv_credit).setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            creditTaps = if (now - lastTapAt < 2000L) creditTaps + 1 else 1
            lastTapAt = now
            if (creditTaps >= 5) {
                creditTaps = 0
                val turnOn = !isTimeEditUnlocked(this)
                setTimeEditUnlocked(this, turnOn)
                if (!turnOn) setManualDateTime(this, null)
                refreshAdvancedState()
                Toast.makeText(
                    this,
                    if (turnOn) "已开启隐藏功能模式：可临时校准时间、手动修改坐标与选择自定义位置；拍照页右下角出现「导入照片」图标"
                    else "已退出隐藏功能模式：位置名称恢复为仅可改名，导入照片入口一并隐藏",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** 隐藏功能模式状态提示：仅在已开启时显示（连点上面的版本说明 5 次可退出） */
    private fun refreshAdvancedState() {
        tvAdvancedState.visibility = if (isTimeEditUnlocked(this)) View.VISIBLE else View.GONE
    }

    // ---- 自定义位置名列表 ----

    private fun refreshCustomList() {
        val list = CustomPlaceStore.load(this)
        llCustomList.removeAllViews()
        val empty = list.isEmpty()
        tvCustomEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        tvCustomCount.text = "已保存 ${list.size} 条 · 点击条目可修改或删除"
        list.forEachIndexed { index, place ->
            llCustomList.addView(buildPlaceRow(place, index))
        }
    }

    /** 单条位置名卡片：框线背景，名称黑字 + 坐标灰字，点击进入编辑 */
    private fun buildPlaceRow(place: CustomPlaceStore.CustomPlace, index: Int): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_edit_box)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        val tvName = TextView(this).apply {
            text = place.name
            textSize = 15f
            setTextColor(0xFF111111.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvCoord = TextView(this).apply {
            text = String.format(Locale.CHINA, "纬度 %.6f · 经度 %.6f", place.lat, place.lng)
            textSize = 12f
            setTextColor(0xFF777777.toInt())
        }

        box.addView(tvName)
        box.addView(tvCoord)
        box.setOnClickListener { showPlaceDialog(index, place) }
        return box
    }

    /** 最近一次系统缓存定位（GPS 优先，其次网络），无权限或无缓存返回 null */
    private fun lastKnownCoords(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        return loc?.let { it.latitude to it.longitude }
    }

    /** 添加（index=null）/ 编辑（index!=null）对话框：名称 + 纬度 + 经度，全部框线输入框黑字 */
    private fun showPlaceDialog(index: Int?, place: CustomPlaceStore.CustomPlace?) {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        fun makeBoxInput(hintText: String, text: String?): EditText =
            EditText(this).apply {
                hint = hintText
                setText(text ?: "")
                textSize = 14f
                setTextColor(0xFF111111.toInt())
                setHintTextColor(0xFFAAAAAA.toInt())
                background = ContextCompat.getDrawable(context, R.drawable.bg_edit_box)
                setPadding(dp(10), dp(9), dp(10), dp(9))
                maxLines = 1
            }

        fun makeLabel(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(10), 0, dp(3))
        }

        // 坐标预填：编辑时用原值；添加时自动填当前位置（无定位则留空待手填）
        val latPrefill: String?
        val lngPrefill: String?
        if (place != null) {
            latPrefill = "%.6f".format(place.lat)
            lngPrefill = "%.6f".format(place.lng)
        } else {
            val last = lastKnownCoords()
            latPrefill = last?.let { "%.6f".format(it.first) }
            lngPrefill = last?.let { "%.6f".format(it.second) }
        }

        val edtName = makeBoxInput("位置名称（如：老库房大门）", place?.name)
        val edtLat = makeBoxInput("纬度，如 44.361230", latPrefill)
        val edtLng = makeBoxInput("经度，如 131.039500", lngPrefill)

        val latRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p1.marginEnd = dp(8)
            addView(edtLat, p1)
            addView(edtLng, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(makeLabel("位置名称"))
            addView(edtName)
            addView(makeLabel("坐标（已自动填入当前位置，可手动修改）"))
            addView(latRow)
        }

        val isEdit = index != null
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isEdit) "修改自定义位置名" else "添加自定义位置名")
            .setView(container)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .apply {
                if (isEdit) setNeutralButton("删除", null)
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = edtName.text.toString().trim()
                val lat = edtLat.text.toString().trim().toDoubleOrNull()
                val lng = edtLng.text.toString().trim().toDoubleOrNull()
                when {
                    name.isEmpty() -> Toast.makeText(this, "请输入位置名称", Toast.LENGTH_SHORT).show()
                    lat == null || lng == null ->
                        Toast.makeText(this, "坐标格式不正确，请输入数字", Toast.LENGTH_SHORT).show()
                    lat < -90 || lat > 90 || lng < -180 || lng > 180 ->
                        Toast.makeText(this, "坐标超出有效范围", Toast.LENGTH_SHORT).show()
                    else -> {
                        if (isEdit) CustomPlaceStore.updateAt(this, index!!, lat, lng, name)
                        else CustomPlaceStore.addOrUpdate(this, lat, lng, name)
                        refreshCustomList()
                        Toast.makeText(this, "已保存：$name", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }
            if (isEdit) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("删除该条位置名")
                        .setMessage("确定删除「${place?.name}」吗？")
                        .setPositiveButton("删除") { _, _ ->
                            CustomPlaceStore.removeAt(this, index!!)
                            refreshCustomList()
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
        dialog.show()
    }
}
