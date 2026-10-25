package com.example.gamebooster

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ====================== THEME + UI HELPERS ======================

object Palette {
    val BG = Color.parseColor("#0D0A12")
    val CARD_STROKE = Color.parseColor("#3A2B50")
    val ACCENT = Color.parseColor("#F3A6F7")
    val TEXT = Color.parseColor("#F4ECF8")
    val SUB = Color.parseColor("#9A8CAB")
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

/** Launch screen: the game icon in the middle with a thin ring that fills up. */
class LaunchView(context: Context) : View(context) {
    private var progress = 0f
    private var iconScale = 1f
    private var icon: Drawable? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.CARD_STROKE
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.ACCENT
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val oval = RectF()
    private val box = RectF()
    private val clip = Path()

    fun setIcon(d: Drawable?) {
        icon = d
        invalidate()
    }

    fun setProgress(p: Float) {
        progress = p
        invalidate()
    }

    fun animateScale(from: Float, to: Float, ms: Long, overshoot: Boolean) {
        val interp: TimeInterpolator = if (overshoot) {
            OvershootInterpolator(1.4f)
        } else {
            PathInterpolator(0.2f, 0f, 0f, 1f)
        }
        ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                iconScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val stroke = maxOf(2f, size * 0.016f)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val r = size / 2f - stroke * 2f

        canvas.drawCircle(cx, cy, r, track)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        if (progress > 0.001f) canvas.drawArc(oval, -90f, 360f * progress, false, arc)

        val s = size * 0.50f * iconScale
        val half = s / 2f
        val d = icon
        if (d != null) {
            d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            box.set(cx - half, cy - half, cx + half, cy + half)
            clip.reset()
            clip.addRoundRect(box, s * 0.22f, s * 0.22f, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            d.draw(canvas)
            canvas.restore()
        } else {
            canvas.drawCircle(cx, cy, size * 0.07f * iconScale, dot)
        }
    }
}

// ====================== SOUND (generated in code, no files needed) ======================

object Sfx {
    private const val RATE = 44100

    private fun addNote(out: DoubleArray, hz: Double, startMs: Int, gain: Double) {
        val start = RATE * startMs / 1000
        var i = start
        while (i < out.size) {
            val t = (i - start).toDouble() / RATE
            val env = Math.exp(-t * 6.0) * minOf(1.0, t / 0.004)
            val tone = Math.sin(2.0 * Math.PI * hz * t) + 0.3 * Math.sin(4.0 * Math.PI * hz * t)
            out[i] += tone * env * gain
            i++
        }
    }

    private fun addSweep(out: DoubleArray, fromHz: Double, toHz: Double, startMs: Int, ms: Int, gain: Double) {
        val start = RATE * startMs / 1000
        val n = RATE * ms / 1000
        var phase = 0.0
        for (k in 0 until n) {
            val idx = start + k
            if (idx >= out.size) break
            val p = k.toDouble() / n
            val eased = p * p * (3.0 - 2.0 * p)
            val hz = fromHz + (toHz - fromHz) * eased
            phase += 2.0 * Math.PI * hz / RATE
            val env = minOf(1.0, p / 0.15) * minOf(1.0, (1.0 - p) / 0.25)
            out[idx] += Math.sin(phase) * env * gain
        }
    }

