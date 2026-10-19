#!/usr/bin/env python3
"""Chạy ở thư mục gốc dự án CarTaxiLauncher:  python3 apply_signs.py
Sao chép RoadSigns.kt vào app/src/main/java/com/cartaxi/launcher/ rồi sửa
LauncherRenderer.kt, NativeMap.kt, CarService.kt. Mỗi chỗ sửa được kiểm tra
phải khớp đúng 1 lần; nếu không khớp, script dừng và không ghi file nào."""
import os
import shutil
import sys

SRC = "app/src/main/java/com/cartaxi/launcher/"


def read(name):
    with open(SRC + name, encoding="utf-8") as f:
        return f.read()


def rep(text, old, new, label):
    n = text.count(old)
    if n != 1:
        sys.exit(f"LỖI: '{label}' khớp {n} lần (cần đúng 1). Chưa ghi gì.")
    return text.replace(old, new)


# ======================= LauncherRenderer.kt =======================
r = read("LauncherRenderer.kt")

r = rep(r, """    val mapsStatus: String,
    val streetName: String
)""", """    val mapsStatus: String,
    val streetName: String,
    val signs: SignsState = SignsState.EMPTY
)""", "UiState")

r = rep(r, "        drawSigns(c, signs)\n", "        drawSigns(c, signs, st)\n", "gọi drawSigns")

# tốc độ chuyển đỏ khi vượt giới hạn + biển giới hạn cạnh ô tốc độ
r = rep(r, """        val sy = sb.centerY() + nSize * 0.36f
        text(c, spd, sb.left + padX, sy, nSize, Color.WHITE, true)
        text(c, "KM/H", sb.left + padX + w3 + 10f * k, sy, uSize, MUTED, true)
    }""", """        val sy = sb.centerY() + nSize * 0.36f
        val lim = st.signs.limit
        val over = lim != null && (st.speedKmh ?: 0) > lim
        text(c, spd, sb.left + padX, sy, nSize, if (over) RED else Color.WHITE, true)
        text(c, "KM/H", sb.left + padX + w3 + 10f * k, sy, uSize, MUTED, true)
        if (lim != null) {
            val sr = sb.height() / 2f
            speedSign(c, sb.right + 12f * k + sr, sb.centerY(), sr, lim, st.signs.estimated, over)
        }
    }""", "ô tốc độ")

