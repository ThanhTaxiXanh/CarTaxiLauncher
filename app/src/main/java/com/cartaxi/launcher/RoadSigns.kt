package com.cartaxi.launcher

import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// type: speed | camera | stop | yield | signals | bump | other
data class SignItem(
    val lat: Double,
    val lon: Double,
    val type: String,
    val label: String,   // chữ ngắn vẽ trong vòng tròn trên bản đồ
    val name: String,    // tên đầy đủ hiện ở widget
    val value: Int       // km/h nếu có
)

data class UpcomingSign(val item: SignItem, val distM: Int)

data class SignsState(
    val limit: Int?,
    val estimated: Boolean,
    val upcoming: List<UpcomingSign>,
    val status: String
) {
    companion object {
        val EMPTY = SignsState(null, false, emptyList(), "Đang chờ vị trí")
    }
}

// Giới hạn tốc độ + biển báo lấy từ OpenStreetMap (Overpass API).
// Bản đồ OpenFreeMap không chứa maxspeed / biển báo nên phải tải riêng.
class RoadSigns(private val cacheFile: File) {
    companion object {
        private val ENDPOINTS = arrayOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
        private const val RADIUS = 900
        private const val REFETCH_M = 350.0
        private const val MAX_AGE_MS = 15 * 60_000L
        private const val MAX_DATA_DISTANCE_M = 900.0

        // Giới hạn ước tính khi OSM không ghi maxspeed (chỉnh tại đây nếu cần)
        private val FALLBACK = mapOf(
            "motorway" to 90, "motorway_link" to 50,
            "trunk" to 60, "trunk_link" to 40,
            "primary" to 50, "primary_link" to 40,
            "secondary" to 50, "secondary_link" to 40,
            "tertiary" to 50, "tertiary_link" to 40,
            "unclassified" to 40, "residential" to 30, "living_street" to 20
        )
        private const val HIGHWAYS =
            "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|" +
                "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"
    }

    private class Way(val highway: String, val all: Int?, val fwd: Int?, val bwd: Int?, val pts: DoubleArray)
    private class Data(
        val lat: Double,
        val lon: Double,
        val ways: List<Way>,
        val signs: List<SignItem>,
        val timeMs: Long,
        val stale: Boolean
    )

    @Volatile private var data: Data? = null
    @Volatile private var fetching = false
    @Volatile private var error = ""
    private var lastTry = -100000L
    private val exec = Executors.newSingleThreadExecutor()

    private var lastWay: Way? = null
    private var lastData: Data? = null
    private var shown: Int? = null
    private var shownEst = false
    private var shownMs = 0L
    private var pendV: Int? = null
    private var pendEst = false
    private var pendN = 0

    init {
        exec.execute { loadCache() }
    }

    // ---------- tải dữ liệu ----------

    fun request(lat: Double, lon: Double) {
        if (fetching) return
        val now = SystemClock.elapsedRealtime()
        val d = data
        if (d != null && !d.stale && dist(lat, lon, d.lat, d.lon) < REFETCH_M && now - d.timeMs < MAX_AGE_MS) return
        if (now - lastTry < 15_000L) return
        lastTry = now
        fetching = true
        exec.execute {
            try {
                val txt = download(lat, lon)
                val parsed = parse(txt, lat, lon, SystemClock.elapsedRealtime(), false)
                data = parsed
                error = ""
                try {
                    cacheFile.writeText("$lat,$lon\n$txt")
                } catch (e: Exception) {
                    logErr("RoadSigns:113", e)
                }
            } catch (e: Exception) {
                error = "Không tải được biển báo"
            } finally {
                fetching = false
            }
        }
    }

    private fun loadCache() {
        try {
            if (!cacheFile.exists()) return
            val t = cacheFile.readText()
            val nl = t.indexOf('\n')
            if (nl < 0) return
            val head = t.substring(0, nl).split(",")
            val lat = head[0].toDouble()
            val lon = head[1].toDouble()
            if (data == null) data = parse(t.substring(nl + 1), lat, lon, 0L, true)
        } catch (e: Exception) {
            logErr("RoadSigns:133", e)
        }
    }

    private fun query(lat: Double, lon: Double): String {
        val a = "(around:$RADIUS,$lat,$lon)"
        return "[out:json][timeout:25];(" +
            "way$a[\"highway\"~\"^($HIGHWAYS)\$\"];" +
            "node$a[\"highway\"~\"^(stop|give_way|traffic_signals|speed_camera)\$\"];" +
            "node$a[\"enforcement\"=\"maxspeed\"];" +
            "node$a[\"traffic_calming\"];" +
            "node$a[\"traffic_sign\"];" +
            "node$a[\"maxspeed\"];" +
            ");out tags geom;"
    }

