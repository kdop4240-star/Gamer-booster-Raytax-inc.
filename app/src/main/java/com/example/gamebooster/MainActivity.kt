package com.example.gamebooster

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
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
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
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

    // Soft light blue used by Acceleration Mode
    val BLUE = Color.parseColor("#A8C7FA")
    val BLUE_TEXT = Color.parseColor("#0B2A5B")
    val BLUE_PANEL = Color.parseColor("#E6101C36")
    val BLUE_STROKE = Color.parseColor("#4A6A9E")
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

/** The app logo: black disc with a ring split in 3 parts. */
class LogoView(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.ACCENT
        strokeCap = Paint.Cap.ROUND
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val oval = RectF()

    fun setAccent(c: Int) {
        ring.color = c
        dot.color = c
        invalidate()
    }

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

/**
 * Full-screen animation for Acceleration Mode. It is drawn in an overlay window,
 * so it plays on top of any app or game. Touches pass straight through it.
 */
class AccelView(
    context: Context,
    private val title: String,
    private val steps: List<String>,
    private val enabling: Boolean
) : View(context) {

    /** Overall progress 0..1, driven by the animator. */
    var p: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val d = context.resources.displayMetrics.density

    private val scrim = Paint()
    private val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(70, 168, 199, 250)
    }
    private val ringArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.BLUE
        strokeCap = Paint.Cap.ROUND
    }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.BLUE
    }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.BLUE }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.2f
    }
    private val stepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val circleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.BLUE
    }
    private val checkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.BLUE }
    private val checkLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.BLUE_TEXT
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val oval = RectF()
    private val bolt = Path()
    private val check = Path()

    private fun clamp(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v
    private fun ease(x: Float): Float = x * x * (3f - 2f * x)
    private fun al(x: Float): Int = (255f * x).toInt().coerceIn(0, 255)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f

        val a = minOf(clamp(p / 0.10f), clamp((1f - p) / 0.14f))
        scrim.color = Color.argb(al(0.80f * a), 6, 12, 30)
        canvas.drawRect(0f, 0f, w, h, scrim)

        // Layout: ring, title, steps (stacked and centered)
        val ringR = 34f * d
        val stepH = 24f * d
        val blockH = ringR * 2f + 26f * d + 26f * d + steps.size * stepH
        val top = (h - blockH) / 2f
        val ringCy = top + ringR
        val titleY = ringCy + ringR + 26f * d
        val stepsTop = titleY + 28f * d

        // Soft expanding (or collapsing) rings
        val maxR = minOf(w, h) * 0.7f
        wave.strokeWidth = 2f * d
        for (k in 0 until 3) {
            val pk = clamp(p * 1.6f - k * 0.22f)
            if (pk <= 0f || pk >= 1f) continue
            val r = if (enabling) ringR + pk * maxR else ringR + (1f - pk) * maxR
            wave.alpha = al(0.45f * (1f - pk) * a)
            canvas.drawCircle(cx, ringCy, r, wave)
        }

        // Progress ring
        ringTrack.strokeWidth = 3f * d
        ringArc.strokeWidth = 3f * d
        ringTrack.alpha = al(0.28f * a)
        canvas.drawCircle(cx, ringCy, ringR, ringTrack)
        oval.set(cx - ringR, ringCy - ringR, cx + ringR, ringCy + ringR)
        val fill = ease(clamp((p - 0.08f) / 0.80f))
        ringArc.alpha = al(a)
        if (fill > 0.001f) canvas.drawArc(oval, -90f, 360f * fill, false, ringArc)

        // Lightning bolt in the middle
        val s = ringR * 0.46f
        bolt.reset()
        bolt.moveTo(cx + 0.18f * s, ringCy - 1.0f * s)
        bolt.lineTo(cx - 0.62f * s, ringCy + 0.15f * s)
        bolt.lineTo(cx - 0.05f * s, ringCy + 0.15f * s)
        bolt.lineTo(cx - 0.20f * s, ringCy + 1.0f * s)
        bolt.lineTo(cx + 0.62f * s, ringCy - 0.20f * s)
        bolt.lineTo(cx + 0.05f * s, ringCy - 0.20f * s)
        bolt.close()
        boltPaint.alpha = al(a)
        canvas.drawPath(bolt, boltPaint)

        // Title
        titlePaint.textSize = 13f * d
        titlePaint.alpha = al(a * clamp((p - 0.06f) / 0.10f))
        canvas.drawText(title, cx, titleY, titlePaint)

        // Steps with check marks
        stepPaint.textSize = 12f * d
        val x0 = cx - 95f * d
        for (i in steps.indices) {
            val start = 0.14f + i * (0.60f / steps.size)
            val ap = clamp((p - start) / 0.08f) * a
            if (ap <= 0f) continue
            val y = stepsTop + i * stepH
            val ccx = x0 + 9f * d
            val ccy = y - 4f * d

            stepPaint.alpha = al(0.9f * ap)
            canvas.drawText(steps[i], x0 + 28f * d, y, stepPaint)

            circleLine.strokeWidth = 1.5f * d
            circleLine.alpha = al(0.55f * ap)
            canvas.drawCircle(ccx, ccy, 8f * d, circleLine)

            val done = clamp((p - start - 0.08f) / 0.07f) * a
            if (done > 0f) {
                checkFill.alpha = al(done)
                canvas.drawCircle(ccx, ccy, 8f * d * clamp(done), checkFill)
                check.reset()
                check.moveTo(ccx - 3.5f * d, ccy)
                check.lineTo(ccx - 1f * d, ccy + 3f * d)
                check.lineTo(ccx + 4f * d, ccy - 3f * d)
                checkLine.strokeWidth = 2f * d
                checkLine.alpha = al(done)
                canvas.drawPath(check, checkLine)
            }
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

    /** Rising sweep and a bright chime when Acceleration Mode turns on. */
    fun accelOn() {
        val out = DoubleArray(RATE * 3400 / 1000)
        addSweep(out, 300.0, 1100.0, 0, 2500, 0.18)
        addNote(out, 1174.7, 2500, 0.26)
        addNote(out, 1568.0, 2600, 0.20)
        play(out)
    }

    /** Two soft falling notes when Acceleration Mode turns off. */
    fun accelOff() {
        val out = DoubleArray(RATE * 1200 / 1000)
        addNote(out, 987.77, 0, 0.26)
        addNote(out, 659.25, 180, 0.26)
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
    private lateinit var panelBg: GradientDrawable
    private lateinit var panelLogo: LogoView
    private lateinit var panelDivider: View
    private lateinit var tempValue: TextView
    private lateinit var ramValue: TextView
    private lateinit var battValue: TextView
    private lateinit var pingText: TextView
    private lateinit var boostBtn: TextView
    private lateinit var accelBtn: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val pinger = Executors.newSingleThreadScheduledExecutor()
    private val prefs by lazy { getSharedPreferences("booster", Context.MODE_PRIVATE) }
    private val argb = ArgbEvaluator()
    private val accentTexts = mutableListOf<TextView>()

    @Volatile
    private var lastPing: Int? = null
    private var expanded = false
    private var accelBusy = false

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
            y = dp(60)
        }
        windowManager.addView(container, params)

        syncAccelState()

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
        panelBg = roundRect(Palette.PANEL, dp(22).toFloat(), Palette.CARD_STROKE, dp(1))
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(dp(16), dp(12), dp(16), dp(14))
            visibility = View.GONE
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panelLogo = LogoView(this)
        header.addView(panelLogo, LinearLayout.LayoutParams(dp(26), dp(26)))
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

        panelDivider = View(this).apply { setBackgroundColor(Palette.CARD_STROKE) }
        panel.addView(panelDivider, LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(10)
        })

        tempValue = statRow("TEMP")
        ramValue = statRow("RAM")
        battValue = statRow("BATTERY")
        pingText = statRow("PING")

        boostBtn = pinkButton(this, "BOOST", 15f)
        boostBtn.setOnClickListener { runBoost() }
        panel.addView(boostBtn, LinearLayout.LayoutParams(MATCH, dp(42)).apply {
            topMargin = dp(14)
        })

        accelBtn = pinkButton(this, "ACCELERATION MODE", 12f).apply { letterSpacing = 0.08f }
        accelBtn.setOnClickListener { onAccelTapped() }
        panel.addView(accelBtn, LinearLayout.LayoutParams(MATCH, dp(42)).apply {
            topMargin = dp(8)
        })

        container.addView(panel, FrameLayout.LayoutParams(dp(220), WRAP))
    }

    private fun statRow(name: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(
            label(this, name, 12f, Palette.SUB),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        val value = label(this, "--", 14f, Palette.ACCENT, true)
        row.addView(value)
        panel.addView(row)
        accentTexts.add(value)
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
        // Keep the whole panel on screen (important in landscape games)
        val maxY = maxOf(0, resources.displayMetrics.heightPixels - dp(310))
        params.y = params.y.coerceIn(0, maxY)
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
        val pkg = prefs.getString("game_pkg", null)
        boostBtn.text = "BOOSTING..."
        Booster.run(this, pkg) { _, freedMb ->
            boostBtn.text = if (freedMb > 0) "FREED $freedMb MB" else "ALL CLEAN"
            handler.postDelayed({ boostBtn.text = "BOOST" }, 2500)
        }
    }

    // ---------------- Acceleration Mode ----------------

    private fun mix(from: Int, to: Int, t: Float): Int = argb.evaluate(t, from, to) as Int

    /** t = 0 is the normal pink theme, t = 1 is the soft light blue theme. */
    private fun applyTheme(t: Float) {
        val accent = mix(Palette.ACCENT, Palette.BLUE, t)
        val stroke = mix(Palette.CARD_STROKE, Palette.BLUE_STROKE, t)
        val btnText = mix(Palette.DARK_TEXT, Palette.BLUE_TEXT, t)

        panelBg.setColor(mix(Palette.PANEL, Palette.BLUE_PANEL, t))
        panelBg.setStroke(dp(1), stroke)
        panelDivider.setBackgroundColor(stroke)
        panelLogo.setAccent(accent)

        (boostBtn.background as GradientDrawable).setColor(accent)
        boostBtn.setTextColor(btnText)
        (accelBtn.background as GradientDrawable).setColor(accent)
        accelBtn.setTextColor(btnText)

        for (v in accentTexts) v.setTextColor(accent)

        bubble.setTextColor(accent)
        (bubble.background as GradientDrawable).setStroke(dp(3), accent)
    }

    private fun animateTheme(from: Float, to: Float) {
        ValueAnimator.ofFloat(from, to).apply {
            duration = 700L
            addUpdateListener { applyTheme(it.animatedValue as Float) }
            start()
        }
    }

    /** If Acceleration Mode was left on, show it as on (or reset if Android turned it off). */
    private fun syncAccelState() {
        if (!prefs.getBoolean("accel_active", false)) return
        val nm = getSystemService(NotificationManager::class.java)
        val stillOn = nm.isNotificationPolicyAccessGranted &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (stillOn) {
            applyTheme(1f)
            accelBtn.text = "DISABLE"
        } else {
            prefs.edit().putBoolean("accel_active", false).apply()
        }
    }

    private fun onAccelTapped() {
        if (accelBusy) return
        if (prefs.getBoolean("accel_active", false)) disableAccel() else enableAccel()
    }

    /** Calls and notifications: Do Not Disturb that still lets alarms and game sound through. */
    private fun applyDnd(nm: NotificationManager) {
        try {
            val old = nm.notificationPolicy
            prefs.edit()
                .putInt("accel_prev_filter", nm.currentInterruptionFilter)
                .putInt("accel_prev_cats", old.priorityCategories)
                .putInt("accel_prev_calls", old.priorityCallSenders)
                .putInt("accel_prev_msgs", old.priorityMessageSenders)
                .putInt("accel_prev_supp", old.suppressedVisualEffects)
                .apply()

            val cats = if (Build.VERSION.SDK_INT >= 28) {
                NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
                    NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
            } else {
                0
            }
            val supp = if (Build.VERSION.SDK_INT >= 28) {
                NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK or
                    NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS or
                    NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT
            } else {
                @Suppress("DEPRECATION")
                NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON
            }
            nm.notificationPolicy = NotificationManager.Policy(
                cats,
                NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                supp
            )
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } catch (e: Exception) {
        }
    }

    private fun restoreDnd() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) return
            nm.notificationPolicy = NotificationManager.Policy(
                prefs.getInt("accel_prev_cats", 0),
                prefs.getInt("accel_prev_calls", 0),
                prefs.getInt("accel_prev_msgs", 0),
                prefs.getInt("accel_prev_supp", 0)
            )
            val f = prefs.getInt("accel_prev_filter", NotificationManager.INTERRUPTION_FILTER_ALL)
            nm.setInterruptionFilter(
                if (f == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                } else {
                    f
                }
            )
        } catch (e: Exception) {
        }
    }

    private fun dirSize(f: File): Long {
        if (!f.isDirectory) return f.length()
        var total = 0L
        f.listFiles()?.forEach { total += dirSize(it) }
        return total
    }

    /** Clears this app's own cache (Android does not allow clearing other apps' cache). */
    private fun clearOwnCache(): Long {
        var freed = 0L
        for (dir in listOfNotNull(cacheDir, externalCacheDir)) {
            freed += dirSize(dir)
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }
        return freed
    }

    /** Plays the full-screen animation in an overlay window (works on top of any app). */
    private fun playOverlay(enabling: Boolean, steps: List<String>, durationMs: Long, onEnd: () -> Unit) {
        val view = AccelView(
            this,
            if (enabling) "ACCELERATION MODE" else "NORMAL MODE",
            steps,
            enabling
        )
        val lp = WindowManager.LayoutParams(
            MATCH,
            MATCH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(view, lp)
        } catch (e: Exception) {
            onEnd()
            return
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { view.p = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    try {
                        windowManager.removeView(view)
                    } catch (e: Exception) {
                    }
                    onEnd()
                }
            })
            start()
        }
    }

    private fun enableAccel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            Toast.makeText(
                this,
                "Allow 'Do Not Disturb access' for Game Booster, go back, then tap again",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        accelBusy = true
        accelBtn.text = "WORKING..."
        Sfx.accelOn()

        val pkg = prefs.getString("game_pkg", null)
        var appsClosed = 0
        var tasksDone = 0
        var animDone = false

        fun tryFinish() {
            if (animDone && tasksDone >= 2) {
                prefs.edit().putBoolean("accel_active", true).apply()
                animateTheme(0f, 1f)
                accelBtn.text = "DISABLE"
                accelBusy = false
                Toast.makeText(this, "Acceleration Mode on \u2022 closed $appsClosed apps", Toast.LENGTH_SHORT).show()
            }
        }

        // 1) Calls + notifications
        applyDnd(nm)

        // 2) Background apps
        Booster.run(this, pkg) { a, _ ->
            appsClosed = a
            tasksDone++
            tryFinish()
        }

        // 3) Cache
        Thread {
            clearOwnCache()
            handler.post {
                tasksDone++
                tryFinish()
            }
        }.start()

        playOverlay(
            true,
            listOf("Silencing calls", "Muting notifications", "Closing background apps", "Clearing cache"),
            3400L
        ) {
            animDone = true
            tryFinish()
        }
    }

    private fun disableAccel() {
        accelBusy = true
        accelBtn.text = "WORKING..."
        Sfx.accelOff()
        restoreDnd()
        playOverlay(false, listOf("Restoring calls", "Restoring notifications"), 1800L) {
            prefs.edit().putBoolean("accel_active", false).apply()
            animateTheme(1f, 0f)
            accelBtn.text = "ACCELERATION MODE"
            accelBusy = false
        }
    }

    // ---------------- Stats ----------------

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
        // Safety: if the floating menu is closed, switch Acceleration Mode off so nothing stays muted
        if (prefs.getBoolean("accel_active", false)) {
            restoreDnd()
            prefs.edit().putBoolean("accel_active", false).apply()
        }
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
