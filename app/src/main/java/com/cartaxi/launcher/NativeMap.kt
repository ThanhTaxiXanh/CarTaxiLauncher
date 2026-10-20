package com.cartaxi.launcher

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
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
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class NativeMap(private val appCtx: Context, private val main: Handler) {
    companion object {
        const val STYLE = "https://tiles.openfreemap.org/styles/liberty"
        const val DPI = 260
        private val ROAD_KEYS = arrayOf(
            "road", "highway", "street", "motorway", "trunk", "primary", "secondary",
            "tertiary", "minor", "service", "bridge", "tunnel", "link", "path", "pedestrian", "track"
        )
        private val LABEL_KEYS = arrayOf("name", "road", "highway", "street", "motorway")
        private val SKIP_CLASS = setOf("rail", "transit", "ferry", "aerialway", "path", "pedestrian", "track", "pier")
    }

    var status = "Đang chờ"
    var frame: Bitmap? = null
    var frameW = 0
    var frameH = 0
    var streetName = ""
    var satUsed = 0
    var onFrame: (() -> Unit)? = null
    var signsState: SignsState = SignsState.EMPTY

    private val signs = RoadSigns(
        java.io.File(appCtx.filesDir, "signs_cache.json"),
        runCatching { appCtx.assets.open("verified_signs.geojson").bufferedReader().use { it.readText() } }.getOrDefault("")
    )
    private var signsKey = Int.MIN_VALUE
    private val signsSrc = "ct-signs"

    private class SnapPoint(val lat: Double, val lon: Double, val heading: Double)

    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var pres: MapPresentation? = null
    private var map: MapLibreMap? = null
    private var arrow: ArrowView? = null
    private var arrowNominal = 48
    private var arrowView = 80
    private var blocked = false

    // camera / người dùng
    private var tilt = 60.0
    private var following = true
    private var lastTouchMs = 0L
    private var lastScaleMs = 0L
    private var userZoom: Double? = null
    private var userZoomMs = 0L
    private var manualZoom = 17.0
    private var swipeRotateMode = false
    private var swipeRotateBtn: TextView? = null
    private var tiltBtn: TextView? = null

    // vị trí
    private val gps = GpsFilter()
    private var hasFix = false
    private var snapped = false
    private var moving = false
    private var speedMs = 0f
    private var smKmh = 0.0
    private var fixMs = 0L
    private var lastRawLat = 0.0
    private var lastRawLon = 0.0
    private var anchorLat = 0.0
    private var anchorLon = 0.0
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
    private var stickyA = Double.NaN
    private var stickyB = Double.NaN

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
                logErr("NativeMap:152", t)
            }
            main.postDelayed(this, 33L)
        }
    }

    fun running(): Boolean = vd != null

    fun setGnss(used: Int, cn0: Double) {
        satUsed = used
        gps.used = used
        gps.cn0 = cn0
    }

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
            logErr("NativeMap:213", t)
        }
        try {
            pres?.dismiss()
        } catch (t: Throwable) {
            logErr("NativeMap:217", t)
        }
        pres = null
        map = null
        arrow = null
        try {
            vd?.release()
        } catch (t: Throwable) {
            logErr("NativeMap:224", t)
        }
        vd = null
        try {
            reader?.close()
        } catch (t: Throwable) {
            logErr("NativeMap:229", t)
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
            manualZoom = (map?.cameraPosition?.zoom ?: dZoom).coerceIn(3.0, 20.0)
        }
        lastTouchMs = SystemClock.elapsedRealtime()
    }

    private fun panByScreen(dx: Float, dy: Float, animate: Boolean) {
        val m = map ?: return
        val pos = m.cameraPosition
        val target = pos.target ?: return
        val proj = m.projection
        val c = proj.toScreenLocation(target)
        val ll = proj.fromScreenLocation(PointF(c.x + dx, c.y + dy))
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder(pos).target(ll).build()
        )
        if (animate) m.animateCamera(update, 400) else m.moveCamera(update)
    }

    fun scrollBy(dx: Float, dy: Float) {
        val m = map ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScaleMs < 350L) return
        if (!dx.isFinite() || !dy.isFinite() || abs(dx) > 400f || abs(dy) > 400f) return
        if (swipeRotateMode) {
            // Android Auto reports scroll deltas but no individual pointer
            // coordinates. In rotate mode, a horizontal swipe controls bearing.
            if (abs(dx) <= 0.2f) return
            leaveFollow()
            val pos = m.cameraPosition
            val next = ((pos.bearing - dx.toDouble() * 0.35) % 360.0 + 360.0) % 360.0
            m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(pos).bearing(next).build()))
        } else {
            leaveFollow()
            panByScreen(dx, dy, false)
        }
    }

    fun fling(vx: Float, vy: Float) {
        if (map == null || swipeRotateMode) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScaleMs < 600L) return
        leaveFollow()
        panByScreen(-vx * 0.12f, -vy * 0.12f, true)
    }

    fun pinch(scale: Float, nx: Float, ny: Float) {
        val m = map ?: return
        if (scale.isNaN() || scale < 0.2f || scale > 5f) return
        val now = SystemClock.elapsedRealtime()
        lastScaleMs = now
        val dz = (ln(scale.toDouble()) / ln(2.0)).coerceIn(-0.25, 0.25)
        if (following) {
            val base = userZoom ?: dZoom
            val z = (base + dz).coerceIn(3.0, 20.0)
            userZoom = z
            userZoomMs = now
            dZoom = z
        } else {
            val pos = m.cameraPosition
            val z = (pos.zoom + dz).coerceIn(3.0, 20.0)
            manualZoom = z
            lastTouchMs = now
            m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(pos).zoom(z).build()))
        }
    }

    private fun recenter() {
        swipeRotateMode = false
        swipeRotateBtn?.text = "↻"
        following = true
        userZoom = null
        if (smKmh.isNaN()) smKmh = 0.0
        dZoom = autoZoom(smKmh)
        val m = map ?: return
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

    private fun toggleSwipeRotate() {
        swipeRotateMode = !swipeRotateMode
        swipeRotateBtn?.text = if (swipeRotateMode) "XOAY" else "↻"
        if (swipeRotateMode) leaveFollow() else lastTouchMs = SystemClock.elapsedRealtime()
    }

    // Android Auto SurfaceCallback does not expose raw two-pointer rotation.
    // Offer explicit 15-degree map rotation, retaining the existing pan/pinch controls.
    private fun rotateMap(deltaDegrees: Double) {
        val m = map ?: return
        leaveFollow()
        val pos = m.cameraPosition
        val current = if (pos.bearing.isFinite()) pos.bearing else 0.0
        val next = ((current + deltaDegrees) % 360.0 + 360.0) % 360.0
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(pos).bearing(next).build()
            )
        )
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
            val pos = m.cameraPosition
            val z = (pos.zoom + delta).coerceIn(3.0, 20.0)
            manualZoom = z
            lastTouchMs = now
            m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(pos).zoom(z).build()), 250)
        }
    }

    // ---------- GPS ----------

    private fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dx = (lon2 - lon1) * 111320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        val dy = (lat2 - lat1) * 111320.0
        return sqrt(dx * dx + dy * dy)
    }

    private fun angDiff(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

    private fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dx = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val dy = lat2 - lat1
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    fun onLocation(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        if (hasFix) {
            if (loc.hasAccuracy() && loc.accuracy > 60f) return
            val d = dist(lastRawLat, lastRawLon, loc.latitude, loc.longitude)
            val dt = (now - fixMs) / 1000.0
            if (dt > 0.0 && dt < 4.0 && d / dt > 70.0) return
        }
        lastRawLat = loc.latitude
        lastRawLon = loc.longitude

        val fx = gps.update(loc, now)
        val sp = fx.speed.toFloat()
        moving = if (fx.stationary) false else if (moving) sp > 0.9f else sp > 2.0f
        speedMs = if (moving) sp else 0f

        var hd = Double.NaN
        if (!hasFix) {
            anchorLat = fx.lat
            anchorLon = fx.lon
        }
        if (moving) {
            hd = fx.course
            if (dist(anchorLat, anchorLon, fx.lat, fx.lon) >= 8.0) {
                val b = bearingTo(anchorLat, anchorLon, fx.lat, fx.lon)
                if (hd.isNaN() || angDiff(b, hd) > 50.0) hd = b
                anchorLat = fx.lat
                anchorLon = fx.lon
            }
        } else {
            anchorLat = fx.lat
            anchorLon = fx.lon
        }

        if (!hasFix) {
            hasFix = true
            val s = snap(fx.lat, fx.lon, Double.NaN)
            snapped = s != null
            tLat = s?.lat ?: fx.lat
            tLon = s?.lon ?: fx.lon
            tHeading = 0.0
            dLat = tLat
            dLon = tLon
            dHeading = tHeading
            fixMs = now
            return
        }
        fixMs = now
        if (moving) {
            val s = snap(fx.lat, fx.lon, hd)
            if (s != null) snapped = true
            tLat = s?.lat ?: fx.lat
            tLon = s?.lon ?: fx.lon
            val cand = when {
                s != null && !s.heading.isNaN() -> s.heading
                !hd.isNaN() -> hd
                else -> tHeading
            }
            if (angDiff(cand, tHeading) >= 4.0) tHeading = cand
        }
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
            logErr("NativeMap:496", t)
        }
    }

    // hd = NaN: đứng yên, chỉ hút vị trí, không đổi hướng
    private fun snap(lat: Double, lon: Double, hd: Double): SnapPoint? {
        ensureRoads(lat, lon)
        if (segs.isEmpty()) return null
        val kx = 111320.0 * cos(Math.toRadians(lat))
        val ky = 111320.0
        val ref = if (hd.isNaN()) tHeading else hd
        var bestScore = 1e9
        var bx = 0.0
        var by = 0.0
        var bBearing = 0.0
        var bOneway = 0.0
        var bA = Double.NaN
        var bB = Double.NaN
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
            val a0 = angDiff(segB, ref)
            val a = min(a0, 180.0 - a0)
            if (!hd.isNaN() && a > 45.0) continue
            var score = d + (if (hd.isNaN()) 0.0 else 0.25 * a)
            if (s[0] == stickyA && s[1] == stickyB) score -= 5.0
            if (score < bestScore) {
                bestScore = score
                bx = px
                by = py
                bBearing = segB
                bOneway = s[4]
                bA = s[0]
                bB = s[1]
                found = true
            }
        }
        if (!found) return null
        stickyA = bA
        stickyB = bB
        var tb = bBearing
        if (angDiff(tb, ref) > 90.0) tb += 180.0
        tb = ((tb % 360.0) + 360.0) % 360.0
        if (bOneway == 0.0) {
            val br = Math.toRadians(tb + 90.0)
            bx += sin(br) * 1.6
            by += cos(br) * 1.6
        }
        return SnapPoint(lat + by / ky, lon + bx / kx, if (hd.isNaN()) Double.NaN else tb)
    }

    // ---------- vòng lặp 30 khung hình / giây ----------

    private fun autoZoom(kmh: Double): Double = when {
        kmh <= 8.0 -> 18.0
        kmh <= 40.0 -> 18.0 - (kmh - 8.0) / 32.0
        kmh <= 80.0 -> 17.0 - (kmh - 40.0) / 40.0 * 0.8
        else -> max(15.6, 16.2 - (kmh - 80.0) / 40.0 * 0.6)
    }

    private fun step() {
        val m = map ?: return
        if (m.style == null) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.033 else ((now - lastFrameMs) / 1000.0).coerceIn(0.005, 0.2)
        lastFrameMs = now
        if (!hasFix) return

        if (dLat.isNaN() || dLon.isNaN()) {
            dLat = tLat
            dLon = tLon
        }
        if (dHeading.isNaN()) dHeading = if (tHeading.isNaN()) 0.0 else tHeading
        if (smKmh.isNaN()) smKmh = 0.0
        if (dZoom.isNaN() || dZoom < 3.0 || dZoom > 21.0) dZoom = 18.0

        // đi tiếp theo vận tốc cuối khi mất GPS (hầm, cầu), giảm dần sau 2 giây
        val ageRaw = (now - fixMs) / 1000.0
        val age = ageRaw.coerceAtMost(6.0)
        val decay = if (ageRaw > 2.0) (1.0 - (ageRaw - 2.0) / 8.0).coerceIn(0.4, 1.0) else 1.0
        var pLat = tLat
        var pLon = tLon
        if (moving && speedMs > 0f) {
            val d = speedMs * age * decay
            val br = Math.toRadians(tHeading)
            pLat += cos(br) * d / 111320.0
            pLon += sin(br) * d / (111320.0 * cos(Math.toRadians(tLat)))
        }
        val a = 1.0 - exp(-dt / 0.15)
        dLat += (pLat - dLat) * a
        dLon += (pLon - dLon) * a

        val diff = ((tHeading - dHeading + 540.0) % 360.0) - 180.0
        dHeading = (((dHeading + diff * (1.0 - exp(-dt / 0.45))) % 360.0) + 360.0) % 360.0

        smKmh += ((if (moving) speedMs * 3.6 else 0.0) - smKmh) * (1.0 - exp(-dt / 1.5))
        if (userZoom != null && now - userZoomMs > 15000L) userZoom = null
        val targetZoom = userZoom ?: autoZoom(smKmh)
        dZoom += (targetZoom - dZoom) * (1.0 - exp(-dt / 0.7))
        if (!following && now - lastTouchMs > 12000L) recenter()

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
        } else {
            val cz = m.cameraPosition.zoom
            if (cz.isNaN() || cz > 20.5 || cz < 2.5) {
                val z = if (cz.isNaN()) 18.0 else cz.coerceIn(3.0, 20.0)
                m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).zoom(z).build()))
            }
        }
        updateArrow(m)
    }

    private fun updateArrow(m: MapLibreMap) {
        val av = arrow ?: return
        val pt = m.projection.toScreenLocation(LatLng(dLat, dLon))
        av.visibility = View.VISIBLE
        av.translationX = pt.x - arrowView / 2f
        av.translationY = pt.y - arrowView / 2f
        av.setPose((dHeading - m.cameraPosition.bearing).toFloat(), tilt.toFloat())
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
                tLat = s.lat
                tLon = s.lon
                snapped = true
            }
        }
        if (nowMs - lastStreetMs > 2000L) {
            lastStreetMs = nowMs
            refreshStreet(nowMs)
        }
        updateSigns()
    }

    // ---------- biển báo & giới hạn tốc độ ----------

    private fun signColor(type: String): String = when (type) {
        "speed" -> "#d62626"
        "no_overtaking" -> "#e5484d"
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
            // Include sign type, label and limit: the same node can change meaning after refresh.
            // Check the style/source before comparing to avoid suppressing the first render.
            var key = near.size
            for (s in near) {
                key = key * 31 + s.lat.hashCode()
                key = key * 31 + s.lon.hashCode()
                key = key * 31 + s.type.hashCode()
                key = key * 31 + s.label.hashCode()
                key = key * 31 + s.value
            }
            val st = map?.style ?: return
            val src = st.getSourceAs<GeoJsonSource>(signsSrc) ?: return
            if (key == signsKey) return
            val feats = near.map { s ->
                Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply {
                    addStringProperty("label", s.label)
                    addStringProperty("color", signColor(s.type))
                    addStringProperty("type", s.type)
                }
            }
            src.setGeoJson(FeatureCollection.fromFeatures(feats))
            signsKey = key
        } catch (t: Throwable) {
            logErr("NativeMap:698", t)
        }
    }

    private fun installSigns(style: Style) {
        try {
            signsKey = Int.MIN_VALUE
            style.addSource(GeoJsonSource(signsSrc))
            style.addLayer(
                CircleLayer("ct-signs-circle", signsSrc).withProperties(
                    PropertyFactory.circleColor(
                        Expression.switchCase(
                            Expression.eq(Expression.get("type"), Expression.literal("speed")),
                            Expression.toColor(Expression.literal("#ffffff")),
                            Expression.toColor(Expression.get("color"))
                        )
                    ),
                    PropertyFactory.circleRadius(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(14, 8f), Expression.stop(17, 15f), Expression.stop(20, 26f)
                        )
                    ),
                    PropertyFactory.circleStrokeColor(
                        Expression.switchCase(
                            Expression.eq(Expression.get("type"), Expression.literal("speed")),
                            Expression.toColor(Expression.literal("#d62626")),
                            Expression.toColor(Expression.literal("#ffffff"))
                        )
                    ),
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
                    PropertyFactory.textColor(
                        Expression.switchCase(
                            Expression.eq(Expression.get("type"), Expression.literal("speed")),
                            Expression.toColor(Expression.literal("#121212")),
                            Expression.toColor(Expression.literal("#ffffff"))
                        )
                    ),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                    PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT)
                )
            )
        } catch (t: Throwable) {
            logErr("NativeMap:735", t)
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
            logErr("NativeMap:807", t)
        }
    }

    // ---------- bản đồ ----------

    private fun onMapReady(m: MapLibreMap) {
        map = m
        m.uiSettings.isCompassEnabled = false
        m.uiSettings.isLogoEnabled = false
        m.uiSettings.isAttributionEnabled = false
        m.uiSettings.setAllGesturesEnabled(false)
        m.setPadding(0, (frameH * 0.46f).toInt(), 0, 0)
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
        // Femto Car Launcher map styles (Apache-2.0) adapted to native MapLibre.
        // Unlike its MapLibre GL JS WebView engine, this renders to our Android
        // Auto VirtualDisplay so the existing SurfaceCallback still works.
        val name = if (night) "map/femto_dark.json" else "map/femto_light.json"
        val styleJson = runCatching {
            appCtx.assets.open(name).bufferedReader().use { it.readText() }
        }.getOrNull()
        val builder = if (styleJson != null) Style.Builder().fromJson(styleJson)
            else Style.Builder().fromUri(STYLE)
        m.setStyle(builder) { style ->
            tuneLabels(style)
            installSigns(style)
            status = if (styleJson == null) "Bản đồ dự phòng OpenFreeMap" else "Bản đồ Femto / OpenFreeMap"
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

    private fun isLabel(id: String): Boolean {
        for (k in LABEL_KEYS) if (id.contains(k)) return true
        return false
    }

    // Keep road names compact so the next junction remains visible.
    // Viewport pitch keeps tilted-map text upright; a dark, softened halo
    // makes bright lettering appear slightly raised without hiding the road.
    private fun tuneLabels(style: Style) {
        for (layer in style.layers) {
            if (layer !is SymbolLayer) continue
            val id = layer.id.lowercase()
            if (!isLabel(id)) continue
            try {
                layer.setProperties(
                    PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT),
                    PropertyFactory.textRotationAlignment(Property.TEXT_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.textSize(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(14, 10f), Expression.stop(16, 14f),
                            Expression.stop(18, 18f), Expression.stop(20, 21f)
                        )
                    ),
                    PropertyFactory.textColor(if (night) "#f8fbff" else "#26384d"),
                    PropertyFactory.textHaloColor(if (night) "#091429" else "#ffffff"),
                    PropertyFactory.textHaloWidth(if (night) 2.0f else 1.8f),
                    PropertyFactory.textHaloBlur(0.7f)
                )
            } catch (t: Throwable) {
                logErr("NativeMap:tuneLabels", t)
            }
        }
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
                logErr("NativeMap:953", t)
            }
        }
    }

    // ---------- mũi tên nằm trên mặt đường, khớp góc nghiêng của bản đồ ----------

    private class ArrowView(c: Context, private val nominal: Int) : View(c) {
        private var rel = 0f
        private var tiltDeg = 0f
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(36, 0, 0, 0)
        }
        private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(8, 52, 140)
        }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(64, 168, 255)
        }
        private val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(10, 98, 226)
        }
        private val path = Path()

        fun setPose(r: Float, t: Float) {
            if (abs(r - rel) > 0.2f || abs(t - tiltDeg) > 0.1f) {
                rel = r
                tiltDeg = t
                invalidate()
            }
        }

        private fun shape(s: Float, scale: Float, vararg p: Float): Path {
            path.reset()
            var i = 0
            while (i < p.size) {
                val x = p[i] * s * scale
                val y = p[i + 1] * s * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                i += 2
            }
            path.close()
            return path
        }

        // vẽ một lớp: tịnh tiến, nén theo góc nghiêng, xoay trong mặt phẳng đường, kéo dài theo trục mũi tên
        private fun layer(c: Canvas, cx: Float, cy: Float, cosT: Float, ky: Float, p: Path, paint: Paint) {
            c.save()
            c.translate(cx, cy)
            c.scale(1f, cosT)
            c.rotate(rel)
            c.scale(1f, ky)
            c.drawPath(p, paint)
            c.restore()
        }

        override fun onDraw(canvas: Canvas) {
            val s = nominal * 0.40f
            val cx = width / 2f
            val cy = height / 2f
            val tr = Math.toRadians(tiltDeg.toDouble())
            val cosT = max(0.35f, cos(tr).toFloat())
            val sinT = sin(tr).toFloat()
            val ky = 1f + 0.6f * sinT
            val h = s * 0.35f * sinT

            // bóng trên mặt đường
            for (i in 1..3) {
                val p = shape(s, 1f + 0.07f * i, 0f, -1f, 0.7f, 0.86f, 0f, 0.42f, -0.7f, 0.86f)
                layer(canvas, cx, cy, cosT, ky, p, shadowPaint)
            }
            // thành bên (độ dày nổi khối)
            if (h > 0.5f) {
                for (i in 1..4) {
                    val p = shape(s, 1f, 0f, -1f, 0.7f, 0.86f, 0f, 0.42f, -0.7f, 0.86f)
                    layer(canvas, cx, cy - h * i / 4f, cosT, ky, p, sidePaint)
                }
            }
            val top = cy - h
            outline.strokeWidth = s * 0.26f
            layer(canvas, cx, top, cosT, ky, shape(s, 1f, 0f, -1f, 0.7f, 0.86f, 0f, 0.42f, -0.7f, 0.86f), outline)
            layer(canvas, cx, top, cosT, ky, shape(s, 1f, 0f, -1f, 0f, 0.42f, -0.7f, 0.86f), leftPaint)
            layer(canvas, cx, top, cosT, ky, shape(s, 1f, 0f, -1f, 0.7f, 0.86f, 0f, 0.42f), rightPaint)
        }
    }

    // ---------- giao diện trên màn hình ảo ----------

    private fun circle(c: Context, label: String, size: Int, onClick: () -> Unit): TextView {
        val tv = TextView(c)
        tv.text = label
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.34f)
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
        arrowNominal = (frameW * 0.115f).toInt().coerceIn(40, 72)
        arrowView = (arrowNominal * 1.8f).toInt()
        val av = ArrowView(c, arrowNominal)
        av.visibility = View.INVISIBLE
        root.addView(av, FrameLayout.LayoutParams(arrowView, arrowView))
        arrow = av

        val size = (frameW * 0.15f).toInt().coerceIn(52, 84)
        val gap = (size * 0.14f).toInt()
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL

        fun add(b: TextView) {
            val lp = LinearLayout.LayoutParams(size, size)
            lp.bottomMargin = gap
            col.addView(b, lp)
        }

        val tiltB = circle(c, "3D", size) { toggleTilt() }
        tiltBtn = tiltB
        add(tiltB)
        add(circle(c, "+", size) { zoomButton(1.0) })
        add(circle(c, "−", size) { zoomButton(-1.0) })
        val rotateGesture = circle(c, "↻", size) { toggleSwipeRotate() }
        swipeRotateBtn = rotateGesture
        add(rotateGesture)
        add(circle(c, "◎", size) { recenter() })

        val flp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        )
        flp.marginEnd = (size * 0.2f).toInt()
        root.addView(col, flp)

        val attr = TextView(c)
        attr.text = "© OpenStreetMap contributors · OpenFreeMap"
        attr.setTextSize(TypedValue.COMPLEX_UNIT_PX, maxOf(11f, frameW * 0.026f))
        attr.setTextColor(Color.rgb(60, 60, 60))
        attr.setBackgroundColor(Color.argb(160, 255, 255, 255))
        attr.setPadding(6, 0, 6, 0)
        val alp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.BOTTOM
        )
        alp.bottomMargin = (frameH * 0.02f).toInt()
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
