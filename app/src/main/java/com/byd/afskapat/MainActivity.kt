package com.byd.afskapat

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mk.aebmanager.R
import com.byd.afskapat.service.AfsAccessibilityService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "afs_prefs"
        private const val PREF_KEY_AUTO = "pref_auto_disable"
    }

    private lateinit var switchAutoDisable: SwitchMaterial
    private lateinit var btnTestCloseNow: MaterialButton
    private lateinit var btnPermAccessibility: MaterialButton
    private lateinit var btnPermOverlay: MaterialButton
    private lateinit var btnPermBattery: MaterialButton
    private lateinit var tvStatusMessage: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupPreferences()
        setupListeners()
    }

    private fun initViews() {
        switchAutoDisable = findViewById(R.id.switchAutoDisable)
        btnTestCloseNow = findViewById(R.id.btnTestCloseNow)
        btnPermAccessibility = findViewById(R.id.btnPermAccessibility)
        btnPermOverlay = findViewById(R.id.btnPermOverlay)
        btnPermBattery = findViewById(R.id.btnPermBattery)
        tvStatusMessage = findViewById(R.id.tvStatusMessage)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
        startLiveStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        stopLiveStatusPolling()
    }

    private fun setupPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAuto = prefs.getBoolean(PREF_KEY_AUTO, true)
        switchAutoDisable.isChecked = isAuto

        switchAutoDisable.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            prefs.edit().putBoolean(PREF_KEY_AUTO, isChecked).apply()
            val text = if (isChecked) "Otomatik kapatma devrede" else "Otomatik kapatma devre dışı bırakıldı"
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        // 1. Manuel Test Butonu
        btnTestCloseNow.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Lütfen önce Erişilebilirlik İznini açın!", Toast.LENGTH_LONG).show()
                openAccessibilitySettings()
                return@setOnClickListener
            }

            tvStatusMessage.text = getString(R.string.status_processing)
            AfsAccessibilityService.triggerCloseProcess(this, source = "Kullanıcı Test Butonu")
        }

        // 2. Erişilebilirlik İzni Butonu
        btnPermAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // 3. Overlay (Üstte Gösterme) İzni Butonu
        btnPermOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        // 4. Pil Optimizasyonu Butonu
        btnPermBattery.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            }
        }
    }

    private fun updatePermissionStates() {
        val successColor = ContextCompat.getColor(this, R.color.success)

        // Erişilebilirlik Kontrolü
        val isAccEnabled = isAccessibilityServiceEnabled()
        if (isAccEnabled) {
            btnPermAccessibility.text = getString(R.string.perm_accessibility_ok)
            btnPermAccessibility.isEnabled = false
            btnPermAccessibility.backgroundTintList = ColorStateList.valueOf(successColor)
        } else {
            btnPermAccessibility.text = getString(R.string.perm_accessibility_enable)
            btnPermAccessibility.isEnabled = true
        }

        // Overlay Kontrolü
        val isOverlayEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        if (isOverlayEnabled) {
            btnPermOverlay.text = getString(R.string.perm_overlay_ok)
            btnPermOverlay.isEnabled = false
            btnPermOverlay.backgroundTintList = ColorStateList.valueOf(successColor)
        } else {
            btnPermOverlay.text = getString(R.string.perm_overlay_enable)
            btnPermOverlay.isEnabled = true
        }

        // Pil Kontrolü
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isBatteryIgnored = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                powerManager.isIgnoringBatteryOptimizations(packageName)
        if (isBatteryIgnored) {
            btnPermBattery.text = getString(R.string.perm_battery_ok)
            btnPermBattery.isEnabled = false
            btnPermBattery.backgroundTintList = ColorStateList.valueOf(successColor)
        } else {
            btnPermBattery.text = getString(R.string.perm_battery_enable)
            btnPermBattery.isEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName) {
                return true
            }
        }
        return false
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun startLiveStatusPolling() {
        statusRunnable = object : Runnable {
            override fun run() {
                val status = AfsAccessibilityService.lastStatusMessage
                if (status.isNotBlank()) {
                    tvStatusMessage.text = status
                }
                updatePermissionStates()
                handler.postDelayed(this, 1500)
            }
        }
        handler.post(statusRunnable!!)
    }

    private fun stopLiveStatusPolling() {
        statusRunnable?.let { handler.removeCallbacks(it) }
    }
}
