package com.cartaxi.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Calendar

data class UiState(val speedKmh: Int?, val qrText: String, val nowMs: Long)

object LauncherRenderer {
    private val BG = Color.rgb(12, 16, 24)
    private val CARD = Color.rgb(28, 36, 50)
    private val STROKE = Color.rgb(60, 72, 92)
    private val ACCENT = Color.rgb(255, 193, 7)
    private val MUTED = Color.rgb(140, 150, 170)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = STROKE
    }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint().apply { isFilterBitmap = false }

    private val DAYS = arrayOf("Chủ nhật", "Thứ hai", "Thứ ba", "Thứ tư", "Thứ năm", "Thứ sáu", "Thứ bảy")

    private class Layout(
        val time: RectF, val lunar: RectF, val maps: RectF,
        val qr: RectF, val media: RectF, val icons: RectF
    )

    private var qrKey: String? = null
    private var qrBmp: Bitmap? = null

    private var lunarKey = -1
    private var lunarText = ""

    fun render(c: Canvas, cw: Int, ch: Int, stable: Rect?, st: UiState) {
        c.drawColor(BG)
        val base = if (stable != null && stable.width() > 100 && stable.height() > 100) stable else Rect(0, 0, cw, ch)
        val pad = minOf(base.width(), base.height()) * 0.02f
        val area = RectF(base.left + pad, base.top + pad, base.right - pad, base.bottom - pad)
        val g = pad
        val lay = if (area.width() < area.height() * 1.25f) portrait(area, g) else landscape(area, g)

        drawTime(c, lay.time, st)
        drawLunar(c, lay.lunar, st.nowMs)
        drawPlaceholder(c, lay.maps, "Maps")
        drawQr(c, lay.qr, st.qrText)
        drawPlaceholder(c, lay.media, "Media")
        drawIcons(c, lay.icons)

        tp.color = ACCENT
        tp.typeface = Typeface.DEFAULT
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = 24f
        c.drawText("${cw}x${ch}  vung ${base.width()}x${base.height()}", area.left + 10f, area.bottom - 10f, tp)
    }

    private fun portrait(a: RectF, g: Float): Layout {
        val colSplit = a.left + a.width() * 0.58f
        val rowTop = a.top + a.height() * 0.19f
        val iconTop = a.top + a.height() * 0.90f
        val qrBottom = rowTop + (a.bottom - rowTop) * 0.62f
        return Layout(
            time = RectF(colSplit + g / 2, a.top, a.right, rowTop - g / 2),
            lunar = RectF(a.left, a.top, colSplit - g / 2, rowTop - g / 2),
            maps = RectF(a.left, rowTop + g / 2, colSplit - g / 2, iconTop - g / 2),
            icons = RectF(a.left, iconTop + g / 2, colSplit - g / 2, a.bottom),
            qr = RectF(colSplit + g / 2, rowTop + g / 2, a.right, qrBottom - g / 2),
            media = RectF(colSplit + g / 2, qrBottom + g / 2, a.right, a.bottom)
        )
    }

    private fun landscape(a: RectF, g: Float): Layout {
        val iconW = a.width() * 0.07f
        val rightLeft = a.right - a.width() * 0.30f
        val midLeft = a.left + iconW + g / 2
        val midRight = rightLeft - g / 2
        val midMid = (midLeft + midRight) / 2
        val rowTop = a.top + a.height() * 0.22f
        val qrBottom = a.top + a.height() * 0.75f
        return Layout(
            time = RectF(midLeft, a.top, midMid - g / 2, rowTop - g / 2),
            lunar = RectF(midMid + g / 2, a.top, midRight, rowTop - g / 2),
            maps = RectF(midLeft, rowTop + g / 2, midRight, a.bottom),
            icons = RectF(a.left, a.top, a.left + iconW - g / 2, a.bottom),
            qr = RectF(rightLeft, a.top, a.right, qrBottom - g / 2),
            media = RectF(rightLeft, qrBottom + g / 2, a.right, a.bottom)
        )
    }

    private fun card(c: Canvas, r: RectF) {
        val rad = minOf(r.width(), r.height()) * 0.08f
        fill.color = CARD
        c.drawRoundRect(r, rad, rad, fill)
        c.drawRoundRect(r, rad, rad, edge)
    }

    private fun drawFit(
        c: Canvas, text: String, cx: Float, cy: Float,
        maxW: Float, maxH: Float, color: Int, bold: Boolean = false
    ) {
        tp.color = color
        tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = maxH
        val w = tp.measureText(text)
        if (w > maxW) tp.textSize = maxH * maxW / w
        val fm = tp.fontMetrics
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
    }

    private fun drawPlaceholder(c: Canvas, r: RectF, label: String) {
        card(c, r)
        drawFit(c, label, r.centerX(), r.centerY(), r.width() * 0.8f, r.height() * 0.25f, MUTED, true)
    }

    private fun drawTime(c: Canvas, r: RectF, st: UiState) {
        card(c, r)
        val cal = Calendar.getInstance().apply { timeInMillis = st.nowMs }
        val hhmm = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val speed = if (st.speedKmh == null) "-- km/h" else "${st.speedKmh} km/h"
        val h = r.height()
        drawFit(c, hhmm, r.centerX(), r.top + h * 0.36f, r.width() * 0.9f, h * 0.50f, Color.WHITE, true)
        drawFit(c, speed, r.centerX(), r.top + h * 0.80f, r.width() * 0.9f, h * 0.28f, ACCENT)
    }

    private fun drawLunar(c: Canvas, r: RectF, nowMs: Long) {
        card(c, r)
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
        val line1 = "${DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]}, $dd/$mm/$yy"
        val h = r.height()
        drawFit(c, line1, r.centerX(), r.top + h * 0.32f, r.width() * 0.92f, h * 0.30f, Color.WHITE, true)
        drawFit(c, lunarText, r.centerX(), r.top + h * 0.72f, r.width() * 0.92f, h * 0.28f, ACCENT)
    }

    private fun qrBitmap(text: String): Bitmap? {
        if (text == qrKey) return qrBmp
        qrKey = text
        qrBmp = try {
            val hints = mapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 1)
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300, hints)
            val px = IntArray(m.width * m.height)
            for (y in 0 until m.height) {
                for (x in 0 until m.width) {
                    px[y * m.width + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        }
        return qrBmp
    }

    private fun drawQr(c: Canvas, r: RectF, text: String) {
        card(c, r)
        val bmp = if (text.isBlank()) null else qrBitmap(text)
        if (bmp == null) {
            drawFit(c, "Mã QR", r.centerX(), r.centerY(), r.width() * 0.8f, r.height() * 0.15f, MUTED, true)
            drawFit(c, "(nhập trong app điện thoại)", r.centerX(), r.centerY() + r.height() * 0.12f, r.width() * 0.85f, r.height() * 0.06f, MUTED)
            return
        }
        val side = minOf(r.width(), r.height()) * 0.92f
        val dst = RectF(r.centerX() - side / 2, r.centerY() - side / 2, r.centerX() + side / 2, r.centerY() + side / 2)
        c.drawBitmap(bmp, null, dst, bmpPaint)
    }

    private fun drawIcons(c: Canvas, r: RectF) {
        card(c, r)
        val n = 5
        val horizontal = r.width() >= r.height()
        val slot = if (horizontal) r.width() / n else r.height() / n
        val rad = minOf(slot, if (horizontal) r.height() else r.width()) * 0.32f
        fill.color = ACCENT
        for (i in 0 until n) {
            val cx = if (horizontal) r.left + slot * (i + 0.5f) else r.centerX()
            val cy = if (horizontal) r.centerY() else r.top + slot * (i + 0.5f)
            c.drawCircle(cx, cy, rad, fill)
        }
    }
}
