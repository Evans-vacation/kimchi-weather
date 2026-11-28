package app.nalssi

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * 기상청 초단기실황 (actual observations, refreshed every hour) through 기상청 API허브.
 * Used for "지금" only — temperature, rain/snow yes-or-no, humidity, wind — because the forecast
 * model (Open-Meteo) sometimes says "이슬비" when nothing is falling.
 *
 * The key comes from the GitHub secret KMA_KEY at build time (BuildConfig.KMA_KEY).
 * No key, outside Korea, or any error → null, and the app keeps the Open-Meteo values.
 */
object Kma {

    data class Obs(
        val temp: Double?,      // T1H  기온 (°C)
        val pty: Int,           // PTY  강수형태: 0 없음, 1 비, 2 비/눈, 3 눈, 5 빗방울, 6 빗방울눈날림, 7 눈날림
        val rn1: Double?,       // RN1  1시간 강수량 (mm)
        val reh: Double?,       // REH  습도 (%)
        val wsd: Double?,       // WSD  풍속 (m/s)
        val vec: Double?,       // VEC  풍향 (deg)
        val base: String,       // yyyyMMddHHmm (KST) of the observation
        val nx: Int,
        val ny: Int
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("temp", temp ?: JSONObject.NULL).put("pty", pty).put("rn1", rn1 ?: JSONObject.NULL)
            .put("reh", reh ?: JSONObject.NULL).put("wsd", wsd ?: JSONObject.NULL).put("vec", vec ?: JSONObject.NULL)
            .put("base", base).put("nx", nx).put("ny", ny)

        companion object {
            fun fromJson(o: JSONObject): Obs = Obs(
                if (o.isNull("temp")) null else o.getDouble("temp"), o.optInt("pty", 0),
                if (o.isNull("rn1")) null else o.getDouble("rn1"), if (o.isNull("reh")) null else o.getDouble("reh"),
                if (o.isNull("wsd")) null else o.getDouble("wsd"), if (o.isNull("vec")) null else o.getDouble("vec"),
                o.optString("base"), o.optInt("nx"), o.optInt("ny")
            )
        }
    }

    fun available(): Boolean = BuildConfig.KMA_KEY.isNotBlank()

    /** Rough box around South Korea (the 동네예보 grid). */
    fun inKorea(lat: Double, lon: Double) = lat in 32.8..38.9 && lon in 124.2..131.9

    private var memo: Pair<String, Pair<Long, Obs>>? = null

    /** Call off the main thread. */
    fun now(lat: Double, lon: Double): Obs? {
        if (!available() || !inKorea(lat, lon)) return null
        val (nx, ny) = grid(lat, lon)
        val key = "$nx,$ny"
        memo?.let { (k, v) -> if (k == key && System.currentTimeMillis() - v.first < 10 * 60_000L) return v.second }
        val kst = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
        // 매시 정각 자료가 40분쯤 올라와요. 그 전이면 한 시간 전 자료를 받아요; 그것도 없으면 두 시간 전.
        if (kst.get(Calendar.MINUTE) < 45) kst.add(Calendar.HOUR_OF_DAY, -1)
        for (attempt in 0..1) {
            val date = "%04d%02d%02d".format(kst.get(Calendar.YEAR), kst.get(Calendar.MONTH) + 1, kst.get(Calendar.DAY_OF_MONTH))
            val time = "%02d00".format(kst.get(Calendar.HOUR_OF_DAY))
            val obs = try { fetch(date, time, nx, ny) } catch (e: Exception) { null }
            if (obs != null) { memo = key to (System.currentTimeMillis() to obs); return obs }
            kst.add(Calendar.HOUR_OF_DAY, -1)
        }
        return null
    }

