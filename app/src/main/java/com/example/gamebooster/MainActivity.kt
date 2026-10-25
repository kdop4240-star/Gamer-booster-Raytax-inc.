package com.example.gamebooster

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ====================== THEME + UI HELPERS ======================

object Palette {
    val BG_TOP = Color.parseColor("#0B0813")
    val BG_BOTTOM = Color.parseColor("#24173A")
    val CARD = Color.parseColor("#221830")
    val CARD_STROKE = Color.parseColor("#4A3563")
    val ACCENT = Color.parseColor("#F3A6F7")
    val ACCENT2 = Color.parseColor("#C98BFF")
    val TEXT = Color.parseColor("#F4ECF8")
    val SUB = Color.parseColor("#A394B5")
    val PANEL = Color.parseColor("#F2140E1C")
    val DARK_TEXT = Color.parseColor("#1A0F24")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun roundRect(fill: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun circle(fill: Int, strokeColor: Int, strokePx: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(strokePx, strokeColor)
    }

fun label(context: Context, text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
    TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun pinkButton(context: Context, text: String, sizeSp: Float): TextView =
    TextView(context).apply {
        this.text = text
        textSize = sizeSp
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.15f
        gravity = Gravity.CENTER
        setTextColor(Palette.DARK_TEXT)
        background = roundRect(Palette.ACCENT, context.dp(18).toFloat())
        isClickable = true
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.alpha = 0.7f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            false
        }
    }

// ====================== CUSTOM VIEWS ======================

/** The app logo: black disc with a pink ring split in 3 parts. */
class LogoView(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.ACCENT
        strokeCap = Paint.Cap.ROUND
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f
        canvas.drawCircle(cx, cy, size / 2f, disc)
        val stroke = size * 0.11f
        ring.strokeWidth = stroke
        val r = size / 2f - stroke * 0.9f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        for (i in 0 until 3) {
            canvas.drawArc(oval, -60f + i * 120f, 100f, false, ring)
        }
        canvas.drawCircle(cx, cy, size * 0.11f, dot)
    }
}

/** Round arc gauge with a big value in the middle and a caption below it. */
class GaugeView(context: Context, private val caption: String) : View(context) {
    private var shown = 0f
    private var valueText = "--"
    private var anim: ValueAnimator? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Palette.CARD_STROKE
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.SUB
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
    }
    private val oval = RectF()

    fun set(fraction: Float, text: String) {
        valueText = text
        val to = fraction.coerceIn(0f, 1f)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, to).apply {
            duration = 700
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bar.shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(Palette.ACCENT2, Palette.ACCENT, Palette.ACCENT),
            floatArrayOf(0f, 0.75f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h)
        val stroke = size * 0.09f
        track.strokeWidth = stroke
        bar.strokeWidth = stroke
        val cx = w / 2f
        val cy = h / 2f
        val r = size / 2f - stroke
        oval.set(cx - r, cy - r, cx + r, cy + r)

        canvas.save()
        canvas.rotate(135f, cx, cy)
        canvas.drawArc(oval, 0f, 270f, false, track)
        if (shown > 0.001f) canvas.drawArc(oval, 0f, 270f * shown, false, bar)
        canvas.restore()

        valuePaint.textSize = size * 0.25f
        canvas.drawText(valueText, cx, cy + size * 0.07f, valuePaint)
        capPaint.textSize = size * 0.095f
        canvas.drawText(caption, cx, cy + size * 0.36f, capPaint)
    }
}

/** Dark gradient background with a soft glow, a hex pattern and thin slashes. */
class BackdropView(context: Context) : View(context) {
    private val bg = Paint()
    private val glow = Paint()
    private val hexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(30, 243, 166, 247)
        strokeWidth = 2f
    }
    private val slash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(26, 243, 166, 247)
        strokeWidth = 3f
    }
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bg.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Palette.BG_TOP, Color.parseColor("#1B1230"), Palette.BG_BOTTOM),
            null, Shader.TileMode.CLAMP
        )
        glow.shader = RadialGradient(
            w * 0.5f, h * 0.45f, h * 0.95f,
            Color.argb(80, 183, 108, 196), Color.argb(0, 183, 108, 196),
            Shader.TileMode.CLAMP
        )
    }

    private fun hex(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        for (i in 0 until 6) {
            val a = Math.toRadians((60 * i - 30).toDouble())
            val px = cx + s * Math.cos(a).toFloat()
            val py = cy + s * Math.sin(a).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        canvas.drawPath(path, hexPaint)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(bg)
        canvas.drawPaint(glow)

        val s = context.dp(22).toFloat()
        val hexW = s * 1.7320508f
        val rowH = s * 1.5f
        var row = 0
        var y = 0f
        while (y < height + s) {
            hexPaint.alpha = (6 + 40 * (y / height)).toInt().coerceIn(0, 255)
            var x = if (row % 2 == 0) 0f else hexW / 2f
            while (x < width + hexW) {
                hex(canvas, x, y, s)
                x += hexW
            }
            y += rowH
            row++
        }

        canvas.drawLine(width * 0.30f, 0f, width * 0.22f, height.toFloat(), slash)
        canvas.drawLine(width * 0.78f, 0f, width * 0.70f, height.toFloat(), slash)
    }
}

