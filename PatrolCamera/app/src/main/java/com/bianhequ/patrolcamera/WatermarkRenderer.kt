package com.bianhequ.patrolcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 水印合成器 v2：示例卡片样式（大号）。
 *
 * 结构（自上而下）：
 *   1. 标题行：黄色圆角块（标题前 2 字，黑字）+ 白色标题余字 —— 标题可在设置中编辑
 *   2. 黄色三角虚线分隔
 *   3. 时间行：超大 "HH:mm" + 右侧 "yyyy年MM月dd日"
 *   4. 位置行：白色定位 pin + 位置名称（自动换行，最多 3 行）
 *   5. 天气行（可选）：天气图标 + "多云 14℃"（设置中可开关，无网络时自动省略）
 *   6. 现场备注（可选，淡黄色，最多 2 行）
 *
 * 右下角独立两行（卡片之外，右对齐、带阴影、无背景）：
 *   真实时间（仅"真实时间"标识字样，不显示具体时间）→ 防伪码；底边固定 h-44u。
 *   天气只画在卡片内部（位置行下方），不再出现在右下角。
 *
 * 深蓝紫半透明圆角卡片，全部尺寸按照片宽度等比缩放（u = 宽/1080），观感与分辨率无关。
 */
object WatermarkRenderer {

    private const val CARD_BG = 0x804848B0.toInt()   // 深蓝紫，50% 不透明（可再调）
    private const val TAG_BG = 0xFFF2C218.toInt()    // 标题黄块
    private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
    private const val COLOR_BLACK = 0xFF1A1A1A.toInt()
    private const val COLOR_NOTE = 0xFFFFE082.toInt() // 备注淡黄
    private const val SHADOW_COLOR = 0xCC000000.toInt() // 右下角白字阴影

    fun render(
        src: Bitmap,
        captureTime: Date,
        title: String,
        locationText: String,
        noteText: String?,
        weatherIcon: Bitmap? = null,
        weatherText: String? = null,
        realTimeText: String? = null,
        codeText: String? = null
    ): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width
        val h = out.height
        // 竖屏：u = 宽/1080；横屏：整体等比缩小，卡片约占宽度 37%~50%（上限 50%）
        val u = if (w > h) minOf(w / 1080f, 0.50f * w / 800f) else w / 1080f

        val timeText = SimpleDateFormat("HH:mm", Locale.CHINA).format(captureTime)
        val dateText = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(captureTime)
        val safeTitle = title.ifBlank { "巡检工作记录" }
        val tagText = safeTitle.take(2)
        val restText = safeTitle.drop(2)

        // ---- 尺寸体系（u 基准） ----
        val pad = 26f * u
        val fTitle = 40f * u
        val tagH = 62f * u
        val tagPadH = 18f * u
        val fTime = 76f * u
        val fDate = 46f * u
        val fLoc = 34f * u
        val fNote = 27f * u
        val fWea = 32f * u
        val weaIcon = 42f * u
        val pinSize = 38f * u
        val pinGap = 12f * u
        val triW = 9f * u
        val triH = 11f * u
        val triGap = 6f * u

        val paintTitleTag = textPaint(COLOR_BLACK, fTitle, bold = true)
        val paintTitleRest = textPaint(COLOR_WHITE, fTitle, bold = true)
        val paintTime = textPaint(COLOR_WHITE, fTime, bold = true)
        val paintDate = textPaint(COLOR_WHITE, fDate)
        val paintLoc = textPaint(COLOR_WHITE, fLoc)
        val paintNote = textPaint(COLOR_NOTE, fNote)
        val paintWea = textPaint(COLOR_WHITE, fWea)

        // ---- 测量：由内容决定卡片宽 ----
        val tagW = paintTitleTag.measureText(tagText) + tagPadH * 2
        val restW = if (restText.isNotEmpty()) paintTitleRest.measureText(restText) + 14f * u else 0f
        val rowTitleW = tagW + restW
        val rowTimeW = paintTime.measureText(timeText) + 12f * u + paintDate.measureText(dateText)

        val cardW = (maxOf(rowTitleW, rowTimeW, 560f * u) + pad * 2)
            .coerceIn(600f * u, 800f * u)
        val textW = cardW - pad * 2 - pinSize - pinGap   // 位置文字可用宽
        val noteW = (cardW - pad * 2).toInt()

