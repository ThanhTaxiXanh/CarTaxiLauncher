package com.cartaxi.launcher

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

class NativeMap(private val appCtx: Context, private val main: Handler) {
    companion object {
        const val DAY = "https://tiles.openfreemap.org/styles/liberty"
        const val NIGHT = "https://tiles.openfreemap.org/styles/dark"
        const val DPI = 220
    }

    var status = "Đang chờ"
    var frame: Bitmap? = null
    var frameW = 0
    var frameH = 0
    var onFrame: (() -> Unit)? = null

    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var pres: MapPresentation? = null
    private var map: MapLibreMap? = null
    private var blocked = false

    private var following = true
    private var northUp = false
    private var tilt = 60.0
    private var zoom = 17.0
    private var night = false
    private var heading = 0f
    private var lastLoc: Location? = null

    private var tiltBtn: TextView? = null
    private var compassBtn: TextView? = null
    private var nightBtn: TextView? = null

    fun running(): Boolean = vd != null

    // ---------- vòng đời ----------

    fun start(w: Int, h: Int) {
        if (vd != null || blocked) return
        val vw = maxOf(64, w / 2 * 2)
        val vh = maxOf(64, h / 2 * 2)
        try {
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

    fun action(i: Int) {
        when (i) {
            0 -> recenter()
            1 -> toggleTilt()
            2 -> toggleNight()
            3 -> zoomBy(1.0)
            4 -> zoomBy(-1.0)
        }
    }

    // ---------- vị trí ----------

    fun onLocation(loc: Location) {
        lastLoc = loc
        if (loc.hasBearing() && loc.speed > 1.0f) heading = loc.bearing
        updateCar()
        if (following) applyCamera()
    }

    private fun updateCar() {
        val loc = lastLoc ?: return
        val st = map?.style ?: return
        val src = st.getSourceAs<GeoJsonSource>("car-src") ?: return
        val f = Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude))
        f.addNumberProperty("bearing", heading)
        src.setGeoJson(f)
    }

    // ---------- camera ----------

    private fun applyCamera() {
        val m = map ?: return
        val b = CameraPosition.Builder()
            .zoom(zoom)
            .tilt(tilt)
            .bearing(if (northUp) 0.0 else heading.toDouble())
        val loc = lastLoc
        if (following && loc != null) b.target(LatLng(loc.latitude, loc.longitude))
        m.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), 600)
    }

    private fun recenter() {
        following = true
        applyCamera()
    }

    private fun toggleTilt() {
        tilt = if (tilt > 0.0) 0.0 else 60.0
        refreshLabels()
        applyCamera()
    }

    private fun toggleNorth() {
        northUp = !northUp
        refreshLabels()
        applyCamera()
    }

    private fun toggleNight() {
        night = !night
        refreshLabels()
        loadStyle()
    }

    private fun zoomBy(delta: Double) {
        val m = map ?: return
        zoom = (m.cameraPosition.zoom + delta).coerceIn(3.0, 20.0)
        applyCamera()
    }

    private fun refreshLabels() {
        tiltBtn?.text = if (tilt > 0.0) "3D" else "2D"
        compassBtn?.text = if (northUp) "N" else "▲"
        nightBtn?.text = if (night) "☾" else "☀"
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
        val lat = loc?.latitude ?: 21.0285
        val lon = loc?.longitude ?: 105.8542
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(lat, lon))
                    .zoom(zoom)
                    .tilt(tilt)
                    .bearing(0.0)
                    .build()
            )
        )
        loadStyle()
    }

    private fun loadStyle() {
        val m = map ?: return
        m.setStyle(Style.Builder().fromUri(if (night) NIGHT else DAY)) { style ->
            status = "Bản đồ đã tải"
            addCar(style)
            updateCar()
            applyCamera()
        }
    }

    private fun addCar(style: Style) {
        style.addImage("car", carBitmap())
        style.addSource(GeoJsonSource("car-src"))
        style.addLayer(
            SymbolLayer("car-layer", "car-src").withProperties(
                PropertyFactory.iconImage("car"),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconRotate(Expression.get("bearing"))
            )
        )
    }

    private fun carBitmap(): Bitmap {
        val s = 120
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Path().apply {
            moveTo(s / 2f, 8f)
            lineTo(s - 20f, s - 14f)
            lineTo(s / 2f, s * 0.74f)
            lineTo(20f, s - 14f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 136, 229)
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeJoin = Paint.Join.ROUND
        }
        c.drawPath(p, fill)
        c.drawPath(p, stroke)
        return b
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

        val compass = circle(c, "▲") { toggleNorth() }
        val tiltB = circle(c, "3D") { toggleTilt() }
        val locate = circle(c, "◎") { recenter() }
        val plus = circle(c, "+") { zoomBy(1.0) }
        val minus = circle(c, "−") { zoomBy(-1.0) }
        val nightB = circle(c, "☀") { toggleNight() }
        compassBtn = compass
        tiltBtn = tiltB
        nightBtn = nightB
        add(compass)
        add(tiltB)
        add(locate)
        add(plus)
        add(minus)
        add(nightB)

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
        root.addView(
            attr,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM
            )
        )
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
            refreshLabels()
        }
    }
}