/** The big glowing BOOST button with angled gaming edges. */
class BoostButton(context: Context) : View(context) {
    var label: String = "BOOST"
        set(value) {
            field = value
            invalidate()
        }

    private var glow = 0.5f
    private var pulse: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = context.dp(2).toFloat()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.DARK_TEXT
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.25f
    }
    private val path = Path()

    init {
        isClickable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** When on, the glow slowly pulses (floating menu is running). */
    fun setActive(on: Boolean) {
        pulse?.cancel()
        pulse = null
        if (on) {
            pulse = ValueAnimator.ofFloat(0.35f, 1f).apply {
                duration = 1100
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    glow = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            glow = 0.5f
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fill.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            Palette.ACCENT, Palette.ACCENT2, Shader.TileMode.CLAMP
        )
        val hh = h * 0.64f
        val effect = CornerPathEffect(hh * 0.14f)
        fill.pathEffect = effect
        edge.pathEffect = effect
    }

    override fun onDraw(canvas: Canvas) {
        val pad = height * 0.18f
        val l = pad
        val t = pad
        val r = width - pad
        val b = height - pad
        val hh = b - t
        val c = hh * 0.5f

        path.reset()
        path.moveTo(l + c, t)
        path.lineTo(r - c, t)
        path.lineTo(r, t + hh / 2f)
        path.lineTo(r - c, b)
        path.lineTo(l + c, b)
        path.lineTo(l, t + hh / 2f)
        path.close()

        fill.setShadowLayer(pad * 0.9f, 0f, 0f, Color.argb((255 * glow).toInt(), 243, 166, 247))
        canvas.drawPath(path, fill)
        canvas.drawPath(path, edge)

        textPaint.textSize = hh * 0.42f
        val y = (t + hh / 2f) - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, y, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> alpha = 0.75f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> alpha = 1f
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        super.onDetachedFromWindow()
    }
}

// ====================== STATE + STATS + BOOST ======================

object OverlayState {
    @Volatile
    var running = false
}

object SystemStats {

    fun ramPercent(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.totalMem <= 0) return -1
        return (100 * (info.totalMem - info.availMem) / info.totalMem).toInt()
    }

    fun availMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private fun battery(context: Context): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    /** Battery temperature in Celsius. Works on every phone. */
    fun batteryTempC(context: Context): Float? {
        val raw = battery(context)?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: return null
        return if (raw == Int.MIN_VALUE) null else raw / 10f
    }

    fun batteryPercent(context: Context): Int {
        val i = battery(context) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    /** Ping in ms. Blocking, so run it on a background thread. */
    fun pingMs(host: String = "8.8.8.8"): Int? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", host))
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy()
                return null
            }
            Regex("time=([0-9.]+)").find(output)?.groupValues?.get(1)?.toFloat()?.toInt()
        } catch (e: Exception) {
            null
        }
    }
}

object Booster {
    /** Closes background apps (except this app and the chosen game), then reports freed RAM. */
    fun run(context: Context, keepPkg: String?, onDone: (Int, Long) -> Unit) {
        val appContext = context.applicationContext
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = SystemStats.availMb(appContext)

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val targets = appContext.packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != appContext.packageName && it != keepPkg }

        targets.forEach { am.killBackgroundProcesses(it) }

        Handler(Looper.getMainLooper()).postDelayed({
            val after = SystemStats.availMb(appContext)
            onDone(targets.size, (after - before).coerceAtLeast(0L))
        }, 1000)
    }
}