    private fun play(out: DoubleArray) {
        Thread {
            try {
                val pcm = ShortArray(out.size) {
                    (out[it].coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(out.size * 1000L / RATE + 200L)
                track.release()
            } catch (e: Exception) {
            }
        }.start()
    }

    /** Soft two-note chime when the app opens. */
    fun intro() {
        val out = DoubleArray(RATE * 900 / 1000)
        addNote(out, 659.25, 0, 0.30)
        addNote(out, 987.77, 140, 0.30)
        play(out)
    }

    /** Smooth rising sweep that ends in a small chime when the launch finishes. */
    fun launch() {
        val out = DoubleArray(RATE * 2400 / 1000)
        addSweep(out, 200.0, 880.0, 0, 1500, 0.22)
        addNote(out, 1318.5, 1500, 0.28)
        addNote(out, 1975.5, 1580, 0.18)
        play(out)
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

// ====================== MAIN SCREEN (LANDSCAPE, MINIMAL) ======================

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("booster", Context.MODE_PRIVATE) }
    private val unit by lazy {
        val dm = resources.displayMetrics
        minOf(dm.widthPixels, dm.heightPixels) / 360f
    }

    private lateinit var column: LinearLayout
    private lateinit var logo: LogoView
    private lateinit var titleView: TextView
    private lateinit var dividerView: View
    private lateinit var gameBlock: LinearLayout
    private lateinit var boostBlock: LinearLayout
    private lateinit var gameName: TextView
    private lateinit var gameIcon: ImageView
    private lateinit var iconPlus: TextView
    private lateinit var boostBtn: TextView
    private lateinit var boostInfo: TextView
    private lateinit var statsText: TextView
    private lateinit var statusText: TextView
    private lateinit var launchLayer: FrameLayout
    private lateinit var launchView: LaunchView
    private lateinit var stageText: TextView

    private var pendingBoost = false
    private var busy = false

    private fun u(v: Int): Int = (v * unit).toInt()

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Palette.BG))

