package com.bianhequ.patrolcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.location.LocationManager
import android.media.MediaActionSound
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
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
 * 布局：全屏取景 + 顶部（定位状态 / 设置入口）+ 左下角实时水印预览（含天气行）
 *       + 右下角板块（真实时间标识 / 防伪码）+ 底部（备注 / 快门）。
 * 拍照：取景定格 → cache 临时文件 → IO 线程（EXIF 摆正 → 水印合成 → MediaStore 入库）
 *       → 清空备注 → Toast 反馈。
 *
 * 交互：
 *   - 点击水印上的位置名称 → 手动命名（保存后 20 米内自动回显）
 *   - 右上角齿轮 → 设置页（巡查标题 / 自定义地名管理 / 版本说明）
 *   - 连点设置页版本说明 5 次解锁后 → 点击水印上的时间/日期可临时校准巡查时间
 *     （仅影响水印显示与照片水印，文件名仍用系统真实时间）
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PatrolCamera"
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvGpsStatus: TextView
    private lateinit var wmRoot: LinearLayout
    private lateinit var tvWmTitleTag: TextView
    private lateinit var tvWmTitle: TextView
    private lateinit var tvWmTime: TextView
    private lateinit var tvWmDate: TextView
    private lateinit var tvWmLocation: TextView
    private lateinit var tvWmNote: TextView
    private lateinit var btnNote: TextView
    private lateinit var btnShutter: View
    private lateinit var btnSettings: ImageView
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
    private var weatherLoading = false

    private val clockFmtTime = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val clockFmtDate = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)

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
        btnNote = findViewById(R.id.btn_note)
        btnShutter = findViewById(R.id.btn_shutter)
        btnSettings = findViewById(R.id.btn_settings)
        weatherRow = findViewById(R.id.weather_row)
        ivWeatherIcon = findViewById(R.id.iv_weather_icon)
        tvWeatherTemp = findViewById(R.id.tv_weather_temp)
        tvWeatherDesc = findViewById(R.id.tv_weather_desc)
        cornerBox = findViewById(R.id.corner_box)
        tvCornerCode = findViewById(R.id.tv_corner_code)

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
        requestPermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        updateTitle()   // 从设置页返回时刷新标题
        refreshClock()  // 从设置页/对话框返回时立即刷新时间显示
        syncWeatherVisibility()   // 天气开关可能在设置页被改动
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
            locationResolver.text.collect { tvWmLocation.text = it }
        }
        lifecycleScope.launch {
            gps.state.collect {
                updateGpsPill(it, noPermission = false)
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

    // ---- 位置手动命名（20 米记忆） ----

    private fun showLocationNamingDialog() {
        val state = gps.state.value
        val coordHint = if (state is GpsProvider.GpsState.Fixed) {
            String.format(Locale.CHINA, "当前坐标：%.6f, %.6f（精度 %.0f 米）",
                state.lat, state.lng, state.accuracyMeters)
        } else {
            "尚未完成定位，暂无法保存自定义地名"
        }
        val input = EditText(this).apply {
            hint = "输入自定义位置名称（如：老库房大门）"
            setText(tvWmLocation.text.toString())
            setSingleLine(false)
            maxLines = 2
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(TextView(this@MainActivity).apply {
                text = coordHint
                textSize = 12f
                alpha = 0.7f
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("手动命名位置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().takeIf { it.isNotEmpty() }
                    ?: run {
                        Toast.makeText(this, "名称为空，未保存", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                val fixed = gps.state.value as? GpsProvider.GpsState.Fixed
                if (fixed == null) {
                    Toast.makeText(this, "尚未定位成功，仅临时显示，未保存", Toast.LENGTH_LONG).show()
                    tvWmLocation.text = name
                    return@setPositiveButton
                }
                CustomPlaceStore.addOrUpdate(this, fixed.lat, fixed.lng, name)
                tvWmLocation.text = name
                Toast.makeText(this, "已保存：该位置 20 米内将显示「$name」", Toast.LENGTH_LONG).show()
            }
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
                            withContext(Dispatchers.IO) {
                                processAndSave(tmpFile, captureTime, title, name)
                            }
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

    /** IO 线程：EXIF 摆正 → 水印合成 → 入相册 */
    private suspend fun processAndSave(
        tmpFile: File,
        captureTime: Date,
        title: String,
        displayName: String
    ): PhotoSaver.SavedPhoto = withContext(Dispatchers.IO) {
        val bitmap = decodeUpright(tmpFile)
            ?: throw IllegalStateException("照片解码失败")
        // 天气：设置中开启且有数据时烙进照片水印；无网络（无数据）则整行省略
        val weather = WeatherRepository.cached()
            ?.takeIf { SettingsActivity.isWeatherEnabled(this@MainActivity) }
        // 右下角两行：真实时间（仅标识字样）与防伪码一律取系统真实时间，不受水印临时校准影响
        val realTime = Date()
        val wm = WatermarkRenderer.render(
            src = bitmap,
            captureTime = captureTime,
            title = title,
            locationText = locationResolver.text.value,
            noteText = currentNote,
            weatherIcon = weather?.let { weatherIconBitmap(it.iconRes, 256) },
            weatherText = weather?.let { "${it.label} ${it.temperature}℃" },
            realTimeText = "真实时间",
            codeText = "防伪码 " + buildWatermarkCode(realTime, title = title)
        )
        PhotoSaver.save(this@MainActivity, wm, displayName)
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
