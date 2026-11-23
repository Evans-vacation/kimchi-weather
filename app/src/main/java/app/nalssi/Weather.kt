package app.nalssi

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Place(val name: String, val lat: Double, val lon: Double)

data class Hour(val time: String, val temp: Double, val code: Int, val isDay: Boolean, val pop: Int)

data class Day(val date: String, val code: Int, val max: Double, val min: Double, val pop: Int)

data class Weather(
    val temp: Double,
    val feels: Double,
    val code: Int,
    val isDay: Boolean,
    val hours: List<Hour>,
    val days: List<Day>,
    val pm10: Double?,
    val pm25: Double?,
    val fetchedAt: Long
)

/** The place the app last showed; widgets use the same one. */
object Prefs {
    private const val FILE = "nalssi"

    fun place(ctx: Context): Place {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Place(
            p.getString("name", "서울") ?: "서울",
            p.getString("lat", "37.5665")?.toDoubleOrNull() ?: 37.5665,
            p.getString("lon", "126.978")?.toDoubleOrNull() ?: 126.978
        )
    }

    fun save(ctx: Context, place: Place) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("name", place.name)
            .putString("lat", place.lat.toString())
            .putString("lon", place.lon.toString())
            .apply()
    }
}

/** Open-Meteo client (free, no API key). Call off the main thread. */
object WeatherRepo {

    private const val CACHE = "nalssi_cache"

    /** Fetches, caches the raw answers (for widget previews and offline redraws), parses. */
    fun fetch(ctx: Context, place: Place): Weather {
        val fcText = get(
                "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                    "&current=temperature_2m,apparent_temperature,is_day,weather_code" +
                    "&hourly=temperature_2m,precipitation_probability,weather_code,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=7",
                10000
            )
        val aqText = try {
            get(
                "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${place.lat}" +
                    "&longitude=${place.lon}&current=pm10,pm2_5&timezone=auto",
                6000
            )
        } catch (e: Exception) {
            null
        }
        val now = System.currentTimeMillis()
        ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
            .putString("fc", fcText).putString("aq", aqText).putLong("time", now).apply()
        return parse(fcText, aqText, now)
    }

    /** Last successful answer, or null. */
    fun cached(ctx: Context): Weather? {
        val p = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
        val fc = p.getString("fc", null) ?: return null
        return try { parse(fc, p.getString("aq", null), p.getLong("time", 0L)) } catch (e: Exception) { null }
    }

    private fun parse(fcText: String, aqText: String?, fetchedAt: Long): Weather {
        val fc = JSONObject(fcText)
        val cur = fc.getJSONObject("current")
        val curHour = cur.getString("time").take(13)

        val h = fc.getJSONObject("hourly")
        val hTime = h.getJSONArray("time")
        val hours = ArrayList<Hour>()
        for (i in 0 until hTime.length()) {
            hours.add(
                Hour(
                    hTime.getString(i),
                    h.getJSONArray("temperature_2m").optDouble(i, 0.0),
                    h.getJSONArray("weather_code").optInt(i, 0),
                    h.getJSONArray("is_day").optInt(i, 1) == 1,
                    h.getJSONArray("precipitation_probability").optInt(i, 0)
                )
            )
        }
        val start = hours.indexOfFirst { it.time.take(13) == curHour }.coerceAtLeast(0)

        val d = fc.getJSONObject("daily")
        val dTime = d.getJSONArray("time")
        val days = ArrayList<Day>()
        for (i in 0 until dTime.length()) {
            days.add(
                Day(
                    dTime.getString(i),
                    d.getJSONArray("weather_code").optInt(i, 0),
                    d.getJSONArray("temperature_2m_max").optDouble(i, 0.0),
                    d.getJSONArray("temperature_2m_min").optDouble(i, 0.0),
                    d.getJSONArray("precipitation_probability_max").optInt(i, 0)
                )
            )
        }

        var pm10: Double? = null
        var pm25: Double? = null
        try {
            val aq = JSONObject(aqText ?: "{}").getJSONObject("current")
            if (!aq.isNull("pm10")) pm10 = aq.getDouble("pm10")
            if (!aq.isNull("pm2_5")) pm25 = aq.getDouble("pm2_5")
        } catch (e: Exception) {
            // air quality is optional
        }

        return Weather(
            temp = cur.getDouble("temperature_2m"),
            feels = cur.getDouble("apparent_temperature"),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            hours = hours.drop(start),
            days = days,
            pm10 = pm10,
            pm25 = pm25,
            fetchedAt = fetchedAt
        )
    }

