package com.cartaxi.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
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
    private var stable: Rect? = null
    private var speedKmh: Int? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = appCtx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    private val ticker = object : Runnable {
        override fun run() {
            draw()
            handler.postDelayed(this, 1000)
        }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else null
        }
    }

    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
            override fun onSurfaceAvailable(container: SurfaceContainer) {
                surface = container.surface
                width = container.width
                height = container.height
                startUpdates()
            }

            override fun onStableAreaChanged(stableArea: Rect) {
                stable = stableArea
                draw()
            }

            override fun onSurfaceDestroyed(container: SurfaceContainer) {
                stopUpdates()
                surface = null
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (appCtx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
        val canvas = try {
            s.lockCanvas(null)
        } catch (e: Exception) {
            return
        }
        try {
            val st = UiState(speedKmh, prefs.getString("qr", "") ?: "", System.currentTimeMillis())
            LauncherRenderer.render(canvas, width, height, stable, st)
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
