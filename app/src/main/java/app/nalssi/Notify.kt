package app.nalssi

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Notifications:
 *  - 비·눈 시작 알림: every ~30 min, checks the next 2 hours; quiet 23:00–06:00; at most once per 3 h,
 *    and not again until it has been dry for 2 h.
 *  - 아침 브리핑: once a day at the chosen time.
 * Wording matches the in-app preview (weather.html: rainNotice / briefNotice).
 */
object Notifier {
    private const val PREFS = "nalssi_notify"
    const val CH_RAIN = "rain"
    const val CH_BRIEF = "brief"
    const val ACTION_RAIN = "app.nalssi.RAIN_CHECK"
    const val ACTION_BRIEF = "app.nalssi.BRIEF"

    // ---- settings -------------------------------------------------------------------------

    fun settings(ctx: Context): JSONObject {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONObject()
            .put("rain", p.getBoolean("rain", false))
            .put("brief", p.getBoolean("brief", false))
            .put("time", p.getString("time", "07:00"))
            .put("permission", canPost(ctx))
    }

    fun save(ctx: Context, json: String) {
        val o = JSONObject(json)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("rain", o.optBoolean("rain", false))
            .putBoolean("brief", o.optBoolean("brief", false))
            .putString("time", o.optString("time", "07:00").ifBlank { "07:00" })
            .apply()
        schedule(ctx)
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // ---- channels & scheduling ------------------------------------------------------------

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_RAIN, "비·눈 시작 알림", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "비나 눈이 곧 시작될 때" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_BRIEF, "아침 날씨 브리핑", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "매일 정한 시간에 오늘 날씨" }
        )
    }

    private fun alarmIntent(ctx: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, action.hashCode(),
            Intent(ctx, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** (Re)arms both alarms according to the settings. */
    fun schedule(ctx: Context) {
        scheduleRain(ctx)
        scheduleBrief(ctx)
    }

    private fun scheduleRain(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rain = alarmIntent(ctx, ACTION_RAIN)
        am.cancel(rain)
        if (p.getBoolean("rain", false)) {
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5 * 60 * 1000L,
                AlarmManager.INTERVAL_HALF_HOUR,
                rain
            )
        }
    }

    fun scheduleBrief(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val brief = alarmIntent(ctx, ACTION_BRIEF)
        am.cancel(brief)
        if (p.getBoolean("brief", false)) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextBriefTime(p.getString("time", "07:00") ?: "07:00"), brief)
        }
    }

    private fun nextBriefTime(time: String): Long {
        val (h, m) = time.split(":").map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 7 }) to (it.getOrElse(1) { 0 }) }
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, m)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis() + 30_000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    // ---- posting --------------------------------------------------------------------------

    fun post(ctx: Context, channel: String, id: Int, title: String, body: String) {
        if (!canPost(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        channels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }

    // ---- wording --------------------------------------------------------------------------

    private fun isWet(c: Int) = c in 51..67 || c in 71..77 || c in 80..86 || c >= 95
    private fun isSnow(c: Int) = c in 71..77 || c == 85 || c == 86

    private fun hourLabel(time: String): String {
        val h = time.substring(11, 13).toIntOrNull() ?: 0
        val h12 = if (h % 12 == 0) 12 else h % 12
        return (if (h < 12) "오전 " else "오후 ") + h12 + "시"
    }

    /** First wet hour within [maxH] hours (w.hours[0] is the current hour). */
    private fun upcoming(w: Weather, maxH: Int): Hour? =
        w.hours.drop(1).take(maxH).firstOrNull { isWet(it.code) && it.pop >= 50 }

    fun rainText(place: Place, w: Weather): Pair<String, String>? {
        val u = upcoming(w, 2) ?: return null
        val snow = isSnow(u.code)
        val what = if (snow) "눈" else "비"
        val title = if (snow) "곧 눈이 와요 ❄️" else "곧 비가 와요 ☔"
        val body = "${place.name}에 ${hourLabel(u.time)}쯤부터 $what 소식이 있어요 (확률 ${u.pop}%). " +
            if (snow) "미끄럼 조심하세요." else "우산 챙기세요."
        return title to body
    }

    fun briefText(place: Place, w: Weather): Pair<String, String> {
        val today = w.days.firstOrNull()
        val nowHour = w.hours.firstOrNull()?.time?.substring(11, 13)?.toIntOrNull() ?: 7
        val u = upcoming(w, (23 - nowHour).coerceAtLeast(1))
        val cmp = w.yesterday?.let { y ->
            val d = w.temp - y
            if (abs(d) < 1) " · 어제와 비슷" else " · 어제보다 ${abs(d).roundToInt()}° ${if (d < 0) "낮음" else "높음"}"
        } ?: ""
        val range = if (today != null) " · 최저 ${today.min.roundToInt()}° / 최고 ${today.max.roundToInt()}°" else ""
        val step = Wx.dustStep(w.pm10, w.pm25)
        val lines = listOfNotNull(
            "${Wx.label(w.code)} ${w.temp.roundToInt()}°$range$cmp",
            if (u != null) "${hourLabel(u.time)}쯤 ${if (isSnow(u.code)) "눈" else "비"} 소식 (확률 ${u.pop}%)" else "오늘은 비 소식이 없어요",
            step?.let { "미세먼지 ${Wx.dustWord(it)}" },
            Wx.advice(w)
        )
        return "오늘의 날씨 · ${place.name}" to lines.joinToString("\n")
    }

    // ---- jobs (background thread) ---------------------------------------------------------

    fun checkRain(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("rain", false)) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val place = Prefs.place(ctx)
        val w = try { WeatherRepo.fetch(ctx, place) } catch (e: Exception) { return }
        val now = System.currentTimeMillis()
        if (isWet(w.code)) {                      // already raining: remember, don't announce
            p.edit().putLong("lastWet", now).apply()
            return
        }
        if (hour >= 23 || hour < 6) return        // quiet hours
        if (now - p.getLong("lastWet", 0L) < 2 * 3600_000L) return
        if (now - p.getLong("lastAlert", 0L) < 3 * 3600_000L) return
        val (title, body) = rainText(place, w) ?: return
        post(ctx, CH_RAIN, 101, title, body)
        p.edit().putLong("lastAlert", now).apply()
    }

    fun briefing(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("brief", false)) return
        val place = Prefs.place(ctx)
        val w = try { WeatherRepo.fetch(ctx, place) } catch (e: Exception) { WeatherRepo.cached(ctx) } ?: return
        val (title, body) = briefText(place, w)
        post(ctx, CH_BRIEF, 102, title, body)
    }
}

/** Alarm, boot and app-update events. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            Notifier.ACTION_RAIN, Notifier.ACTION_BRIEF -> {
                val pending = goAsync()
                Thread {
                    try {
                        if (intent.action == Notifier.ACTION_RAIN) Notifier.checkRain(ctx) else Notifier.briefing(ctx)
                    } finally {
                        if (intent.action == Notifier.ACTION_BRIEF) Notifier.scheduleBrief(ctx)   // tomorrow
                        pending.finish()
                    }
                }.start()
            }
            else -> Notifier.schedule(ctx)        // BOOT_COMPLETED, MY_PACKAGE_REPLACED
        }
    }
}
