package com.example.blackbar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var barView: View? = null
    private var barHeightDp = DEFAULT_HEIGHT

    private val showAgain = Runnable {
        if (isEnabled()) showBar()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        barHeightDp = prefs().getInt(KEY_HEIGHT, DEFAULT_HEIGHT).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs().edit().putBoolean(KEY_ENABLED, false).apply()
                handler.removeCallbacks(showAgain)
                removeBar()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                barHeightDp = (intent.getIntExtra(EXTRA_HEIGHT, barHeightDp))
                    .coerceIn(MIN_HEIGHT, MAX_HEIGHT)
                updateBarHeight()
                return START_STICKY
            }
            else -> {
                if (intent == null && !isEnabled()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                prefs().edit().putBoolean(KEY_ENABLED, true).apply()
                barHeightDp = prefs().getInt(KEY_HEIGHT, DEFAULT_HEIGHT)
                    .coerceIn(MIN_HEIGHT, MAX_HEIGHT)
                startForeground(NOTIFICATION_ID, buildNotification())
                showBar()
                return START_STICKY
            }
        }
    }

    private fun showBar() {
        if (!isEnabled() || !Settings.canDrawOverlays(this) || barView != null) return

        val bar = View(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnClickListener {
                handler.removeCallbacks(showAgain)
                removeBar()
                handler.postDelayed(showAgain, HIDE_DURATION_MS)
            }
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(barHeightDp),
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
        }

        try {
            windowManager.addView(bar, params)
            barView = bar
        } catch (_: Exception) {
            barView = null
        }
    }

    private fun updateBarHeight() {
        val view = barView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.height = dp(barHeightDp)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            removeBar()
        }
    }

    private fun removeBar() {
        val view = barView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Окно могло быть уже удалено системой.
        } finally {
            barView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Чёрная полоса",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление о работе чёрной полосы"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Чёрная полоса включена")
            .setContentText("Нажмите на полосу, чтобы скрыть её на 2 секунды.")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Выключить",
                stopPendingIntent
            )
            .build()
    }

    private fun isEnabled(): Boolean = prefs().getBoolean(KEY_ENABLED, false)

    private fun prefs() = getSharedPreferences("black_bar_settings", MODE_PRIVATE)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        handler.removeCallbacks(showAgain)
        removeBar()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.blackbar.START"
        const val ACTION_STOP = "com.example.blackbar.STOP"
        const val ACTION_UPDATE = "com.example.blackbar.UPDATE"
        const val EXTRA_HEIGHT = "height"

        private const val CHANNEL_ID = "black_bar_service"
        private const val NOTIFICATION_ID = 1001
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HEIGHT = "height"
        private const val DEFAULT_HEIGHT = 40
        private const val MIN_HEIGHT = 10
        private const val MAX_HEIGHT = 150
        private const val HIDE_DURATION_MS = 2_000L
    }
}
