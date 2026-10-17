package com.cartaxi.launcher

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import android.graphics.Point as APoint

class NativeMap(private val appCtx: Context, private val main: Handler) {
    companion object {
        const val STYLE = "https://tiles.openfreemap.org/styles/liberty"
        const val DPI = 260
        private val ARROW = arrayOf(
            doubleArrayOf(0.0, 12.0), doubleArrayOf(8.0, -9.0),
            doubleArrayOf(0.0, -4.5), doubleArrayOf(-8.0, -9.0)
        )
        private val ROAD_KEYS = arrayOf(
            "road", "highway", "street", "motorway", "trunk", "primary", "secondary",
            "tertiary", "minor", "service", "bridge", "tunnel", "link", "path", "pedestrian", "track"
        )
        private val SKIP_CLASS = setOf("rail", "transit", "ferry", "aerialway", "path", "pedestrian", "track", "pier")
    }

    var status = "Đang chờ"
    var frame: Bitmap? = null
    var frameW = 0
    var frameH = 0
    var streetName = ""
    var onFrame: (() -> Unit)? = null

    private class SnapPoint(val lat: Double, val lon: Double, val heading: Double)

    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var pres: MapPresentation? = null
    private var map: MapLibreMap? = null
    private var blocked = false

    // camera / người dùng
    private var tilt = 60.0
    private var following = true
    private var lastTouchMs = 0L
    private var userZoom: Double? = null
    private var userZoomMs = 0L
    private var manualZoom = 17.0
    private var tiltBtn: TextView? = null

    // vị trí
    private var hasFix = false
    private var snapped = false
    private var moving = false
    private var speedMs = 0f
    private var smKmh = 0.0
    private var fixMs = 0L
    private var lastRawLat = 0.0
    private var lastRawLon = 0.0
    private var tLat = 21.0285
    private var tLon = 105.8542
    private var tHeading = 0.0
    private var dLat = 21.0285
    private var dLon = 105.8542
    private var dHeading = 0.0
    private var dZoom = 18.0
    private var lastFrameMs = 0L

    // đường để hút vào làn
    private val segs = ArrayList<DoubleArray>()
    private var segCenterLat = 0.0
    private var segCenterLon = 0.0
    private var segMs = 0L

    // ngày / đêm, tên đường
    private var night = false
    private var dnInit = false
    private var lastStreetMs = 0L
    private var lastStreetSeen = 0L

    private val loop = object : Runnable {
        override fun run() {
            try {
                step()
            } catch (t: Throwable) {
            }
            main.postDelayed(this, 33L)
        }
    }

    fun running(): Boolean = vd != null

    // ---------- vòng đời ----------

    fun start(w: Int, h: Int) {
        if (vd != null || blocked) return
        val vw = maxOf(64, w / 2 * 2)
        val vh = maxOf(64, h / 2 * 2)
        try {
            update(System.currentTimeMillis())
            MapLibre.getInstance(appCtx)
            val r = ImageReader.newInstance(vw, vh, PixelFormat.RGBA_8888, 2)
            r.setOnImageAvailableListener({ rd -> onImage(rd) }, main)
            val dm = appCtx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            val d = dm.createVirtualDisplay("CarTaxiMap", vw, vh, DPI, r.surface, flags)
            if (d == null) {
                r.close()
                status = "Không tạo được màn hình ảo"
                blocked = true
                return
            }
            reader = r
            vd = d
            frameW = vw
            frameH = vh
            following = true
            status = "Đang tải bản đồ..."
            val p = MapPresentation(appCtx, d.display)
            pres = p
            p.show()
            p.mapView?.onStart()
            p.mapView?.onResume()
        } catch (t: Throwable) {
            status = "Lỗi bản đồ: " + t.message
            blocked = true
            stop()
        }
    }

    fun stop() {
        main.removeCallbacks(loop)
        try {
            pres?.mapView?.let {
                it.onPause()
                it.onStop()
                it.onDestroy()
            }
        } catch (t: Throwable) {
        }
        try {
            pres?.dismiss()
        } catch (t: Throwable) {
        }
        pres = null
        map = null
        try {
            vd?.release()
        } catch (t: Throwable) {
        }
        vd = null
        try {
            reader?.close()
        } catch (t: Throwable) {
        }
        reader = null
        frame = null
    }

    // ---------- khung hình ----------