    private fun download(lat: Double, lon: Double): String {
        val body = ("data=" + URLEncoder.encode(query(lat, lon), "UTF-8")).toByteArray()
        var last: Exception? = null
        for (ep in ENDPOINTS) {
            try {
                val c = URL(ep).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000
                c.readTimeout = 30000
                c.doOutput = true
                c.setRequestProperty("User-Agent", "CarTaxiLauncher/0.1 (Android)")
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                if (code != 200) {
                    c.disconnect()
                    throw IllegalStateException("HTTP $code")
                }
                val s = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                return s
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no endpoint")
    }

    // ---------- phân tích ----------

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

    private fun parseSpeed(s: String?): Int? {
        if (s == null) return null
        val t = s.trim().lowercase()
        if (t == "walk") return 7
        val m = Regex("^(\\d{1,3})(?:\\s*(mph|km/h|kmh|kph))?$").matchEntire(t) ?: return null
        var v = m.groupValues[1].toInt()
        if (m.groupValues[2] == "mph") v = (v * 1.609).roundToInt()
        return if (v in 5..160) v else null
    }

    private fun parse(txt: String, lat: Double, lon: Double, now: Long, stale: Boolean): Data {
        val els = JSONObject(txt).getJSONArray("elements")
        val ways = ArrayList<Way>()
        val signs = ArrayList<SignItem>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tg = e.optJSONObject("tags") ?: continue
            if (e.optString("type") == "way") {
                val hw = tg.str("highway") ?: continue
                val g = e.optJSONArray("geometry") ?: continue
                if (g.length() < 2) continue
                val pts = DoubleArray(g.length() * 2)
                for (j in 0 until g.length()) {
                    val p = g.getJSONObject(j)
                    pts[2 * j] = p.getDouble("lat")
                    pts[2 * j + 1] = p.getDouble("lon")
                }
                ways.add(
                    Way(
                        hw,
                        parseSpeed(tg.str("maxspeed")),
                        parseSpeed(tg.str("maxspeed:forward")),
                        parseSpeed(tg.str("maxspeed:backward")),
                        pts
                    )
                )
            } else if (e.optString("type") == "node") {
                if (!e.has("lat") || !e.has("lon")) continue
                classify(tg, e.getDouble("lat"), e.getDouble("lon"))?.let { signs.add(it) }
            }
        }
        return Data(lat, lon, ways, signs, now, stale)
    }

    private fun classify(t: JSONObject, lat: Double, lon: Double): SignItem? {
        val hw = t.str("highway")
        val ms = parseSpeed(t.str("maxspeed"))
        return when {
            hw == "speed_camera" || t.str("enforcement") == "maxspeed" ->
                SignItem(lat, lon, "camera", "CAM", if (ms != null) "Camera tốc độ $ms" else "Camera tốc độ", ms ?: 0)
            hw == "stop" -> SignItem(lat, lon, "stop", "STOP", "Biển dừng", 0)
            hw == "give_way" -> SignItem(lat, lon, "yield", "NH", "Nhường đường", 0)
            hw == "traffic_signals" -> SignItem(lat, lon, "signals", "ĐÈN", "Đèn giao thông", 0)
            t.str("traffic_calming") != null -> SignItem(lat, lon, "bump", "GỜ", "Gờ giảm tốc", 0)
            ms != null -> SignItem(lat, lon, "speed", ms.toString(), "Giới hạn $ms km/h", ms)
            t.str("traffic_sign") != null -> fromTrafficSign(t.str("traffic_sign")!!, lat, lon)
            else -> null
        }
    }

    private fun fromTrafficSign(ts: String, lat: Double, lon: Double): SignItem {
        val s = ts.lowercase()
        val num = Regex("\\[(\\d{2,3})\\]").find(s)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            (s.contains("maxspeed") || s.contains("127")) && num != null ->
                SignItem(lat, lon, "speed", num.toString(), "Giới hạn $num km/h", num)
            s.contains("maxspeed") || s.contains("127") ->
                SignItem(lat, lon, "other", "!", "Biển giới hạn tốc độ", 0)
            s.contains("stop") || s.contains("122") -> SignItem(lat, lon, "stop", "STOP", "Biển dừng", 0)
            s.contains("give_way") || s.contains("yield") -> SignItem(lat, lon, "yield", "NH", "Nhường đường", 0)
            else -> SignItem(lat, lon, "other", "!", "Biển báo: " + ts.take(24), 0)
        }
    }

    // ---------- truy vấn ----------

