package com.cartaxi.launcher

import android.location.Location
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

class GpsFix(
    val lat: Double,
    val lon: Double,
    val speed: Double,      // m/s
    val course: Double,     // độ, NaN nếu chưa biết
    val stationary: Boolean
)

// Bộ lọc Kalman vận tốc không đổi cho từng trục (Đông, Bắc), có trọng số theo chất lượng GPS
class GpsFilter {
    var used = 0        // số vệ tinh đang dùng
    var cn0 = 0.0       // cường độ tín hiệu trung bình (dB-Hz)

    private class Axis {
        var p = 0.0
        var v = 0.0
        var a = 400.0
        var b = 0.0
        var c = 25.0

        fun predict(dt: Double, q: Double) {
            p += v * dt
            val a2 = a + 2 * dt * b + dt * dt * c + q * dt * dt * dt * dt / 4
            val b2 = b + dt * c + q * dt * dt * dt / 2
            val c2 = c + q * dt * dt
            a = a2
            b = b2
            c = c2
        }

        fun updatePos(z: Double, r: Double) {
            val s = a + r
            val ka = a / s
            val kb = b / s
            val inn = z - p
            p += ka * inn
            v += kb * inn
            val a2 = (1 - ka) * a
            val b2 = (1 - ka) * b
            val c2 = c - kb * b
            a = a2
            b = b2
            c = c2
        }

        fun updateVel(z: Double, r: Double) {
            val s = c + r
            val ka = b / s
            val kb = c / s
            val inn = z - v
            p += ka * inn
            v += kb * inn
            val a2 = a - ka * b
            val b2 = b - ka * c
            val c2 = c - kb * c
            a = a2
            b = b2
            c = c2
        }
    }

    private val px = Axis()
    private val py = Axis()
    private var has = false
    private var originLat = 0.0
    private var originLon = 0.0
    private var kx = 111320.0
    private val ky = 111320.0
    private var lastMs = 0L
    private var stillCount = 0
    private var rejects = 0

    private fun qualityFactor(): Double {
        var f = 1.0
        if (used in 1..5) f *= 2.5 else if (used in 6..7) f *= 1.4
        if (cn0 > 0.0 && cn0 < 22.0) f *= 1.8 else if (cn0 >= 22.0 && cn0 < 28.0) f *= 1.25
        return f
    }

    fun reset() {
        has = false
        stillCount = 0
        rejects = 0
    }

    fun update(loc: Location, nowMs: Long): GpsFix {
        if (!has) {
            originLat = loc.latitude
            originLon = loc.longitude
            kx = 111320.0 * cos(Math.toRadians(originLat))
            px.p = 0.0
            py.p = 0.0
            px.v = 0.0
            py.v = 0.0
            for (ax in arrayOf(px, py)) {
                ax.a = 100.0
                ax.b = 0.0
                ax.c = 25.0
            }
            has = true
            lastMs = nowMs
            stillCount = 0
            rejects = 0
            return out(false)
        }

        val x = (loc.longitude - originLon) * kx
        val y = (loc.latitude - originLat) * ky
        if (abs(x) > 20000.0 || abs(y) > 20000.0) {
            reset()
            return update(loc, nowMs)
        }

        val dt = ((nowMs - lastMs) / 1000.0).coerceIn(0.05, 10.0)
        lastMs = nowMs
        if (dt >= 6.0) {
            for (ax in arrayOf(px, py)) {
                ax.a = max(ax.a, 400.0)
                ax.c = max(ax.c, 25.0)
            }
        }
        val q = 4.0
        px.predict(dt, q)
        py.predict(dt, q)

        val acc = if (loc.hasAccuracy()) max(3.0, loc.accuracy.toDouble()) else 15.0
        var r = acc * acc * 0.5 * qualityFactor()

        val sp = if (loc.hasSpeed() && !loc.speed.isNaN()) loc.speed.toDouble() else Double.NaN
        val spAcc = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.toDouble() else 1.0

        if (!sp.isNaN() && sp < 0.6) stillCount++ else stillCount = 0
        val still = stillCount >= 3
        if (still) r *= 6.0

        val dx = x - px.p
        val dy = y - py.p
        val gate = dx * dx / (px.a + r) + dy * dy / (py.a + r)
        if (gate > 25.0 && rejects < 3) {
            r *= 30.0
            rejects++
        } else {
            rejects = 0
        }
        px.updatePos(x, r)
        py.updatePos(y, r)

        if (still) {
            px.updateVel(0.0, 0.0004)
            py.updateVel(0.0, 0.0004)
        } else if (!sp.isNaN() && sp > 1.0 && loc.hasBearing()) {
            val bAcc = if (loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees.toDouble() else 15.0
            if (bAcc < 40.0) {
                val br = Math.toRadians(loc.bearing.toDouble())
                val vx = sp * sin(br)
                val vy = sp * cos(br)
                val lat = sp * Math.toRadians(max(bAcc, 3.0))
                val rv = max(0.09, spAcc * spAcc) + lat * lat
                px.updateVel(vx, rv)
                py.updateVel(vy, rv)
            }
        }
        return out(still)
    }

    private fun out(still: Boolean): GpsFix {
        val spd = hypot(px.v, py.v)
        val course = if (spd > 1.5) (Math.toDegrees(atan2(px.v, py.v)) + 360.0) % 360.0 else Double.NaN
        return GpsFix(
            originLat + py.p / ky,
            originLon + px.p / kx,
            if (still) 0.0 else spd,
            course,
            still
        )
    }
}
