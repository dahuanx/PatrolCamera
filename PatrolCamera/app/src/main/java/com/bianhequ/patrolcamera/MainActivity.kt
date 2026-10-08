package com.bianhequ.patrolcamera

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.net.Uri
import android.location.LocationManager
import android.media.MediaActionSound
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 巡查水印相机 主界面。
 *
 * 布局：全屏取景 + 顶部（刷新位置 / 定位状态 / 设置入口）+ 左下角实时水印预览（含经纬度行、天气行）
 *       + 右下角板块（真实时间标识 / 防伪码）+ 底部（备注 / 快门）。
 * 拍照：取景定格 → cache 临时文件 → IO 线程（EXIF 摆正 → 水印合成 → MediaStore 入库）
 *       → 屏幕中央弹出成片缩略图、边缩小边飞向右下角淡出（保存反馈动画）→ 清空备注 → Toast 反馈。
 *
 * 交互：
 *   - 点击水印上的位置名称 → 手动命名位置：可改位置名称、手动填写经纬度、从已保存的
 *     自定义位置里挑一个填表（保存后 20 米内自动回显；若所填坐标偏离真实 GPS，
 *     本次拍照的地名与坐标行改用手填值，点左上角"刷新位置"或重开 App 恢复真实 GPS）
 *   - 右上角齿轮 → 设置页（巡查标题 / 自定义地名管理 / 天气与坐标开关 / 版本说明）
 *   - 连点设置页版本说明 5 次 → 开 / 关"隐藏功能模式"；开启时点击水印上的时间/日期
 *     可临时校准巡查时间（仅影响水印显示与照片水印，文件名仍用系统真实时间）
 *   - 隐藏功能模式开启时，右下角"真实时间"上方多出一个**导入照片图标**：从相册选一张已有照片，
 *     按**当前设定的时间与位置**补上水印（横版 / 竖版自动识别），另存为新照片，原图不动
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PatrolCamera"

        /** 导入照片的尺寸上限（长边像素）：超出者先降采样，避免大图 OOM */
        private const val MAX_IMPORT_SIDE = 4096

        /** 保存动画用缩略图的长边像素（避免把整张成片搬上 UI 线程） */
        private const val THUMB_MAX_SIDE = 480

        /** 保存动画缩略图的视图标记（连拍时用于撤掉上一张未飞完的缩略图） */
        private const val TAG_SAVE_THUMB = "save_thumb"
    }

    /** 一次成片的结果：入库信息 + 动画用缩略图 + 是否竖版（横竖在成片后判定，用于 Toast 文案） */
    private data class SaveResult(
        val photo: PhotoSaver.SavedPhoto,
        val thumb: Bitmap?,
        val portrait: Boolean
    )

    private lateinit var previewView: PreviewView
    private lateinit var tvGpsStatus: TextView
    private lateinit var wmRoot: LinearLayout
    private lateinit var tvWmTitleTag: TextView
    private lateinit var tvWmTitle: TextView
    private lateinit var tvWmTime: TextView
    private lateinit var tvWmDate: TextView
    private lateinit var tvWmLocation: TextView
    private lateinit var tvWmNote: TextView
    private lateinit var tvWmCoordinate: TextView
    private lateinit var btnNote: TextView
    private lateinit var btnShutter: View
    private lateinit var btnSettings: ImageView
    private lateinit var btnRefreshLocation: TextView

    /** 导入照片入口（图标）：隐藏在隐藏功能模式下才显示，位置在右下角"真实时间"上方 */
    private lateinit var btnImportPhoto: ImageView
    private lateinit var weatherRow: LinearLayout
    private lateinit var ivWeatherIcon: ImageView
    private lateinit var tvWeatherTemp: TextView
    private lateinit var tvWeatherDesc: TextView
    private lateinit var cornerBox: LinearLayout
    private lateinit var tvCornerCode: TextView

    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var gps: GpsProvider
    private lateinit var locationResolver: LocationResolver
    private var patrolPoints: List<PatrolPoint> = emptyList()
    private var shutterSound: MediaActionSound? = null

    private var currentNote: String? = null
    private var capturing = false

    /** 导入照片进行中（防重复触发） */
    private var importingPhoto = false
    private var weatherLoading = false

    /** 上次点"刷新位置"的时间戳（2 秒冷却防连点） */
    private var lastRefreshTapAt = 0L

    /**
     * 手动设置的位置（点水印上的位置名称后，手填坐标或选择自定义位置时生效）。
     * 仅「隐藏功能模式」开启时才能设置；隐藏模式关闭、点"刷新位置"或重开 App 即恢复真实 GPS 与自动解析的地名。
     */
    private var manualName: String? = null
    private var manualLat: Double? = null
    private var manualLng: Double? = null

    private val clockFmtTime = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val clockFmtDate = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)

    /** 导入照片（隐藏功能模式）：系统相册选图，单选，取消回调 uri 为 null */
    private val photoPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importPhoto(uri)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cameraGranted = result[Manifest.permission.CAMERA] == true
            val locationGranted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (cameraGranted) startCamera()
            if (locationGranted) gps.start() else updateGpsPill(null, noPermission = true)
            if (!cameraGranted) {
                Toast.makeText(this, "未授予相机权限，无法拍照", Toast.LENGTH_LONG).show()
            }
        }

    // ---- 生命周期 ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        tvGpsStatus = findViewById(R.id.tv_gps_status)
        wmRoot = findViewById(R.id.wm_root)
        tvWmTitleTag = findViewById(R.id.tv_wm_title_tag)
        tvWmTitle = findViewById(R.id.tv_wm_title)
        tvWmTime = findViewById(R.id.tv_wm_time)
        tvWmDate = findViewById(R.id.tv_wm_date)
        tvWmLocation = findViewById(R.id.tv_wm_location)
        tvWmNote = findViewById(R.id.tv_wm_note)
        tvWmCoordinate = findViewById(R.id.tv_wm_coordinate)
        btnNote = findViewById(R.id.btn_note)
        btnShutter = findViewById(R.id.btn_shutter)
        btnSettings = findViewById(R.id.btn_settings)
        weatherRow = findViewById(R.id.weather_row)
        ivWeatherIcon = findViewById(R.id.iv_weather_icon)
        tvWeatherTemp = findViewById(R.id.tv_weather_temp)
        tvWeatherDesc = findViewById(R.id.tv_weather_desc)
        cornerBox = findViewById(R.id.corner_box)
        tvCornerCode = findViewById(R.id.tv_corner_code)
        btnRefreshLocation = findViewById(R.id.btn_refresh_location)
        btnImportPhoto = findViewById(R.id.btn_import_photo)

        cameraExecutor = Executors.newSingleThreadExecutor()
        shutterSound = MediaActionSound()
        shutterSound?.load(MediaActionSound.SHUTTER_CLICK)

        patrolPoints = try {
            PatrolPoint.loadFromAssets(assets.open("patrol_points.json"))
        } catch (e: Exception) {
            Log.e(TAG, "点位库加载失败: ${e.message}")
            emptyList()
        }
        Log.i(TAG, "点位库加载完成，共 ${patrolPoints.size} 个点位")

        gps = GpsProvider(this)
        locationResolver = LocationResolver(lifecycleScope, this, gps, patrolPoints)

        btnShutter.setOnClickListener { takePhoto() }
        btnNote.setOnClickListener { showNoteDialog() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        tvWmLocation.setOnClickListener { showLocationNamingDialog() }
        // 强制刷新位置：重置定位状态并重新监听（2 秒冷却防连点）
        btnRefreshLocation.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastRefreshTapAt < 2000L) return@setOnClickListener
            lastRefreshTapAt = now
            if (!gps.hasPermission()) {
                requestPermissionsAndStart()
                return@setOnClickListener
            }
            if (!gps.isGpsEnabled()) {
                Toast.makeText(this, "定位服务未开启，请到系统设置打开定位", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            gps.restart()
            val hadManual = clearManualOverride()
            Toast.makeText(
                this,
                if (hadManual) "已恢复真实 GPS 与自动地名，正在重新定位…" else "正在重新定位…",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 导入照片（仅隐藏功能模式可见）：选相册里已有照片，按当前设定的时间与位置补上水印
        btnImportPhoto.setOnClickListener {
            if (!SettingsActivity.isTimeEditUnlocked(this)) {
                Toast.makeText(this, "该功能需先开启隐藏功能模式", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            photoPicker.launch("image/*")
        }

        // 时间校准（设置页连点版本说明 5 次解锁后可用）
        val timeEditListener = View.OnClickListener {
            if (SettingsActivity.isTimeEditUnlocked(this)) showTimeEditDialog()
        }
        tvWmTime.setOnClickListener(timeEditListener)
        tvWmDate.setOnClickListener(timeEditListener)

        updateTitle()
        observeState()
        startClock()
        syncWeatherVisibility()
        syncAdvancedVisibility()   // "导入照片"入口只在隐藏功能模式下显示
        requestPermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        updateTitle()   // 从设置页返回时刷新标题
        refreshClock()  // 从设置页/对话框返回时立即刷新时间显示
        syncWeatherVisibility()   // 天气开关可能在设置页被改动
        syncAdvancedVisibility()  // 隐藏功能模式可能在设置页被开 / 关（决定"导入照片"按钮）
        // 隐藏功能模式已被关掉时，手填坐标 / 自定义位置不再允许生效，回到真实 GPS 与自动地名
        if (!SettingsActivity.isTimeEditUnlocked(this)) clearManualOverride()
        updateCoordinate(gps.state.value)   // 坐标开关可能在设置页被改动
        if (gps.hasPermission()) gps.start()
    }

    override fun onPause() {
        gps.stop()
        super.onPause()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        shutterSound?.release()
        super.onDestroy()
    }

    // ---- 标题（设置中可编辑） ----

    private fun updateTitle() {
        val title = SettingsActivity.loadTitle(this)
        tvWmTitleTag.text = title.take(2)
        val rest = title.drop(2)
        tvWmTitle.text = rest
        tvWmTitle.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- 权限与相机 ----

    private fun requestPermissionsAndStart() {
        val need = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (need.isEmpty()) {
            startCamera()
            gps.start()
        } else {
            permissionLauncher.launch(need.toTypedArray())
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
                Log.i(TAG, "相机绑定成功")
            } catch (e: Exception) {
                Log.e(TAG, "相机启动失败: ${e.message}")
                Toast.makeText(this, "相机启动失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- 状态驱动 ----

    private fun observeState() {
        lifecycleScope.launch {
            // 手动设置生效期间，地名不被自动解析结果覆盖
            locationResolver.text.collect { tvWmLocation.text = manualName ?: it }
        }
        lifecycleScope.launch {
            gps.state.collect {
                updateGpsPill(it, noPermission = false)
                updateCoordinate(it)
                if (it is GpsProvider.GpsState.Fixed) refreshWeather(it.lat, it.lng)
            }
        }
    }

    private fun updateGpsPill(state: GpsProvider.GpsState?, noPermission: Boolean) {
        val text = when {
            noPermission -> "无定位权限"
            state is GpsProvider.GpsState.Fixed ->
                if (state.accuracyMeters <= 50f) "定位成功 · 精度 ${state.accuracyMeters.toInt()}m"
                else "定位中 · 精度 ${state.accuracyMeters.toInt()}m"
            else -> "定位中…"
        }
        tvGpsStatus.text = text
        tvGpsStatus.setBackgroundResource(
            if (state is GpsProvider.GpsState.Fixed && state.accuracyMeters <= 50f)
                R.drawable.bg_pill_ok else R.drawable.bg_pill_wait
        )
    }

    private fun startClock() {
        lifecycleScope.launch {
            while (true) {
                refreshClock()
                // 1 秒一跳：右下角"真实时间"在预览里也要走秒
                delay(1_000L - System.currentTimeMillis() % 1_000L)
            }
        }
    }

    /** 刷新时间显示：主水印时间优先用临时校准时间；右下角"真实时间"始终用系统真实时间 */
    private fun refreshClock() {
        val shown = SettingsActivity.getManualDateTime(this) ?: Date()
        tvWmTime.text = clockFmtTime.format(shown)
        tvWmDate.text = clockFmtDate.format(shown)
        refreshCorner()
    }

    // ---- 右下角板块：真实时间标识 + 防伪码（板块常显，天气行在左侧水印卡片内） ----

    /** 刷新右下角防伪码（基于系统真实时间，不受水印临时校准影响；"真实时间"为固定标识字样） */
    private fun refreshCorner() {
        tvCornerCode.text = "防伪码 " + buildWatermarkCode(Date())
        cornerBox.visibility = View.VISIBLE
    }

    /** 防伪码：系统真实时间 + 当前位置 + 标题 + 现场备注（预览与成片用同一套算法） */
    private fun buildWatermarkCode(
        realTime: Date,
        title: String = SettingsActivity.loadTitle(this),
        note: String? = currentNote
    ): String {
        val fixed = gps.state.value as? GpsProvider.GpsState.Fixed
        return WatermarkCode.generate(
            timestampSec = realTime.time / 1000L,
            lat = fixed?.lat,
            lng = fixed?.lng,
            title = title,
            note = note
        )
    }

    // ---- 经纬度坐标行（设置中可开关，默认开启） ----

    /**
     * 刷新经纬度坐标行：开关开启且能取到坐标时显示，否则整行隐藏。
     * 优先显示手动设置的坐标（仅"隐藏功能模式"下点击位置名称手填 / 选择自定义位置时可设置），
     * 否则显示系统真实 GPS 原始坐标。
     */
    private fun updateCoordinate(state: GpsProvider.GpsState?) {
        if (!SettingsActivity.isCoordinateEnabled(this)) {
            tvWmCoordinate.visibility = View.GONE
            return
        }
        val mLat = manualLat
        val mLng = manualLng
        val text = if (mLat != null && mLng != null) {
            formatCoordinate(mLat, mLng)
        } else {
            (state as? GpsProvider.GpsState.Fixed)?.let { formatCoordinate(it.lat, it.lng) }
        }
        if (text == null) {
            tvWmCoordinate.visibility = View.GONE
            return
        }
        tvWmCoordinate.text = text
        tvWmCoordinate.visibility = View.VISIBLE
    }

    /** 经纬度文案：纬度在前、经度在后，6 位小数（约 0.1 米精度），与设置页自定义地名列表一致 */
    private fun formatCoordinate(lat: Double, lng: Double): String =
        String.format(Locale.CHINA, "坐标 %.6f, %.6f", lat, lng)

    // ---- 右下角天气（设置中可开关） ----

    /** 按设置显示/隐藏天气行；开启时用缓存即时回填，并按需拉取最新数据 */
    private fun syncWeatherVisibility() {
        if (!SettingsActivity.isWeatherEnabled(this)) {
            weatherRow.visibility = View.GONE
            return
        }
        WeatherRepository.cached()?.let { renderWeather(it) }
        val st = gps.state.value
        if (st is GpsProvider.GpsState.Fixed) {
            refreshWeather(st.lat, st.lng)
        } else {
            // 定位尚未完成时先用系统缓存位置取天气，加快首屏出现速度
            lastKnownCoords()?.let { refreshWeather(it.first, it.second) }
        }
        if (WeatherRepository.cached() == null) weatherRow.visibility = View.GONE
    }

    /** 最近一次系统缓存定位（GPS 优先、网络兜底），无权限或无缓存返回 null */
    private fun lastKnownCoords(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(LocationManager::class.java) ?: return null
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        return loc?.let { it.latitude to it.longitude }
    }

    /** 拉取天气（有新鲜缓存时不联网；失败保留旧数据，不打扰用户） */
    private fun refreshWeather(lat: Double, lng: Double) {
        if (!SettingsActivity.isWeatherEnabled(this) || weatherLoading) return
        lifecycleScope.launch {
            weatherLoading = true
            try {
                val w = WeatherRepository.fetch(lat, lng)
                if (w != null) {
                    renderWeather(w)
                } else if (WeatherRepository.cached() == null) {
                    weatherRow.visibility = View.GONE
                }
            } finally {
                weatherLoading = false
            }
        }
    }

    /** 天气只控制水印卡片内的"天气行"，右下角板块（真实时间标识 + 防伪码）始终显示 */
    private fun renderWeather(w: WeatherInfo) {
        if (!SettingsActivity.isWeatherEnabled(this)) {
            weatherRow.visibility = View.GONE
            return
        }
        ivWeatherIcon.setImageResource(w.iconRes)
        tvWeatherTemp.text = "${w.temperature}℃"
        tvWeatherDesc.text = w.label
        weatherRow.visibility = View.VISIBLE
    }

    // ---- 时间临时校准（彩蛋解锁后点击水印时间/日期触发） ----

    private fun showTimeEditDialog() {
        val shown = SettingsActivity.getManualDateTime(this) ?: Date()
        val fmtDateIn = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val fmtTimeIn = SimpleDateFormat("HH:mm", Locale.CHINA)

        val inputDate = EditText(this).apply {
            hint = "日期，如 2026-09-13"
            setText(fmtDateIn.format(shown))
        }
        val inputTime = EditText(this).apply {
            hint = "时间，如 14:30"
            setText(fmtTimeIn.format(shown))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(TextView(this@MainActivity).apply {
                text = "临时校准巡查日期与时间，仅影响水印显示与照片水印，文件名仍使用系统真实时间。"
                textSize = 12f
                alpha = 0.7f
            })
            addView(inputDate)
            addView(inputTime)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("时间校准")
            .setView(container)
            .setPositiveButton("保存", null)   // 校验拦截，见 setOnShowListener
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复系统时间", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = try {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
                        isLenient = false
                    }.parse("${inputDate.text.toString().trim()} ${inputTime.text.toString().trim()}")
                } catch (e: Exception) {
                    null
                }
                if (parsed == null) {
                    inputDate.error = "格式：日期 yyyy-MM-dd、时间 HH:mm"
                    Toast.makeText(this, "格式不正确，请检查日期与时间", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                SettingsActivity.setManualDateTime(this, parsed)
                refreshClock()
                Toast.makeText(
                    this,
                    "已临时校准：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(parsed)}",
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                SettingsActivity.setManualDateTime(this, null)
                refreshClock()
                Toast.makeText(this, "已恢复系统时间", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // ---- 点击位置名称：手动命名 / 手动坐标 / 选择自定义位置（20 米记忆） ----

    /** 应用手动设置的位置：预览上的地名与坐标行立即切换到手填值 */
    private fun applyManualOverride(name: String, lat: Double, lng: Double) {
        manualName = name
        manualLat = lat
        manualLng = lng
        refreshManualView()
    }

    /** 清除手动设置，回到"自动解析地名 + 真实 GPS"；返回原先是否处于手动状态 */
    private fun clearManualOverride(): Boolean {
        val had = manualName != null || manualLat != null || manualLng != null
        manualName = null
        manualLat = null
        manualLng = null
        refreshManualView()
        return had
    }

    /** 重绘预览上的地名行与坐标行（手动设置与自动解析共用的唯一出口） */
    private fun refreshManualView() {
        tvWmLocation.text = manualName ?: locationResolver.text.value
        updateCoordinate(gps.state.value)
    }

    /**
     * 点击水印上的位置名称弹出（分两档）：
     *   - 正常模式：与 v1.2.9 一致，只能手动命名（保存后 20 米内自动回显），改不了坐标；
     *   - 隐藏功能模式：额外提供「选择自定义位置」与「手动填写/修改坐标」，
     *     坐标偏离真实 GPS 时覆盖本次拍照的坐标行。
     */
    private fun showLocationNamingDialog() {
        val advanced = SettingsActivity.isTimeEditUnlocked(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        fun boxInput(hintText: String, text: String?): EditText = EditText(this).apply {
            hint = hintText
            setText(text ?: "")
            textSize = 14f
            setTextColor(0xFF111111.toInt())
            setHintTextColor(0xFFAAAAAA.toInt())
            background = ContextCompat.getDrawable(context, R.drawable.bg_edit_box)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            maxLines = 1
        }

        fun label(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(10), 0, dp(3))
        }

        val fixed = gps.state.value as? GpsProvider.GpsState.Fixed
        val gpsHint = if (fixed != null) {
            String.format(
                Locale.CHINA, "真实 GPS：%.6f, %.6f（精度 %.0f 米）",
                fixed.lat, fixed.lng, fixed.accuracyMeters
            )
        } else {
            "尚未完成定位，可手动填写坐标"
        }

        // 预填：手动设置优先，其次当前自动解析出的地名（"定位中…"不预填）
        val curName = manualName
            ?: locationResolver.text.value.takeIf { it != "定位中…" }.orEmpty()
        val curLat = manualLat ?: fixed?.lat
        val curLng = manualLng ?: fixed?.lng

        val edtName = boxInput("输入位置名称（如：老库房大门）", curName)
        val edtLat = boxInput("纬度，如 44.361230", curLat?.let { "%.6f".format(it) })
        val edtLng = boxInput("经度，如 131.039500", curLng?.let { "%.6f".format(it) })

        val latRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p1.marginEnd = dp(8)
            addView(edtLat, p1)
            addView(edtLng, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        /** 从已保存的自定义位置中挑选后填表；若名称与坐标原样未改，保存时不重复写库 */
        var picked: CustomPlaceStore.CustomPlace? = null
        val btnPick = Button(this).apply {
            text = "选择自定义位置"
            setOnClickListener {
                showCustomPlacePicker { place ->
                    picked = place
                    edtName.setText(place.name)
                    edtLat.setText("%.6f".format(place.lat))
                    edtLng.setText("%.6f".format(place.lng))
                    Toast.makeText(
                        this@MainActivity,
                        "已填入「${place.name}」，点\"保存\"生效",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
            if (advanced) {
                addView(TextView(this@MainActivity).apply {
                    text = gpsHint
                    textSize = 12f
                    alpha = 0.7f
                })
            }
            addView(label("位置名称"))
            addView(edtName)
            if (advanced) {
                addView(btnPick)
                addView(label("坐标（可手动修改；不填则用真实 GPS，偏离真实位置时覆盖本次拍照的坐标行）"))
                addView(latRow)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("手动命名位置")
            .setView(container)
            .setPositiveButton("保存", null)   // 校验拦截，见 setOnShowListener
            .setNegativeButton("取消", null)
            .apply {
                if (advanced && (manualName != null || manualLat != null)) {
                    setNeutralButton("恢复定位", null)
                }
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = edtName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "请输入位置名称", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // 正常模式下坐标框未显示，恒按"留空"处理，即只能用真实 GPS 写库（v1.2.9 行为）
                val latRaw = if (advanced) edtLat.text.toString().trim() else ""
                val lngRaw = if (advanced) edtLng.text.toString().trim() else ""

                // 坐标留空：有定位就按真实 GPS 保存，无定位则仅临时显示地名（与旧版行为一致）
                if (latRaw.isEmpty() || lngRaw.isEmpty()) {
                    if (fixed == null) {
                        manualName = name
                        manualLat = null
                        manualLng = null
                        refreshManualView()
                        Toast.makeText(this, "尚未定位成功，仅临时显示，未保存", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    CustomPlaceStore.addOrUpdate(this, fixed.lat, fixed.lng, name)
                    clearManualOverride()
                    tvWmLocation.text = name
                    Toast.makeText(this, "已保存：该位置 20 米内将显示「$name」", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    return@setOnClickListener
                }

                val lat = latRaw.toDoubleOrNull()
                val lng = lngRaw.toDoubleOrNull()
                if (lat == null || lng == null) {
                    Toast.makeText(this, "坐标格式不正确，请输入数字", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                    Toast.makeText(this, "坐标超出有效范围", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // 填的与所选自定义位置一致时不重复写库，避免 20 米内两条记录互相改名
                // （输入框按 6 位小数回显，与原值有微小差值，故用距离判定而非等值判定）
                val sameAsPicked = picked?.let { p ->
                    p.name == name && PointMatcher.distanceMeters(lat, lng, p.lat, p.lng) < 2.0
                } == true
                if (!sameAsPicked) CustomPlaceStore.addOrUpdate(this, lat, lng, name)

                // 填的就是当前位置 → 维持"自动匹配"（走出 20 米自动失效，与旧版一致）；
                // 偏离真实位置 → 手动设置生效，覆盖本次拍照的地名与坐标行
                val nearGps = fixed != null &&
                    PointMatcher.distanceMeters(lat, lng, fixed.lat, fixed.lng) < 2.0
                if (nearGps) {
                    clearManualOverride()
                    tvWmLocation.text = name
                } else {
                    applyManualOverride(name, lat, lng)
                }
                Toast.makeText(
                    this,
                    if (nearGps) "已保存：该位置 20 米内将显示「$name」"
                    else "已应用：地名与坐标行显示手填值，点左上角「刷新位置」可恢复真实定位",
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                clearManualOverride()
                Toast.makeText(this, "已恢复自动地名与真实 GPS", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** 已保存的自定义位置选择器（名称 + 坐标，点选后回填表单） */
    private fun showCustomPlacePicker(onPick: (CustomPlaceStore.CustomPlace) -> Unit) {
        val list = CustomPlaceStore.load(this)
        if (list.isEmpty()) {
            Toast.makeText(
                this,
                "还没有自定义位置：可在设置页「自定义位置名」里添加，或直接填写坐标后保存",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val items = list.map {
            String.format(Locale.CHINA, "%s\n纬度 %.6f · 经度 %.6f", it.name, it.lat, it.lng)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择自定义位置")
            .setItems(items) { _, which -> onPick(list[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 备注输入 ----

    private fun showNoteDialog() {
        val input = EditText(this).apply {
            hint = "现场备注（如：围栏破损、设备正常）"
            setText(currentNote ?: "")
            setSingleLine(false)
            maxLines = 3
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("现场备注")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                currentNote = input.text.toString().trim().takeIf { it.isNotEmpty() }
                tvWmNote.visibility = if (currentNote != null) View.VISIBLE else View.GONE
                tvWmNote.text = currentNote
                btnNote.text = currentNote?.take(8)?.plus("…") ?: "＋ 备注"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 隐藏功能模式：导入照片（给相册里已有的照片补上水印） ----

    /** "导入照片"入口可见性：只在隐藏功能模式下出现 */
    private fun syncAdvancedVisibility() {
        btnImportPhoto.visibility =
            if (SettingsActivity.isTimeEditUnlocked(this)) View.VISIBLE else View.GONE
    }

    /**
     * 导入照片：把相册里已有的照片按**当前设定**补上水印，另存为新照片（原图一律不动）。
     * 时间用当前设定时间（含隐藏模式的临时校准），位置用当前设定的地名与坐标，
     * 标题、备注、天气、防伪码与拍照完全一致；横版 / 竖版由渲染器自动识别。
     */
    private fun importPhoto(uri: Uri) {
        if (importingPhoto) return
        importingPhoto = true
        val captureTime = SettingsActivity.getManualDateTime(this) ?: Date()
        val title = SettingsActivity.loadTitle(this)
        val fileName = PhotoSaver.buildImportFileName(Date())
        Toast.makeText(this, "正在生成水印照片…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val bitmap = decodeUpright(this@MainActivity, uri)
                        ?: throw IllegalStateException("照片解码失败")
                    val wm = composeWatermark(bitmap, captureTime, title)
                    SaveResult(
                        photo = PhotoSaver.save(this@MainActivity, wm, fileName),
                        thumb = makeThumb(wm),
                        portrait = wm.height >= wm.width
                    )
                }
                playSaveAnimation(result.thumb)
                Toast.makeText(
                    this@MainActivity,
                    "已保存：${result.photo.displayName}\n（${if (result.portrait) "竖版" else "横版"}照片 · 相册 · 巡查水印相机）",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "导入照片失败: ${e.message}")
                Toast.makeText(this@MainActivity, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                importingPhoto = false
            }
        }
    }

    /**
     * 从相册 Uri 读图并按 EXIF 方向摆正；长边超过 MAX_IMPORT_SIDE 时先降采样。
     * 相册里的照片方向通常只写在 EXIF 里，不摆正会出现横竖颠倒。
     */
    private fun decodeUpright(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMPORT_SIDE) sample *= 2

        val rotation = try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                when (
                    ExifInterface(ins).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            Log.w(TAG, "读取导入照片 EXIF 失败: ${e.message}")
            0f
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        if (rotation != 0f) {
            val m = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    // ---- 拍照 ----

    @SuppressLint("RestrictedApi")
    private fun takePhoto() {
        if (!::imageCapture.isInitialized) {
            Toast.makeText(this, "相机尚未就绪，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (capturing) return
        capturing = true
        btnShutter.isEnabled = false
        shutterSound?.play(MediaActionSound.SHUTTER_CLICK)

        // 拍照方向跟随当前屏幕旋转：横握时成片为横向照片，
        // EXIF 摆正后水印即落在横向照片的左下角
        previewView.display?.rotation?.let { imageCapture.targetRotation = it }

        val tmpFile = File(cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(tmpFile).build()
        // 水印时间：优先临时校准时间；文件名时间：始终系统真实时间
        val captureTime = SettingsActivity.getManualDateTime(this) ?: Date()
        val fileNameTime = Date()
        val title = SettingsActivity.loadTitle(this)

        imageCapture.takePicture(
            opts,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch {
                        try {
                            val name = PhotoSaver.buildFileName(fileNameTime)
                            val result = withContext(Dispatchers.IO) {
                                processAndSave(tmpFile, captureTime, title, name)
                            }
                            playSaveAnimation(result.thumb)
                            Toast.makeText(
                                this@MainActivity,
                                "已保存：$name\n（相册 · 巡查水印相机）",
                                Toast.LENGTH_SHORT
                            ).show()
                            // 每张照片备注独立，用完即清
                            currentNote = null
                            tvWmNote.visibility = View.GONE
                            btnNote.text = "＋ 备注"
                        } catch (e: Exception) {
                            Log.e(TAG, "照片处理失败", e)
                            Toast.makeText(
                                this@MainActivity,
                                "保存失败：${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            tmpFile.delete()
                            capturing = false
                            btnShutter.isEnabled = true
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败: ${exception.message}", exception)
                    Toast.makeText(
                        this@MainActivity,
                        "拍照失败：${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    tmpFile.delete()
                    capturing = false
                    btnShutter.isEnabled = true
                }
            }
        )
    }

    /** IO 线程：EXIF 摆正 → 水印合成 → 入相册（顺带生成"飞入"动画用的缩略图） */
    private suspend fun processAndSave(
        tmpFile: File,
        captureTime: Date,
        title: String,
        displayName: String
    ): SaveResult = withContext(Dispatchers.IO) {
        val bitmap = decodeUpright(tmpFile)
            ?: throw IllegalStateException("照片解码失败")
        val wm = composeWatermark(bitmap, captureTime, title)
        SaveResult(
            photo = PhotoSaver.save(this@MainActivity, wm, displayName),
            thumb = makeThumb(wm),
            portrait = wm.height >= wm.width
        )
    }

    /** 生成"飞入"动画用的缩略图（长边 THUMB_MAX_SIDE，另建小图避免把整张成片搬上 UI 线程） */
    private fun makeThumb(src: Bitmap): Bitmap? {
        val longSide = maxOf(src.width, src.height)
        if (longSide <= 0) return null
        val scale = THUMB_MAX_SIDE.toFloat() / longSide
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(src, w, h, true)
        } catch (e: Exception) {
            Log.w(TAG, "缩略图生成失败: ${e.message}")
            null
        }
    }

    /**
     * 保存完成动画：屏幕中央先浮现一张成片缩略图（小于屏幕、圆角），
     * 随后一边缩小一边向右下角（防伪码位置）移动并淡出，暗示"照片已存入相册"。
     * 拍照与导入照片共用；动画结束即移除视图。
     */
    private fun playSaveAnimation(thumb: Bitmap?) {
        if (thumb == null || isFinishing || isDestroyed) return
        val container = window.decorView as? ViewGroup ?: return
        // 上一张的动画还没飞完就先撤掉，避免两张缩略图叠在一起
        container.findViewWithTag<View>(TAG_SAVE_THUMB)?.let { container.removeView(it) }

        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val dm = resources.displayMetrics
        val maxSide = minOf(dm.widthPixels, dm.heightPixels) * 0.46f
        val ratio = thumb.width.toFloat() / thumb.height
        val w = (if (ratio >= 1f) maxSide else maxSide * ratio).toInt().coerceAtLeast(1)
        val h = (if (ratio >= 1f) maxSide / ratio else maxSide).toInt().coerceAtLeast(1)

        val iv = ImageView(this).apply {
            tag = TAG_SAVE_THUMB
            setImageBitmap(thumb)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
            // 圆角（API 21+ 的 outline 裁剪，minSdk 26 无兼容问题）
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
            clipToOutline = true
        }
        container.addView(iv, FrameLayout.LayoutParams(w, h, Gravity.CENTER))

        iv.post {
            if (isFinishing || isDestroyed) {
                container.removeView(iv)
                return@post
            }
            // 起点＝屏幕正中的缩略图中心；终点＝右下角防伪码中心
            val startLoc = IntArray(2)
            iv.getLocationInWindow(startLoc)
            val startCx = startLoc[0] + iv.width / 2f
            val startCy = startLoc[1] + iv.height / 2f
            val endLoc = IntArray(2)
            tvCornerCode.getLocationInWindow(endLoc)
            val endCx = endLoc[0] + tvCornerCode.width / 2f
            val endCy = endLoc[1] + tvCornerCode.height / 2f

            val appear = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(iv, "alpha", 0f, 1f),
                    ObjectAnimator.ofFloat(iv, "scaleX", 0.88f, 1f),
                    ObjectAnimator.ofFloat(iv, "scaleY", 0.88f, 1f)
                )
                duration = 200L
                interpolator = DecelerateInterpolator()
            }
            val fly = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(iv, "translationX", 0f, endCx - startCx),
                    ObjectAnimator.ofFloat(iv, "translationY", 0f, endCy - startCy),
                    ObjectAnimator.ofFloat(iv, "scaleX", 1f, 0.10f),
                    ObjectAnimator.ofFloat(iv, "scaleY", 1f, 0.10f),
                    ObjectAnimator.ofFloat(iv, "alpha", 1f, 0f)
                )
                duration = 700L
                interpolator = AccelerateInterpolator()
            }
            AnimatorSet().apply {
                playSequentially(appear, fly)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        container.removeView(iv)
                    }
                })
                start()
            }
        }
    }

    /**
     * 把当前设定（标题、时间、位置名称、坐标、备注、天气、防伪码）烙到 src 上。
     * 拍照与"导入照片"共用这一条路径，保证"预览所见即成片"；
     * 横版 / 竖版由渲染器按宽高自动区分（横版卡片缩到 ≤50% 宽）。
     */
    private fun composeWatermark(src: Bitmap, captureTime: Date, title: String): Bitmap {
        // 天气：设置中开启且有数据时烙进照片水印；无网络（无数据）则整行省略
        val weather = WeatherRepository.cached()
            ?.takeIf { SettingsActivity.isWeatherEnabled(this) }
        // 经纬度行：手动设置优先（隐藏功能模式下手填 / 选的坐标），否则用真实 GPS 原始坐标；
        // 设置中关闭坐标显示时整行省略
        val mLat = manualLat
        val mLng = manualLng
        val coordText = if (!SettingsActivity.isCoordinateEnabled(this)) {
            null
        } else if (mLat != null && mLng != null) {
            formatCoordinate(mLat, mLng)
        } else {
            (gps.state.value as? GpsProvider.GpsState.Fixed)?.let { formatCoordinate(it.lat, it.lng) }
        }
        // 地名行：手动设置优先，否则用自动解析结果
        val locationText = manualName ?: locationResolver.text.value
        // 右下角两行：真实时间（仅标识字样）与防伪码一律取系统真实时间 + 真实 GPS，不受手动干预影响
        val realTime = Date()
        return WatermarkRenderer.render(
            src = src,
            captureTime = captureTime,
            title = title,
            locationText = locationText,
            noteText = currentNote,
            weatherIcon = weather?.let { weatherIconBitmap(it.iconRes, 256) },
            weatherText = weather?.let { "${it.label} ${it.temperature}℃" },
            coordinateText = coordText,
            realTimeText = "真实时间",
            codeText = "防伪码 " + buildWatermarkCode(realTime, title = title)
        )
    }

    /** 天气矢量图标 → 位图（供水印合成使用） */
    private fun weatherIconBitmap(resId: Int, sizePx: Int): Bitmap? {
        return try {
            val d = ContextCompat.getDrawable(this, resId) ?: return null
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, sizePx, sizePx)
            d.draw(canvas)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "天气图标渲染失败: ${e.message}")
            null
        }
    }

    /** 按 EXIF 方向解码，返回摆正后的 Bitmap */
    private fun decodeUpright(file: File): Bitmap? {
        val exif = ExifInterface(file.absolutePath)
        val rotation = when (
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        if (rotation != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }
        return bmp
    }
}