        // Draw into the camera-cutout area too
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = attrs
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Palette.BG)

        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- Top bar: logo + title in the top-left corner, status at the top-right ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(24), u(20), u(24), 0)
        }
        logo = LogoView(this)
        top.addView(logo, lp(u(34), u(34)))
        titleView = label(this, "GAME BOOSTER", 16f, Palette.TEXT, true).apply {
            letterSpacing = 0.14f
            setPadding(u(12), 0, 0, 0)
        }
        top.addView(titleView, lp(0, WRAP, 1f))
        statusText = label(this, "", 11f, Palette.SUB, true).apply {
            setPadding(u(10), u(8), u(4), u(8))
            isClickable = true
        }
        statusText.setOnClickListener { togglePill() }
        top.addView(statusText, lp(WRAP, WRAP))
        column.addView(top, lp(MATCH, WRAP))

        // ---- Main row: game on the left, BOOST on the right ----
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(28), 0, u(28), u(16))
        }

        gameBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
        }
        val iconBox = FrameLayout(this)
        iconPlus = TextView(this).apply {
            text = "+"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Palette.ACCENT)
            background = roundRect(Color.TRANSPARENT, u(22).toFloat(), Palette.CARD_STROKE, maxOf(2, u(2)))
        }
        gameIcon = ImageView(this).apply {
            background = roundRect(Color.BLACK, u(22).toFloat())
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        iconBox.addView(iconPlus, FrameLayout.LayoutParams(MATCH, MATCH))
        iconBox.addView(gameIcon, FrameLayout.LayoutParams(MATCH, MATCH))
        gameBlock.addView(iconBox, lp(u(92), u(92)))

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(18), 0, 0, 0)
        }
        texts.addView(
            label(this, "GAME", 10f, Palette.SUB, true).apply { letterSpacing = 0.25f },
            lp(WRAP, WRAP)
        )
        gameName = label(this, "Choose a game", 22f, Palette.TEXT, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(gameName, lp(u(220), WRAP).apply { topMargin = u(2) })
        texts.addView(
            label(this, "Tap to change", 12f, Palette.ACCENT),
            lp(WRAP, WRAP).apply { topMargin = u(4) }
        )
        gameBlock.addView(texts, lp(WRAP, WRAP))
        gameBlock.setOnClickListener { showGamePicker() }
        main.addView(gameBlock, lp(0, MATCH, 1f))

        dividerView = View(this).apply { setBackgroundColor(Palette.CARD_STROKE) }
        main.addView(dividerView, lp(maxOf(1, u(1)), u(120)))

        boostBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        boostBtn = pinkButton(this, "BOOST", 20f)
        boostBtn.setOnClickListener { onBoostTapped() }
        boostBlock.addView(boostBtn, lp(u(260), u(64)))
        boostInfo = label(
            this,
            "Cleans RAM, opens the floating menu, launches your game",
            11f, Palette.SUB
        ).apply { gravity = Gravity.CENTER }
        boostBlock.addView(boostInfo, lp(u(260), WRAP).apply { topMargin = u(12) })
        statsText = label(this, "--", 12f, Palette.TEXT, true).apply { gravity = Gravity.CENTER }
        boostBlock.addView(statsText, lp(WRAP, WRAP).apply { topMargin = u(10) })
        main.addView(boostBlock, lp(0, MATCH, 1f))

        column.addView(main, lp(MATCH, 0, 1f))

        // ---- Launch layer (covers everything while launching) ----
        launchLayer = FrameLayout(this).apply {
            setBackgroundColor(Palette.BG)
            visibility = View.GONE
            isClickable = true
        }
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        launchView = LaunchView(this)
        center.addView(launchView, lp(u(190), u(190)))
        stageText = label(this, "", 14f, Palette.TEXT, true).apply {
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }
        center.addView(stageText, lp(WRAP, WRAP).apply { topMargin = u(14) })
        launchLayer.addView(center, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(launchLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        // Keep content clear of the camera cutout. The top bar only moves if the cutout touches it.
        root.setOnApplyWindowInsetsListener { _, insets ->
            var l = 0
            var r = 0
            var t = 0
            var b = 0
            var topHit = false
            if (Build.VERSION.SDK_INT >= 28) {
                val cut = insets.displayCutout
                if (cut != null) {
                    l = cut.safeInsetLeft
                    r = cut.safeInsetRight
                    t = cut.safeInsetTop
                    b = cut.safeInsetBottom
                    val band = Rect(0, 0, resources.displayMetrics.widthPixels, u(20) + u(34) + u(12))
                    for (rect in cut.boundingRects) {
                        if (Rect.intersects(rect, band)) topHit = true
                    }
                }
            }
            top.setPadding(
                u(24) + (if (topHit) l else 0),
                u(20) + (if (topHit) t else 0),
                u(24) + (if (topHit) r else 0),
                0
            )
            main.setPadding(u(28) + l, 0, u(28) + r, u(16) + b)
            insets
        }

        prepIntro()
        setContentView(root)
        root.requestApplyInsets()
        showSelectedGame()
        refreshState()
        root.post { playIntro() }
    }

    // ---------------- App opening animation ----------------

    private fun prepIntro() {
        logo.alpha = 0f
        logo.rotation = -120f
        logo.scaleX = 0.5f
        logo.scaleY = 0.5f
        titleView.alpha = 0f
        titleView.translationX = -u(16).toFloat()
        statusText.alpha = 0f
        gameBlock.alpha = 0f
        gameBlock.translationX = -u(30).toFloat()
        dividerView.alpha = 0f
        dividerView.scaleY = 0f
        boostBlock.alpha = 0f
        boostBlock.translationY = u(30).toFloat()
    }

    private fun reveal(v: View, delay: Long, duration: Long) {
        v.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .scaleY(1f)
            .setStartDelay(delay)
            .setDuration(duration)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    private fun playIntro() {
        Sfx.intro()
        logo.animate()
            .alpha(1f)
            .rotation(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(900L)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
        reveal(titleView, 200L, 700L)
        reveal(statusText, 350L, 600L)
        reveal(gameBlock, 300L, 800L)
        reveal(dividerView, 400L, 700L)
        reveal(boostBlock, 450L, 800L)
    }

    // ---------------- System bars + lifecycle ----------------

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
            val tempText = if (t != null) "%.1f\u00B0C".format(t) else "--"
            val ramText = if (ram >= 0) "$ram%" else "--"
            statsText.text = "$tempText   \u2022   RAM $ramText"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        refreshState()
        handler.post(tick)
        if (pendingBoost) {
            pendingBoost = false
            if (Settings.canDrawOverlays(this)) onBoostTapped()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun refreshState() {
        val on = OverlayState.running
        statusText.text = if (on) "\u25CF  Floating menu on" else "\u25CB  Floating menu off"
        statusText.setTextColor(if (on) Palette.ACCENT else Palette.SUB)
    }

    // ---------------- Game picker ----------------

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
        if (!ok) gameName.text = "Choose a game"
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

    // ---------------- BOOST + launch animation ----------------

    private fun onBoostTapped() {
        if (busy) return
        if (!Settings.canDrawOverlays(this)) {
            pendingBoost = true
            Toast.makeText(this, "Allow 'Display over other apps', then come back", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startLaunchSequence()
    }

    private fun setStage(text: String) {
        stageText.animate().alpha(0f).setDuration(120L).withEndAction {
            stageText.text = text
            stageText.animate().alpha(1f).setDuration(180L).start()
        }.start()
    }

    private fun startLaunchSequence() {
        busy = true
        val pkg = prefs.getString("game_pkg", null)
        val icon: Drawable? = try {
            if (pkg != null) packageManager.getApplicationIcon(pkg) else null
        } catch (e: Exception) {
            null
        }
        val gameLabel = if (pkg != null) gameName.text.toString() else "your game"

        launchView.setIcon(icon)
        launchView.setProgress(0f)
        launchView.animateScale(0.5f, 1f, 700L, true)
        stageText.animate().cancel()
        stageText.alpha = 1f
        stageText.text = "Cleaning memory"
        launchLayer.animate().cancel()
        launchLayer.alpha = 0f
        launchLayer.visibility = View.VISIBLE
        launchLayer.animate().alpha(1f).setDuration(320L).start()
        column.animate().scaleX(0.94f).scaleY(0.94f).alpha(0f).setDuration(320L).start()
        Sfx.launch()

        var boostDone = false
        var animDone = false
        var apps = 0
        var freed = 0L
        var stage = 0

        fun tryFinish() {
            if (boostDone && animDone) completeLaunch(pkg, apps, freed)
        }

        Booster.run(this, pkg) { a, f ->
            apps = a
            freed = f
            boostDone = true
            tryFinish()
        }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            interpolator = PathInterpolator(0.45f, 0f, 0.15f, 1f)
            addUpdateListener {
                val p = it.animatedValue as Float
                launchView.setProgress(p)
                val s = if (p < 0.34f) 0 else if (p < 0.70f) 1 else 2
                if (s != stage) {
                    stage = s
                    if (s == 1) {
                        if (!OverlayState.running) {
                            startOverlayService(false)
                            refreshState()
                        }
                        setStage("Starting floating menu")
                    } else if (s == 2) {
                        setStage(if (pkg != null) "Launching $gameLabel" else "Almost ready")
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animDone = true
                    tryFinish()
                }
            })
            start()
        }
    }

    private fun completeLaunch(pkg: String?, apps: Int, freed: Long) {
        launchView.animateScale(1f, 1.12f, 320L, false)
        val cleaned = if (freed > 0) {
            "Cleaned $apps apps \u2022 freed $freed MB"
        } else {
            "Cleaned $apps apps \u2022 RAM was already clean"
        }
        boostInfo.text = if (pkg == null) "$cleaned \u2022 pick a game to auto-launch it" else cleaned

        handler.postDelayed({
            val intent = if (pkg != null) packageManager.getLaunchIntentForPackage(pkg) else null
            if (intent != null) startActivity(intent)
            val launched = intent != null
            handler.postDelayed({ endLaunch(launched) }, if (launched) 900L else 400L)
        }, 360L)
    }

    private fun endLaunch(gameLaunched: Boolean) {
        if (gameLaunched) {
            // The game is on screen now, so just reset instantly behind it
            launchLayer.animate().cancel()
            column.animate().cancel()
            launchLayer.visibility = View.GONE
            launchLayer.alpha = 1f
            column.scaleX = 1f
            column.scaleY = 1f
            column.alpha = 1f
            busy = false
        } else {
            launchLayer.animate().alpha(0f).setDuration(300L).withEndAction {
                launchLayer.visibility = View.GONE
                launchLayer.alpha = 1f
                busy = false
            }.start()
            column.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300L).start()
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

    /** Tapping the status text at the top right turns the floating menu off (or on). */
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
        attachTouch(bubble, true)

        // ---- Menu panel ----
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