NEW_SIGNS = '''    // ---------- biển báo & giới hạn tốc độ ----------

    private val oct = Path()
    private val tmp = RectF()

    private fun speedSign(c: Canvas, cx: Float, cy: Float, r: Float, value: Int?, est: Boolean, over: Boolean) {
        fill.color = Color.WHITE
        c.drawCircle(cx, cy, r, fill)
        val ring = r * 0.2f
        linePaint.strokeWidth = ring
        linePaint.color = if (value == null) Color.rgb(150, 150, 150) else Color.rgb(214, 38, 38)
        c.drawCircle(cx, cy, r - ring / 2f, linePaint)
        val s = value?.toString() ?: "--"
        val size = if (s.length >= 3) r * 0.8f else r * 1.0f
        val shift = if (est && value != null) r * 0.08f else 0f
        textMid(c, s, cx, cy - shift, size, if (over) RED else Color.BLACK, true, r * 1.1f)
        if (est && value != null) {
            textMid(c, "~", cx, cy + r * 0.55f, r * 0.42f, Color.rgb(110, 110, 110), true)
        }
    }

    private fun signGlyph(c: Canvas, cx: Float, cy: Float, r: Float, s: SignItem) {
        when (s.type) {
            "speed" -> speedSign(c, cx, cy, r, s.value, false, false)
            "stop" -> {
                oct.reset()
                for (i in 0 until 8) {
                    val a = Math.toRadians(22.5 + 45.0 * i)
                    val x = cx + (r * Math.cos(a)).toFloat()
                    val y = cy + (r * Math.sin(a)).toFloat()
                    if (i == 0) oct.moveTo(x, y) else oct.lineTo(x, y)
                }
                oct.close()
                fill.color = Color.rgb(200, 30, 30)
                c.drawPath(oct, fill)
                textMid(c, "STOP", cx, cy, r * 0.52f, Color.WHITE, true, r * 1.6f)
            }
            "yield" -> {
                tri.reset()
                tri.moveTo(cx - r, cy - r * 0.8f)
                tri.lineTo(cx + r, cy - r * 0.8f)
                tri.lineTo(cx, cy + r * 0.95f)
                tri.close()
                fill.color = Color.rgb(214, 38, 38)
                c.drawPath(tri, fill)
                tri.reset()
                tri.moveTo(cx - r * 0.5f, cy - r * 0.4f)
                tri.lineTo(cx + r * 0.5f, cy - r * 0.4f)
                tri.lineTo(cx, cy + r * 0.5f)
                tri.close()
                fill.color = Color.WHITE
                c.drawPath(tri, fill)
            }
            "signals" -> {
                tmp.set(cx - r * 0.55f, cy - r, cx + r * 0.55f, cy + r)
                fill.color = Color.rgb(30, 34, 40)
                c.drawRoundRect(tmp, r * 0.25f, r * 0.25f, fill)
                fill.color = RED
                c.drawCircle(cx, cy - r * 0.6f, r * 0.22f, fill)
                fill.color = ACCENT
                c.drawCircle(cx, cy, r * 0.22f, fill)
                fill.color = GREEN_DAY
                c.drawCircle(cx, cy + r * 0.6f, r * 0.22f, fill)
            }
            "camera" -> {
                fill.color = Color.rgb(123, 31, 162)
                tmp.set(cx - r * 0.4f, cy - r * 0.85f, cx + r * 0.4f, cy - r * 0.45f)
                c.drawRoundRect(tmp, r * 0.12f, r * 0.12f, fill)
                tmp.set(cx - r, cy - r * 0.6f, cx + r, cy + r * 0.7f)
                c.drawRoundRect(tmp, r * 0.2f, r * 0.2f, fill)
                fill.color = Color.WHITE
                c.drawCircle(cx, cy + r * 0.05f, r * 0.4f, fill)
                fill.color = Color.rgb(40, 20, 60)
                c.drawCircle(cx, cy + r * 0.05f, r * 0.2f, fill)
            }
            "bump" -> {
                tri.reset()
                tri.moveTo(cx, cy - r)
                tri.lineTo(cx + r, cy + r * 0.85f)
                tri.lineTo(cx - r, cy + r * 0.85f)
                tri.close()
                fill.color = Color.rgb(214, 38, 38)
                c.drawPath(tri, fill)
                tri.reset()
                tri.moveTo(cx, cy - r * 0.55f)
                tri.lineTo(cx + r * 0.62f, cy + r * 0.6f)
                tri.lineTo(cx - r * 0.62f, cy + r * 0.6f)
                tri.close()
                fill.color = Color.rgb(255, 214, 0)
                c.drawPath(tri, fill)
                textMid(c, "!", cx, cy + r * 0.2f, r * 0.8f, Color.BLACK, true)
            }
            else -> {
                fill.color = BLUE
                c.drawCircle(cx, cy, r, fill)
                textMid(c, "!", cx, cy, r * 1.1f, Color.WHITE, true)
            }
        }
    }

    private fun fmtDist(m: Int): String = if (m >= 100) "${m / 10 * 10} m" else "$m m"

    private fun drawSigns(c: Canvas, r: RectF, st: UiState) {
        val sg = st.signs
        val k = minOf(r.width() / 318f, r.height() / 223f, 1.2f)
        card(c, r, 24f * k)
        val pad = maxOf(8f, 14f * k)
        val inner = RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
        val lim = sg.limit
        val over = lim != null && (st.speedKmh ?: 0) > lim
        val fs = maxOf(11f, 15f * k)
        val sub = when {
            over -> "Đang vượt tốc độ"
            lim == null -> sg.status
            sg.estimated -> "Ước tính theo loại đường"
            else -> "Theo dữ liệu bản đồ"
        }
        val subColor = if (over) RED else MUTED
        val wide = r.width() >= r.height() * 1.25f
        val listTop: Float

        if (wide) {
            val sr = minOf(inner.height() * 0.30f, inner.width() * 0.16f)
            val cx = inner.left + sr
            val cy = inner.top + sr
            speedSign(c, cx, cy, sr, lim, sg.estimated, over)
            val tx = cx + sr + pad
            val mw = inner.right - tx
            text(c, "Giới hạn tốc độ", tx, cy - sr * 0.1f, fs * 1.1f, Color.WHITE, true, Paint.Align.LEFT, mw)
            text(c, sub, tx, cy + sr * 0.4f, fs * 0.85f, subColor, false, Paint.Align.LEFT, mw)
            listTop = cy + sr + pad
        } else {
            val sr = minOf(inner.width() * 0.24f, inner.height() * 0.2f)
            val cx = inner.centerX()
            val cy = inner.top + sr
            speedSign(c, cx, cy, sr, lim, sg.estimated, over)
            textMid(c, sub, cx, cy + sr + fs * 1.0f, fs * 0.85f, subColor, false, inner.width())
            listTop = cy + sr + fs * 2.0f
        }

        val rowH = maxOf(24f, 34f * k)
        val rows = minOf(3, ((inner.bottom - listTop) / rowH).toInt())
        if (rows <= 0) return
        if (sg.upcoming.isEmpty()) {
            textMid(c, "Chưa có biển báo phía trước", inner.centerX(), listTop + rowH / 2f, fs * 0.9f, MUTED, false, inner.width())
            return
        }
        val gr = rowH * 0.36f
        for ((i, u) in sg.upcoming.take(rows).withIndex()) {
            val cy = listTop + i * rowH + rowH / 2f
            signGlyph(c, inner.left + gr, cy, gr, u.item)
            val ds = fmtDist(u.distM)
            val dcol = if (u.distM <= 80) RED else ACCENT
            tp.typeface = Typeface.DEFAULT_BOLD
            tp.textSize = fs
            val dw = tp.measureText(ds)
            text(c, ds, inner.right, cy + fs * 0.36f, fs, dcol, true, Paint.Align.RIGHT)
            val nx = inner.left + gr * 2f + 8f * k
            text(c, u.item.name, nx, cy + fs * 0.36f, fs, Color.WHITE, false, Paint.Align.LEFT, inner.right - dw - 8f * k - nx)
        }
    }

'''

