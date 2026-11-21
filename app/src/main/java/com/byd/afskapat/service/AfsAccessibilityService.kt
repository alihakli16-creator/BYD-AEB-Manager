package com.byd.afskapat.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.byd.aebtoggle.R
import com.byd.afskapat.util.OverlayHelper

/**
 * DiLink 3.0 (Android 10) Erişilebilirlik Servisi.
 * Araç çalıştığında AFS'yi otomatik kapatır.
 */
class AfsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AFSKapat_Service"
        var isServiceRunning = false
            private set
        var lastStatusMessage: String = "Servis hazır"
            private set

        private var instance: AfsAccessibilityService? = null

        fun triggerCloseProcess(context: Context, source: String = "Manuel") {
            Log.d(TAG, "Kapatma süreci tetiklendi. Kaynak: $source")
            instance?.startAutomation() ?: run {
                Log.w(TAG, "Erişilebilirlik servisi henüz aktif değil.")
            }
        }
    }

    private lateinit var overlayHelper: OverlayHelper
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isAutomationActive = false
    private var automationStartTime = 0L
    private val timeoutMs = 15_000L

    private val adasKeywords = listOf(
        "adas", "sürüş asistanı", "sürüş destek", "güvenlik yardımı",
        "driving assist", "safety assist", "active safety", "akıllı sürüş"
    )

    private val aebKeywords = listOf(
        "otomatik acil fren", "acil durum freni", "acil fren", "otomatik frenleme",
        "aeb", "automatic emergency braking", "autonomous emergency braking",
        "pcw & aeb", "ön çarpışma önleme"
    )

    private val confirmKeywords = listOf(
        "tamam", "onayla", "kapat", "devre dışı bırak", "evet",
        "ok", "confirm", "turn off", "disable", "sure"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        overlayHelper = OverlayHelper(this)
        updateStatus(getString(R.string.status_permissions_granted))
        Log.i(TAG, "AfsAccessibilityService başarıyla bağlandı (DiLink 3.0 / Android 10).")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        if (instance == this) instance = null
        Log.i(TAG, "AfsAccessibilityService sonlandırıldı.")
    }

    fun startAutomation() {
        if (isAutomationActive) return

        isAutomationActive = true
        automationStartTime = SystemClock.elapsedRealtime()
        updateStatus(getString(R.string.status_processing))
        overlayHelper.showMessage(getString(R.string.status_navigating_adas), durationMs = 2500)

        openBydSettings()

        mainHandler.postDelayed({
            if (isAutomationActive && SystemClock.elapsedRealtime() - automationStartTime >= timeoutMs) {
                isAutomationActive = false
                updateStatus(getString(R.string.status_timeout_error))
                Log.w(TAG, "Otomasyon zaman aşımına uğradı.")
            }
        }, timeoutMs + 500)
    }

    private fun openBydSettings() {
        val targetPackages = listOf("com.byd.carsetting", "com.byd.settings", "com.android.settings")
        for (pkg in targetPackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(intent)
                return
            }
        }
        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAutomationActive || event == null) return
        val rootNode = rootInActiveWindow ?: return

        try {
            if (handleConfirmationDialog(rootNode)) return
            if (findAndToggleAebSwitch(rootNode)) return
            navigateToAdasTab(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Erişilebilirlik hatası: ${e.message}", e)
        }
    }

    private fun navigateToAdasTab(rootNode: AccessibilityNodeInfo): Boolean {
        for (keyword in adasKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                if (node.isVisibleToUser) {
                    val clickableNode = findClickableParent(node) ?: node
                    if (clickableNode.isClickable && !clickableNode.isSelected) {
                        clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun findAndToggleAebSwitch(rootNode: AccessibilityNodeInfo): Boolean {
        for (keyword in aebKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            for (labelNode in nodes) {
                if (!labelNode.isVisibleToUser) continue
                val switchNode = findAssociatedSwitch(labelNode)
                if (switchNode != null) {
                    if (switchNode.isChecked) {
                        updateStatus(getString(R.string.status_toggling_aeb))
                        switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                        mainHandler.postDelayed({
                            val currentRoot = rootInActiveWindow
                            if (currentRoot != null) handleConfirmationDialog(currentRoot)
                        }, 350)
                        return true
                    } else {
                        completeAutomation(alreadyOff = true)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun handleConfirmationDialog(rootNode: AccessibilityNodeInfo): Boolean {
        for (keyword in confirmKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                if (node.isVisibleToUser) {
                    val clickableNode = findClickableParent(node) ?: node
                    if (clickableNode.isClickable) {
                        updateStatus(getString(R.string.status_confirming_dialog))
                        clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                        mainHandler.postDelayed({
                            completeAutomation(alreadyOff = false)
                        }, 500)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun findAssociatedSwitch(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isSwitchWidget(node)) return node
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 3) {
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                if (isSwitchWidget(child)) return child
            }
            current = current.parent
            depth++
        }
        return null
    }

    private fun isSwitchWidget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        return className.contains("Switch", true) ||
                className.contains("CheckBox", true) ||
                className.contains("ToggleButton", true) ||
                node.isCheckable
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun completeAutomation(alreadyOff: Boolean) {
        if (!isAutomationActive) return
        isAutomationActive = false

        if (alreadyOff) {
            val msg = getString(R.string.status_aeb_already_off)
            updateStatus(msg)
            overlayHelper.showMessage(msg, isSuccess = true)
        } else {
            val msg = getString(R.string.status_aeb_disabled_success)
            updateStatus(msg)
            overlayHelper.showMessage(msg, isSuccess = true, durationMs = 4000)
        }

        mainHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 300)
    }

    private fun updateStatus(status: String) {
        lastStatusMessage = status
        Log.i(TAG, "Durum: $status")
    }

    override fun onInterrupt() {
        isAutomationActive = false
    }
}