        val locLayout = buildLayout(locationText.take(60), paintLoc, textW.toInt(), maxLines = 3)
        val noteLayout = noteText?.takeIf { it.isNotBlank() }
            ?.let { buildLayout(it.take(60), paintNote, noteW, maxLines = 2) }

        // ---- 高度累计 ----
        val gapTitleDash = 22f * u
        val dashH = triH
        val gapDashTime = 18f * u
        val timeH = fTime * 1.10f
        val gapTimeLoc = 12f * u
        val gapLocNote = 14f * u
        val padTop = 22f * u
        val padBottom = 24f * u

        val showWeather = !weatherText.isNullOrBlank()
        val weaRowH = maxOf(weaIcon, fWea * 1.2f)

        var contentH = padTop + tagH + gapTitleDash + dashH + gapDashTime +
            timeH + gapTimeLoc + locLayout.height
        if (showWeather) contentH += gapLocNote + weaRowH
        if (noteLayout != null) contentH += gapLocNote + noteLayout.height
        contentH += padBottom

        // ---- 卡片位置：左下角 ----
        val left = 28f * u
        val bottom = h - 44f * u
        val top = bottom - contentH
        val x = left + pad

        // 背板
        val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_BG }
        canvas.drawRoundRect(
            RectF(left, top, left + cardW, bottom), 14f * u, 14f * u, paintBg
        )

        var y = top + padTop

        // 1) 标题行：黄块 + 余字
        val paintTagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TAG_BG }
        canvas.drawRoundRect(
            RectF(x, y, x + tagW, y + tagH), 8f * u, 8f * u, paintTagBg
        )
        val fmTag = paintTitleTag.fontMetrics
        val tagBaseline = y + tagH / 2f - (fmTag.ascent + fmTag.descent) / 2f
        paintTitleTag.textAlign = Paint.Align.CENTER
        canvas.drawText(tagText, x + tagW / 2f, tagBaseline, paintTitleTag)
        paintTitleTag.textAlign = Paint.Align.LEFT
        if (restText.isNotEmpty()) {
            val fmRest = paintTitleRest.fontMetrics
            val restBaseline = y + tagH / 2f - (fmRest.ascent + fmRest.descent) / 2f
            // 余字在"黄块右缘 → 卡片右内边距"的剩余区域内水平居中（与预览 tv_wm_title 一致）；
            // 文字宽超过剩余区域时不居中，退化为紧贴黄块右侧，避免溢出卡片。
            val restW = paintTitleRest.measureText(restText)
            val restLeft = x + tagW + 14f * u
            val restRight = left + cardW - pad
            val restCx = if (restW >= restRight - restLeft) {
                restLeft + restW / 2f
            } else {
                ((restLeft + restRight) / 2f).coerceIn(restLeft + restW / 2f, restRight - restW / 2f)
            }
            paintTitleRest.textAlign = Paint.Align.CENTER
            canvas.drawText(restText, restCx, restBaseline, paintTitleRest)
            paintTitleRest.textAlign = Paint.Align.LEFT
        }
        y += tagH + gapTitleDash

        // 2) 黄色三角虚线
        drawDashTriangles(canvas, x, y, cardW - pad * 2, triW, triH, triGap)
        y += dashH + gapDashTime

        // 3) 时间行：时间左对齐、日期右对齐（基线对齐）
        val fmTime = paintTime.fontMetrics
        val timeBaseline = y - fmTime.ascent
        canvas.drawText(timeText, x, timeBaseline, paintTime)
        val fmDate = paintDate.fontMetrics
        val dateBaseline = timeBaseline + (fmDate.ascent + fmDate.descent) / 2f -
            (fmTime.ascent + fmTime.descent) / 2f
        val dateW = paintDate.measureText(dateText)
        canvas.drawText(dateText, left + cardW - pad - dateW, dateBaseline, paintDate)
        y += timeH + gapTimeLoc

        // 4) 位置行：pin + 多行文字
        val fmLoc = paintLoc.fontMetrics
        val firstLineBaseline = y - fmLoc.ascent
        val pinCy = firstLineBaseline - fLoc * 0.32f
        drawPin(canvas, x + pinSize / 2f, pinCy, pinSize, CARD_BG)
        canvas.save()
        canvas.translate(x + pinSize + pinGap, y)
        locLayout.draw(canvas)
        canvas.restore()
        y += locLayout.height

        // 5) 天气行：图标 + "多云 14℃"（可选）
        if (showWeather) {
            y += gapLocNote
            val iconTop = y + (weaRowH - weaIcon) / 2f
            if (weatherIcon != null) {
                val dst = RectF(x, iconTop, x + weaIcon, iconTop + weaIcon)
                canvas.drawBitmap(
                    weatherIcon, null, dst,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
            }
            val textX = if (weatherIcon != null) x + weaIcon + 12f * u else x
            val fmWea = paintWea.fontMetrics
            val weaBaseline = y + weaRowH / 2f - (fmWea.ascent + fmWea.descent) / 2f
            canvas.drawText(weatherText ?: "", textX, weaBaseline, paintWea)
            y += weaRowH
        }

        // 6) 现场备注（可选）
        if (noteLayout != null) {
            y += gapLocNote
            canvas.save()
            canvas.translate(x, y)
            noteLayout.draw(canvas)
            canvas.restore()
        }

        // 7) 右下角两行：真实时间（仅标识字样）→ 防伪码
        //    全部右对齐、白字带阴影、无背景；自底边（h - 44u）向上排布，
        //    某行内容为空则整行跳过，其余行位置保持不变。天气已移入卡片内部，此处不再绘制。
        val fCornerReal = 32f * u
        val fCornerCode = 26f * u
        val cornerLineGap = 10f * u
        val cornerRight = w - 40f * u
        val cornerBottom = h - 44f * u

        val paintCornerReal = textPaint(COLOR_WHITE, fCornerReal).apply {
            textAlign = Paint.Align.RIGHT
            setShadowLayer(3f * u, 0f, 1f * u, SHADOW_COLOR)
        }
        val paintCornerCode = textPaint(COLOR_WHITE, fCornerCode).apply {
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.RIGHT
            setShadowLayer(3f * u, 0f, 1f * u, SHADOW_COLOR)
        }

        var cornerBaseline = cornerBottom
        if (!codeText.isNullOrBlank()) {
            canvas.drawText(codeText, cornerRight, cornerBaseline, paintCornerCode)
            cornerBaseline -= fCornerCode * 1.45f + cornerLineGap
        }
        if (!realTimeText.isNullOrBlank()) {
            canvas.drawText(realTimeText, cornerRight, cornerBaseline, paintCornerReal)
        }

        return out
    }

    private fun textPaint(color: Int, size: Float, bold: Boolean = false): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            isFakeBoldText = bold
        }

    private fun buildLayout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout {
        val safeWidth = width.coerceAtLeast((paint.textSize * 3).toInt())
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, safeWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            
            .setLineSpacing(0f, 1.15f)
            .build()
        return if (layout.lineCount <= maxLines) layout else {
            // 超行截断：重新按截断文本布局
            val end = layout.getLineVisibleEnd(maxLines - 1)
            val clipped = if (end < text.length) text.substring(0, end - 1) + "…" else text
            StaticLayout.Builder
                .obtain(clipped, 0, clipped.length, paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                
                .setLineSpacing(0f, 1.15f)
                .build()
        }
    }

    /** 一排黄色小右三角，模拟示例图中的"►►►"虚线分隔 */
    private fun drawDashTriangles(
        canvas: Canvas, x: Float, y: Float, width: Float,
        triW: Float, triH: Float, gap: Float
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TAG_BG }
        val path = Path()
        var cx = x
        while (cx + triW <= x + width) {
            path.moveTo(cx, y)
            path.lineTo(cx + triW, y + triH / 2f)
            path.lineTo(cx, y + triH)
            path.close()
            cx += triW + gap
        }
        canvas.drawPath(path, paint)
    }

    /** 定位 pin：白色水滴 + 卡片色内孔 */
    private fun drawPin(canvas: Canvas, cx: Float, cy: Float, size: Float, holeColor: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_WHITE }
        val r = size * 0.36f
        val path = Path()
        path.addCircle(cx, cy - size * 0.10f, r, Path.Direction.CW)
        path.moveTo(cx - r * 0.92f, cy - size * 0.05f)
        path.lineTo(cx + r * 0.92f, cy - size * 0.05f)
        path.lineTo(cx, cy + size * 0.42f)
        path.close()
        canvas.drawPath(path, paint)

        val paintHole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = holeColor }
        canvas.drawCircle(cx, cy - size * 0.10f, r * 0.42f, paintHole)
    }
}
