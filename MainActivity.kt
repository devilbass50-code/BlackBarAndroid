package com.example.blackbar

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var heightText: TextView
    private var waitingForPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) refreshStatus()
        if (waitingForPermission && hasRequiredPermissions()) {
            waitingForPermission = false
            startMonitor()
        }
    }

    private fun buildUi() {
        val pad = dp(18)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(22), pad, pad)
            setBackgroundColor(0xFFF4F4F4.toInt())
        }

        root.addView(TextView(this).apply {
            text = "Black Bar"
            textSize = 27f
            setTextColor(0xFF171717.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Выбери приложения, в которых должна появляться чёрная полоса."
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(7), 0, dp(16))
        })

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF555555.toInt())
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(statusText)

        val permissionsButton = Button(this).apply {
            text = "Настроить разрешения Android"
            isAllCaps = false
            setOnClickListener { requestPermissionsFlow() }
        }
        root.addView(permissionsButton, matchWidth())

        root.addView(TextView(this).apply {
            text = "Высота полосы"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF171717.toInt())
            setPadding(0, dp(20), 0, dp(5))
        })
        val initialHeight = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        heightText = TextView(this).apply {
            text = "$initialHeight dp"
            textSize = 14f
            setTextColor(0xFF555555.toInt())
        }
        root.addView(heightText)
        root.addView(SeekBar(this).apply {
            max = MAX_HEIGHT - MIN_HEIGHT
            progress = initialHeight - MIN_HEIGHT
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val h = progress + MIN_HEIGHT
                    heightText.text = "$h dp"
                    prefs.edit().putInt(KEY_HEIGHT, h).apply()
                    sendUpdate()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        root.addView(TextView(this).apply {
            text = "Установленные приложения"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF171717.toInt())
            setPadding(0, dp(18), 0, dp(8))
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val selected = prefs.getStringSet(KEY_SELECTED, emptySet())?.toMutableSet() ?: mutableSetOf()
        val apps = getLaunchableApps()
        if (apps.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Не удалось получить список приложений."
                setTextColor(0xFF666666.toInt())
            })
        } else {
            for (app in apps) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                }
                val icon = try { packageManager.getApplicationIcon(app.packageName) } catch (_: Exception) { null }
                val iconView = android.widget.ImageView(this).apply {
                    if (icon != null) setImageDrawable(icon)
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
                }
                row.addView(iconView)
                val label = TextView(this).apply {
                    text = app.label
                    textSize = 15f
                    setTextColor(0xFF222222.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(label)
                val check = CheckBox(this).apply {
                    isChecked = selected.contains(app.packageName)
                    contentDescription = "Показывать полосу в ${app.label}"
                    setOnCheckedChangeListener { _, checked ->
                        val current = prefs.getStringSet(KEY_SELECTED, emptySet())?.toMutableSet() ?: mutableSetOf()
                        if (checked) current.add(app.packageName) else current.remove(app.packageName)
                        prefs.edit().putStringSet(KEY_SELECTED, current).apply()
                        sendUpdate()
                    }
                }
                row.addView(check)
                list.addView(row)
                val divider = View(this).apply {
                    setBackgroundColor(0xFFE0E0E0.toInt())
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                }
                list.addView(divider)
            }
        }
        root.addView(list)
        root.addView(Button(this).apply {
            text = "Остановить автоматическую полосу"
            isAllCaps = false
            setOnClickListener { stopMonitor() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        setContentView(android.widget.ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    private data class LaunchableApp(val packageName: String, val label: String)

    @Suppress("DEPRECATION")
    private fun getLaunchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
        return infos.mapNotNull {
            val pkg = it.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == packageName) return@mapNotNull null
            LaunchableApp(pkg, it.loadLabel(packageManager).toString())
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasRequiredPermissions() =
        Settings.canDrawOverlays(this) && hasUsageAccess()

    private fun requestPermissionsFlow() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!hasUsageAccess()) {
            waitingForPermission = true
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        startMonitor()
    }

    private fun startMonitor() {
        if (!hasRequiredPermissions()) {
            refreshStatus()
            return
        }
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        val intent = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        refreshStatus()
    }

    private fun stopMonitor() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        refreshStatus()
    }

    private fun sendUpdate() {
        if (prefs.getBoolean(KEY_ENABLED, false)) {
            val intent = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_UPDATE)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
    }

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        statusText.text = when {
            !Settings.canDrawOverlays(this) -> "Нужно разрешение на показ поверх других приложений."
            !hasUsageAccess() -> "Нужно разрешить доступ к статистике использования."
            prefs.getBoolean(KEY_ENABLED, false) -> "Автоматический режим включён."
            else -> "Разрешения готовы. Включи режим кнопкой ниже."
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "black_bar_settings"
        private const val KEY_SELECTED = "selected_packages"
        private const val KEY_HEIGHT = "height"
        private const val KEY_ENABLED = "enabled"
        private const val DEFAULT_HEIGHT = 40
        private const val MIN_HEIGHT = 10
        private const val MAX_HEIGHT = 150
    }
}
