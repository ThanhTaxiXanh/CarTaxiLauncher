package com.cartaxi.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import java.io.File
import java.util.Calendar

data class UiState(
    val speedKmh: Int?,
    val gpsFix: Boolean,
    val batteryPct: Int,
    val qrFile: File?,
    val nowMs: Long,
    val selectedIcon: Int,
    val events: List<EventLine>,
    val mapFrame: Bitmap?,
    val mapFrameW: Int,
    val mapFrameH: Int,
    val mapsStatus: String,
    val streetName: String
)

object LauncherRenderer {
    const val ICON_COUNT = 6

    private val BG = Color.rgb(11, 16, 24)
    private val CARD = Color.rgb(21, 29, 43)
    private val PILL = Color.rgb(18, 26, 39)
    private val LINE = Color.rgb(36, 48, 68)
    private val ACCENT = Color.rgb(255, 193, 7)
    private val MUTED = Color.rgb(138, 150, 171)
    private val SOFT = Color.rgb(159, 176, 200)
    private val BLUE = Color.rgb(47, 125, 246)
    private val GREEN_DAY = Color.rgb(76, 217, 100)
    private val RED = Color.rgb(255, 77, 77)
    private val BUBBLE = Color.argb(195, 12, 18, 28)
    private val STREET = Color.argb(238, 20, 28, 66)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = LINE
    }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint().apply { isFilterBitmap = true }
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

    private val DAYS = arrayOf("Chủ nhật", "Thứ hai", "Thứ ba", "Thứ tư", "Thứ năm", "Thứ sáu", "Thứ bảy")

    private val ICON_DATA = arrayOf(
        "M3 11l9-8 9 8M5 10v10h5v-6h4v6h5V10",
        "M6 6h12a3.5 3.5 0 0 1 3.5 3.5v5a3.5 3.5 0 0 1-3.5 3.5H6a3.5 3.5 0 0 1-3.5-3.5v-5A3.5 3.5 0 0 1 6 6zM10 9.5v5l4.5-2.5z",
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM10 9l5 3-5 3z",
        "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18",
        "M5.5 4h3A1.5 1.5 0 0 1 10 5.5v3A1.5 1.5 0 0 1 8.5 10h-3A1.5 1.5 0 0 1 4 8.5v-3A1.5 1.5 0 0 1 5.5 4zM15 4h3a1.5 1.5 0 0 1 1.5 1.5v3A1.5 1.5 0 0 1 18 10h-3a1.5 1.5 0 0 1-1.5-1.5v-3A1.5 1.5 0 0 1 15 4zM5.5 13.5h3A1.5 1.5 0 0 1 10 15v3a1.5 1.5 0 0 1-1.5 1.5h-3A1.5 1.5 0 0 1 4 18v-3a1.5 1.5 0 0 1 1.5-1.5zM15 13.5h3a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-3a1.5 1.5 0 0 1-1.5-1.5v-3A1.5 1.5 0 0 1 15 13.5z",
        "M4 7h10M18 7h2M4 17h2M10 17h10M16 5a2 2 0 1 0 0 4a2 2 0 1 0 0-4M8 15a2 2 0 1 0 0 4a2 2 0 1 0 0-4"
    )
    private val ICON_COLORS = intArrayOf(
        Color.WHITE, Color.rgb(255, 90, 90), SOFT, SOFT, SOFT, SOFT
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

    private var lastMain: RectF? = null
    private var lastIcons: RectF? = null
    private var lastMapDst: RectF? = null

    private var qrStamp = 0L
    private var qrSrc: Bitmap? = null
    private var qrScaled: Bitmap? = null
    private var qrScaledW = 0
    private var qrScaledH = 0

    private var lunarKey = -1
    private var lunarText = ""
    private var lunarRed = false

    // ---------- API cho CarService ----------

    fun mapRect(): RectF? = lastMain

    fun hitIcon(x: Float, y: Float): Int {
        val r = lastIcons ?: return -1
        if (!r.contains(x, y)) return -1
        val i = ((y - r.top) / (r.height() / ICON_COUNT)).toInt()
        return i.coerceIn(0, ICON_COUNT - 1)
    }

    fun hitMapPoint(x: Float, y: Float): FloatArray? {
        val d = lastMapDst ?: return null
        if (!d.contains(x, y)) return null
        return floatArrayOf((x - d.left) / d.width(), (y - d.top) / d.height())
    }

    // ---------- bố cục tự động ngang / dọc ----------

    fun render(c: Canvas, cw: Int, ch: Int, st: UiState) {
        c.drawColor(BG)
        val pad = minOf(cw, ch) * 0.013f
        val g = pad
        val a = RectF(pad, pad, cw - pad, ch - pad)
        lastMapDst = null

        val icons: RectF
        val main: RectF
        val cal: RectF
        val qr: RectF
        val signs: RectF

        if (a.width() >= a.height() * 1.3f) {
            // Màn hình ngang: icon | bản đồ | (lịch trên, QR + biển báo dưới)
            val avail = a.width() - 2 * g
            val iconW = avail * 0.075f
            val mapW = avail * 0.44f
            icons = RectF(a.left, a.top, a.left + iconW, a.bottom)
            main = RectF(icons.right + g, a.top, icons.right + g + mapW, a.bottom)
            val rx = main.right + g
            val calH = a.height() * 0.38f
            cal = RectF(rx, a.top, a.right, a.top + calH)
            val lowTop = cal.bottom + g
            val qrW = (a.right - rx - g) * 0.6f
            qr = RectF(rx, lowTop, rx + qrW, a.bottom)
            signs = RectF(qr.right + g, lowTop, a.right, a.bottom)
        } else {
            // Màn hình dọc hoặc gần vuông
            val avail = a.width() - 2 * g
            val iconW = avail * 0.0948f
            val mainW = avail * 0.5463f
            icons = RectF(a.left, a.top, a.left + iconW, a.bottom)
            main = RectF(icons.right + g, a.top, icons.right + g + mainW, a.bottom)
            val rx = main.right + g
            val availH = a.height() - 2 * g
            val calH = availH * 0.20f
            val qrH = availH * 0.55f
            cal = RectF(rx, a.top, a.right, a.top + calH)
            qr = RectF(rx, cal.bottom + g, a.right, cal.bottom + g + qrH)
            signs = RectF(rx, qr.bottom + g, a.right, a.bottom)
        }
        lastIcons = icons
        lastMain = main

        drawMain(c, main, st)
        drawCalendar(c, cal, st)
        drawQr(c, qr, st.qrFile)
        drawSigns(c, signs)
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
        fill.color = CARD
        c.drawRoundRect(r, rad, rad, fill)
        c.drawRoundRect(r, rad, rad, edge)
    }

    private fun bubble(c: Canvas, r: RectF, color: Int = BUBBLE) {
        fill.color = color
        val rad = r.height() / 2f
        c.drawRoundRect(r, rad, rad, fill)
    }

    // ---------- thẻ bản đồ ----------

    private fun drawMain(c: Canvas, m: RectF, st: UiState) {
        val k = minOf(m.width() / 484f, m.height() / 560f)
        val rad = 28f * k
        fill.color = CARD
        c.drawRoundRect(m, rad, rad, fill)

        c.save()
        clip.reset()
        clip.addRoundRect(m, rad, rad, Path.Direction.CW)
        c.clipPath(clip)

        val f = st.mapFrame
        if (f != null && st.mapFrameW > 0 && st.mapFrameH > 0) {
            srcRect.set(0, 0, minOf(st.mapFrameW, f.width), minOf(st.mapFrameH, f.height))
            framePaint.alpha = 255
            c.drawBitmap(f, srcRect, m, framePaint)
            lastMapDst = m
        } else {
            textMid(c, "Bản đồ", m.centerX(), m.centerY() - 14f * k, 44f * k, MUTED, true)
            textMid(c, st.mapsStatus, m.centerX(), m.centerY() + 34f * k, maxOf(12f, 16f * k), ACCENT, false, m.width() * 0.85f)
        }

        drawStreet(c, m, k, st.streetName)
        drawBubbles(c, m, k, st)
        drawChips(c, m, k, st)

        c.restore()
        c.drawRoundRect(m, rad, rad, edge)
    }

    private fun drawStreet(c: Canvas, m: RectF, k: Float, name: String) {
        if (name.isEmpty()) return
        val size = maxOf(14f, 26f * k)
        val cy = m.bottom - 101f * k
        val maxW = m.width() * 0.84f
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textSize = size
        val w = minOf(tp.measureText(name), maxW)
        val r = RectF(m.centerX() - w / 2f - 18f * k, cy - size * 0.8f, m.centerX() + w / 2f + 18f * k, cy + size * 0.8f)
        bubble(c, r, STREET)
        textMid(c, name, m.centerX(), cy, size, Color.WHITE, true, maxW)
    }

    private fun drawBubbles(c: Canvas, m: RectF, k: Float, st: UiState) {
        val cal = Calendar.getInstance().apply { timeInMillis = st.nowMs }
        val hhmm = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val sec = String.format("%02d", cal.get(Calendar.SECOND))
        val padX = 18f * k

        val tSize = 54f * k
        val sSize = maxOf(12f, 22f * k)
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textSize = tSize
        val w1 = tp.measureText(hhmm)
        tp.textSize = sSize
        val w2 = tp.measureText(sec)
        val tb = RectF(m.left + 16f * k, m.top + 16f * k, m.left + 16f * k + padX * 2 + w1 + 8f * k + w2, m.top + 86f * k)
        bubble(c, tb)
        val ty = tb.centerY() + tSize * 0.36f
        text(c, hhmm, tb.left + padX, ty, tSize, Color.WHITE, true)
        text(c, sec, tb.left + padX + w1 + 8f * k, ty, sSize, ACCENT, true)

        val label = if ((st.speedKmh ?: 0) > 3) "Đang chạy" else "Đang dừng"
        val lSize = maxOf(11f, 16f * k)
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textSize = lSize
        val cw = tp.measureText(label) + 32f * k
        val chip = RectF(m.right - 16f * k - cw, tb.centerY() - 18f * k, m.right - 16f * k, tb.centerY() + 18f * k)
        bubble(c, chip)
        textMid(c, label, chip.centerX(), chip.centerY(), lSize, SOFT, true)

        val spd = (st.speedKmh ?: 0).toString()
        val nSize = 60f * k
        val uSize = maxOf(11f, 18f * k)
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textSize = nSize
        val w3 = tp.measureText(spd)
        tp.textSize = uSize
        val w4 = tp.measureText("KM/H")
        val sb = RectF(tb.left, tb.bottom + 12f * k, tb.left + padX * 2 + w3 + 10f * k + w4, tb.bottom + 12f * k + 80f * k)
        bubble(c, sb)
        val sy = sb.centerY() + nSize * 0.36f
        text(c, spd, sb.left + padX, sy, nSize, Color.WHITE, true)
        text(c, "KM/H", sb.left + padX + w3 + 10f * k, sy, uSize, MUTED, true)
    }

    private fun drawChips(c: Canvas, m: RectF, k: Float, st: UiState) {
        val left = m.left + 14f * k
        val right = m.right - 14f * k
        val bottom = m.bottom - 14f * k
        val h = 58f * k
        val gap = 8f * k
        val w = (right - left - 2 * gap) / 3f
        val items = arrayOf(
            "GPS" to (if (st.gpsFix) "Tốt" else "Đang bắt"),
            "Pin điện thoại" to (if (st.batteryPct >= 0) "${st.batteryPct}%" else "—"),
            "Android Auto" to "Đã kết nối"
        )
        val s1 = maxOf(11f, 12f * k)
        val s2 = maxOf(13f, 16f * k)
        for (i in 0 until 3) {
            val x = left + i * (w + gap)
            val rc = RectF(x, bottom - h, x + w, bottom)
            fill.color = Color.argb(224, 18, 26, 39)
            c.drawRoundRect(rc, 14f * k, 14f * k, fill)
            c.drawRoundRect(rc, 14f * k, 14f * k, edge)
            text(c, items[i].first, rc.left + 10f * k, rc.top + 22f * k, s1, MUTED, false, Paint.Align.LEFT, w - 18f * k)
            text(c, items[i].second, rc.left + 10f * k, rc.top + 46f * k, s2, Color.WHITE, true, Paint.Align.LEFT, w - 18f * k)
        }
    }

    // ---------- lịch và sự kiện ----------

    private fun drawEventLine(c: Canvas, ev: EventLine, x: Float, y: Float, maxW: Float, size: Float) {
        val sep = if (ev.countdown.isEmpty()) "" else " - "
        tp.typeface = Typeface.DEFAULT
        tp.textSize = size
        val w1 = tp.measureText(ev.text)
        val w2 = tp.measureText(sep)
        tp.typeface = Typeface.DEFAULT_BOLD
        val w3 = tp.measureText(ev.countdown)
        val total = w1 + w2 + w3
        val s = if (total > maxW) size * maxW / total else size
        val f = s / size

        tp.textAlign = Paint.Align.LEFT
        tp.textSize = s
        tp.typeface = Typeface.DEFAULT
        tp.color = if (ev.red) RED else Color.WHITE
        c.drawText(ev.text, x, y, tp)
        if (ev.countdown.isNotEmpty()) {
            tp.color = Color.WHITE
            c.drawText(sep, x + w1 * f, y, tp)
            tp.typeface = Typeface.DEFAULT_BOLD
            tp.color = if (ev.urgent) RED else ACCENT
            c.drawText(ev.countdown, x + (w1 + w2) * f, y, tp)
        }
    }

    private fun drawCalendar(c: Canvas, r: RectF, st: UiState) {
        val k = minOf(r.width() / 318f, r.height() / 152f)
        card(c, r, 24f * k)
        val cal = Calendar.getInstance().apply { timeInMillis = st.nowMs }
        val dd = cal.get(Calendar.DAY_OF_MONTH)
        val mm = cal.get(Calendar.MONTH) + 1
        val yy = cal.get(Calendar.YEAR)
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val key = yy * 10000 + mm * 100 + dd
        if (key != lunarKey) {
            val l = LunarCalendar.solarToLunar(dd, mm, yy)
            val leap = if (l.leap) " nhuận" else ""
            lunarText = "ÂL: ${l.day}/${l.month}$leap, ${LunarCalendar.canChi(l.year)}"
            lunarRed = l.day == 1 || l.day == 15
            lunarKey = key
        }
        val dayColor = when (dow) {
            Calendar.SATURDAY -> GREEN_DAY
            Calendar.SUNDAY -> RED
            else -> Color.WHITE
        }
        val hasEv = st.events.isNotEmpty()
        val y0 = if (hasEv) r.top + 14f * k else r.centerY() - 42f * k

        val dayStr = dd.toString()
        val dx = r.left + 18f * k
        text(c, dayStr, dx, y0 + 58f * k, 66f * k, dayColor, true)
        val dw = tp.measureText(dayStr)
        val tx = dx + dw + 14f * k
        val maxW = r.right - tx - 12f * k
        text(c, DAYS[dow - 1], tx, y0 + 24f * k, maxOf(13f, 22f * k), dayColor, true, Paint.Align.LEFT, maxW)
        text(c, "Tháng $mm, $yy", tx, y0 + 46f * k, maxOf(12f, 16f * k), Color.WHITE, false, Paint.Align.LEFT, maxW)
        text(c, lunarText, tx, y0 + 68f * k, maxOf(12f, 17f * k), if (lunarRed) RED else ACCENT, true, Paint.Align.LEFT, maxW)

        if (hasEv) {
            linePaint.color = LINE
            linePaint.strokeWidth = 1.5f
            c.drawLine(r.left + 16f * k, r.top + 92f * k, r.right - 16f * k, r.top + 92f * k, linePaint)
            var y = r.top + 116f * k
            for (ev in st.events.take(2)) {
                drawEventLine(c, ev, r.left + 16f * k, y, r.width() - 32f * k, maxOf(12f, 14f * k))
                y += 24f * k
            }
        }
    }

    // ---------- QR ----------

    private fun trimWhite(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val p = px[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r < 235 || g < 235 || b < 235) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return src
        val m = (minOf(w, h) * 0.02f).toInt()
        val l = maxOf(0, minX - m)
        val t = maxOf(0, minY - m)
        val rr = minOf(w, maxX + m + 1)
        val bb = minOf(h, maxY + m + 1)
        return Bitmap.createBitmap(src, l, t, rr - l, bb - t)
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
            qrSrc = BitmapFactory.decodeFile(f.path)?.let { trimWhite(it) }
            qrStamp = stamp
            qrScaled = null
        }
        return qrSrc
    }

    private fun drawQr(c: Canvas, r: RectF, file: File?) {
        val k = minOf(r.width() / 318f, 1.2f)
        card(c, r, 24f * k)
        val margin = maxOf(6f, 10f * k)
        val panel = RectF(r.left + margin, r.top + margin, r.right - margin, r.bottom - margin)
        fill.color = Color.WHITE
        c.drawRoundRect(panel, 18f * k, 18f * k, fill)

        val bmp = loadQr(file)
        if (bmp == null) {
            val gray = Color.rgb(90, 90, 90)
            textMid(c, "Chưa có ảnh QR", panel.centerX(), panel.centerY() - 10f * k, maxOf(13f, 20f * k), gray, true, panel.width() * 0.85f)
            textMid(c, "Chọn ảnh trong app điện thoại", panel.centerX(), panel.centerY() + 18f * k, maxOf(11f, 14f * k), gray, false, panel.width() * 0.85f)
            return
        }
        val inset = maxOf(4f, 8f * k)
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

    // ---------- khung chờ cho biển báo ----------

    private fun drawSigns(c: Canvas, r: RectF) {
        val k = minOf(r.width() / 318f, r.height() / 223f, 1.2f)
        card(c, r, 24f * k)
        val pad = maxOf(8f, 16f * k)
        text(c, "Biển báo & giao thông", r.left + pad, r.top + pad + maxOf(12f, 17f * k), maxOf(12f, 17f * k), Color.WHITE, true, Paint.Align.LEFT, r.width() - 2 * pad)
        text(c, "Sẽ có ở bản cập nhật sau", r.left + pad, r.top + pad + maxOf(12f, 17f * k) + maxOf(14f, 20f * k), maxOf(11f, 13f * k), MUTED, false, Paint.Align.LEFT, r.width() - 2 * pad)
        val cy = r.top + r.height() * 0.70f
        val rad = maxOf(12f, 26f * k)
        linePaint.strokeWidth = maxOf(3f, 6f * k)
        linePaint.color = Color.argb(70, 214, 69, 65)
        for (i in 0 until 3) {
            val cx = r.left + r.width() * (2 * i + 1) / 6f
            c.drawCircle(cx, cy, rad, linePaint)
        }
    }

    // ---------- cột icon ----------

    private fun drawIcons(c: Canvas, r: RectF, selected: Int) {
        val k = minOf(r.width() / 84f, r.height() / (ICON_COUNT * 66f))
        val rad = r.width() / 2f
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