    private fun onImage(rd: ImageReader) {
        val img: Image? = try {
            rd.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img == null) return
        try {
            val p = img.planes[0]
            val rw = p.rowStride / p.pixelStride
            var b = frame
            if (b == null || b.width != rw || b.height != img.height) {
                b = Bitmap.createBitmap(rw, img.height, Bitmap.Config.ARGB_8888)
                frame = b
            }
            val buf = p.buffer
            buf.rewind()
            b!!.copyPixelsFromBuffer(buf)
        } catch (e: Exception) {
            status = "Lỗi khung hình: " + e.message
        } finally {
            img.close()
        }
        onFrame?.invoke()
    }

    // ---------- cảm ứng từ Android Auto ----------

    fun tap(nx: Float, ny: Float) {
        val root = pres?.root ?: return
        val x = nx * frameW
        val y = ny * frameH
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        root.dispatchTouchEvent(down)
        down.recycle()
        main.postDelayed({
            val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            root.dispatchTouchEvent(up)
            up.recycle()
        }, 60)
    }

    private fun leaveFollow() {
        if (following) {
            following = false
            manualZoom = dZoom
        }
        lastTouchMs = SystemClock.elapsedRealtime()
    }

    
private fun moveCameraByPixels(
    dx: Float,
    dy: Float,
    animate: Boolean = false
) {
    val m = map ?: return
    val position = m.cameraPosition
    val target = position.target ?: return

    // Ước lượng khoảng cách mặt đất tương ứng với một pixel.
    val latRad = Math.toRadians(target.latitude)
    val metersPerPixel =
        156543.03392 * cos(latRad) / 2.0.pow(position.zoom)

    val metersPerDegree = 111320.0
    val cosLat = cos(latRad).coerceAtLeast(0.01)

    val newLat = (
        target.latitude + dy * metersPerPixel / metersPerDegree
    ).coerceIn(-85.0, 85.0)

    val newLon =
        target.longitude -
        dx * metersPerPixel / (metersPerDegree * cosLat)

    val update = CameraUpdateFactory.newCameraPosition(
        CameraPosition.Builder(position)
            .target(LatLng(newLat, newLon))
            .build()
    )

    if (animate) {
        m.animateCamera(update, 400)
    } else {
        m.moveCamera(update)
    }
}

fun scrollBy(dx: Float, dy: Float) {
    if (map == null) return
    leaveFollow()
    moveCameraByPixels(dx, dy)
}

fun fling(vx: Float, vy: Float) {
    if (map == null) return
    leaveFollow()
    moveCameraByPixels(
        -vx * 0.12f,
        -vy * 0.12f,
        animate = true
    )
}


    fun pinch(scale: Float, nx: Float, ny: Float) {
        val m = map ?: return
        leaveFollow()
        val dz = ln(scale.toDouble()) / ln(2.0)
        m.moveCamera(CameraUpdateFactory.zoomBy(dz, APoint((nx * frameW).toInt(), (ny * frameH).toInt())))
        manualZoom = m.cameraPosition.zoom
    }

    private fun recenter() {
        following = true
        userZoom = null
    }

    private fun toggleTilt() {
        tilt = if (tilt > 0.0) 0.0 else 60.0
        tiltBtn?.text = if (tilt > 0.0) "3D" else "2D"
    }

    private fun zoomButton(delta: Double) {
        val now = SystemClock.elapsedRealtime()
        if (following) {
            val base = userZoom ?: dZoom
            userZoom = (base + delta).coerceIn(3.0, 20.0)
            userZoomMs = now
        } else {
            val m = map ?: return
            m.animateCamera(CameraUpdateFactory.zoomBy(delta), 250)
            manualZoom = (manualZoom + delta).coerceIn(3.0, 20.0)
            lastTouchMs = now
        }
    }

    // ---------- GPS ----------

