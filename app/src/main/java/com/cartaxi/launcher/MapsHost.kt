package com.cartaxi.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

object Shell {
    fun ready(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    private val method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    fun run(cmd: String): String = try {
        val p = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        (out + err).trim()
    } catch (t: Throwable) {
        "ERR " + (t.cause ?: t).message
    }
}

class MapsHost(private val appCtx: Context, private val main: Handler) {
    companion object {
        const val PKG = "com.google.android.apps.maps"
    }

    var status = "Đang chờ"
    var frame: Bitmap? = null
    var frameW = 0
    var frameH = 0
    var onFrame: (() -> Unit)? = null

    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var displayId = -1
    private var component = ""
    private var blocked = false
    private val exec = Executors.newSingleThreadExecutor()

    fun running(): Boolean = vd != null

    private fun run(cmd: String, cb: ((String) -> Unit)? = null) {
        exec.execute {
            val out = Shell.run(cmd)
            if (cb != null) main.post { cb(out) }
        }
    }

    fun start(w: Int, h: Int) {
        if (vd != null || blocked) return
        if (!Shell.ready()) {
            status = "Chưa kết nối Shizuku"
            return
        }
        val comp = appCtx.packageManager.getLaunchIntentForPackage(PKG)?.component?.flattenToShortString()
        if (comp == null) {
            status = "Không thấy Google Maps"
            blocked = true
            return
        }
        component = comp
        val vw = maxOf(64, w / 2 * 2)
        val vh = maxOf(64, h / 2 * 2)
        try {
            val r = ImageReader.newInstance(vw, vh, PixelFormat.RGBA_8888, 2)
            r.setOnImageAvailableListener({ rd -> onImage(rd) }, main)
            val dm = appCtx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val d = dm.createVirtualDisplay("CarTaxiMaps", vw, vh, 200, r.surface, flags)
            if (d == null) {
                r.close()
                status = "Không tạo được màn hình ảo"
                blocked = true
                return
            }
            reader = r
            vd = d
            displayId = d.display.displayId
            frameW = vw
            frameH = vh
            status = "Màn hình ảo #$displayId, đang mở Maps"
            launch()
        } catch (t: Throwable) {
            status = "Lỗi tạo màn hình ảo: " + t.message
            blocked = true
        }
    }

    private fun launch() {
        run("am start --display $displayId -n $component") { out ->
            status = "am: " + out.replace("\n", " ").take(90)
        }
    }

    fun relaunch() {
        if (vd == null) {
            blocked = false
        } else {
            launch()
        }
    }

    fun tap(nx: Float, ny: Float) {
        if (vd == null) return
        val x = (nx * frameW).toInt()
        val y = (ny * frameH).toInt()
        run("input -d $displayId tap $x $y")
    }

    fun back() {
        if (vd == null) return
        run("input -d $displayId keyevent 4")
    }

    fun stop() {
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
}
