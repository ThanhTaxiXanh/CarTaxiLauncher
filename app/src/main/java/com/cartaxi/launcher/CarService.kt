package com.cartaxi.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

class CarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session {
        val appCtx: Context = this
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen = LauncherScreen(carContext, appCtx)
        }
    }
}

class LauncherScreen(ctx: CarContext, private val appCtx: Context) : Screen(ctx) {
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var speedKmh: Int? = null
    private var lastFixMs = 0L
    private var selected = 0
    private var lastDraw = 0L
    private var evKey = ""
    private var evLines: List<EventLine> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = appCtx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val qrFile = File(appCtx.filesDir, "qr.png")
    private val maps = NativeMap(appCtx, handler)

    private val ticker = object : Runnable {
        override fun run() {
            draw()
            ensureMaps()
            maps.update(System.currentTimeMillis())
            handler.postDelayed(this, 1000)
        }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFixMs = SystemClock.uptimeMillis()
            speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else null
            maps.onLocation(location)
        }
    }

    init {
        maps.onFrame = {
            if (SystemClock.uptimeMillis() - lastDraw >= 40) draw()
        }
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
            override fun onSurfaceAvailable(container: SurfaceContainer) {
                surface = container.surface
                width = container.width
                height = container.height
                startUpdates()
            }

            override fun onClick(x: Float, y: Float) {
                handleClick(x, y)
            }

            override fun onSurfaceDestroyed(container: SurfaceContainer) {
                stopUpdates()
                maps.stop()
                surface = null
            }
        })
    }

    private fun handleClick(x: Float, y: Float) {
        val i = LauncherRenderer.hitIcon(x, y)
        if (i >= 0) {
            selected = i
            draw()
            return
        }
        val p = LauncherRenderer.hitMapPoint(x, y)
        if (p != null) maps.tap(p[0], p[1])
    }

    private fun battery(): Int {
        val i = appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun eventLines(): List<EventLine> {
        val raw = prefs.getString("events", "") ?: ""
        val today = LocalDate.now()
        val key = raw + today.toString()
        if (key != evKey) {
            evKey = key
            evLines = try {
                Events.lines(raw, today)
            } catch (e: Exception) {
                emptyList()
            }
        }
        return evLines
    }

    private fun ensureMaps() {
        if (maps.running()) return
        val r = LauncherRenderer.mapRect() ?: return
        maps.start(r.width().toInt(), r.height().toInt())
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (appCtx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { maps.onLocation(it) }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
        } catch (e: Exception) {
        }
    }

    private fun stopUpdates() {
        handler.removeCallbacks(ticker)
        try {
            val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(locListener)
        } catch (e: Exception) {
        }
    }

    private fun draw() {
        val s = surface ?: return
        if (!s.isValid) return
        lastDraw = SystemClock.uptimeMillis()
        val canvas = try {
            s.lockCanvas(null)
        } catch (e: Exception) {
            return
        }
        try {
            val st = UiState(
                speedKmh = speedKmh,
                gpsFix = lastFixMs != 0L && SystemClock.uptimeMillis() - lastFixMs < 5000L,
                batteryPct = battery(),
                qrFile = qrFile,
                nowMs = System.currentTimeMillis(),
                selectedIcon = selected,
                events = eventLines(),
                mapFrame = maps.frame,
                mapFrameW = maps.frameW,
                mapFrameH = maps.frameH,
                mapsStatus = maps.status,
                streetName = maps.streetName
            )
            LauncherRenderer.render(canvas, width, height, st)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }

    override fun onGetTemplate(): Template =
        NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Menu")
                            .setOnClickListener { }
                            .build()
                    )
                    .build()
            )
            .build()
}
