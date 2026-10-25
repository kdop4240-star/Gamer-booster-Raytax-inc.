package com.example.gamebooster

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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ====================== THEME + UI HELPERS ======================

object Palette {
    val BG_TOP = Color.parseColor("#0F0B16")
    val BG_BOTTOM = Color.parseColor("#2B1C3F")
    val CARD = Color.parseColor("#221830")
    val CARD_STROKE = Color.parseColor("#4A3563")
    val ACCENT = Color.parseColor("#F3A6F7")
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

class StatTile(context: Context, caption: String) {
    val value: TextView = label(context, "--", 20f, Palette.ACCENT, true).apply {
        gravity = Gravity.CENTER
    }
    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = roundRect(Palette.CARD, context.dp(16).toFloat(), Palette.CARD_STROKE, context.dp(1))
        setPadding(context.dp(8), context.dp(14), context.dp(8), context.dp(14))
        addView(value)
        addView(label(context, caption, 11f, Palette.SUB).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
        })
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

// ====================== MAIN SCREEN ======================

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("booster", Context.MODE_PRIVATE) }

    private lateinit var gameName: TextView
    private lateinit var gameIcon: ImageView
    private lateinit var tempTile: StatTile
    private lateinit var ramTile: StatTile
    private lateinit var battTile: StatTile
    private lateinit var boostBtn: TextView
    private lateinit var boostInfo: TextView
    private lateinit var overlayBtn: TextView

    private fun lp(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = top }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BG_TOP
        window.navigationBarColor = Palette.BG_BOTTOM

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Palette.BG_TOP, Palette.BG_BOTTOM)
            )
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        // ---- Header ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = View(this).apply { background = circle(Color.BLACK, Palette.ACCENT, dp(5)) }
        header.addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titles.addView(label(this, "GAME BOOSTER", 20f, Palette.TEXT, true).apply { letterSpacing = 0.12f })
        titles.addView(label(this, "Performance mode", 12f, Palette.SUB))
        header.addView(titles)
        root.addView(header)

        // ---- Game card ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(Palette.CARD, dp(22).toFloat(), Palette.CARD_STROKE, dp(1))
            setPadding(dp(16), dp(18), dp(16), dp(18))
            isClickable = true
        }
        gameIcon = ImageView(this).apply {
            background = roundRect(Color.BLACK, dp(14).toFloat(), Palette.ACCENT, dp(2))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        card.addView(gameIcon, LinearLayout.LayoutParams(dp(60), dp(60)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        texts.addView(label(this, "SELECTED GAME", 10f, Palette.ACCENT, true).apply { letterSpacing = 0.15f })
        gameName = label(this, "Tap to choose a game", 18f, Palette.TEXT, true)
        texts.addView(gameName)
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(label(this, "\u203A", 28f, Palette.ACCENT, true))
        card.setOnClickListener { showGamePicker() }
        root.addView(card, lp(top = dp(28)))

        // ---- Stat tiles ----
        tempTile = StatTile(this, "TEMP")
        ramTile = StatTile(this, "RAM")
        battTile = StatTile(this, "BATTERY")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(tempTile, ramTile, battTile).forEachIndexed { i, tile ->
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) p.leftMargin = dp(10)
            row.addView(tile.root, p)
        }
        root.addView(row, lp(top = dp(16)))

        // ---- BOOST button ----
        boostBtn = pinkButton(this, "BOOST", 22f)
        boostBtn.setOnClickListener { runBoost() }
        root.addView(boostBtn, lp(dp(64), dp(28)))
        boostInfo = label(this, "Frees RAM, then launches your game", 12f, Palette.SUB).apply {
            gravity = Gravity.CENTER
        }
        root.addView(boostInfo, lp(top = dp(10)))

        // ---- Floating menu toggle ----
        overlayBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setTextColor(Palette.ACCENT)
            background = roundRect(Color.TRANSPARENT, dp(18).toFloat(), Palette.ACCENT, dp(2))
            isClickable = true
        }
        overlayBtn.setOnClickListener { toggleOverlay() }
        root.addView(overlayBtn, lp(dp(54), dp(24)))
        root.addView(
            label(this, "Double-tap the floating bubble to open the in-game menu", 12f, Palette.SUB)
                .apply { gravity = Gravity.CENTER },
            lp(top = dp(10))
        )

        showSelectedGame()
    }

    private val tick = object : Runnable {
        override fun run() {
            val t = SystemStats.batteryTempC(this@MainActivity)
            val ram = SystemStats.ramPercent(this@MainActivity)
            val batt = SystemStats.batteryPercent(this@MainActivity)
            tempTile.value.text = t?.let { "%.1f\u00B0".format(it) } ?: "--"
            ramTile.value.text = if (ram >= 0) "$ram%" else "--"
            battTile.value.text = if (batt >= 0) "$batt%" else "--"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayButton()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun showSelectedGame() {
        val pkg = prefs.getString("game_pkg", null)
        if (pkg == null) {
            gameName.text = "Tap to choose a game"
            gameIcon.setImageDrawable(null)
            return
        }
        try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            gameName.text = packageManager.getApplicationLabel(info).toString()
            gameIcon.setImageDrawable(packageManager.getApplicationIcon(pkg))
        } catch (e: Exception) {
            gameName.text = "Tap to choose a game"
            gameIcon.setImageDrawable(null)
        }
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

    private fun runBoost() {
        val pkg = prefs.getString("game_pkg", null)
        boostBtn.text = "BOOSTING..."
        boostBtn.isEnabled = false
        Booster.run(this, pkg) { apps, freedMb ->
            boostBtn.text = "BOOST"
            boostBtn.isEnabled = true
            boostInfo.text = if (freedMb > 0) {
                "Cleaned $apps apps \u2022 freed $freedMb MB"
            } else {
                "Cleaned $apps apps \u2022 RAM was already clean"
            }
            if (pkg != null) {
                if (!OverlayState.running) {
                    startOverlay(false)
                    refreshOverlayButton()
                }
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
            }
        }
    }

    private fun startOverlay(promptIfMissing: Boolean): Boolean {
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

    private fun toggleOverlay() {
        if (OverlayState.running) {
            stopService(Intent(this, OverlayService::class.java))
            OverlayState.running = false
        } else {
            startOverlay(true)
        }
        refreshOverlayButton()
    }

    private fun refreshOverlayButton() {
        overlayBtn.text = if (OverlayState.running) "FLOATING MENU: ON" else "FLOATING MENU: OFF"
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
        val ring = View(this).apply { background = circle(Color.BLACK, Palette.ACCENT, dp(3)) }
        header.addView(ring, LinearLayout.LayoutParams(dp(24), dp(24)))
        val title = label(this, "GAME BOOSTER", 13f, Palette.TEXT, true).apply {
            letterSpacing = 0.1f
            setPadding(dp(10), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = label(this, "\u00D7", 24f, Palette.SUB, true).apply {
            setPadding(dp(12), 0, dp(2), 0)
            setOnClickListener { collapse() }
        }
        header.addView(close)
        panel.addView(header)
        attachTouch(header, false)

        val divider = View(this).apply { setBackgroundColor(Palette.CARD_STROKE) }
        panel.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10)
        })

        tempValue = statRow("TEMP")
        ramValue = statRow("RAM")
        battValue = statRow("BATTERY")
        pingText = statRow("PING")

        boostBtn = pinkButton(this, "BOOST", 15f)
        boostBtn.setOnClickListener { runBoost() }
        panel.addView(boostBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(16)
        })

        container.addView(panel, FrameLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun statRow(name: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(
            label(this, name, 12f, Palette.SUB),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