// ====================== MAIN SCREEN (LANDSCAPE) ======================

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("booster", Context.MODE_PRIVATE) }
    private val unit by lazy {
        val dm = resources.displayMetrics
        minOf(dm.widthPixels, dm.heightPixels) / 360f
    }

    private lateinit var gameName: TextView
    private lateinit var gameIcon: ImageView
    private lateinit var iconPlus: TextView
    private lateinit var tempGauge: GaugeView
    private lateinit var pingGauge: GaugeView
    private lateinit var ramGauge: GaugeView
    private lateinit var battGauge: GaugeView
    private lateinit var boostBtn: BoostButton
    private lateinit var boostInfo: TextView
    private lateinit var statusPill: TextView

    private var pendingBoost = false
    private var busy = false
    private var pinger: ScheduledExecutorService? = null

    @Volatile
    private var lastPing: Int? = null

    private fun u(v: Int): Int = (v * unit).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Palette.BG_TOP))

        val root = FrameLayout(this)
        root.addView(BackdropView(this), FrameLayout.LayoutParams(MATCH, MATCH))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(26), u(14), u(26), u(10))
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- Top bar: logo, title, floating-menu status ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(LogoView(this), LinearLayout.LayoutParams(u(42), u(42)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(12), 0, 0, 0)
        }
        titles.addView(label(this, "GAME BOOSTER", 20f, Palette.TEXT, true).apply { letterSpacing = 0.14f })
        titles.addView(label(this, "PERFORMANCE MODE", 10f, Palette.ACCENT, true).apply { letterSpacing = 0.25f })
        top.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))

        statusPill = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setPadding(u(16), u(8), u(16), u(8))
            isClickable = true
        }
        statusPill.setOnClickListener { togglePill() }
        top.addView(statusPill, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- Game card (center) ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#3B2760"), Color.parseColor("#1D1330"))
            ).apply {
                cornerRadius = u(26).toFloat()
                setStroke(maxOf(2, u(1)), Palette.CARD_STROKE)
            }
            isClickable = true
            setPadding(u(12), u(10), u(12), u(10))
        }
        card.addView(
            label(this, "SELECTED GAME", 10f, Palette.ACCENT, true).apply { letterSpacing = 0.25f },
            LinearLayout.LayoutParams(WRAP, WRAP)
        )

        val iconBox = FrameLayout(this)
        iconPlus = TextView(this).apply {
            text = "+"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(Palette.ACCENT)
            background = roundRect(Color.parseColor("#120C1C"), u(20).toFloat(), Palette.ACCENT, maxOf(2, u(2)))
        }
        gameIcon = ImageView(this).apply {
            background = roundRect(Color.BLACK, u(20).toFloat())
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        iconBox.addView(iconPlus, FrameLayout.LayoutParams(MATCH, MATCH))
        iconBox.addView(gameIcon, FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(iconBox, LinearLayout.LayoutParams(u(86), u(86)).apply { topMargin = u(8) })

        gameName = label(this, "Choose your game", 20f, Palette.TEXT, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(gameName, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = u(10) })

        val hint = label(this, "TAP TO CHANGE", 10f, Palette.SUB, true).apply {
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
            setPadding(u(12), u(5), u(12), u(5))
            background = roundRect(Color.TRANSPARENT, u(14).toFloat(), Palette.CARD_STROKE, maxOf(2, u(1)))
        }
        card.addView(hint, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = u(8) })
        card.setOnClickListener { showGamePicker() }

        // ---- Main row: gauges | card | gauges ----
        tempGauge = GaugeView(this, "TEMP")
        pingGauge = GaugeView(this, "PING MS")
        ramGauge = GaugeView(this, "RAM")
        battGauge = GaugeView(this, "BATTERY")

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val colW = u(120)
        main.addView(gaugeColumn(tempGauge, pingGauge), LinearLayout.LayoutParams(colW, MATCH))
        main.addView(
            card,
            LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(u(14), u(6), u(14), u(6)) }
        )
        main.addView(gaugeColumn(ramGauge, battGauge), LinearLayout.LayoutParams(colW, MATCH))
        content.addView(main, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // ---- Bottom: BOOST ----
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        boostBtn = BoostButton(this)
        boostBtn.setOnClickListener { onBoostTapped() }
        bottom.addView(boostBtn, LinearLayout.LayoutParams(u(340), u(86)))
        boostInfo = label(
            this,
            "Cleans RAM \u2022 starts the floating menu \u2022 launches your game",
            11f, Palette.SUB
        ).apply { gravity = Gravity.CENTER }
        bottom.addView(boostInfo, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = -u(10) })
        content.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(root)
        showSelectedGame()
        refreshState()
    }

    private fun gaugeColumn(a: GaugeView, b: GaugeView): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(a, LinearLayout.LayoutParams(MATCH, 0, 1f))
        col.addView(b, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private val tick = object : Runnable {
        override fun run() {
            val t = SystemStats.batteryTempC(this@MainActivity)
            val ram = SystemStats.ramPercent(this@MainActivity)
            val batt = SystemStats.batteryPercent(this@MainActivity)
            val ping = lastPing

            if (t != null) tempGauge.set((t - 20f) / 30f, "%.1f\u00B0".format(t)) else tempGauge.set(0f, "--")
            if (ram >= 0) ramGauge.set(ram / 100f, "$ram%") else ramGauge.set(0f, "--")
            if (batt >= 0) battGauge.set(batt / 100f, "$batt%") else battGauge.set(0f, "--")
            if (ping != null) pingGauge.set(1f - ping / 200f, "$ping") else pingGauge.set(0f, "--")

            handler.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        refreshState()
        handler.post(tick)
        pinger = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleAtFixedRate({ lastPing = SystemStats.pingMs() }, 0, 4, TimeUnit.SECONDS)
        }
        if (pendingBoost) {
            pendingBoost = false
            if (Settings.canDrawOverlays(this)) onBoostTapped()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        pinger?.shutdownNow()
        pinger = null
    }

    private fun refreshState() {
        val on = OverlayState.running
        statusPill.text = if (on) "\u25CF  FLOATING MENU ON" else "\u25CB  FLOATING MENU OFF"
        statusPill.setTextColor(if (on) Palette.ACCENT else Palette.SUB)
        statusPill.background = roundRect(
            if (on) Color.argb(40, 243, 166, 247) else Color.TRANSPARENT,
            u(20).toFloat(),
            if (on) Palette.ACCENT else Palette.CARD_STROKE,
            maxOf(2, u(1))
        )
        boostBtn.setActive(on)
    }

    private fun showSelectedGame() {
        val pkg = prefs.getString("game_pkg", null)
        var ok = false
        if (pkg != null) {
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                gameName.text = packageManager.getApplicationLabel(info).toString()
                gameIcon.setImageDrawable(packageManager.getApplicationIcon(pkg))
                ok = true
            } catch (e: Exception) {
            }
        }
        if (!ok) gameName.text = "Choose your game"
        gameIcon.visibility = if (ok) View.VISIBLE else View.GONE
        iconPlus.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun showGamePicker() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        if (apps.isEmpty()) {
            Toast.makeText(this, "No apps found", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Choose your game")
            .setItems(apps.map { it.second }.toTypedArray()) { _, which ->
                prefs.edit().putString("game_pkg", apps[which].first).apply()
                showSelectedGame()
            }
            .show()
    }

    /** BOOST = floating menu on + clean RAM + launch the game. */
    private fun onBoostTapped() {
        if (busy) return
        if (!Settings.canDrawOverlays(this)) {
            pendingBoost = true
            Toast.makeText(this, "Allow 'Display over other apps', then come back", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startOverlayService(false)
        refreshState()

        busy = true
        boostBtn.label = "BOOSTING..."
        val pkg = prefs.getString("game_pkg", null)
        Booster.run(this, pkg) { apps, freedMb ->
            busy = false
            boostBtn.label = "BOOST"
            val cleaned = if (freedMb > 0) {
                "Cleaned $apps apps \u2022 freed $freedMb MB"
            } else {
                "Cleaned $apps apps \u2022 RAM was already clean"
            }
            boostInfo.text = if (pkg == null) "$cleaned \u2022 pick a game to auto-launch it" else cleaned
            if (pkg != null) {
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
            }
        }
    }

    private fun startOverlayService(promptIfMissing: Boolean): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            if (promptIfMissing) {
                Toast.makeText(this, "Allow 'Display over other apps', then tap again", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            return false
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        OverlayState.running = true
        return true
    }

    /** Tapping the status pill turns the floating menu off (or on). */
    private fun togglePill() {
        if (OverlayState.running) {
            stopService(Intent(this, OverlayService::class.java))
            OverlayState.running = false
        } else {
            startOverlayService(true)
        }
        refreshState()
    }
}

// ====================== FLOATING BUBBLE + MENU ======================

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var container: FrameLayout
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var tempValue: TextView
    private lateinit var ramValue: TextView
    private lateinit var battValue: TextView
    private lateinit var pingText: TextView
    private lateinit var boostBtn: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val pinger = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var lastPing: Int? = null
    private var expanded = false

    override fun onCreate() {
        super.onCreate()

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        OverlayState.running = true

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildViews()

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - dp(60)
            y = dp(160)
        }
        windowManager.addView(container, params)

        pinger.scheduleAtFixedRate({ lastPing = SystemStats.pingMs() }, 0, 3, TimeUnit.SECONDS)
        handler.post(refresh)
        Toast.makeText(this, "Double-tap the bubble to open the menu", Toast.LENGTH_LONG).show()
    }

    private fun buildViews() {
        container = FrameLayout(this)

        // ---- Small indicator bubble ----
        bubble = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Palette.ACCENT)
            text = "\u2022"
            background = circle(Color.argb(235, 0, 0, 0), Palette.ACCENT, dp(3))
        }
        container.addView(bubble, FrameLayout.LayoutParams(dp(48), dp(48)))
        attachTouch(bubble, true)// ---- Menu panel ----
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(Palette.PANEL, dp(22).toFloat(), Palette.CARD_STROKE, dp(1))
            setPadding(dp(16), dp(12), dp(16), dp(16))
            visibility = View.GONE
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(LogoView(this), LinearLayout.LayoutParams(dp(26), dp(26)))
        val title = label(this, "GAME BOOSTER", 13f, Palette.TEXT, true).apply {
            letterSpacing = 0.1f
            setPadding(dp(10), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        val close = label(this, "\u00D7", 24f, Palette.SUB, true).apply {
            setPadding(dp(12), 0, dp(2), 0)
            setOnClickListener { collapse() }
        }
        header.addView(close)
        panel.addView(header)
        attachTouch(header, false)

        val divider = View(this).apply { setBackgroundColor(Palette.CARD_STROKE) }
        panel.addView(divider, LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(10)
        })

        tempValue = statRow("TEMP")
        ramValue = statRow("RAM")
        battValue = statRow("BATTERY")
        pingText = statRow("PING")

        boostBtn = pinkButton(this, "BOOST", 15f)
        boostBtn.setOnClickListener { runBoost() }
        panel.addView(boostBtn, LinearLayout.LayoutParams(MATCH, dp(46)).apply {
            topMargin = dp(16)
        })

        container.addView(panel, FrameLayout.LayoutParams(dp(220), WRAP))
    }

    private fun statRow(name: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(
            label(this, name, 12f, Palette.SUB),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        val value = label(this, "--", 14f, Palette.ACCENT, true)
        row.addView(value)
        panel.addView(row)
        return value
    }

    /** Drag to move. If allowDoubleTap is true, a double-tap opens the menu. */
    private fun attachTouch(target: View, allowDoubleTap: Boolean) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (allowDoubleTap) expand()
                return true
            }
        })
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        target.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(container, params)
                }
            }
            true
        }
    }

    private fun expand() {
        expanded = true
        bubble.visibility = View.GONE
        panel.visibility = View.VISIBLE
        val minX = dp(8)
        val maxX = maxOf(minX, resources.displayMetrics.widthPixels - dp(220) - dp(8))
        params.x = params.x.coerceIn(minX, maxX)
        windowManager.updateViewLayout(container, params)
        updateStats()
    }

    private fun collapse() {
        expanded = false
        panel.visibility = View.GONE
        bubble.visibility = View.VISIBLE
        windowManager.updateViewLayout(container, params)
    }

    private fun runBoost() {
        val pkg = getSharedPreferences("booster", Context.MODE_PRIVATE).getString("game_pkg", null)
        boostBtn.text = "BOOSTING..."
        Booster.run(this, pkg) { _, freedMb ->
            boostBtn.text = if (freedMb > 0) "FREED $freedMb MB" else "ALL CLEAN"
            handler.postDelayed({ boostBtn.text = "BOOST" }, 2500)
        }
    }

    private fun updateStats() {
        val t = SystemStats.batteryTempC(this)
        val ram = SystemStats.ramPercent(this)
        val batt = SystemStats.batteryPercent(this)
        val ping = lastPing
        tempValue.text = t?.let { "%.1f\u00B0C".format(it) } ?: "--"
        ramValue.text = if (ram >= 0) "$ram%" else "--"
        battValue.text = if (batt >= 0) "$batt%" else "--"
        pingText.text = ping?.let { "$it ms" } ?: "--"
    }

    private val refresh = object : Runnable {
        override fun run() {
            val t = SystemStats.batteryTempC(this@OverlayService)
            bubble.text = t?.let { "%.0f\u00B0".format(it) } ?: "\u2022"
            if (expanded) updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "game_booster"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Game Booster", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("Game Booster is running")
            .setContentText("Floating menu is on")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        OverlayState.running = false
        handler.removeCallbacksAndMessages(null)
        pinger.shutdownNow()
        if (::container.isInitialized) {
            try {
                windowManager.removeView(container)
            } catch (e: Exception) {
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
