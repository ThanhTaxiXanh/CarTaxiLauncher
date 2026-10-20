package com.cartaxi.launcher

import android.content.Context
import android.location.Location
import kotlin.math.max

/**
 * Per-device trip telemetry inspired by Femto Car Launcher (Apache-2.0).
 * Filters GPS jitter/teleports and reports average speed including parked time.
 * Does not transmit locations or collect route history.
 */
class TripMeter(context: Context, private val nowMs: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("trip_metrics", Context.MODE_PRIVATE)
    private var meters = prefs.getFloat("meters", 0f).toDouble()
    private var elapsedMs = prefs.getLong("elapsed", 0L)
    private var previous: Location? = null
    private var lastFixTimeMs = 0L
    private var lastSavedMs = 0L

    data class Readout(val distanceKm: Double, val averageKmh: Double)
    fun snapshot() = Readout(meters / 1000.0, if (elapsedMs > 0) meters * 3600.0 / elapsedMs else 0.0)

    fun reset() {
        meters = 0.0
        elapsedMs = 0L
        previous = null
        lastFixTimeMs = 0L
        persist()
    }

    fun onLocation(location: Location) {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        if (location.hasAccuracy() && location.accuracy > 65f) return
        val t = location.elapsedRealtimeNanos / 1_000_000L
        val old = previous
        if (old != null) {
            val dt = t - lastFixTimeMs
            if (dt <= 0L) return
            if (dt <= 15_000L) {
                val distance = old.distanceTo(location).toDouble()
                val accurate = max(old.accuracy.takeIf { old.hasAccuracy() } ?: 10f,
                    location.accuracy.takeIf { location.hasAccuracy() } ?: 10f)
                val derivedMs = distance * 1000.0 / dt
                if (distance > max(8.0, accurate.toDouble()) && derivedMs <= 55.0) meters += distance
                elapsedMs += dt
            }
        }
        previous = Location(location)
        lastFixTimeMs = t
        if (nowMs() - lastSavedMs > 15_000L) persist()
    }

    fun persist() {
        lastSavedMs = nowMs()
        prefs.edit().putFloat("meters", meters.toFloat()).putLong("elapsed", elapsedMs).apply()
    }
}