    private fun get(url: String, timeout: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** Weather code → Korean label, icon and widget background. */
object Wx {
    fun label(c: Int): String = when (c) {
        0 -> "맑음"
        1 -> "대체로 맑음"
        2 -> "구름 조금"
        3 -> "흐림"
        45, 48 -> "안개"
        in 51..57 -> "이슬비"
        65 -> "강한 비"
        66, 67 -> "어는 비"
        in 61..64 -> "비"
        75 -> "많은 눈"
        77 -> "싸락눈"
        in 71..76 -> "눈"
        in 80..82 -> "소나기"
        85, 86 -> "눈 소나기"
        95 -> "뇌우"
        96, 99 -> "우박 동반 뇌우"
        else -> "-"
    }

    private fun cat(c: Int): String = when (c) {
        0, 1 -> "clear"
        2 -> "partly"
        3 -> "cloudy"
        45, 48 -> "fog"
        in 51..67, in 80..82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        in 95..99 -> "storm"
        else -> "cloudy"
    }

    fun icon(c: Int, day: Boolean): Int = when (cat(c)) {
        "clear" -> if (day) R.drawable.ic_w_clear_day else R.drawable.ic_w_clear_night
        "partly" -> if (day) R.drawable.ic_w_partly_day else R.drawable.ic_w_partly_night
        "fog" -> R.drawable.ic_w_fog
        "rain" -> R.drawable.ic_w_rain
        "snow" -> R.drawable.ic_w_snow
        "storm" -> R.drawable.ic_w_storm
        else -> R.drawable.ic_w_cloudy
    }

    fun scene(c: Int, day: Boolean): Int = when (cat(c)) {
        "clear" -> if (day) R.drawable.scene_clear_day else R.drawable.scene_clear_night
        "partly" -> if (day) R.drawable.scene_partly_day else R.drawable.scene_partly_night
        "fog" -> if (day) R.drawable.scene_fog_day else R.drawable.scene_fog_night
        "rain" -> if (day) R.drawable.scene_rain_day else R.drawable.scene_rain_night
        "snow" -> if (day) R.drawable.scene_snow_day else R.drawable.scene_snow_night
        "storm" -> if (day) R.drawable.scene_storm_day else R.drawable.scene_storm_night
        else -> if (day) R.drawable.scene_cloudy_day else R.drawable.scene_cloudy_night
    }

    fun bg(c: Int, day: Boolean): Int = when (cat(c)) {
        "clear" -> if (day) R.drawable.bg_clear_day else R.drawable.bg_clear_night
        "partly" -> if (day) R.drawable.bg_partly_day else R.drawable.bg_partly_night
        "fog" -> if (day) R.drawable.bg_fog else R.drawable.bg_night_dim
        "rain" -> if (day) R.drawable.bg_rain else R.drawable.bg_night_dim
        "snow" -> if (day) R.drawable.bg_snow else R.drawable.bg_night_dim
        "storm" -> R.drawable.bg_storm
        else -> if (day) R.drawable.bg_cloudy else R.drawable.bg_night_dim
    }

    /**
     * 9-step dust level from the worse of PM10 / PM2.5, 0 = cleanest … 8 = worst.
     * 0-2 green (좋음), 3-5 blue (보통), 6-8 red (나쁨/위험), each group split into 3 by the 환경부 bands.
     */
    fun dustStep(pm10: Double?, pm25: Double?): Int? {
        val c10 = doubleArrayOf(10.0, 20.0, 30.0, 45.0, 60.0, 80.0, 115.0, 150.0)
        val c25 = doubleArrayOf(5.0, 10.0, 15.0, 22.0, 29.0, 35.0, 55.0, 75.0)
        fun step(v: Double, cuts: DoubleArray) = cuts.count { v > it }
        val a = pm10?.let { step(it, c10) }
        val b = pm25?.let { step(it, c25) }
        return when {
            a == null && b == null -> null
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }
    }

    /** Lit cells for the 3-cell meter: green fills up as air gets cleaner, blue and red fill up as it gets worse. */
    fun dustCells(step: Int): Int = if (step < 3) 3 - step else step % 3 + 1

    fun dustCell(step: Int): Int = when {
        step < 3 -> R.drawable.cell_green
        step < 6 -> R.drawable.cell_blue
        else -> R.drawable.cell_red
    }

    fun dustWord(step: Int): String = when (step) {
        0 -> "아주 좋음"
        1, 2 -> "좋음"
        3, 4, 5 -> "보통"
        6, 7 -> "나쁨"
        else -> "위험"
    }

    /**
     * A short tip for today that fits two widget lines, e.g. "우산 챙기고 패딩 입어요".
     * First what's coming (storm, snow, rain, bad air), then what to wear.
     */
    fun advice(w: Weather): String {
        val next = w.hours.take(12)
        val pop = next.maxOfOrNull { it.pop } ?: 0
        val codes = listOf(w.code) + next.map { it.code }
        val today = w.days.firstOrNull()
        val dust = dustStep(w.pm10, w.pm25)

        // (joined form, standalone form)
        val coming: Pair<String, String>? = when {
            codes.any { it >= 95 } -> "천둥번개 조심," to "천둥번개 조심하세요"
            codes.any { it == 75 || it == 86 } -> "겨울타이어 챙기고" to "겨울타이어 챙기세요"
            codes.any { it in 71..77 || it == 85 } && pop >= 50 -> "빙판길 조심," to "빙판길 조심하세요"
            (codes.any { it in 51..67 || it in 80..82 } && pop >= 60) || w.code in 61..67 || w.code in 80..82 ->
                "우산 챙기고" to "우산 꼭 챙기세요"
            pop >= 40 -> "작은 우산 챙기고" to "작은 우산 챙기세요"
            dust != null && dust >= 6 -> "마스크 쓰고" to "마스크 꼭 쓰세요"
            else -> null
        }

        val feels = w.feels
        val max = today?.max ?: w.temp
        val min = today?.min ?: w.temp
        val wear: String? = when {
            feels <= -10 -> "패딩·장갑 꼭 챙겨요"
            feels <= -3 -> "패딩 입어요"
            feels <= 5 -> "두꺼운 외투 입어요"
            max >= 33 -> "물 자주 마셔요"
            max >= 28 -> "시원하게 입어요"
            max - min >= 10 -> "겉옷 챙겨요"
            feels <= 12 -> "가벼운 겉옷 챙겨요"
            else -> null
        }
        return when {
            coming != null && wear != null -> "${coming.first} $wear"
            coming != null -> coming.second
            wear != null -> wear
            else -> "외출하기 좋은 날이에요"
        }
    }

    /** Korean air-quality grades (환경부 기준). */
    fun dust(v: Double?, pm10: Boolean): String {
        if (v == null) return "-"
        val cuts = if (pm10) listOf(30.0, 80.0, 150.0) else listOf(15.0, 35.0, 75.0)
        val names = listOf("좋음", "보통", "나쁨", "매우 나쁨")
        var i = 0
        while (i < 3 && v > cuts[i]) i++
        return names[i]
    }
}
