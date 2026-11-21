package com.byd.afskapat.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.byd.afskapat.service.AfsAccessibilityService

/**
 * Araç çalıştığında, kontak açıldığında veya ekran uyandığında tetiklenen BroadcastReceiver.
 * DiLink 3.0 (Android 10) sistem başlangıç ve uyku döngüleriyle tam uyumludur.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AFSKapat_BootReceiver"
        private const val PREFS_NAME = "afs_prefs"
        private const val PREF_KEY_AUTO = "pref_auto_disable"
        private const val PREF_KEY_DELAY = "pref_delay_seconds"
        private var lastTriggerTime = 0L
        private const val COOLDOWN_MS = 15_000L // 15 saniye içinde mükerrer tetiklemeleri önle
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Sistem sinyali alındı: $action")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAutoDisableEnabled = prefs.getBoolean(PREF_KEY_AUTO, true)
        if (!isAutoDisableEnabled) {
            Log.d(TAG, "Otomatik kapatma kullanıcı tarafından devre dışı bırakılmış.")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < COOLDOWN_MS) {
            Log.d(TAG, "Mükerrer tetikleme engellendi (Bekleme süresi aktif).")
            return
        }
        lastTriggerTime = now

        // Araç ekranı ve servislerin tam yüklenmesi için ayarlanmış bekleme süresi (varsayılan 3 sn)
        val delaySeconds = prefs.getInt(PREF_KEY_DELAY, 3)
        val delayMs = delaySeconds * 1000L

        Log.i(TAG, "AFS kapatma işlemi $delayMs ms sonra başlatılacak...")
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                AfsAccessibilityService.triggerCloseProcess(context, source = "BootReceiver: $action")
            } catch (e: Exception) {
                Log.e(TAG, "Otomasyon tetiklenirken hata oluştu: ${e.message}", e)
            }
        }, delayMs)
    }
}
