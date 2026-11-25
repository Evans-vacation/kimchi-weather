package app.nalssi

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import android.graphics.Color
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
    private lateinit var colorBox: LinearLayout
    private val swatches = ArrayList<Pair<View, Int>>()
    private var bgGroup: RadioGroup? = null
    private val bgButtons = HashMap<String, Int>()
    private lateinit var photoBtn: Button

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
            Kind.BAR -> 90
            Kind.SMALL -> 170
            Kind.MEDIUM -> 175
            Kind.LARGE -> 380
        }
        col.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(previewHeight + 32)))

        // Background
        section(col, "배경")
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        bgGroup = group
        WidgetPrefs.BACKGROUNDS.forEach { (key, label) ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 16f
                minHeight = dp(48)
                isChecked = key == style.bg
                setOnClickListener { setBg(key) }
            }
            bgButtons[key] = rb.id
            group.addView(rb)
        }
        col.addView(group)

        colorBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        WidgetPrefs.COLORS.chunked(6).forEach { rowColors ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowColors.forEach { c ->
                val sw = View(this).apply {
                    contentDescription = "배경 색"
                    setOnClickListener { pickColor(c) }
                }
                swatches.add(sw to c)
                row.addView(sw, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            colorBox.addView(row)
        }
        colorBox.addView(TextView(this).apply {
            text = "직접 고르기 (색상 · 밝기)"
            alpha = 0.7f
            setPadding(0, dp(10), 0, 0)
        })
        val hsv = FloatArray(3)
        Color.colorToHSV(style.color, hsv)
        val hue = SeekBar(this).apply { max = 360; progress = hsv[0].roundToInt() }
        val light = SeekBar(this).apply { max = 100; progress = (hsv[2] * 100).roundToInt().coerceIn(20, 85) }
        val custom = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = light.progress.coerceIn(20, 85) / 100f
                pickColor(Color.HSVToColor(floatArrayOf(hue.progress.toFloat(), 0.55f, v)))
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        hue.setOnSeekBarChangeListener(custom)
        light.setOnSeekBarChangeListener(custom)
        colorBox.addView(hue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        colorBox.addView(light, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        col.addView(colorBox)
        photoBtn = Button(this).apply {
            text = "내 사진 고르기"
            setOnClickListener { pickPhoto() }
        }
        col.addView(photoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        paintSwatches()

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

    private fun pickPhoto() {
        val pick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_PHOTO)
        } catch (e: Exception) {
            Toast.makeText(this, "사진을 고를 수 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Activity result API needs AndroidX; this app keeps no dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            if (!Photos.has(this, widgetId) && style.bg == "photo") setBg("scene")
            return
        }
        Toast.makeText(this, "사진을 준비하고 있어요…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = Photos.save(applicationContext, widgetId, uri)
            runOnUiThread {
                if (ok) setBg("photo") else {
                    Toast.makeText(this, "이 사진은 쓸 수 없어요", Toast.LENGTH_SHORT).show()
                    if (!Photos.has(this, widgetId)) setBg("scene")
                }
            }
        }.start()
    }

    private fun setBg(key: String) {
        if (key == "photo" && !Photos.has(this, widgetId)) {
            pickPhoto()
            return
        }
        style = style.copy(bg = key)
        bgButtons[key]?.let { bgGroup?.check(it) }
        paintSwatches()
        refreshPreview()
    }

    private fun pickColor(c: Int) {
        style = style.copy(color = c)
        setBg("color")
    }

    private fun paintSwatches() {
        colorBox.alpha = if (style.bg == "color") 1f else 0.45f
        if (::photoBtn.isInitialized) photoBtn.text = if (Photos.has(this, widgetId)) "내 사진 바꾸기" else "내 사진 고르기"
        for ((v, c) in swatches) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(c)
                if (style.bg == "color" && c == style.color) setStroke(dp(3), 0xFF888888.toInt())
            }
        }
    }

    private fun updateAlphaLabel() {
        alphaLabel.text = "배경 투명도  ${style.transparency}%"
    }

    private fun refreshPreview() {
        val w = WeatherRepo.cached(this) ?: WidgetUpdater.sample()
        val rv = WidgetUpdater.build(this, kind, Prefs.place(this), w, style, widgetId)
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

    companion object {
        private const val REQ_PHOTO = 7
    }
}