    private fun fetch(date: String, time: String, nx: Int, ny: Int): Obs? {
        val url = "https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/getUltraSrtNcst" +
            "?pageNo=1&numOfRows=20&dataType=JSON&base_date=$date&base_time=$time&nx=$nx&ny=$ny&authKey=${BuildConfig.KMA_KEY}"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        val text = try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        val resp = JSONObject(text).optJSONObject("response") ?: return null
        if (resp.optJSONObject("header")?.optString("resultCode") != "00") return null
        val items = resp.optJSONObject("body")?.optJSONObject("items")?.optJSONArray("item") ?: return null
        val v = HashMap<String, Double>()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            it.optString("obsrValue").trim().toDoubleOrNull()?.let { x -> v[it.optString("category")] = x }
        }
        if (v.isEmpty()) return null
        fun ok(k: String) = v[k]?.takeIf { it > -900 && it < 900 }     // -998.9 etc. = 결측
        return Obs(ok("T1H"), ok("PTY")?.toInt() ?: 0, ok("RN1"), ok("REH"), ok("WSD"), ok("VEC"), date + time, nx, ny)
    }

    // ---- forecasts: 초단기예보 (next 6 h) + 단기예보 (next 3–5 days) ---------------------------------

    /** One forecast hour from 기상청, keyed by "yyyy-MM-ddTHH:00" (KST, same as Open-Meteo times in Korea). */
    data class Fc(val temp: Double?, val pop: Int?, val code: Int?)

    private var fcMemo: Pair<String, Pair<Long, Map<String, Fc>>>? = null

    /** Call off the main thread. Empty map when there is no key, outside Korea, or on error. */
    fun forecast(lat: Double, lon: Double): Map<String, Fc> {
        if (!available() || !inKorea(lat, lon)) return emptyMap()
        val (nx, ny) = grid(lat, lon)
        val key = "$nx,$ny"
        fcMemo?.let { (k, v) -> if (k == key && System.currentTimeMillis() - v.first < 20 * 60_000L) return v.second }
        val out = LinkedHashMap<String, Fc>()
        // 단기예보: 02, 05, 08, 11, 14, 17, 20, 23시 발표, 10분쯤 뒤에 올라와요.
        try {
            val k = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
            k.add(Calendar.MINUTE, -15)
            var h = k.get(Calendar.HOUR_OF_DAY)
            val back = ((h - 2) % 3 + 3) % 3
            k.add(Calendar.HOUR_OF_DAY, -back); h = k.get(Calendar.HOUR_OF_DAY)
            out.putAll(fcst("getVilageFcst", ymd(k), "%02d00".format(h), nx, ny, short = true))
        } catch (e: Exception) { }
        // 초단기예보: 매시 30분 발표, 45분쯤 올라와요. 앞 6시간은 이게 더 정확해서 덮어써요.
        try {
            val k = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
            k.add(Calendar.MINUTE, -45)
            val u = fcst("getUltraSrtFcst", ymd(k), "%02d30".format(k.get(Calendar.HOUR_OF_DAY)), nx, ny, short = false)
            for ((t, f) in u) {
                val old = out[t]
                out[t] = Fc(f.temp ?: old?.temp, f.pop ?: old?.pop, f.code ?: old?.code)
            }
        } catch (e: Exception) { }
        if (out.isNotEmpty()) fcMemo = key to (System.currentTimeMillis() to out)
        return out
    }

    fun forecastJson(m: Map<String, Fc>): JSONObject {
        val o = JSONObject()
        for ((t, f) in m) o.put(t, JSONObject().put("t", f.temp ?: JSONObject.NULL).put("p", f.pop ?: JSONObject.NULL).put("c", f.code ?: JSONObject.NULL))
        return o
    }

    private fun ymd(k: Calendar) = "%04d%02d%02d".format(k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH))

    private fun fcst(op: String, date: String, time: String, nx: Int, ny: Int, short: Boolean): Map<String, Fc> {
        val url = "https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/$op" +
            "?pageNo=1&numOfRows=2000&dataType=JSON&base_date=$date&base_time=$time&nx=$nx&ny=$ny&authKey=${BuildConfig.KMA_KEY}"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 7000
        c.readTimeout = 9000
        val text = try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        val resp = JSONObject(text).optJSONObject("response") ?: return emptyMap()
        if (resp.optJSONObject("header")?.optString("resultCode") != "00") return emptyMap()
        val items = resp.optJSONObject("body")?.optJSONObject("items")?.optJSONArray("item") ?: return emptyMap()
        val byTime = LinkedHashMap<String, HashMap<String, String>>()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val d = it.optString("fcstDate"); val t = it.optString("fcstTime")
            if (d.length != 8 || t.length != 4) continue
            val k = "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}T${t.substring(0, 2)}:00"
            byTime.getOrPut(k) { HashMap() }[it.optString("category")] = it.optString("fcstValue")
        }
        val out = LinkedHashMap<String, Fc>()
        for ((k, v) in byTime) {
            val temp = (if (short) v["TMP"] else v["T1H"])?.toDoubleOrNull()?.takeIf { it > -90 && it < 90 }
            val pop = v["POP"]?.toIntOrNull()?.takeIf { it in 0..100 }
            out[k] = Fc(temp, pop, fcCode(v["SKY"]?.toIntOrNull(), v["PTY"]?.toIntOrNull(), v["LGT"]?.toDoubleOrNull()))
        }
        return out
    }

    /** 하늘상태 SKY (1 맑음, 3 구름많음, 4 흐림) + 강수형태 PTY + 낙뢰 LGT → Open-Meteo weather code. */
    fun fcCode(sky: Int?, pty: Int?, lgt: Double?): Int? {
        if (lgt != null && lgt > 0) return 95
        when (pty) {
            1 -> return 61
            2, 6 -> return 66
            3 -> return 73
            4 -> return 80
            5 -> return 51
            7 -> return 71
        }
        return when (sky) { 1 -> 0; 3 -> 2; 4 -> 3; else -> null }
    }

    // ---- 중기예보: 4~10일 후 (하루 한 번 18시 발표) -------------------------------------------------

    /** One day from 기상청 중기예보, keyed by "yyyy-MM-dd". */
    data class MidDay(val min: Double?, val max: Double?, val pop: Int?, val code: Int?)

    /** 중기기온 city (예보지점) with its 중기육상 region. Nearest one is used. */
    private class MidPt(val lat: Double, val lon: Double, val ta: String, val land: String)
    private val MID = listOf(
        MidPt(37.57, 126.98, "11B10101", "11B00000"), MidPt(37.46, 126.71, "11B20201", "11B00000"), // 서울, 인천
        MidPt(37.26, 127.03, "11B20601", "11B00000"), MidPt(37.76, 126.78, "11B20305", "11B00000"), // 수원, 파주
        MidPt(37.88, 127.73, "11D10301", "11D10000"), MidPt(37.34, 127.92, "11D10401", "11D10000"), // 춘천, 원주
        MidPt(37.75, 128.88, "11D20501", "11D20000"),                                              // 강릉
        MidPt(36.35, 127.38, "11C20401", "11C20000"), MidPt(36.48, 127.29, "11C20404", "11C20000"), // 대전, 세종
        MidPt(36.60, 126.66, "11C20104", "11C20000"),                                              // 홍성
        MidPt(36.64, 127.49, "11C10301", "11C10000"), MidPt(36.99, 127.93, "11C10101", "11C10000"), // 청주, 충주
        MidPt(35.16, 126.85, "11F20501", "11F20000"), MidPt(34.81, 126.39, "21F20801", "11F20000"), // 광주, 목포
        MidPt(34.76, 127.66, "11F20401", "11F20000"),                                              // 여수
        MidPt(35.82, 127.15, "11F10201", "11F10000"), MidPt(35.97, 126.74, "21F10501", "11F10000"), // 전주, 군산
        MidPt(35.87, 128.60, "11H10701", "11H10000"), MidPt(36.57, 128.73, "11H10501", "11H10000"), // 대구, 안동
        MidPt(36.02, 129.34, "11H10201", "11H10000"),                                              // 포항
        MidPt(35.18, 129.08, "11H20201", "11H20000"), MidPt(35.54, 129.31, "11H20101", "11H20000"), // 부산, 울산
        MidPt(35.23, 128.68, "11H20301", "11H20000"), MidPt(35.18, 128.11, "11H20701", "11H20000"), // 창원, 진주
        MidPt(33.50, 126.53, "11G00201", "11G00000"), MidPt(33.25, 126.56, "11G00401", "11G00000")  // 제주, 서귀포
    )

    private var midMemo: Pair<String, Pair<Long, Map<String, MidDay>>>? = null

    /** Call off the main thread. Empty map when there is no key, outside Korea, or on error. */
    fun mid(lat: Double, lon: Double): Map<String, MidDay> {
        if (!available() || !inKorea(lat, lon)) return emptyMap()
        val k2 = cos(lat * PI / 180).let { it * it }
        val pt = MID.minBy { (it.lat - lat).pow(2) + (it.lon - lon).pow(2) * k2 }
        midMemo?.let { (k, v) -> if (k == pt.ta && System.currentTimeMillis() - v.first < 60 * 60_000L) return v.second }
        // 발표: 매일 18시 (2026.11.27.~). 18시 15분 전이면 어제 18시 발표분을 받아요.
        val issue = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
        if (issue.get(Calendar.HOUR_OF_DAY) * 60 + issue.get(Calendar.MINUTE) < 18 * 60 + 15) issue.add(Calendar.DAY_OF_MONTH, -1)
        val tmFc = ymd(issue) + "1800"
        val ta = try { midItem("getMidTa", pt.ta, tmFc) } catch (e: Exception) { null }
        val land = try { midItem("getMidLandFcst", pt.land, tmFc) } catch (e: Exception) { null }
        if (ta == null && land == null) return emptyMap()
        val out = LinkedHashMap<String, MidDay>()
        for (n in 3..10) {
            val d = issue.clone() as Calendar
            d.add(Calendar.DAY_OF_MONTH, n)
            val key = "%04d-%02d-%02d".format(d.get(Calendar.YEAR), d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH))
            fun num(o: JSONObject?, f: String) = o?.opt(f)?.toString()?.trim()?.toDoubleOrNull()
            val min = num(ta, "taMin$n"); val max = num(ta, "taMax$n")
            val pop = if (n <= 7) listOfNotNull(num(land, "rnSt${n}Am"), num(land, "rnSt${n}Pm")).maxOrNull() else num(land, "rnSt$n")
            val wf = if (n <= 7) listOfNotNull(land?.optString("wf${n}Am"), land?.optString("wf${n}Pm")).filter { it.isNotBlank() }
                     else listOfNotNull(land?.optString("wf$n")).filter { it.isNotBlank() }
            val codes = wf.mapNotNull { wfCode(it) }
            val code = codes.firstOrNull { it >= 51 } ?: codes.maxOrNull()
            if (min == null && max == null && pop == null && code == null) continue
            out[key] = MidDay(min, max, pop?.toInt(), code)
        }
        if (out.isNotEmpty()) midMemo = pt.ta to (System.currentTimeMillis() to out)
        return out
    }

    fun midJson(m: Map<String, MidDay>): JSONObject {
        val o = JSONObject()
        for ((d, v) in m) o.put(d, JSONObject().put("mn", v.min ?: JSONObject.NULL).put("mx", v.max ?: JSONObject.NULL)
            .put("p", v.pop ?: JSONObject.NULL).put("c", v.code ?: JSONObject.NULL))
        return o
    }

    private fun midItem(op: String, reg: String, tmFc: String): JSONObject? {
        val url = "https://apihub.kma.go.kr/api/typ02/openApi/MidFcstInfoService/$op" +
            "?pageNo=1&numOfRows=10&dataType=JSON&regId=$reg&tmFc=$tmFc&authKey=${BuildConfig.KMA_KEY}"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 7000
        c.readTimeout = 7000
        val text = try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        val resp = JSONObject(text).optJSONObject("response") ?: return null
        if (resp.optJSONObject("header")?.optString("resultCode") != "00") return null
        val items = resp.optJSONObject("body")?.optJSONObject("items") ?: return null
        return items.optJSONArray("item")?.optJSONObject(0) ?: items.optJSONObject("item")
    }

    /** 중기 날씨 글 ("구름많고 비", "흐리고 눈/비" …) → Open-Meteo weather code. */
    fun wfCode(wf: String): Int? {
        val w = wf.replace(" ", "")
        return when {
            w.contains("비/눈") || w.contains("눈/비") -> 66
            w.contains("소나기") -> 80
            w.contains("눈") -> 73
            w.contains("비") -> 61
            w.contains("흐림") || w.contains("흐리") -> 3
            w.contains("구름많") -> 2
            w.contains("맑") -> 0
            else -> null
        }
    }

    /**
     * Open-Meteo weather code corrected by what is actually falling.
     * Nothing falling → keep the sky (clear/cloudy/fog) but drop rain/snow; something falling → show it.
     */
    fun correctCode(model: Int, pty: Int): Int {
        val modelWet = model in 51..67 || model in 71..77 || model in 80..86 || model >= 95
        return when (pty) {
            0 -> if (modelWet) 3 else model
            1 -> if (model in 61..65 || model in 80..82 || model >= 95) model else 61
            2, 6 -> 66                          // 비/눈 섞임 → "어는 비" 칸이 가장 가까워요
            3 -> if (model in 71..77 || model == 85 || model == 86) model else 73
            5 -> if (model in 51..57) model else 51
            7 -> 71
            else -> model
        }
    }

    /** 위경도 → 기상청 동네예보 격자 (Lambert conformal conic, 5 km). */
    fun grid(lat: Double, lon: Double): Pair<Int, Int> {
        val re = 6371.00877 / 5.0
        val slat1 = 30.0 * PI / 180; val slat2 = 60.0 * PI / 180
        val olon = 126.0 * PI / 180; val olat = 38.0 * PI / 180
        val xo = 43.0; val yo = 136.0
        var sn = tan(PI * 0.25 + slat2 * 0.5) / tan(PI * 0.25 + slat1 * 0.5)
        sn = ln(cos(slat1) / cos(slat2)) / ln(sn)
        var sf = tan(PI * 0.25 + slat1 * 0.5)
        sf = sf.pow(sn) * cos(slat1) / sn
        var ro = tan(PI * 0.25 + olat * 0.5)
        ro = re * sf / ro.pow(sn)
        var ra = tan(PI * 0.25 + lat * PI / 180 * 0.5)
        ra = re * sf / ra.pow(sn)
        var theta = lon * PI / 180 - olon
        if (theta > PI) theta -= 2.0 * PI
        if (theta < -PI) theta += 2.0 * PI
        theta *= sn
        val x = floor(ra * sin(theta) + xo + 0.5).toInt()
        val y = floor(ro - ra * cos(theta) + yo + 0.5).toInt()
        return x to y
    }

}
