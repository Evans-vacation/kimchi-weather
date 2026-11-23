package app.nalssi

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class Kind(val layout: Int) {
    BAR(R.layout.widget_bar),
    SMALL(R.layout.widget_small),
    MEDIUM(R.layout.widget_medium),
    LARGE(R.layout.widget_large)
}

/**
 * Look of one widget, chosen in WidgetConfigActivity.
 * transparency: 0 (solid) – 100 (clear). bg: "scene" (weather picture), "auto" (weather colour), "color" (own colour).
 */
data class WStyle(
    val transparency: Int = 0,
    val font: String = "default",
    val scale: Float = 1f,
    val bg: String = "scene",
    val color: Int = 0xFF2F6AA6.toInt()
)

object WidgetPrefs {
    private const val FILE = "nalssi_widgets"

    val FONTS = listOf(
        "default" to "기본",
        "light" to "가늘게",
        "bold" to "굵게",
        "serif" to "명조",
        "condensed" to "좁게",
        "mono" to "고정폭"
    )
    val SIZES = listOf(0.85f to "작게", 1f to "보통", 1.15f to "크게", 1.3f to "아주 크게")
    val BACKGROUNDS = listOf("scene" to "날씨 그림 (자동)", "auto" to "날씨 색 (자동)", "color" to "내가 고른 색")
    val COLORS = listOf(
        0xFF1F2A44, 0xFF2F6AA6, 0xFF3A8FB7, 0xFF2E8B7A, 0xFF3B7D4F, 0xFF5B4B8A,
        0xFF8E4B7E, 0xFFB5546B, 0xFFC0703A, 0xFF8A6B3E, 0xFF4A4F57, 0xFF16181C
    ).map { it.toInt() }

    fun get(ctx: Context, id: Int): WStyle {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return WStyle(
            p.getInt("t_$id", 0).coerceIn(0, 100),
            p.getString("f_$id", "default") ?: "default",
            p.getFloat("s_$id", 1f),
            p.getString("b_$id", "scene") ?: "scene",
            p.getInt("c_$id", 0xFF2F6AA6.toInt())
        )
    }

    fun save(ctx: Context, id: Int, s: WStyle) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("t_$id", s.transparency).putString("f_$id", s.font).putFloat("s_$id", s.scale)
            .putString("b_$id", s.bg).putInt("c_$id", s.color).apply()
    }

    fun delete(ctx: Context, id: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove("t_$id").remove("f_$id").remove("s_$id").remove("b_$id").remove("c_$id").apply()
    }

    fun typeface(font: String): Typeface = when (font) {
        "light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        "bold" -> Typeface.DEFAULT_BOLD
        "serif" -> Typeface.SERIF
        "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        "mono" -> Typeface.MONOSPACE
        else -> Typeface.DEFAULT
    }
}

/** Shared behaviour: each update fetches once and redraws every widget of all sizes. */
abstract class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                WidgetUpdater.refreshAll(context.applicationContext)
                Updater.maybeUpdateInBackground(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) WidgetPrefs.delete(context, id)
    }
}

class BarWidget : BaseWidget()
class SmallWidget : BaseWidget()
class MediumWidget : BaseWidget()
class LargeWidget : BaseWidget()

object WidgetUpdater {

    private val providers = listOf(
        Kind.BAR to BarWidget::class.java,
        Kind.SMALL to SmallWidget::class.java,
        Kind.MEDIUM to MediumWidget::class.java,
        Kind.LARGE to LargeWidget::class.java
    )

    private fun targets(ctx: Context): List<Pair<Kind, IntArray>> {
        val mgr = AppWidgetManager.getInstance(ctx)
        return providers.map { (kind, cls) -> kind to mgr.getAppWidgetIds(ComponentName(ctx, cls)) }
    }

