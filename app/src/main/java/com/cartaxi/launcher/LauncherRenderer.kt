package com.cartaxi.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.util.Calendar

data class UiState(
    val speedKmh: Int?,
    val qrFile: File?,
    val nowMs: Long,
    val layoutMode: Int, // 0 tự động, 1 ngang, 2 dọc
    val selectedIcon: Int
)

object LauncherRenderer {
    private const val ICON_COUNT = 5

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

    private val DAYS = arrayOf("Chủ nhật", "Thứ hai", "Thứ ba", "Thứ tư", "Thứ năm", "Thứ sáu", "Thứ bảy")

    private class Layout(
        val time: RectF, val lunar: RectF, val maps: RectF,
        val qr: RectF, val media: RectF, val icons: RectF
    )

    private var lastIcons: RectF? = null

    private var qrStamp = 0L
    private var qrSrc: Bitmap? = null
    private var qrScaled: Bitmap? = null
    private var qrScaledW = 0
    private var qrScaledH = 0

    private var lunarKey = -1
    private var lunarText = ""

    fun render(c: Canvas, cw: Int, ch: Int, st: UiState) {
        c.drawColor(BG)
        val pad = minOf(cw, ch) * 0.012f
        val g = minOf(cw, ch) * 0.016f
        val area = RectF(pad, pad, cw - pad, ch - pad)
        val land = when (st.layoutMode) {
            1 -> true
            2 -> false
            else -> area.width() >= area.height() * 1.25f
        }
        val lay = if (land) landscape(area, g) else portrait(area, g)
        lastIcons = lay.icons

        drawTime(c, lay.time, st)
        drawLunar(c, lay.lunar, st.nowMs)
        drawPlaceholder(c, lay.maps, "Maps")
        drawQr(c, lay.qr, st.qrFile)
        drawPlaceholder(c, lay.media, "Media")
        drawIcons(c, lay.icons, st.selectedIcon)
    }

    fun hitIcon(x: Float, y: Float): Int {
        val r = lastIcons ?: return -1
        if (!r.contains(x, y)) return -1
        val horizontal = r.width() >= r.height()
        val slot = if (horizontal) r.width() / ICON_COUNT else r.height() / ICON_COUNT
        val i = ((if (horizontal) x - r.left else y - r.top) / slot).toInt()
        return i.coerceIn(0, ICON_COUNT - 1)
    }

    private fun portrait(a: RectF, g: Float): Layout {
        val colSplit = a.left + a.width() * 0.56f
        val rowTop = a.top + a.height() * 0.19f
        val iconTop = a.top + a.height() * 0.90f
        val qrBottom = rowTop + (a.bottom - rowTop) * 0.70f
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
        val iconW = a.width() * 0.09f
        val rightLeft = a.right - a.width() * 0.36f
        val midLeft = a.left + iconW + g / 2
        val midRight = rightLeft - g / 2
        val midMid = (midLeft + midRight) / 2
        val rowTop = a.top + a.height() * 0.20f
        val qrBottom = a.top + a.height() * 0.80f
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
        card(c, r)
        val bmp = loadQr(file)
        if (bmp == null) {
            drawFit(c, "Mã QR", r.centerX(), r.centerY(), r.width() * 0.8f, r.height() * 0.12f, MUTED, true)
            drawFit(
                c, "(chọn ảnh trong app điện thoại)", r.centerX(), r.centerY() + r.height() * 0.10f,
                r.width() * 0.85f, r.height() * 0.05f, MUTED
            )
            return
        }
        val inset = minOf(r.width(), r.height()) * 0.03f
        val iw = r.width() - 2 * inset
        val ih = r.height() - 2 * inset
        val scale = minOf(iw / bmp.width, ih / bmp.height)
        val dw = maxOf(1, (bmp.width * scale).toInt())
        val dh = maxOf(1, (bmp.height * scale).toInt())
        if (qrScaled == null || qrScaledW != dw || qrScaledH != dh) {
            qrScaled = Bitmap.createScaledBitmap(bmp, dw, dh, true)
            qrScaledW = dw
            qrScaledH = dh
        }
        c.drawBitmap(qrScaled!!, r.centerX() - dw / 2f, r.centerY() - dh / 2f, null)
    }

    private fun drawIcons(c: Canvas, r: RectF, selected: Int) {
        card(c, r)
        val horizontal = r.width() >= r.height()
        val slot = if (horizontal) r.width() / ICON_COUNT else r.height() / ICON_COUNT
        val rad = minOf(slot, if (horizontal) r.height() else r.width()) * 0.36f
        for (i in 0 until ICON_COUNT) {
            val cx = if (horizontal) r.left + slot * (i + 0.5f) else r.centerX()
            val cy = if (horizontal) r.centerY() else r.top + slot * (i + 0.5f)
            fill.color = if (i == selected) Color.WHITE else ACCENT
            c.drawCircle(cx, cy, rad, fill)
        }
    }
}