    private fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dx = (lon2 - lon1) * 111320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        val dy = (lat2 - lat1) * 111320.0
        return sqrt(dx * dx + dy * dy)
    }

    fun onLocation(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        val sp = if (loc.hasSpeed()) loc.speed else 0f
        if (hasFix) {
            if (loc.hasAccuracy() && loc.accuracy > 60f) return
            val d = dist(lastRawLat, lastRawLon, loc.latitude, loc.longitude)
            val dt = (now - fixMs) / 1000.0
            if (dt > 0.0 && dt < 4.0 && d / dt > 70.0) return
        }
        lastRawLat = loc.latitude
        lastRawLon = loc.longitude
        moving = if (moving) sp > 0.9f else sp > 2.0f
        speedMs = if (moving) sp else 0f
        val hd = if (loc.hasBearing() && moving) loc.bearing.toDouble() else Double.NaN
        if (!hasFix) {
            hasFix = true
            val s = snap(loc.latitude, loc.longitude, hd)
            snapped = s != null
            setTarget(s ?: SnapPoint(loc.latitude, loc.longitude, if (hd.isNaN()) 0.0 else hd))
            dLat = tLat
            dLon = tLon
            dHeading = tHeading
            fixMs = now
            return
        }
        fixMs = now
        if (moving) {
            val s = snap(loc.latitude, loc.longitude, if (hd.isNaN()) tHeading else hd)
            if (s != null) snapped = true
            setTarget(s ?: SnapPoint(loc.latitude, loc.longitude, if (hd.isNaN()) tHeading else hd))
        }
    }

    private fun setTarget(s: SnapPoint) {
        tLat = s.lat
        tLon = s.lon
        tHeading = s.heading
    }

    // ---------- hút vào làn đường ----------

    private fun ensureRoads(lat: Double, lon: Double) {
        val now = SystemClock.elapsedRealtime()
        if (segs.isNotEmpty() && now - segMs < 4000L && dist(lat, lon, segCenterLat, segCenterLon) < 80.0) return
        val st = map?.style ?: return
        try {
            val src = st.sources.firstOrNull { it is VectorSource } as? VectorSource ?: return
            val feats = src.querySourceFeatures(arrayOf("transportation"), null)
            segs.clear()
            for (f in feats) {
                val cls = try {
                    f.getStringProperty("class")
                } catch (e: Exception) {
                    null
                } ?: continue
                if (cls in SKIP_CLASS) continue
                val ow = try {
                    f.getNumberProperty("oneway")?.toInt() ?: 0
                } catch (e: Exception) {
                    0
                }
                val g = f.geometry()
                val lines: List<List<Point>> = when (g) {
                    is LineString -> listOf(g.coordinates())
                    is MultiLineString -> g.coordinates()
                    else -> continue
                }
                for (pts in lines) {
                    for (i in 0 until pts.size - 1) {
                        val a = pts[i]
                        val b = pts[i + 1]
                        if (dist(lat, lon, a.latitude(), a.longitude()) > 400.0) continue
                        segs.add(doubleArrayOf(a.latitude(), a.longitude(), b.latitude(), b.longitude(), ow.toDouble()))
                    }
                }
            }
            segCenterLat = lat
            segCenterLon = lon
            segMs = now
        } catch (t: Throwable) {
        }
    }

    private fun snap(lat: Double, lon: Double, hd: Double): SnapPoint? {
        ensureRoads(lat, lon)
        if (segs.isEmpty()) return null
        val kx = 111320.0 * cos(Math.toRadians(lat))
        val ky = 111320.0
        var bestScore = 1e9
        var bx = 0.0
        var by = 0.0
        var bBearing = 0.0
        var bOneway = 0.0
        var found = false
        for (s in segs) {
            val ax = (s[1] - lon) * kx
            val ay = (s[0] - lat) * ky
            val cx = (s[3] - lon) * kx
            val cy = (s[2] - lat) * ky
            val dx = cx - ax
            val dy = cy - ay
            val len2 = dx * dx + dy * dy
            if (len2 < 0.01) continue
            val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d > 25.0) continue
            val segB = Math.toDegrees(atan2(dx, dy))
            var score = d
            if (!hd.isNaN()) {
                val diff = abs(((segB - hd + 540.0) % 360.0) - 180.0)
                val a = min(diff, 180.0 - diff)
                score += 0.3 * a
                if (a > 75.0) score += 30.0
            }
            if (score < bestScore) {
                bestScore = score
                bx = px
                by = py
                bBearing = segB
                bOneway = s[4]
                found = true
            }
        }
        if (!found) return null
        val ref = if (hd.isNaN()) tHeading else hd
        var tb = bBearing
        val dd = abs(((tb - ref + 540.0) % 360.0) - 180.0)
        if (dd > 90.0) tb += 180.0
        tb = ((tb % 360.0) + 360.0) % 360.0
        if (bOneway == 0.0) {
            val br = Math.toRadians(tb + 90.0)
            bx += sin(br) * 1.6
            by += cos(br) * 1.6
        }
        return SnapPoint(lat + by / ky, lon + bx / kx, tb)
    }

    // ---------- vòng lặp 30 khung hình / giây ----------

    private fun autoZoom(kmh: Double): Double = when {
        kmh <= 8.0 -> 18.0
        kmh <= 40.0 -> 18.0 - (kmh - 8.0) / 32.0
        kmh <= 80.0 -> 17.0 - (kmh - 40.0) / 40.0 * 0.8
        else -> maxOf(15.6, 16.2 - (kmh - 80.0) / 40.0 * 0.6)
    }

    private fun step() {
        val m = map ?: return
        if (m.style == null) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.033 else ((now - lastFrameMs) / 1000.0).coerceIn(0.005, 0.2)
        lastFrameMs = now
        if (!hasFix) return

        val age = ((now - fixMs) / 1000.0).coerceAtMost(1.3)
        var pLat = tLat
        var pLon = tLon
        if (moving && speedMs > 0f) {
            val d = speedMs * age
            val br = Math.toRadians(tHeading)
            pLat += cos(br) * d / 111320.0
            pLon += sin(br) * d / (111320.0 * cos(Math.toRadians(tLat)))
        }
        val a = 1.0 - exp(-dt / 0.15)
        dLat += (pLat - dLat) * a
        dLon += (pLon - dLon) * a

        val diff = ((tHeading - dHeading + 540.0) % 360.0) - 180.0
        dHeading = (((dHeading + diff * (1.0 - exp(-dt / 0.3))) % 360.0) + 360.0) % 360.0

        smKmh += ((if (moving) speedMs * 3.6 else 0.0) - smKmh) * (1.0 - exp(-dt / 1.5))
        if (userZoom != null && now - userZoomMs > 15000L) userZoom = null
        val targetZoom = userZoom ?: autoZoom(smKmh)
        dZoom += (targetZoom - dZoom) * (1.0 - exp(-dt / 0.7))
        if (!following && now - lastTouchMs > 12000L) recenter()

        val zs = if (following) dZoom else manualZoom
        updateCar(2.0.pow(18.0 - zs).coerceIn(0.8, 5.0))
        if (following) {
            m.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(dLat, dLon))
                        .zoom(dZoom)
                        .tilt(tilt)
                        .bearing(dHeading)
                        .build()
                )
            )
        }
    }

    // ---------- ngày / đêm, tên đường ----------

    fun update(nowMs: Long) {
        val lat = if (hasFix) tLat else 21.0285
        val lon = if (hasFix) tLon else 105.8542
        val el = sunElevation(lat, lon, nowMs)
        val wantNight = if (!dnInit) el < -2.0 else if (night) el < 1.0 else el < -3.0
        dnInit = true
        if (wantNight != night) {
            night = wantNight
            if (map != null) loadStyle()
        }
        if (hasFix && !snapped) {
            val s = snap(tLat, tLon, Double.NaN)
            if (s != null) {
                setTarget(s)
                snapped = true
            }
        }
        if (nowMs - lastStreetMs > 2000L) {
            lastStreetMs = nowMs
            refreshStreet(nowMs)
        }
    }

    private fun sunElevation(lat: Double, lon: Double, nowMs: Long): Double {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        val n = cal.get(Calendar.DAY_OF_YEAR)
        val tzH = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 3600000.0
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60.0
        val b = Math.toRadians(360.0 / 364.0 * (n - 81))
        val eot = 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)
        val decl = Math.toRadians(23.45 * sin(Math.toRadians(360.0 / 365.0 * (n - 81))))
        val tst = minutes + eot + 4 * lon - 60 * tzH
        val ha = Math.toRadians(tst / 4 - 180)
        val latR = Math.toRadians(lat)
        val el = asin(sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(ha))
        return Math.toDegrees(el)
    }

    private fun segDist(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }

    private fun refreshStreet(nowMs: Long) {
        if (!hasFix) return
        val st = map?.style ?: return
        try {
            val src = st.sources.firstOrNull { it is VectorSource } as? VectorSource ?: return
            val feats = src.querySourceFeatures(arrayOf("transportation_name"), null)
            val kx = 111320.0 * cos(Math.toRadians(dLat))
            val ky = 111320.0
            var best: String? = null
            var bestD = 30.0
            for (f in feats) {
                val name = try {
                    f.getStringProperty("name")
                } catch (e: Exception) {
                    null
                } ?: continue
                val g = f.geometry()
                val lines: List<List<Point>> = when (g) {
                    is LineString -> listOf(g.coordinates())
                    is MultiLineString -> g.coordinates()
                    else -> continue
                }
                for (pts in lines) {
                    for (i in 0 until pts.size - 1) {
                        val ax = (pts[i].longitude() - dLon) * kx
                        val ay = (pts[i].latitude() - dLat) * ky
                        val bx = (pts[i + 1].longitude() - dLon) * kx
                        val by = (pts[i + 1].latitude() - dLat) * ky
                        val d = segDist(ax, ay, bx, by)
                        if (d < bestD) {
                            bestD = d
                            best = name
                        }
                    }
                }
            }
            if (best != null) {
                streetName = best
                lastStreetSeen = nowMs
            } else if (nowMs - lastStreetSeen > 8000L) {
                streetName = ""
            }
        } catch (t: Throwable) {
        }
    }

    // ---------- bản đồ ----------

    private fun onMapReady(m: MapLibreMap) {
        map = m
        m.uiSettings.isCompassEnabled = false
        m.uiSettings.isLogoEnabled = false
        m.uiSettings.isAttributionEnabled = false
        m.uiSettings.setAllGesturesEnabled(false)
        m.setPadding(0, (frameH * 0.40f).toInt(), 0, 0)
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(dLat, dLon))
                    .zoom(dZoom)
                    .tilt(tilt)
                    .bearing(dHeading)
                    .build()
            )
        )
        loadStyle()
        main.removeCallbacks(loop)
        main.post(loop)
    }

    private fun loadStyle() {
        val m = map ?: return
        m.setStyle(Style.Builder().fromUri(STYLE)) { style ->
            if (night) themeNight(style)
            addCar(style)
            status = "Bản đồ đã tải"
        }
    }

    private fun roadWidth(k: Float): Expression = Expression.interpolate(
        Expression.linear(), Expression.zoom(),
        Expression.stop(12, 1.0f * k),
        Expression.stop(14, 3.5f * k),
        Expression.stop(16, 8.5f * k),
        Expression.stop(18, 22f * k),
        Expression.stop(20, 52f * k)
    )

    private fun isRoad(id: String): Boolean {
        for (k in ROAD_KEYS) if (id.contains(k)) return true
        return false
    }

    private fun themeNight(style: Style) {
        for (layer in style.layers) {
            val id = layer.id.lowercase()
            try {
                when (layer) {
                    is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor("#0f1030"))
                    is FillLayer -> {
                        val col = when {
                            id.contains("water") -> "#0b2f73"
                            id.contains("park") || id.contains("wood") || id.contains("grass") ||
                                id.contains("forest") || id.contains("landcover") || id.contains("garden") -> "#16261f"
                            id.contains("building") -> "#232746"
                            else -> "#11133a"
                        }
                        layer.setProperties(PropertyFactory.fillColor(col))
                    }
                    is LineLayer -> {
                        val casing = id.contains("casing") || id.contains("outline")
                        if (isRoad(id) && !id.contains("rail")) {
                            if (casing) {
                                layer.setProperties(PropertyFactory.lineOpacity(0.0f))
                            } else {
                                val k = when {
                                    id.contains("motorway") || id.contains("trunk") -> 1.35f
                                    id.contains("primary") -> 1.1f
                                    id.contains("secondary") || id.contains("tertiary") -> 0.9f
                                    id.contains("path") || id.contains("pedestrian") || id.contains("track") -> 0.3f
                                    id.contains("service") -> 0.5f
                                    else -> 0.65f
                                }
                                val col = if (k >= 1.1f) "#62626c" else if (k <= 0.5f) "#3a3a46" else "#4f4f58"
                                layer.setProperties(
                                    PropertyFactory.lineColor(col),
                                    PropertyFactory.lineOpacity(1.0f),
                                    PropertyFactory.lineWidth(roadWidth(k))
                                )
                            }
                        } else {
                            val col = when {
                                id.contains("water") -> "#0b2f73"
                                id.contains("rail") || id.contains("transit") -> "#3a3c60"
                                else -> "#2a2d55"
                            }
                            layer.setProperties(PropertyFactory.lineColor(col))
                        }
                    }
                    is SymbolLayer -> layer.setProperties(
                        PropertyFactory.textColor("#ffffff"),
                        PropertyFactory.textHaloColor("#000000"),
                        PropertyFactory.textHaloWidth(1.6f),
                        PropertyFactory.textSize(
                            Expression.interpolate(
                                Expression.linear(), Expression.zoom(),
                                Expression.stop(14, 12f), Expression.stop(17, 17f), Expression.stop(19, 22f)
                            )
                        )
                    )
                    is FillExtrusionLayer -> layer.setProperties(
                        PropertyFactory.fillExtrusionColor("#434b66"),
                        PropertyFactory.fillExtrusionOpacity(0.95f)
                    )
                    else -> {
                    }
                }
            } catch (t: Throwable) {
            }
        }
    }

    // ---------- mũi tên 3D ----------

    private fun ring(lat: Double, lon: Double, bearingDeg: Double, scale: Double): Polygon {
        val th = Math.toRadians(bearingDeg)
        val cosT = cos(th)
        val sinT = sin(th)
        val kLon = 111320.0 * cos(Math.toRadians(lat))
        val pts = ArrayList<Point>()
        for (p in ARROW) {
            val x = p[0] * scale
            val y = p[1] * scale
            val xr = x * cosT + y * sinT
            val yr = -x * sinT + y * cosT
            pts.add(Point.fromLngLat(lon + xr / kLon, lat + yr / 111320.0))
        }
        pts.add(pts[0])
        return Polygon.fromLngLats(listOf(pts))
    }

    private fun part(poly: Polygon, base: Double, top: Double, color: String): Feature {
        val f = Feature.fromGeometry(poly)
        f.addNumberProperty("b", base)
        f.addNumberProperty("h", top)
        f.addStringProperty("c", color)
        return f
    }

    private fun addCar(style: Style) {
        style.addSource(GeoJsonSource("car-src"))
        style.addLayer(
            FillExtrusionLayer("car-3d", "car-src").withProperties(
                PropertyFactory.fillExtrusionColor(Expression.toColor(Expression.get("c"))),
                PropertyFactory.fillExtrusionHeight(Expression.get("h")),
                PropertyFactory.fillExtrusionBase(Expression.get("b")),
                PropertyFactory.fillExtrusionOpacity(1.0f)
            )
        )
    }

    private fun updateCar(s: Double) {
        val src = map?.style?.getSourceAs<GeoJsonSource>("car-src") ?: return
        src.setGeoJson(
            FeatureCollection.fromFeatures(
                listOf(
                    part(ring(dLat, dLon, dHeading, 1.22 * s), 0.0, 0.4 * s, "#000000"),
                    part(ring(dLat, dLon, dHeading, 1.0 * s), 0.4 * s, 2.6 * s, "#ffffff")
                )
            )
        )
    }

    // ---------- giao diện trên màn hình ảo ----------

    private fun circle(c: Context, label: String, onClick: () -> Unit): TextView {
        val tv = TextView(c)
        tv.text = label
        tv.textSize = 17f
        tv.setTextColor(Color.WHITE)
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.argb(225, 38, 44, 64))
        tv.background = bg
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun buildOverlay(c: Context, root: FrameLayout) {
        val d = c.resources.displayMetrics.density
        val size = (50 * d).toInt()
        val gap = (8 * d).toInt()
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL

        fun add(b: TextView) {
            val lp = LinearLayout.LayoutParams(size, size)
            lp.bottomMargin = gap
            col.addView(b, lp)
        }

        val tiltB = circle(c, "3D") { toggleTilt() }
        tiltBtn = tiltB
        add(tiltB)
        add(circle(c, "+") { zoomButton(1.0) })
        add(circle(c, "−") { zoomButton(-1.0) })
        add(circle(c, "◎") { recenter() })

        val flp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        )
        flp.marginEnd = (10 * d).toInt()
        root.addView(col, flp)

        val attr = TextView(c)
        attr.text = "© OpenStreetMap contributors · OpenFreeMap"
        attr.textSize = 9f
        attr.setTextColor(Color.rgb(60, 60, 60))
        attr.setBackgroundColor(Color.argb(160, 255, 255, 255))
        attr.setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
        val alp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.BOTTOM
        )
        alp.bottomMargin = 132
        root.addView(attr, alp)
    }

    private inner class MapPresentation(ctx: Context, display: Display) : Presentation(ctx, display) {
        var root: FrameLayout? = null
        var mapView: MapView? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val c = context
            val opts = MapLibreMapOptions.createFromAttributes(c, null).textureMode(true)
            val mv = MapView(c, opts)
            val fl = FrameLayout(c)
            fl.setBackgroundColor(Color.rgb(15, 16, 48))
            fl.addView(
                mv,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            buildOverlay(c, fl)
            setContentView(fl)
            root = fl
            mapView = mv
            mv.onCreate(null)
            mv.getMapAsync { m -> onMapReady(m) }
        }
    }
}
