package com.example.gamebooster

import android.app.ActivityManager
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ---------- MAIN SCREEN ----------

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Game Booster"
            textSize = 24f
            setPadding(0, 0, 0, 48)
        }
        val startBtn = Button(this).apply { text = "Start overlay" }
        val stopBtn = Button(this).apply { text = "Stop overlay" }
        layout.addView(title)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)

        startBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow 'Display over other apps', then tap Start again", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
                }
                startForegroundService(Intent(this, OverlayService::class.java))
            }
        }
        stopBtn.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
        }
    }
}

// ---------- OVERLAY SERVICE ----------

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var statsText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val pinger = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var pingValue: Int? = null

    override fun onCreate() {
        super.onCreate()

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        statsText = TextView(this).apply {
            setBackgroundColor(Color.argb(204, 17, 20, 24))
            setTextColor(Color.rgb(230, 230, 230))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(24, 24, 24, 24)
            text = "--"
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable and not touchable: taps go through to the game below
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            x = 16
            y = 200
        }

        windowManager.addView(statsText, params)

        pinger.scheduleAtFixedRate({ pingValue = SystemStats.pingMs() }, 0, 3, TimeUnit.SECONDS)
        handler.post(refresh)
    }

    private val refresh = object : Runnable {
        override fun run() {
            val cpu = SystemStats.cpuPercent()
            val ram = SystemStats.ramPercent(this@OverlayService)
            val temp = SystemStats.tempC()
            val ping = pingValue

            statsText.text = buildString {
                append("CPU  ").append(if (cpu >= 0) "$cpu%" else "--").append('\n')
                append("RAM  ").append(if (ram >= 0) "$ram%" else "--").append('\n')
                append("TEMP ").append(temp?.let { "%.0f C".format(it) } ?: "--").append('\n')
                append("PING ").append(ping?.let { "$it ms" } ?: "--")
            }
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
            .setContentText("Performance overlay is on")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        pinger.shutdownNow()
        if (::statsText.isInitialized) {
            windowManager.removeView(statsText)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ---------- SYSTEM STATS ----------

object SystemStats {

    private var lastIdle = 0L
    private var lastTotal = 0L

    /** CPU usage in percent, measured between calls. */
    fun cpuPercent(): Int {
        return try {
            val line = File("/proc/stat").useLines { it.first() }
            val parts = line.trim().split("\\s+".toRegex()).drop(1).map { it.toLong() }
            val idle = parts[3] + parts[4]
            val total = parts.sum()
            val dIdle = idle - lastIdle
            val dTotal = total - lastTotal
            lastIdle = idle
            lastTotal = total
            if (dTotal <= 0) 0 else (100 * (dTotal - dIdle) / dTotal).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            -1
        }
    }

    /** RAM in use, in percent. */
    fun ramPercent(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.totalMem <= 0) return -1
        return (100 * (info.totalMem - info.availMem) / info.totalMem).toInt()
    }

    /** CPU temperature in Celsius, or null if the device doesn't expose it. */
    fun tempC(): Float? {
        val zones = File("/sys/class/thermal").listFiles { f ->
            f.name.startsWith("thermal_zone")
        } ?: return null

        for (zone in zones) {
            val type = File(zone, "type").takeIf { it.canRead() }?.readText()?.trim() ?: continue
            if (type.contains("cpu", ignoreCase = true) || type.contains("tsens", ignoreCase = true)) {
                val raw = File(zone, "temp").takeIf { it.canRead() }?.readText()?.trim()?.toFloatOrNull()
                    ?: continue
                return if (raw > 1000f) raw / 1000f else raw
            }
        }
        return null
    }

    /** Ping in ms. Blocking, so it only runs on the background thread. */
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
