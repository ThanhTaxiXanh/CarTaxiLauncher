package com.cartaxi.launcher

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
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

class CarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = LauncherScreen(carContext)
    }
}

class LauncherScreen(ctx: CarContext) : Screen(ctx) {
    private var surface: Surface? = null

    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
            override fun onSurfaceAvailable(container: SurfaceContainer) {
                surface = container.surface
                draw(container.width, container.height)
            }

            override fun onSurfaceDestroyed(container: SurfaceContainer) {
                surface = null
            }
        })
    }

    private fun draw(w: Int, h: Int) {
        val s = surface ?: return
        val canvas = s.lockCanvas(null)
        canvas.drawColor(Color.rgb(15, 20, 30))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = h / 10f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("CarTaxiLauncher", w / 2f, h / 2f, paint)
        s.unlockCanvasAndPost(canvas)
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