a = r.index("    // ---------- khung chờ cho biển báo ----------")
b = r.index("    // ---------- cột icon ----------")
r = r[:a] + NEW_SIGNS + r[b:]

# ======================= NativeMap.kt =======================
m = read("NativeMap.kt")

m = rep(m, "import org.maplibre.android.style.layers.BackgroundLayer\n",
        "import org.maplibre.android.style.layers.BackgroundLayer\nimport org.maplibre.android.style.layers.CircleLayer\n", "import CircleLayer")
m = rep(m, "import org.maplibre.android.style.sources.VectorSource\n",
        "import org.maplibre.android.style.sources.GeoJsonSource\nimport org.maplibre.android.style.sources.VectorSource\n", "import GeoJsonSource")
m = rep(m, "import org.maplibre.geojson.MultiLineString\n",
        "import org.maplibre.geojson.Feature\nimport org.maplibre.geojson.FeatureCollection\nimport org.maplibre.geojson.MultiLineString\n", "import Feature")

m = rep(m, """    var onFrame: (() -> Unit)? = null

    private class SnapPoint(""", """    var onFrame: (() -> Unit)? = null
    var signsState: SignsState = SignsState.EMPTY

    private val signs = RoadSigns(java.io.File(appCtx.filesDir, "signs_cache.json"))
    private var signsKey = Int.MIN_VALUE
    private val signsSrc = "ct-signs"

    private class SnapPoint(""", "fields")

