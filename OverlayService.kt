package com.example.blackbar

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var bar: View? = null
    private var currentPackage: String? = null
    private var lastQueryTime = 0L
    private var hideUntil = 0L
    private var heightDp = DEFAULT_HEIGHT

    private val poll = object : Runnable {
        override fun run() {
            if (!isEnabled()) {
                removeBar()
                stopSelf()
                return
            }
            detectForegroundPackage()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val showAfterTap = Runnable {
        if (isSelected(currentPackage)) showBar()
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        heightDp = prefs().getInt(KEY_HEIGHT, DEFAULT_HEIGHT).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs().edit().putBoolean(KEY_ENABLED, false).apply()
                handler.removeCallbacks(poll)
                handler.removeCallbacks(showAfterTap)
                removeBar()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                heightDp = prefs().getInt(KEY_HEIGHT, DEFAULT_HEIGHT).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
                handler.removeCallbacks(poll)
                handler.post(poll)
                return START_STICKY
            }
            else -> {
                if (intent == null && !isEnabled()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                prefs().edit().putBoolean(KEY_ENABLED, true).apply()
                startForeground(NOTIFICATION_ID, notification())
                lastQueryTime = System.currentTimeMillis() - 10_000L
                handler.removeCallbacks(poll)
                handler.post(poll)
                return START_STICKY
            }
        }
    }

    private fun detectForegroundPackage() {
        if (!Settings.canDrawOverlays(this) || !hasUsageAccess()) {
            removeBar()
            return
        }
        val now = System.currentTimeMillis()
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usage.queryEvents(lastQueryTime, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            val foreground = if (Build.VERSION.SDK_INT >= 29) {
                type == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                type == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            if (foreground) currentPackage = event.packageName
        }
        lastQueryTime = now
        if (isSelected(currentPackage) && now >= hideUntil) {
            if (bar == null) showBar()
        } else {
            handler.removeCallbacks(showAfterTap)
            removeBar()
        }
    }

    private fun showBar() {
        if (bar != null || !Settings.canDrawOverlays(this)) return
        val view = View(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnClickListener {
                hideUntil = System.currentTimeMillis() + HIDE_MS
                handler.removeCallbacks(showAfterTap)
                removeBar()
                handler.postDelayed(showAfterTap, HIDE_MS)
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, dp(heightDp), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        try {
            wm.addView(view, params)
            bar = view
        } catch (_: Exception) { bar = null }
    }

    private fun removeBar() {
        val v = bar ?: return
        try { wm.removeView(v) } catch (_: Exception) { }
        bar = null
    }

    private fun isSelected(pkg: String?): Boolean {
        if (pkg.isNullOrBlank() || pkg == packageName) return false
        return prefs().getStringSet(KEY_SELECTED, emptySet())?.contains(pkg) == true
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isEnabled() = prefs().getBoolean(KEY_ENABLED, false)
    private fun prefs() = getSharedPreferences("black_bar_settings", MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Black Bar", android.app.NotificationManager.IMPORTANCE_LOW)
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Black Bar активен")
            .setContentText("Полоса показывается только в выбранных приложениях.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Выключить", stop)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        handler.removeCallbacks(showAfterTap)
        removeBar()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.blackbar.START"
        const val ACTION_STOP = "com.example.blackbar.STOP"
        const val ACTION_UPDATE = "com.example.blackbar.UPDATE"
        private const val CHANNEL = "black_bar_channel"
        private const val NOTIFICATION_ID = 1002
        private const val KEY_SELECTED = "selected_packages"
        private const val KEY_HEIGHT = "height"
        private const val KEY_ENABLED = "enabled"
        private const val DEFAULT_HEIGHT = 40
        private const val MIN_HEIGHT = 10
        private const val MAX_HEIGHT = 150
        private const val POLL_MS = 600L
        private const val HIDE_MS = 2_000L
    }
}
