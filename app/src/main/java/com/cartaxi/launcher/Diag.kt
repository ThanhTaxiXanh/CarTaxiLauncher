package com.cartaxi.launcher

import android.util.Log
import android.os.SystemClock

private const val DIAG_TAG = "CarTaxi"
private const val DIAG_MIN_GAP_MS = 5000L
private val diagLast = HashMap<String, Long>()

// Ghi lỗi bị nuốt vào logcat (tag "CarTaxi"). Mỗi vị trí chỉ ghi tối đa 1 lần / 5 giây
// để các vòng lặp nóng (33 ms) không làm tràn logcat.
fun logErr(where: String, t: Throwable) {
    val now = SystemClock.elapsedRealtime()
    val allowed = synchronized(diagLast) {
        val last = diagLast[where] ?: -DIAG_MIN_GAP_MS
        if (now - last < DIAG_MIN_GAP_MS) {
            false
        } else {
            diagLast[where] = now
            true
        }
    }
    if (!allowed) return
    Log.w(DIAG_TAG, "$where: ${t.javaClass.simpleName}: ${t.message}")
}
