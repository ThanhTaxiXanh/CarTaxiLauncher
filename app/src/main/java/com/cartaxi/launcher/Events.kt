package com.cartaxi.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// repeat: 0 = hằng năm, 1 = hằng tháng, 2 = một lần
data class CalEvent(
    val id: Long,
    val title: String,
    val lunar: Boolean,
    val day: Int,
    val month: Int,
    val year: Int,
    val repeat: Int
)

data class EventLine(val text: String, val countdown: String, val urgent: Boolean)

object Events {
    fun parse(raw: String?): List<CalEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CalEvent(
                    o.getLong("id"), o.getString("title"), o.getBoolean("lunar"),
                    o.getInt("day"), o.getInt("month"), o.optInt("year", 0), o.optInt("repeat", 0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun load(ctx: Context): List<CalEvent> =
        parse(ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("events", ""))

    fun save(ctx: Context, list: List<CalEvent>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject().put("id", e.id).put("title", e.title).put("lunar", e.lunar)
                    .put("day", e.day).put("month", e.month).put("year", e.year).put("repeat", e.repeat)
            )
        }
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("events", arr.toString()).apply()
    }

    private fun solarDate(y: Int, m: Int, d: Int): LocalDate? {
        if (m !in 1..12 || d < 1) return null
        return try {
            LocalDate.of(y, m, minOf(d, YearMonth.of(y, m).lengthOfMonth()))
        } catch (e: Exception) {
            null
        }
    }

    private fun lunarDate(d: Int, m: Int, y: Int): LocalDate? {
        val s = LunarCalendar.lunarToSolar(d, m, y, false) ?: return null
        val ld = try {
            LocalDate.of(s[2], s[1], s[0])
        } catch (e: Exception) {
            return null
        }
        val back = LunarCalendar.solarToLunar(s[0], s[1], s[2])
        if (back.day != d || back.month != m || back.leap) return null
        return ld
    }

    fun nextDate(e: CalEvent, today: LocalDate): LocalDate? {
        val cands = ArrayList<LocalDate>()
        val tl = LunarCalendar.solarToLunar(today.dayOfMonth, today.monthValue, today.year)
        when (e.repeat) {
            0 -> for (dy in -1..2) {
                val d = if (e.lunar) lunarDate(e.day, e.month, tl.year + dy)
                else solarDate(today.year + dy, e.month, e.day)
                if (d != null) cands.add(d)
            }
            1 -> for (i in 0..14) {
                if (e.lunar) {
                    val mm = (tl.month - 1 + i) % 12 + 1
                    val yy = tl.year + (tl.month - 1 + i) / 12
                    lunarDate(e.day, mm, yy)?.let { cands.add(it) }
                } else {
                    val ym = YearMonth.of(today.year, today.monthValue).plusMonths(i.toLong())
                    solarDate(ym.year, ym.monthValue, e.day)?.let { cands.add(it) }
                }
            }
            else -> {
                val d = if (e.lunar) lunarDate(e.day, e.month, e.year) else solarDate(e.year, e.month, e.day)
                if (d != null) cands.add(d)
            }
        }
        return cands.filter { !it.isBefore(today) }.minByOrNull { it.toEpochDay() }
    }

    fun whenText(e: CalEvent): String {
        val al = if (e.lunar) " (ÂL)" else ""
        return when (e.repeat) {
            1 -> "ngày ${e.day}$al hằng tháng"
            2 -> "${e.day}/${e.month}/${e.year}$al"
            else -> "${e.day}/${e.month}$al"
        }
    }

    fun lines(raw: String?, today: LocalDate): List<EventLine> {
        val ups = parse(raw).mapNotNull { e ->
            val d = nextDate(e, today) ?: return@mapNotNull null
            Triple(e, d, ChronoUnit.DAYS.between(today, d).toInt())
        }.sortedWith(compareBy({ it.third }, { it.first.id })).take(2)
        return ups.map { (e, _, left) ->
            val short = when (e.repeat) {
                1 -> "ngày ${e.day}" + (if (e.lunar) " (ÂL)" else "")
                2 -> "${e.day}/${e.month}/${e.year}" + (if (e.lunar) " (ÂL)" else "")
                else -> "${e.day}/${e.month}" + (if (e.lunar) " (ÂL)" else "")
            }
            val cd = when {
                left == 0 -> "Hôm nay"
                left <= 30 -> "Còn $left ngày"
                else -> ""
            }
            EventLine("${e.title} $short", cd, left <= 3)
        }
    }
}