    fun kindOf(ctx: Context, id: Int): Kind? {
        val cls = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id)?.provider?.className ?: return null
        return providers.firstOrNull { it.second.name == cls }?.first
    }

    /** Blocking; call from a background thread. */
    fun refreshAll(ctx: Context) {
        val all = targets(ctx)
        if (all.all { it.second.isEmpty() }) return
        val mgr = AppWidgetManager.getInstance(ctx)
        val place = Prefs.place(ctx)
        var failed = false
        val w = try {
            WeatherRepo.fetch(ctx, place)
        } catch (e: Exception) {
            failed = true
            WeatherRepo.cached(ctx)
        }
        for ((kind, ids) in all) {
            for (id in ids) {
                if (w != null) {
                    val rv = build(ctx, kind, place, w, WidgetPrefs.get(ctx, id))
                    if (failed && kind != Kind.BAR) rv.setTextViewText(R.id.updated, "연결 실패 · 이전 정보")
                    mgr.updateAppWidget(id, rv)
                } else {
                    val rv = RemoteViews(ctx.packageName, kind.layout)
                    rv.setTextViewText(R.id.updated, "연결 실패 · 탭해서 다시 시도")
                    rv.setOnClickPendingIntent(R.id.root, openApp(ctx))
                    mgr.partiallyUpdateAppWidget(id, rv)
                }
            }
        }
    }

    /** Redraw one widget from the cache right away (after its settings change). */
    fun renderOne(ctx: Context, id: Int) {
        val kind = kindOf(ctx, id) ?: return
        val w = WeatherRepo.cached(ctx) ?: return
        AppWidgetManager.getInstance(ctx).updateAppWidget(id, build(ctx, kind, Prefs.place(ctx), w, WidgetPrefs.get(ctx, id)))
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun deg(t: Double) = "${t.roundToInt()}°"

    private fun styled(text: String, font: String): CharSequence {
        if (font == "default" || text.isEmpty()) return text
        val s = SpannableString(text)
        val span: Any = when (font) {
            "light" -> TypefaceSpan("sans-serif-light")
            "bold" -> StyleSpan(Typeface.BOLD)
            "serif" -> TypefaceSpan("serif")
            "condensed" -> TypefaceSpan("sans-serif-condensed")
            "mono" -> TypefaceSpan("monospace")
            else -> return text
        }
        s.setSpan(span, 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return s
    }

    /** Sample data for the settings preview before anything has been fetched. */
    fun sample(): Weather {
        val today = LocalDate.now()
        val hours = (0..7).map { k ->
            Hour("${today}T${"%02d".format((13 + k) % 24)}:00", 4.0 + (if (k < 2) k else 2 - k), if (k < 3) 2 else 3, true, if (k > 4) 30 else 0)
        }
        val days = (0..5).map { k ->
            Day(today.plusDays(k.toLong()).toString(), listOf(2, 3, 61, 0, 1, 2)[k], 7.0 + k % 3, -1.0 + k % 2, listOf(0, 20, 70, 0, 10, 0)[k])
        }
        return Weather(4.0, 1.0, 2, true, hours, days, 38.0, 18.0, System.currentTimeMillis())
    }

    fun build(ctx: Context, kind: Kind, place: Place, w: Weather, st: WStyle): RemoteViews {
        val pkg = ctx.packageName
        val rv = RemoteViews(pkg, kind.layout)
        fun text(v: RemoteViews, id: Int, value: String, sp: Float) {
            v.setTextViewText(id, styled(value, st.font))
            v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp * st.scale)
        }

        val alpha = (255 * (100 - st.transparency) / 100f).roundToInt()
        when (st.bg) {
            "color" -> {
                rv.setImageViewResource(R.id.bg, R.drawable.bg_solid)
                rv.setInt(R.id.bg, "setColorFilter", st.color)
                rv.setViewVisibility(R.id.scrim, View.GONE)
            }
            "auto" -> {
                rv.setImageViewResource(R.id.bg, Wx.bg(w.code, w.isDay))
                rv.setInt(R.id.bg, "setColorFilter", 0)
                rv.setViewVisibility(R.id.scrim, View.GONE)
            }
            else -> {
                rv.setImageViewResource(R.id.bg, Wx.scene(w.code, w.isDay))
                rv.setInt(R.id.bg, "setColorFilter", 0)
                rv.setViewVisibility(R.id.scrim, View.VISIBLE)
                rv.setInt(R.id.scrim, "setImageAlpha", alpha)
            }
        }
        rv.setInt(R.id.bg, "setImageAlpha", alpha)
        rv.setOnClickPendingIntent(R.id.root, openApp(ctx))
        rv.setImageViewResource(R.id.icon, Wx.icon(w.code, w.isDay))

        val today = w.days.firstOrNull()
        val tempSp = when (kind) { Kind.BAR -> 26f; Kind.SMALL -> 42f; else -> 40f }
        text(rv, R.id.city, place.name, if (kind == Kind.BAR) 16f else 14f)
        text(rv, R.id.temp, deg(w.temp), tempSp)
        text(rv, R.id.cond, Wx.label(w.code), when (kind) { Kind.BAR -> 13f; Kind.SMALL -> 14f; else -> 13f })

        if (kind == Kind.BAR) {
            text(rv, R.id.hl, if (today != null) "${deg(today.max)} / ${deg(today.min)}" else "", 14f)
            text(rv, R.id.tip, Wx.advice(w), 12f)
            val step = Wx.dustStep(w.pm10, w.pm25)
            text(rv, R.id.dust, if (step != null) "미세 ${Wx.dustWord(step).removePrefix("아주 ")}" else "", 13f)
            if (step == null) {
                rv.setViewVisibility(R.id.meter, View.GONE)
            } else {
                rv.setViewVisibility(R.id.meter, View.VISIBLE)
                val lit = Wx.dustCells(step)
                listOf(R.id.m1, R.id.m2, R.id.m3).forEachIndexed { i, id ->
                    rv.setImageViewResource(id, if (i < lit) Wx.dustCell(step) else R.drawable.cell_off)
                }
            }
            // This week, from tomorrow
            rv.removeAllViews(R.id.week)
            w.days.drop(1).take(5).forEach { d ->
                val item = RemoteViews(pkg, R.layout.item_week)
                val name = try {
                    LocalDate.parse(d.date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
                } catch (e: Exception) {
                    ""
                }
                text(item, R.id.w_day, name, 12f)
                item.setImageViewResource(R.id.w_icon, Wx.icon(d.code, true))
                text(item, R.id.w_temp, deg(d.max), 12f)
                rv.addView(R.id.week, item)
            }
            return rv
        }

        text(rv, R.id.hl, if (today != null) "${deg(today.max)} / ${deg(today.min)} · 체감 ${deg(w.feels)}" else "체감 ${deg(w.feels)}", 12f)
        val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(w.fetchedAt))
        text(rv, R.id.updated, "$time 업데이트", 10f)

        if (kind == Kind.MEDIUM || kind == Kind.LARGE) {
            rv.removeAllViews(R.id.hourly)
            val count = if (kind == Kind.LARGE) 6 else 5
            for (h in w.hours.drop(1).take(count)) {
                val item = RemoteViews(pkg, R.layout.item_hour)
                text(item, R.id.h_time, "${h.time.substring(11, 13).toInt()}시", 11f)
                item.setImageViewResource(R.id.h_icon, Wx.icon(h.code, h.isDay))
                text(item, R.id.h_temp, deg(h.temp), 13f)
                text(item, R.id.h_pop, if (h.pop >= 20) "${h.pop}%" else "", 10f)
                rv.addView(R.id.hourly, item)
            }
        }

        if (kind == Kind.LARGE) {
            val pm10 = w.pm10?.let { "미세 ${Wx.dust(it, true)} ${it.roundToInt()}" } ?: "미세 -"
            val pm25 = w.pm25?.let { "초미세 ${Wx.dust(it, false)} ${it.roundToInt()}" } ?: "초미세 -"
            text(rv, R.id.dust, "$pm10  ·  $pm25 ㎍/㎥", 12f)

            rv.removeAllViews(R.id.daily)
            w.days.take(5).forEachIndexed { i, d ->
                val item = RemoteViews(pkg, R.layout.item_day)
                val name = if (i == 0) "오늘" else try {
                    LocalDate.parse(d.date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
                } catch (e: Exception) {
                    d.date.takeLast(5)
                }
                text(item, R.id.d_name, name, 13f)
                item.setImageViewResource(R.id.d_icon, Wx.icon(d.code, true))
                text(item, R.id.d_pop, if (d.pop >= 10) "${d.pop}%" else "", 12f)
                text(item, R.id.d_min, deg(d.min), 13f)
                text(item, R.id.d_max, deg(d.max), 13f)
                rv.addView(R.id.daily, item)
            }
        }
        return rv
    }
}
