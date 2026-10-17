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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class NativeMap(private val appCtx: Context, private val main: Handler) {
    companion object {
        const val STYLE = "https://tiles.openfreemap.org/styles/liberty"
        const val DPI = 260
        private val ARROW = arrayOf(
            doubleArrayOf(0.0, 9.0), doubleArrayOf(5.5, -7.0),
            doubleArrayOf(0.0, -3.5), doubleArrayOf(-5.5, -7.0)
        )
    }

    var status = "Đang chờ"
    var frame: Bitmap? = null
    var frameW = 0
    var frameH = 0
    var streetName = ""
    var onFrame: (() -> Unit)? = null

    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var pres: MapPresentation? = null
    private var map: MapLibreMap? = null
    private var blocked = false

    private var tilt = 60.0
    private var zoom = 17.0
    private var heading = 0f
    private var lastLoc: Location? = null
    private var night = false
    private var dnInit = false
    private var lastStreetMs = 0L
    private var lastStreetSeen = 0L
    private var tiltBtn: TextView? = null

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

    // ---------- vị trí, ngày/đêm, tên đường ----------

    fun onLocation(loc: Location) {
        lastLoc = loc
        if (loc.hasBearing() && loc.speed > 1.0f) heading = loc.bearing
        updateCar()
        applyCamera()
    }

    fun update(nowMs: Long) {
        val loc = lastLoc
        val lat = loc?.latitude ?: 21.0285
        val lon = loc?.longitude ?: 105.8542
        val el = sunElevation(lat, lon, nowMs)
        val wantNight = if (!dnInit) el < -2.0 else if (night) el < 1.0 else el < -3.0
        dnInit = true
        if (wantNight != night) {
            night = wantNight
            if (map != null) loadStyle()
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
        var t = if (len2 == 0.0) 0.0 else -(ax * dx + ay * dy) / len2
        t = t.coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }

    private fun refreshStreet(nowMs: Long) {
        val loc = lastLoc ?: return
        val st = map?.style ?: return
        try {
            val src = st.sources.firstOrNull { it is VectorSource } as? VectorSource ?: return
            val feats = src.querySourceFeatures(arrayOf("transportation_name"), null)
            val kx = 111320.0 * cos(Math.toRadians(loc.latitude))
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
                        val ax = (pts[i].longitude() - loc.longitude) * kx
                        val ay = (pts[i].latitude() - loc.latitude) * ky
                        val bx = (pts[i + 1].longitude() - loc.longitude) * kx
                        val by = (pts[i + 1].latitude() - loc.latitude) * ky
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

    // ---------- camera ----------

    private fun applyCamera() {
        val m = map ?: return
        val b = CameraPosition.Builder()
            .zoom(zoom)
            .tilt(tilt)
            .bearing(heading.toDouble())
        val loc = lastLoc
        if (loc != null) b.target(LatLng(loc.latitude, loc.longitude))
        m.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), 600)
    }

    private fun toggleTilt() {
        tilt = if (tilt > 0.0) 0.0 else 60.0
        tiltBtn?.text = if (tilt > 0.0) "3D" else "2D"
        applyCamera()
    }

    private fun zoomBy(delta: Double) {
        val m = map ?: return
        zoom = (m.cameraPosition.zoom + delta).coerceIn(3.0, 20.0)
        applyCamera()
    }

    // ---------- bản đồ ----------

    private fun onMapReady(m: MapLibreMap) {
        map = m
        m.uiSettings.isCompassEnabled = false
        m.uiSettings.isLogoEnabled = false
        m.uiSettings.isAttributionEnabled = false
        m.uiSettings.setAllGesturesEnabled(false)
        m.setPadding(0, (frameH * 0.30f).toInt(), 0, 0)
        val loc = lastLoc
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(loc?.latitude ?: 21.0285, loc?.longitude ?: 105.8542))
                    .zoom(zoom)
                    .tilt(tilt)
                    .bearing(heading.toDouble())
                    .build()
            )
        )
        loadStyle()
    }

    private fun loadStyle() {
        val m = map ?: return
        m.setStyle(Style.Builder().fromUri(STYLE)) { style ->
            if (night) themeNight(style)
            addCar(style)
            updateCar()
            status = "Bản đồ đã tải"
            applyCamera()
        }
    }

    private fun themeNight(style: Style) {
        for (layer in style.layers) {
            val id = layer.id.lowercase()
            try {
                when (layer) {
                    is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor("#18202c"))
                    is FillLayer -> {
                        val col = when {
                            id.contains("water") -> "#1f4466"
                            id.contains("park") || id.contains("wood") || id.contains("grass") ||
                                id.contains("forest") || id.contains("landcover") || id.contains("garden") -> "#1f3a2c"
                            id.contains("building") -> "#2c3a50"
                            else -> "#222d3c"
                        }
                        layer.setProperties(PropertyFactory.fillColor(col))
                    }
                    is LineLayer -> {
                        val col = when {
                            id.contains("casing") || id.contains("outline") -> "#0c1118"
                            id.contains("motorway") || id.contains("trunk") -> "#ffb74d"
                            id.contains("primary") -> "#ffe082"
                            id.contains("secondary") || id.contains("tertiary") -> "#f1f5fb"
                            id.contains("water") -> "#2b6aa3"
                            id.contains("rail") || id.contains("transit") -> "#5d6e86"
                            id.contains("boundary") -> "#4f5f76"
                            else -> "#b4c2d6"
                        }
                        layer.setProperties(PropertyFactory.lineColor(col))
                    }
                    is SymbolLayer -> layer.setProperties(
                        PropertyFactory.textColor("#f4f7fb"),
                        PropertyFactory.textHaloColor("#0b1017"),
                        PropertyFactory.textHaloWidth(1.8f)
                    )
                    is FillExtrusionLayer -> layer.setProperties(
                        PropertyFactory.fillExtrusionColor("#4d5f7a"),
                        PropertyFactory.fillExtrusionOpacity(0.92f)
                    )
                    else -> {
                    }
                }
            } catch (t: Throwable) {
            }
        }
    }

    // ---------- mũi tên 3D ----------

    private fun ring(lat: Double, lon: Double, bearingDeg: Float, scale: Double): Polygon {
        val th = Math.toRadians(bearingDeg.toDouble())
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

    private fun carCollection(loc: Location): FeatureCollection {
        val lat = loc.latitude
        val lon = loc.longitude
        return FeatureCollection.fromFeatures(
            listOf(
                part(ring(lat, lon, heading, 1.35), 0.0, 0.3, "#10151c"),
                part(ring(lat, lon, heading, 1.0), 0.3, 3.3, "#1e88e5"),
                part(ring(lat, lon, heading, 0.55), 3.3, 4.6, "#cfe8ff")
            )
        )
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

    private fun updateCar() {
        val loc = lastLoc ?: return
        val st = map?.style ?: return
        val src = st.getSourceAs<GeoJsonSource>("car-src") ?: return
        src.setGeoJson(carCollection(loc))
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
        add(circle(c, "+") { zoomBy(1.0) })
        add(circle(c, "−") { zoomBy(-1.0) })

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
        alp.bottomMargin = (58 * d).toInt()
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
            fl.setBackgroundColor(Color.rgb(235, 238, 230))
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
