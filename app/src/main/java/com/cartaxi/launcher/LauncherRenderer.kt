package com.cartaxi.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import java.io.File
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class UiState(
    val speedKmh: Int?,
    val gpsFix: Boolean,
    val batteryPct: Int,
    val qrFile: File?,
    val nowMs: Long,
    val selectedIcon: Int,
    val mapExpanded: Boolean,
    val mapFrame: Bitmap?,
    val mapFrameW: Int,
    val mapFrameH: Int,
    val mapsStatus: String
)

object LauncherRenderer {
    const val ICON_COUNT = 7
    private const val CAR_FX = 0.5f
    private const val CAR_FY = 0.65f

    private val BG = Color.rgb(11, 16, 24)
    private val CARD = Color.rgb(21, 29, 43)
    private val CARD_CLEAR = Color.argb(0, 21, 29, 43)
    private val PILL = Color.rgb(18, 26, 39)
    private val LINE = Color.rgb(36, 48, 68)
    private val CHIP = Color.rgb(30, 40, 56)
    private val ACCENT = Color.rgb(255, 193, 7)
    private val MUTED = Color.rgb(138, 150, 171)
    private val SOFT = Color.rgb(159, 176, 200)
    private val BLUE = Color.rgb(47, 125, 246)
    private val GREEN = Color.rgb(46, 204, 113)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = LINE
    }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint().apply { isFilterBitmap = true }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val srcRect = Rect()
    private val clip = Path()
    private val tri = Path()

    private val DAYS = arrayOf("Chủ nhật", "Thứ hai", "Thứ ba", "Thứ tư", "Thứ năm", "Thứ sáu", "Thứ bảy")

    private val ICON_DATA = arrayOf(
        "M3 11l9-8 9 8M5 10v10h5v-6h4v6h5V10",
        "M4 11l16-7-7 16-2-7z",
        "M6 6h12a3.5 3.5 0 0 1 3.5 3.5v5a3.5 3.5 0 0 1-3.5 3.5H6a3.5 3.5 0 0 1-3.5-3.5v-5A3.5 3.5 0 0 1 6 6zM10 9.5v5l4.5-2.5z",
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM10 9l5 3-5 3z",
        "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18",
        "M5.5 4h3A1.5 1.5 0 0 1 10 5.5v3A1.5 1.5 0 0 1 8.5 10h-3A1.5 1.5 0 0 1 4 8.5v-3A1.5 1.5 0 0 1 5.5 4zM15 4h3a1.5 1.5 0 0 1 1.5 1.5v3A1.5 1.5 0 0 1 18 10h-3a1.5 1.5 0 0 1-1.5-1.5v-3A1.5 1.5 0 0 1 15 4zM5.5 13.5h3A1.5 1.5 0 0 1 10 15v3a1.5 1.5 0 0 1-1.5 1.5h-3A1.5 1.5 0 0 1 4 18v-3a1.5 1.5 0 0 1 1.5-1.5zM15 13.5h3a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-3a1.5 1.5 0 0 1-1.5-1.5v-3A1.5 1.5 0 0 1 15 13.5z",
        "M4 7h10M18 7h2M4 17h2M10 17h10M16 5a2 2 0 1 0 0 4a2 2 0 1 0 0-4M8 15a2 2 0 1 0 0 4a2 2 0 1 0 0-4"
    )
    private val ICON_COLORS = intArrayOf(
        Color.WHITE, SOFT, Color.rgb(255, 90, 90), SOFT, SOFT, SOFT, SOFT
    )
    private val iconPaths: List<Path?> by lazy {
        ICON_DATA.map {
            try {
                PathParser.createPathFromPathData(it)
            } catch (t: Throwable) {
                null
            }
        }
    }

    private var lastArea: RectF? = null
    private var lastIcons: RectF? = null
    private var lastMiniTap: RectF? = null
    private var lastMapDst: RectF? = null
    private val mediaBtns = Array(3) { RectF() }

    private var qrStamp = 0L
    private var qrSrc: Bitmap? = null
    private var qrScaled: Bitmap? = null
    private var qrScaledW = 0
    private var qrScaledH = 0

    private var lunarKey = -1
    private var lunarText = ""

    // ---------- API cho CarService ----------

    fun areaRect(): RectF? = lastArea

    fun hitIcon(x: Float, y: Float): Int {
        val r = lastIcons ?: return -1
        if (!r.contains(x, y)) return -1
        val i = ((y - r.top) / (r.height() / ICON_COUNT)).toInt()
        return i.coerceIn(0, ICON_COUNT - 1)
    }

    fun hitMiniMap(x: Float, y: Float): Boolean = lastMiniTap?.contains(x, y) == true

    fun hitMedia(x: Float, y: Float): Int {
        for (i in 0 until 3) {
            val b = mediaBtns[i]
            if (b.width() > 0f && b.contains(x, y)) return i
        }
        return -1
    }

    fun hitMapPoint(x: Float, y: Float): FloatArray? {
        val d = lastMapDst ?: return null
        if (!d.contains(x, y)) return null
        return floatArrayOf((x - d.left) / d.width(), (y - d.top) / d.height())
    }

    // ---------- vẽ ----------

    fun render(c: Canvas, cw: Int, ch: Int, st: UiState) {
        c.drawColor(BG)
        val pad = minOf(cw, ch) * 0.013f
        val g = pad
        val a = RectF(pad, pad, cw - pad, ch - pad)
        lastArea = a
        lastMiniTap = null
        lastMapDst = null
        for (b in mediaBtns) b.setEmpty()

        val avail = a.width() - 2 * g
        val iconW = avail * 0.0948f
        val mainW = avail * 0.5463f
        val icons = RectF(a.left, a.top, a.left + iconW, a.bottom)
        lastIcons = icons

        if (st.mapExpanded) {
            drawExpanded(c, a, st)
        } else {
            val main = RectF(icons.right + g, a.top, icons.right + g + mainW, a.bottom)
            val rx = main.right + g
            val availH = a.height() - 2 * g
            val calH = availH * 0.1759f
            val qrH = availH * 0.5653f
            val cal = RectF(rx, a.top, a.right, a.top + calH)
            val qr = RectF(rx, cal.bottom + g, a.right, cal.bottom + g + qrH)
            val media = RectF(rx, qr.bottom + g, a.right, a.bottom)
            drawMain(c, main, st)
            drawCalendar(c, cal, st.nowMs)
            drawQr(c, qr, st.qrFile)
            drawMedia(c, media)
        }
        drawIcons(c, icons, st.selectedIcon)
    }

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f
    ) {
        tp.color = color
        tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tp.textAlign = align
        tp.textSize = size
        if (maxW > 0f) {
            val w = tp.measureText(s)
            if (w > maxW) tp.textSize = size * maxW / w
        }
        c.drawText(s, x, y, tp)
    }

    private fun textMid(
        c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int,
        bold: Boolean = false, maxW: Float = 0f
    ) {
        tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tp.textSize = size
        if (maxW > 0f) {
            val w = tp.measureText(s)
            if (w > maxW) tp.textSize = size * maxW / w
        }
        val fm = tp.fontMetrics
        tp.color = color
        tp.textAlign = Paint.Align.CENTER
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
    }

    private fun card(c: Canvas, r: RectF, rad: Float) {
        fill.shader = null
        fill.color = CARD
        c.drawRoundRect(r, rad, rad, fill)
        c.drawRoundRect(r, rad, rad, edge)
    }

    // ---------- bản đồ toàn màn hình ----------

    private fun drawExpanded(c: Canvas, a: RectF, st: UiState) {
        val f = st.mapFrame
        if (f == null || st.mapFrameW <= 0 || st.mapFrameH <= 0) {
            textMid(c, "Bản đồ", a.centerX(), a.centerY() - 20f, 56f, MUTED, true)
            textMid(c, st.mapsStatus, a.centerX(), a.centerY() + 40f, 22f, ACCENT, false, a.width() * 0.9f)
            return
        }
        srcRect.set(0, 0, minOf(st.mapFrameW, f.width), minOf(st.mapFrameH, f.height))
        framePaint.alpha = 255
        c.drawBitmap(f, srcRect, a, framePaint)
        lastMapDst = a
    }

    // ---------- thẻ chính ----------

    private fun drawMain(c: Canvas, m: RectF, st: UiState) {
        val k = minOf(m.width() / 484f, m.height() / 820f)
        val rad = 28f * k
        fill.shader = null
        fill.color = CARD
        c.drawRoundRect(m, rad, rad, fill)

        c.save()
        clip.reset()
        clip.addRoundRect(m, rad, rad, Path.Direction.CW)
        c.clipPath(clip)

        val mapH = 560f / 820f * m.height()
        val mr = RectF(m.left, m.bottom - mapH, m.right, m.bottom)
        lastMiniTap = RectF(m.left, m.top + m.height() * 0.40f, m.right, m.bottom - 76f * k)
        drawMiniMap(c, mr, st, k)

        drawHeader(c, m, k, st)
        drawGauge(c, m, k, st.speedKmh ?: 0)
        drawChips(c, m, k, st)

        c.restore()
        c.drawRoundRect(m, rad, rad, edge)
    }

    private fun drawMiniMap(c: Canvas, mr: RectF, st: UiState, k: Float) {
        val f = st.mapFrame
        if (f == null || st.mapFrameW <= 0 || st.mapFrameH <= 0) {
            textMid(c, st.mapsStatus, mr.centerX(), mr.centerY() + 60f * k, 16f * k, MUTED, false, mr.width() * 0.8f)
            return
        }
        val fw = st.mapFrameW.toFloat()
        val fh = st.mapFrameH.toFloat()
        val ww = minOf(mr.width(), fw)
        val wh = minOf(mr.height(), fh)
        val left = (fw * CAR_FX - ww / 2f).coerceIn(0f, fw - ww)
        val top = (fh * CAR_FY - wh * 0.68f).coerceIn(0f, fh - wh)
        srcRect.set(left.toInt(), top.toInt(), (left + ww).toInt(), (top + wh).toInt())
        framePaint.alpha = 215
        c.drawBitmap(f, srcRect, mr, framePaint)

        fill.shader = null
        fill.color = Color.argb(70, 21, 29, 43)
        c.drawRect(mr, fill)

        fill.shader = LinearGradient(mr.left, mr.top, mr.left, mr.top + mr.height() * 0.5f, CARD, CARD_CLEAR, Shader.TileMode.CLAMP)
        c.drawRect(mr, fill)
        val sw = mr.width() * 0.14f
        fill.shader = LinearGradient(mr.left, 0f, mr.left + sw, 0f, Color.argb(230, 21, 29, 43), CARD_CLEAR, Shader.TileMode.CLAMP)
        c.drawRect(mr.left, mr.top, mr.left + sw, mr.bottom, fill)
        fill.shader = LinearGradient(mr.right - sw, 0f, mr.right, 0f, CARD_CLEAR, Color.argb(230, 21, 29, 43), Shader.TileMode.CLAMP)
        c.drawRect(mr.right - sw, mr.top, mr.right, mr.bottom, fill)
        fill.shader = null
    }

    private fun drawHeader(c: Canvas, m: RectF, k: Float, st: UiState) {
        val cal = Calendar.getInstance().apply { timeInMillis = st.nowMs }
        val hhmm = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val sec = String.format("%02d", cal.get(Calendar.SECOND))
        val tx = m.left + 26f * k
        val ty = m.top + 22f * k + 60f * k * 0.85f
        text(c, hhmm, tx, ty, 60f * k, Color.WHITE, true)
        val w = tp.measureText(hhmm)
        text(c, sec, tx + w + 8f * k, ty, 24f * k, ACCENT, true)

        val label = if ((st.speedKmh ?: 0) > 3) "Đang chạy" else "Đang dừng"
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textSize = 16f * k
        val cw = tp.measureText(label) + 32f * k
        val cy = m.top + 52f * k
        val chip = RectF(m.right - 26f * k - cw, cy - 18f * k, m.right - 26f * k, cy + 18f * k)
        fill.shader = null
        fill.color = CHIP
        c.drawRoundRect(chip, 18f * k, 18f * k, fill)
        textMid(c, label, chip.centerX(), chip.centerY(), 16f * k, SOFT, true)
    }

    private fun drawGauge(c: Canvas, m: RectF, k: Float, speed: Int) {
        val gs = k * 0.889f
        val cx = m.centerX()
        val cy = m.top + 94f * k + 170f * gs
        val r = 140f * gs
        val arc = RectF(cx - r, cy - r, cx + r, cy + r)

        arcPaint.strokeWidth = 10f * gs
        arcPaint.color = LINE
        c.drawArc(arc, 135f, 270f, false, arcPaint)

        val sweep = 270f * minOf(speed, 200) / 200f
        arcPaint.color = ACCENT
        if (sweep < 1f) {
            val sx = cx + r * cos(135.0 * PI / 180.0).toFloat()
            val sy = cy + r * sin(135.0 * PI / 180.0).toFloat()
            fill.shader = null
            fill.color = ACCENT
            c.drawCircle(sx, sy, 5f * gs, fill)
        } else {
            c.drawArc(arc, 135f, sweep, false, arcPaint)
        }

        var v = 0
        while (v <= 200) {
            val ang = (135.0 + 270.0 * v / 200.0) * PI / 180.0
            val major = v % 40 == 0
            val r1 = (if (major) 122f else 127f) * gs
            val r2 = 131f * gs
            linePaint.color = if (major) MUTED else Color.rgb(58, 69, 88)
            linePaint.strokeWidth = (if (major) 2.4f else 1.4f) * gs
            c.drawLine(
                cx + r1 * cos(ang).toFloat(), cy + r1 * sin(ang).toFloat(),
                cx + r2 * cos(ang).toFloat(), cy + r2 * sin(ang).toFloat(), linePaint
            )
            if (major) {
                val rl = 104f * gs
                textMid(c, v.toString(), cx + rl * cos(ang).toFloat(), cy + rl * sin(ang).toFloat(), 15f * gs, MUTED, true)
            }
            v += 10
        }

        text(c, speed.toString(), cx, cy + 42f * gs, 118f * gs, Color.WHITE, true, Paint.Align.CENTER)
        tp.letterSpacing = 0.2f
        text(c, "KM/H", cx, cy + 80f * gs, 19f * gs, MUTED, true, Paint.Align.CENTER)
        tp.letterSpacing = 0f
    }

    private fun drawChips(c: Canvas, m: RectF, k: Float, st: UiState) {
        val left = m.left + 14f * k
        val right = m.right - 14f * k
        val bottom = m.bottom - 14f * k
        val h = 58f * k
        val gap = 8f * k
        val w = (right - left - 3 * gap) / 4f
        val items = arrayOf(
            "Tốc độ" to (if (st.speedKmh == null) "—" else "${st.speedKmh} km/h"),
            "GPS" to (if (st.gpsFix) "Tốt" else "Đang bắt"),
            "Pin điện thoại" to (if (st.batteryPct >= 0) "${st.batteryPct}%" else "—"),
            "Android Auto" to "Đã kết nối"
        )
        for (i in 0 until 4) {
            val x = left + i * (w + gap)
            val rc = RectF(x, bottom - h, x + w, bottom)
            fill.shader = null
            fill.color = Color.argb(224, 18, 26, 39)
            c.drawRoundRect(rc, 14f * k, 14f * k, fill)
            c.drawRoundRect(rc, 14f * k, 14f * k, edge)
            text(c, items[i].first, rc.left + 8f * k, rc.top + 22f * k, 12f * k, MUTED, false, Paint.Align.LEFT, w - 14f * k)
            text(c, items[i].second, rc.left + 8f * k, rc.top + 46f * k, 15f * k, Color.WHITE, true, Paint.Align.LEFT, w - 14f * k)
        }
    }

    // ---------- cột phải ----------

    private fun drawCalendar(c: Canvas, r: RectF, nowMs: Long) {
        val k = r.height() / 140f
        card(c, r, 24f * k)
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val dd = cal.get(Calendar.DAY_OF_MONTH)
        val mm = cal.get(Calendar.MONTH) + 1
        val yy = cal.get(Calendar.YEAR)
        val key = yy * 10000 + mm * 100 + dd
        if (key != lunarKey) {
            val l = LunarCalendar.solarToLunar(dd, mm, yy)
            val leap = if (l.leap) " nhuận" else ""
            lunarText = "Âm: ${l.day}/${l.month}$leap ${LunarCalendar.canChi(l.year)}"
            lunarKey = key
        }
        val dayStr = dd.toString()
        val dx = r.left + 20f * k
        text(c, dayStr, dx, r.centerY() + 76f * k * 0.35f, 76f * k, ACCENT, true)
        val dw = tp.measureText(dayStr)
        val tx = dx + dw + 16f * k
        val maxW = r.right - tx - 14f * k
        val top = r.centerY() - 43f * k
        text(c, DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1], tx, top + 22f * k, 24f * k, Color.WHITE, true, Paint.Align.LEFT, maxW)
        text(c, "Tháng $mm, $yy", tx, top + 44f * k, 16f * k, MUTED, false, Paint.Align.LEFT, maxW)
        text(c, lunarText, tx, top + 74f * k, 18f * k, ACCENT, true, Paint.Align.LEFT, maxW)
    }

    private fun loadQr(f: File?): Bitmap? {
        if (f == null || !f.exists()) {
            qrSrc = null
            qrScaled = null
            qrStamp = 0L
            return null
        }
        val stamp = f.lastModified() xor f.length()
        if (stamp != qrStamp) {
            qrSrc = BitmapFactory.decodeFile(f.path)
            qrStamp = stamp
            qrScaled = null
        }
        return qrSrc
    }

    private fun drawQr(c: Canvas, r: RectF, file: File?) {
        val k = r.width() / 318f
        card(c, r, 24f * k)
        text(c, "QR thanh toán", r.left + 16f * k, r.top + 16f * k + 20f * k * 0.85f, 20f * k, Color.WHITE, true)
        text(c, "Đưa màn hình để quét", r.left + 16f * k, r.top + 62f * k, 14f * k, MUTED)
        val panel = RectF(r.left + 16f * k, r.top + 76f * k, r.right - 16f * k, r.bottom - 16f * k)
        fill.shader = null
        fill.color = Color.WHITE
        c.drawRoundRect(panel, 18f * k, 18f * k, fill)

        val bmp = loadQr(file)
        if (bmp == null) {
            val gray = Color.rgb(90, 90, 90)
            textMid(c, "Chưa có ảnh QR", panel.centerX(), panel.centerY() - 10f * k, 20f * k, gray, true, panel.width() * 0.85f)
            textMid(c, "Chọn ảnh trong app điện thoại", panel.centerX(), panel.centerY() + 18f * k, 14f * k, gray, false, panel.width() * 0.85f)
            return
        }
        val inset = 10f * k
        val iw = panel.width() - 2 * inset
        val ih = panel.height() - 2 * inset
        val scale = minOf(iw / bmp.width, ih / bmp.height)
        val dw = maxOf(1, (bmp.width * scale).toInt())
        val dh = maxOf(1, (bmp.height * scale).toInt())
        if (qrScaled == null || qrScaledW != dw || qrScaledH != dh) {
            qrScaled = Bitmap.createScaledBitmap(bmp, dw, dh, true)
            qrScaledW = dw
            qrScaledH = dh
        }
        c.drawBitmap(qrScaled!!, panel.centerX() - dw / 2f, panel.centerY() - dh / 2f, null)
    }

    private fun drawMedia(c: Canvas, r: RectF) {
        val k = r.width() / 318f
        card(c, r, 24f * k)
        val art = RectF(r.left + 16f * k, r.top + 16f * k, r.left + 84f * k, r.top + 84f * k)
        fill.shader = LinearGradient(art.left, art.top, art.right, art.bottom, Color.rgb(255, 122, 89), Color.rgb(123, 63, 228), Shader.TileMode.CLAMP)
        c.drawRoundRect(art, 14f * k, 14f * k, fill)
        fill.shader = null

        val tx = art.right + 14f * k
        val maxW = r.right - tx - 16f * k
        text(c, "Chưa phát gì", tx, r.top + 46f * k, 18f * k, Color.WHITE, true, Paint.Align.LEFT, maxW)
        text(c, "Điều khiển app nhạc", tx, r.top + 70f * k, 15f * k, MUTED, false, Paint.Align.LEFT, maxW)

        val barY = r.top + 108f * k
        val bar = RectF(r.left + 16f * k, barY, r.right - 16f * k, barY + 5f * k)
        fill.color = LINE
        c.drawRoundRect(bar, 3f * k, 3f * k, fill)

        val cy = r.bottom - 16f * k - 28f * k
        for (i in 0 until 3) {
            val cx = r.left + r.width() * (2 * i + 1) / 6f
            val rc = RectF(cx - 28f * k, cy - 28f * k, cx + 28f * k, cy + 28f * k)
            mediaBtns[i].set(rc)
            fill.color = CHIP
            c.drawCircle(cx, cy, 28f * k, fill)
            fill.color = Color.WHITE
            when (i) {
                0 -> {
                    c.drawRect(cx - 9f * k, cy - 8f * k, cx - 6.5f * k, cy + 8f * k, fill)
                    triangle(c, cx + 9f * k, cy - 8f * k, cx + 9f * k, cy + 8f * k, cx - 5f * k, cy)
                }
                1 -> {
                    c.drawRoundRect(RectF(cx - 7f * k, cy - 8f * k, cx - 2f * k, cy + 8f * k), 1.5f * k, 1.5f * k, fill)
                    c.drawRoundRect(RectF(cx + 2f * k, cy - 8f * k, cx + 7f * k, cy + 8f * k), 1.5f * k, 1.5f * k, fill)
                }
                else -> {
                    c.drawRect(cx + 6.5f * k, cy - 8f * k, cx + 9f * k, cy + 8f * k, fill)
                    triangle(c, cx - 9f * k, cy - 8f * k, cx - 9f * k, cy + 8f * k, cx + 5f * k, cy)
                }
            }
        }
    }

    private fun triangle(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        tri.reset()
        tri.moveTo(x1, y1)
        tri.lineTo(x2, y2)
        tri.lineTo(x3, y3)
        tri.close()
        c.drawPath(tri, fill)
    }

    // ---------- cột icon ----------

    private fun drawIcons(c: Canvas, r: RectF, selected: Int) {
        val k = r.width() / 84f
        val rad = r.width() / 2f
        fill.shader = null
        fill.color = PILL
        c.drawRoundRect(r, rad, rad, fill)
        c.drawRoundRect(r, rad, rad, edge)

        val d = 60f * k
        val gap = (r.height() - ICON_COUNT * d) / (ICON_COUNT + 1)
        for (i in 0 until ICON_COUNT) {
            val cx = r.centerX()
            val cy = r.top + gap * (i + 1) + d * (i + 0.5f)
            val active = i == selected
            if (active) {
                fill.color = BLUE
                c.drawCircle(cx, cy, d / 2f, fill)
            }
            val p = iconPaths[i] ?: continue
            c.save()
            c.translate(cx - 15f * k, cy - 15f * k)
            val sc = 30f * k / 24f
            c.scale(sc, sc)
            iconStroke.color = if (active) Color.WHITE else ICON_COLORS[i]
            c.drawPath(p, iconStroke)
            c.restore()
        }
    }
}
