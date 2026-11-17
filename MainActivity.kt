package com.example.blackbar

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("black_bar_settings", MODE_PRIVATE) }
    private lateinit var enabledSwitch: Switch
    private lateinit var heightLabel: TextView
    private lateinit var heightSeekBar: SeekBar
    private var waitingForOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = dp(22)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(28), padding, padding)
            setBackgroundColor(0xFFF4F4F4.toInt())
        }

        val title = TextView(this).apply {
            text = "Чёрная полоса"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF171717.toInt())
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Настрой внешний вид полосы внизу экрана."
            textSize = 15f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(8), 0, dp(24))
        }
        root.addView(subtitle)

        enabledSwitch = Switch(this).apply {
            text = "Показывать поверх приложений"
            textSize = 16f
            isChecked = prefs.getBoolean(KEY_ENABLED, false) && Settings.canDrawOverlays(this@MainActivity)
        }
        root.addView(enabledSwitch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val heightHeading = TextView(this).apply {
            text = "Высота полосы"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF171717.toInt())
            setPadding(0, dp(30), 0, dp(8))
        }
        root.addView(heightHeading)

        val savedHeight = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        heightLabel = TextView(this).apply {
            text = "$savedHeight dp"
            textSize = 15f
            setTextColor(0xFF555555.toInt())
        }
        root.addView(heightLabel)

        heightSeekBar = SeekBar(this).apply {
            max = MAX_HEIGHT - MIN_HEIGHT
            progress = savedHeight - MIN_HEIGHT
            contentDescription = "Высота чёрной полосы"
        }
        root.addView(heightSeekBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val hint = TextView(this).apply {
            text = "Нажми на чёрную полосу — она исчезнет на 2 секунды, затем появится снова."
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(20), 0, dp(24))
        }
        root.addView(hint)

        val permissionButton = Button(this).apply {
            text = "Разрешение «Поверх других приложений»"
            isAllCaps = false
            setOnClickListener { openOverlayPermissionSettings() }
        }
        root.addView(permissionButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val status = TextView(this).apply {
            text = if (Settings.canDrawOverlays(this@MainActivity)) {
                "Разрешение выдано"
            } else {
                "Для работы нужно разрешить отображение поверх других приложений."
            }
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(status)

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    waitingForOverlayPermission = true
                    enabledSwitch.isChecked = false
                    openOverlayPermissionSettings()
                } else {
                    startOverlay()
                }
            } else {
                stopOverlay()
            }
        }

        heightSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val height = progress + MIN_HEIGHT
                heightLabel.text = "$height dp"
                prefs.edit().putInt(KEY_HEIGHT, height).apply()
                if (prefs.getBoolean(KEY_ENABLED, false)) {
                    startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_UPDATE
                        putExtra(OverlayService.EXTRA_HEIGHT, height)
                    })
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        setContentView(root)

        // Восстанавливаем отображение, если оно было включено ранее.
        if (enabledSwitch.isChecked) {
            startOverlay()
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlayPermission && Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = false
            enabledSwitch.isChecked = true
        }
    }

    private fun startOverlay() {
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopOverlay() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        })
    }

    private fun openOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HEIGHT = "height"
        private const val DEFAULT_HEIGHT = 40
        private const val MIN_HEIGHT = 10
        private const val MAX_HEIGHT = 150
    }
}