m = rep(m, """        if (nowMs - lastStreetMs > 2000L) {
            lastStreetMs = nowMs
            refreshStreet(nowMs)
        }
    }
""", """        if (nowMs - lastStreetMs > 2000L) {
            lastStreetMs = nowMs
            refreshStreet(nowMs)
        }
        updateSigns()
    }

    // ---------- biển báo & giới hạn tốc độ ----------

    private fun signColor(type: String): String = when (type) {
        "speed" -> "#d62626"
        "camera" -> "#7b1fa2"
        "stop" -> "#c62828"
        "yield" -> "#ef6c00"
        "signals" -> "#2e7d32"
        "bump" -> "#ef8f00"
        else -> "#1565c0"
    }

    private fun updateSigns() {
        if (!hasFix) return
        try {
            signs.request(tLat, tLon)
            signsState = signs.state(tLat, tLon, tHeading, moving)
            val near = signs.nearby(tLat, tLon, 600.0, 60)
            var key = near.size
            for (s in near) key = key * 31 + (s.lat.hashCode() xor s.lon.hashCode())
            if (key == signsKey) return
            val st = map?.style ?: return
            val src = st.getSourceAs<GeoJsonSource>(signsSrc) ?: return
            val feats = near.map { s ->
                Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply {
                    addStringProperty("label", s.label)
                    addStringProperty("color", signColor(s.type))
                }
            }
            src.setGeoJson(FeatureCollection.fromFeatures(feats))
            signsKey = key
        } catch (t: Throwable) {
        }
    }

    private fun installSigns(style: Style) {
        try {
            signsKey = Int.MIN_VALUE
            style.addSource(GeoJsonSource(signsSrc))
            style.addLayer(
                CircleLayer("ct-signs-circle", signsSrc).withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleRadius(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(14, 8f), Expression.stop(17, 15f), Expression.stop(20, 26f)
                        )
                    ),
                    PropertyFactory.circleStrokeColor("#ffffff"),
                    PropertyFactory.circleStrokeWidth(2.5f)
                )
            )
            style.addLayer(
                SymbolLayer("ct-signs-label", signsSrc).withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(14, 8f), Expression.stop(17, 12f), Expression.stop(20, 18f)
                        )
                    ),
                    PropertyFactory.textColor("#ffffff"),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                    PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT)
                )
            )
        } catch (t: Throwable) {
        }
    }
""", "update()")

m = rep(m, """            tuneLabels(style)
            status = "Bản đồ đã tải\"""", """            tuneLabels(style)
            installSigns(style)
            status = "Bản đồ đã tải\"""", "loadStyle")

# ======================= CarService.kt =======================
cs = read("CarService.kt")
cs = rep(cs, """                mapsStatus = maps.status,
                streetName = maps.streetName
            )""", """                mapsStatus = maps.status,
                streetName = maps.streetName,
                signs = maps.signsState
            )""", "draw()")

# ---------- ghi file (chỉ khi mọi chỗ sửa đều khớp) ----------
here = os.path.dirname(os.path.abspath(__file__))
shutil.copyfile(os.path.join(here, "RoadSigns.kt"), SRC + "RoadSigns.kt")
for name, txt in (("LauncherRenderer.kt", r), ("NativeMap.kt", m), ("CarService.kt", cs)):
    with open(SRC + name, "w", encoding="utf-8") as f:
        f.write(txt)
print("Xong: đã thêm RoadSigns.kt và sửa LauncherRenderer.kt, NativeMap.kt, CarService.kt")
