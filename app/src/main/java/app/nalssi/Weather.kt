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

    fun fetch(place: Place): Weather {
        val fc = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                    "&current=temperature_2m,apparent_temperature,is_day,weather_code" +
                    "&hourly=temperature_2m,precipitation_probability,weather_code,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=7",
                10000
            )
        )
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
            val aq = JSONObject(
                get(
                    "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${place.lat}" +
                        "&longitude=${place.lon}&current=pm10,pm2_5&timezone=auto",
                    6000
                )
            ).getJSONObject("current")
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
            fetchedAt = System.currentTimeMillis()
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

    fun bg(c: Int, day: Boolean): Int = when (cat(c)) {
        "clear" -> if (day) R.drawable.bg_clear_day else R.drawable.bg_clear_night
        "partly" -> if (day) R.drawable.bg_partly_day else R.drawable.bg_partly_night
        "fog" -> if (day) R.drawable.bg_fog else R.drawable.bg_night_dim
        "rain" -> if (day) R.drawable.bg_rain else R.drawable.bg_night_dim
        "snow" -> if (day) R.drawable.bg_snow else R.drawable.bg_night_dim
        "storm" -> R.drawable.bg_storm
        else -> if (day) R.drawable.bg_cloudy else R.drawable.bg_night_dim
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
