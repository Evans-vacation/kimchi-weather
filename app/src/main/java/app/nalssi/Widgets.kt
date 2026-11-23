package app.nalssi

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class Kind(val layout: Int) {
    SMALL(R.layout.widget_small),
    MEDIUM(R.layout.widget_medium),
    LARGE(R.layout.widget_large)
}

/** Shared behaviour: each update fetches once and redraws every widget of all three sizes. */
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
}

class SmallWidget : BaseWidget()
class MediumWidget : BaseWidget()
class LargeWidget : BaseWidget()

object WidgetUpdater {

    private fun targets(ctx: Context): List<Pair<Kind, IntArray>> {
        val mgr = AppWidgetManager.getInstance(ctx)
        return listOf(
            Kind.SMALL to mgr.getAppWidgetIds(ComponentName(ctx, SmallWidget::class.java)),
            Kind.MEDIUM to mgr.getAppWidgetIds(ComponentName(ctx, MediumWidget::class.java)),
            Kind.LARGE to mgr.getAppWidgetIds(ComponentName(ctx, LargeWidget::class.java))
        )
    }

    /** Blocking; call from a background thread. */
    fun refreshAll(ctx: Context) {
        val all = targets(ctx)
        if (all.all { it.second.isEmpty() }) return
        val mgr = AppWidgetManager.getInstance(ctx)
        val place = Prefs.place(ctx)
        val w = try {
            WeatherRepo.fetch(place)
        } catch (e: Exception) {
            for ((kind, ids) in all) {
                if (ids.isEmpty()) continue
                val rv = RemoteViews(ctx.packageName, kind.layout)
                rv.setTextViewText(R.id.updated, "연결 실패 · 탭해서 다시 시도")
                rv.setOnClickPendingIntent(R.id.root, openApp(ctx))
                mgr.partiallyUpdateAppWidget(ids, rv)
            }
            return
        }
        for ((kind, ids) in all) {
            if (ids.isEmpty()) continue
            mgr.updateAppWidget(ids, build(ctx, kind, place, w))
        }
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun deg(t: Double) = "${t.roundToInt()}°"

    private fun build(ctx: Context, kind: Kind, place: Place, w: Weather): RemoteViews {
        val pkg = ctx.packageName
        val rv = RemoteViews(pkg, kind.layout)
        rv.setInt(R.id.root, "setBackgroundResource", Wx.bg(w.code, w.isDay))
        rv.setOnClickPendingIntent(R.id.root, openApp(ctx))
        rv.setTextViewText(R.id.city, place.name)
        rv.setTextViewText(R.id.temp, deg(w.temp))
        rv.setImageViewResource(R.id.icon, Wx.icon(w.code, w.isDay))
        rv.setTextViewText(R.id.cond, Wx.label(w.code))
        val today = w.days.firstOrNull()
        rv.setTextViewText(
            R.id.hl,
            if (today != null) "${deg(today.max)} / ${deg(today.min)} · 체감 ${deg(w.feels)}" else "체감 ${deg(w.feels)}"
        )
        val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(w.fetchedAt))
        rv.setTextViewText(R.id.updated, "$time 업데이트")

        if (kind != Kind.SMALL) {
            rv.removeAllViews(R.id.hourly)
            val count = if (kind == Kind.LARGE) 6 else 5
            for (h in w.hours.drop(1).take(count)) {
                val item = RemoteViews(pkg, R.layout.item_hour)
                item.setTextViewText(R.id.h_time, "${h.time.substring(11, 13).toInt()}시")
                item.setImageViewResource(R.id.h_icon, Wx.icon(h.code, h.isDay))
                item.setTextViewText(R.id.h_temp, deg(h.temp))
                item.setTextViewText(R.id.h_pop, if (h.pop >= 20) "${h.pop}%" else "")
                rv.addView(R.id.hourly, item)
            }
        }

        if (kind == Kind.LARGE) {
            val pm10 = w.pm10?.let { "미세 ${Wx.dust(it, true)} ${it.roundToInt()}" } ?: "미세 -"
            val pm25 = w.pm25?.let { "초미세 ${Wx.dust(it, false)} ${it.roundToInt()}" } ?: "초미세 -"
            rv.setTextViewText(R.id.dust, "$pm10  ·  $pm25 ㎍/㎥")

            rv.removeAllViews(R.id.daily)
            w.days.take(5).forEachIndexed { i, d ->
                val item = RemoteViews(pkg, R.layout.item_day)
                val name = if (i == 0) "오늘" else try {
                    LocalDate.parse(d.date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
                } catch (e: Exception) {
                    d.date.takeLast(5)
                }
                item.setTextViewText(R.id.d_name, name)
                item.setImageViewResource(R.id.d_icon, Wx.icon(d.code, true))
                item.setTextViewText(R.id.d_pop, if (d.pop >= 10) "${d.pop}%" else "")
                item.setTextViewText(R.id.d_min, deg(d.min))
                item.setTextViewText(R.id.d_max, deg(d.max))
                rv.addView(R.id.daily, item)
            }
        }
        return rv
    }
}