    private fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dx = (lon2 - lon1) * 111320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        val dy = (lat2 - lat1) * 111320.0
        return sqrt(dx * dx + dy * dy)
    }

    private fun angDiff(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

    /** Biển báo trong bán kính, dùng để vẽ lên bản đồ. */
    fun nearby(lat: Double, lon: Double, radius: Double, max: Int): List<SignItem> {
        val d = data ?: return emptyList()
        if (d.stale || dist(lat, lon, d.lat, d.lon) > MAX_DATA_DISTANCE_M || SystemClock.elapsedRealtime() - d.timeMs > MAX_AGE_MS) return emptyList()
        return d.signs.filter { dist(lat, lon, it.lat, it.lon) <= radius }
            .sortedBy { dist(lat, lon, it.lat, it.lon) }
            .take(max)
    }

    private fun commit(v: Int, est: Boolean, now: Long) {
        val cur = shown
        if (cur == null) {
            shown = v; shownEst = est; shownMs = now; pendV = null; pendN = 0
        } else if (cur == v && est == shownEst) {
            shownMs = now; pendV = null; pendN = 0
        } else {
            if (pendV == v && pendEst == est) pendN++ else {
                pendV = v; pendEst = est; pendN = 1
            }
            if (pendN >= 2) {
                shown = v; shownEst = est; shownMs = now; pendV = null; pendN = 0
            }
        }
    }

    /** Gọi mỗi giây. heading = hướng đi hiện tại (độ). */
    fun state(lat: Double, lon: Double, heading: Double, moving: Boolean): SignsState {
        val d = data ?: return SignsState(
            null, false, emptyList(),
            if (fetching) "Đang tải biển báo..." else if (error.isNotEmpty()) error else "Chưa có dữ liệu"
        )
        val now = SystemClock.elapsedRealtime()
        if (d !== lastData) {
            lastData = d
            lastWay = null
            shown = null
            pendV = null
            pendN = 0
        }
        if (d.stale || dist(lat, lon, d.lat, d.lon) > MAX_DATA_DISTANCE_M || now - d.timeMs > MAX_AGE_MS) {
            shown = null
            lastWay = null
            pendV = null
            pendN = 0
            return SignsState(null, false, emptyList(), if (fetching) "Đang cập nhật biển báo..." else if (error.isNotEmpty()) error else "Dữ liệu biển báo đã cũ")
        }
        val kx = 111320.0 * cos(Math.toRadians(lat))
        val ky = 111320.0

        // 1) đoạn đường đang chạy -> giới hạn tốc độ
        var bestScore = 1e9
        var bestWay: Way? = null
        var bestFwd = true
        for (w in d.ways) {
            val p = w.pts
            val n = p.size / 2
            for (i in 0 until n - 1) {
                val ax = (p[2 * i + 1] - lon) * kx
                val ay = (p[2 * i] - lat) * ky
                val bx = (p[2 * i + 3] - lon) * kx
                val by = (p[2 * i + 2] - lat) * ky
                if ((ax > 30 && bx > 30) || (ax < -30 && bx < -30) || (ay > 30 && by > 30) || (ay < -30 && by < -30)) continue
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                if (len2 < 0.01) continue
                val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val px = ax + t * dx
                val py = ay + t * dy
                val dd = sqrt(px * px + py * py)
                if (dd > 25.0) continue
                val segB = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
                val a0 = angDiff(segB, heading)
                var score = dd
                if (moving && heading.isFinite()) {
                    val a = min(a0, 180.0 - a0)
                    if (a > 45.0) continue
                    score += 0.25 * a
                }
                if (w === lastWay) score -= 5.0
                if (score < bestScore) {
                    bestScore = score
                    bestWay = w
                    bestFwd = a0 <= 90.0
                }
            }
        }
        val w = bestWay
        if (w != null) {
            lastWay = w
            // Opposite-direction tags and generic highway types are not valid legal speed limits.
            val explicit = if (bestFwd) (w.fwd ?: w.all) else (w.bwd ?: w.all)
            if (explicit != null) commit(explicit, false, now)
            else { shown = null; pendV = null; pendN = 0 }
        } else {
            // Clear the previous road's value if road matching fails.
            shown = null
            lastWay = null
            pendV = null
            pendN = 0
        }

        // 2) biển báo phía trước (trong nón 30° trước mũi xe, tối đa 350 m)
        val cand = ArrayList<UpcomingSign>()
        for (s in d.signs) {
            val dx = (s.lon - lon) * kx
            val dy = (s.lat - lat) * ky
            val dd = sqrt(dx * dx + dy * dy)
            if (dd < 10.0 || dd > 350.0) continue
            val b = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
            if (moving && heading.isFinite()) {
                val a = angDiff(b, heading)
                if (a > 30.0) continue
                if (dd * sin(Math.toRadians(a)) > 22.0) continue
            }
            cand.add(UpcomingSign(s, dd.roundToInt()))
        }
        cand.sortBy { it.distM }
        val ups = ArrayList<UpcomingSign>()
        for (c in cand) {
            if (ups.none { it.item.type == c.item.type && abs(it.distM - c.distM) < 25 }) ups.add(c)
            if (ups.size >= 4) break
        }
        return SignsState(shown, shownEst, ups, if (shown == null) "Chưa xác định giới hạn trên đường" else "Giới hạn từ dữ liệu OSM")
    }
}
