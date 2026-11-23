package app.nalssi

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Widget settings: background transparency, font and text size, with a live preview.
 * Opens when the user long-presses a widget → 설정 (or right after adding one on older Android).
 */
class WidgetConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var kind = Kind.MEDIUM
    private var style = WStyle()
    private lateinit var preview: FrameLayout
    private lateinit var alphaLabel: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        kind = WidgetUpdater.kindOf(this, widgetId) ?: Kind.MEDIUM
        style = WidgetPrefs.get(this, widgetId)

        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        scroll.addView(col)
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }

        col.addView(TextView(this).apply {
            text = "위젯 설정"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = when (kind) {
                Kind.BAR -> "한 줄 위젯 (1×4)"
                Kind.SMALL -> "소형 위젯 (2×2)"
                Kind.MEDIUM -> "중형 위젯 (4×2)"
                Kind.LARGE -> "대형 위젯 (4×4)"
            }
            alpha = 0.7f
            setPadding(0, dp(2), 0, dp(14))
        })

        // Live preview on a wallpaper-like backdrop
        preview = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF2B3566.toInt(), 0xFF7A5A8F.toInt(), 0xFFD98C74.toInt())
            ).apply { cornerRadius = dp(20).toFloat() }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val previewHeight = when (kind) {
            Kind.BAR -> 76
            Kind.SMALL -> 170
            Kind.MEDIUM -> 175
            Kind.LARGE -> 380
        }
        col.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(previewHeight + 32)))

        // Transparency
        alphaLabel = section(col, "")
        val seek = SeekBar(this).apply {
            max = 100
            progress = style.transparency
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    style = style.copy(transparency = value)
                    updateAlphaLabel()
                    refreshPreview()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        col.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        updateAlphaLabel()

        // Font
        section(col, "글꼴")
        val fonts = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        WidgetPrefs.FONTS.forEach { (key, label) ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "$label   가나다 123°"
                typeface = WidgetPrefs.typeface(key)
                textSize = 17f
                minHeight = dp(48)
                isChecked = key == style.font
                setOnClickListener {
                    style = style.copy(font = key)
                    refreshPreview()
                }
            }
            fonts.addView(rb)
        }
        col.addView(fonts)

        // Size
        section(col, "글자 크기")
        val sizes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        WidgetPrefs.SIZES.forEach { (scale, label) ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 15f
                minHeight = dp(48)
                isChecked = abs(scale - style.scale) < 0.01f
                setOnClickListener {
                    style = style.copy(scale = scale)
                    refreshPreview()
                }
            }
            sizes.addView(rb, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        col.addView(sizes)

        col.addView(TextView(this).apply {
            text = "한글은 휴대폰 기본 글꼴에 따라 일부 글꼴이 비슷하게 보일 수 있어요. 숫자는 글꼴 차이가 잘 보여요."
            alpha = 0.6f
            textSize = 13f
            setPadding(0, dp(10), 0, dp(4))
        })

        // Buttons
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = "초기화"
            setOnClickListener { resetToDefault() }
        })
        buttons.addView(Button(this).apply {
            text = "저장"
            setOnClickListener { save() }
        })
        col.addView(buttons)

        refreshPreview()
    }

    private fun section(parent: LinearLayout, title: String): TextView {
        val t = TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(4))
        }
        parent.addView(t)
        return t
    }

    private fun updateAlphaLabel() {
        alphaLabel.text = "배경 투명도  ${style.transparency}%"
    }

    private fun refreshPreview() {
        val w = WeatherRepo.cached(this) ?: WidgetUpdater.sample()
        val rv = WidgetUpdater.build(this, kind, Prefs.place(this), w, style)
        preview.removeAllViews()
        try {
            val v = rv.apply(applicationContext, preview)
            val width = if (kind == Kind.SMALL) dp(170) else ViewGroup.LayoutParams.MATCH_PARENT
            preview.addView(v, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        } catch (e: Exception) {
            preview.addView(TextView(this).apply { text = "미리보기를 그리지 못했어요" })
        }
    }

    private fun resetToDefault() {
        // Recreate the screen with default values
        WidgetPrefs.save(this, widgetId, WStyle())
        recreate()
    }

    private fun save() {
        WidgetPrefs.save(this, widgetId, style)
        WidgetUpdater.renderOne(applicationContext, widgetId)
        val ctx = applicationContext
        Thread { WidgetUpdater.refreshAll(ctx) }.start()
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
