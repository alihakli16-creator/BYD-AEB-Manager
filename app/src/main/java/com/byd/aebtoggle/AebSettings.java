package com.byd.aebtoggle;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.byd.aebtoggle.core.Mode;
import com.byd.aebtoggle.core.SettingsRules;
import com.byd.aebtoggle.core.SettingsView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Resilient multi-location settings store (v19 refactor S1 + Stage 9).
 *
 * Persists settings redundantly to:
 *   1. Primary App Private SharedPreferences ("AEB_Prefs")
 *   2. Device Protected Storage SharedPreferences ("AEB_Prefs")
 *   3. App External Files backup: getExternalFilesDir(null)/settings_backup.json
 *   4. Public SDCard backup: /sdcard/AFSKapat/settings.json (Non-sensitive settings only)
 *
 * Automatically restores from backups if the primary storage is ever cleared.
 * Provides storage diagnostics for all five candidate storage locations.
 */
public final class AebSettings implements SettingsView {

    private static final String TAG = "AebSettings";
    private static final String PREFS = "AEB_Prefs";
    private static final String KEY_MODE = "protection_mode";
    private static final String KEY_START_ON_BOOT = "start_on_boot";
    private static final String KEY_SHOW_FLOATING = "show_floating";
    private static final String KEY_LEGACY_DISABLE = "disable_aeb";
    private static final String KEY_FIRST_RUN = "is_first_run_v18_9";
    private static final String KEY_HUD_X = "hud_x";
    private static final String KEY_HUD_Y = "hud_y";
    private static final String KEY_BOOT_CONSUMED_ID = "boot_consumed_id";
    private static final String KEY_SETUP_COMPLETE = "first_time_setup_complete";
    private static final String KEY_AUTO_ADB_CONFIRM = "auto_adb_confirm";

    private static volatile boolean manualOverrideActive = false;

    private final Context context;
    private final SharedPreferences prefs;
    private final SharedPreferences dpcPrefs;

    public AebSettings(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        SharedPreferences dpc = null;
        try {
            Context dpcCtx = context.createDeviceProtectedStorageContext();
            if (dpcCtx != null) {
                dpc = dpcCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Device protected context unavailable: " + t.getMessage());
        }
        this.dpcPrefs = dpc;

        // Auto-restore on startup if primary prefs was wiped
        ensureRestoredFromBackup();
    }

    /** Raw stored value, null when missing. */
    private Object raw(String key) {
        Object val = prefs.getAll().get(key);
        if (val != null) return val;
        if (dpcPrefs != null) {
            return dpcPrefs.getAll().get(key);
        }
        return null;
    }

    @Override
    public int mode() {
        return SettingsRules.mode(raw(KEY_MODE));
    }

    public boolean startOnBoot() {
        return SettingsRules.flag(raw(KEY_START_ON_BOOT), SettingsRules.DEFAULT_START_ON_BOOT);
    }

    public boolean showFloating() {
        return SettingsRules.flag(raw(KEY_SHOW_FLOATING), SettingsRules.DEFAULT_SHOW_FLOATING);
    }

    public boolean isAutoAdbConfirmEnabled() {
        return SettingsRules.flag(raw(KEY_AUTO_ADB_CONFIRM), true);
    }

    public void setAutoAdbConfirmEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_ADB_CONFIRM, enabled).apply();
        if (dpcPrefs != null) dpcPrefs.edit().putBoolean(KEY_AUTO_ADB_CONFIRM, enabled).apply();
    }

    public int hudX() {
        return SettingsRules.number(raw(KEY_HUD_X), 0);
    }

    public int hudY() {
        return SettingsRules.number(raw(KEY_HUD_Y), 100);
    }

    public void saveHudPosition(int x, int y) {
        prefs.edit().putInt(KEY_HUD_X, x).putInt(KEY_HUD_Y, y).apply();
        if (dpcPrefs != null) {
            dpcPrefs.edit().putInt(KEY_HUD_X, x).putInt(KEY_HUD_Y, y).apply();
        }
    }

    public static String getKernelBootId() {
        File f = new File("/proc/sys/kernel/random/boot_id");
        if (f.exists() && f.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line = br.readLine();
                if (line != null) return line.trim();
            } catch (Throwable ignored) {}
        }
        return "unknown";
    }

    private long currentBootId() {
        try {
            int bootCount = android.provider.Settings.Global.getInt(
                    context.getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT, -1);
            if (bootCount > 0) return (long) bootCount;
        } catch (Throwable ignored) {}
        long bootTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return bootTimeMs / 10000L;
    }

    @Override
    public boolean isBootEnforceConsumed() {
        long stored = prefs.getLong(KEY_BOOT_CONSUMED_ID, -1L);
        return stored != -1L && stored == currentBootId();
    }

    @Override
    public void setBootEnforceConsumed(boolean consumed) {
        if (consumed) {
            long bid = currentBootId();
            prefs.edit().putLong(KEY_BOOT_CONSUMED_ID, bid).apply();
            if (dpcPrefs != null) dpcPrefs.edit().putLong(KEY_BOOT_CONSUMED_ID, bid).apply();
        } else {
            prefs.edit().remove(KEY_BOOT_CONSUMED_ID).apply();
            if (dpcPrefs != null) dpcPrefs.edit().remove(KEY_BOOT_CONSUMED_ID).apply();
        }
    }

    @Override
    public boolean isManualOverrideActive() {
        return manualOverrideActive;
    }

    @Override
    public void setManualOverrideActive(boolean active) {
        manualOverrideActive = active;
        StateStore.event("settings: manualOverrideActive = " + active);
    }

    public boolean isSetupComplete() {
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false);
    }

    public void setSetupComplete(boolean complete) {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETE, complete).apply();
        if (dpcPrefs != null) dpcPrefs.edit().putBoolean(KEY_SETUP_COMPLETE, complete).apply();
    }

    public void save(boolean startOnBoot, boolean showFloating, int mode) {
        int before = mode();
        if (before != mode) {
            StateStore.event("settings: mode " + Mode.label(before) + " -> " + Mode.label(mode));
            setBootEnforceConsumed(false);
        }
        // 1. Primary prefs
        prefs.edit()
                .putBoolean(KEY_START_ON_BOOT, startOnBoot)
                .putBoolean(KEY_SHOW_FLOATING, showFloating)
                .putInt(KEY_MODE, mode)
                .apply();

        // 2. Device protected prefs
        if (dpcPrefs != null) {
            dpcPrefs.edit()
                    .putBoolean(KEY_START_ON_BOOT, startOnBoot)
                    .putBoolean(KEY_SHOW_FLOATING, showFloating)
                    .putInt(KEY_MODE, mode)
                    .apply();
        }

        // 3. Backup to external files & sdcard
        writeJsonBackup(startOnBoot, showFloating, mode);
    }

    private void writeJsonBackup(boolean startOnBoot, boolean showFloating, int mode) {
        try {
            JSONObject json = new JSONObject();
            json.put(KEY_START_ON_BOOT, startOnBoot);
            json.put(KEY_SHOW_FLOATING, showFloating);
            json.put(KEY_MODE, mode);
            json.put(KEY_SETUP_COMPLETE, isSetupComplete());
            json.put("saved_at", System.currentTimeMillis());

            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

            // Location 3: App External Files
            File extDir = context.getExternalFilesDir(null);
            if (extDir != null) {
                File f = new File(extDir, "settings_backup.json");
                writeFile(f, data);
            }

            // Location 4: Public SDCard
            File sdDir = new File("/sdcard/AFSKapat");
            if (sdDir.exists() || sdDir.mkdirs()) {
                File sf = new File(sdDir, "settings.json");
                writeFile(sf, data);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed writing settings backup: " + t.getMessage());
        }
    }

    private void ensureRestoredFromBackup() {
        if (prefs.contains(KEY_MODE)) return; // Primary is already populated

        // 1. Try DPC prefs
        if (dpcPrefs != null && dpcPrefs.contains(KEY_MODE)) {
            Log.i(TAG, "Restoring settings from Device Protected Storage");
            StateStore.event("settings: Cihaz korumalı depolamadan geri yüklendi");
            copyPrefs(dpcPrefs, prefs);
            return;
        }

        // 2. Try App External Files
        try {
            File extDir = context.getExternalFilesDir(null);
            if (extDir != null) {
                File f = new File(extDir, "settings_backup.json");
                if (f.exists() && f.length() > 0) {
                    if (restoreFromJson(f)) {
                        StateStore.event("settings: Harici dizinden geri yüklendi: " + f.getAbsolutePath());
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 3. Try Public SDCard
        try {
            File sf = new File("/sdcard/AFSKapat/settings.json");
            if (sf.exists() && sf.length() > 0) {
                if (restoreFromJson(sf)) {
                    StateStore.event("settings: SDCard'dan geri yüklendi: " + sf.getAbsolutePath());
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean restoreFromJson(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            JSONObject json = new JSONObject(sb.toString());

            boolean startOnBoot = json.optBoolean(KEY_START_ON_BOOT, SettingsRules.DEFAULT_START_ON_BOOT);
            boolean showFloating = json.optBoolean(KEY_SHOW_FLOATING, SettingsRules.DEFAULT_SHOW_FLOATING);
            int mode = json.optInt(KEY_MODE, SettingsRules.DEFAULT_MODE);
            boolean setupComplete = json.optBoolean(KEY_SETUP_COMPLETE, false);

            prefs.edit()
                    .putBoolean(KEY_START_ON_BOOT, startOnBoot)
                    .putBoolean(KEY_SHOW_FLOATING, showFloating)
                    .putInt(KEY_MODE, mode)
                    .putBoolean(KEY_SETUP_COMPLETE, setupComplete)
                    .apply();
            if (dpcPrefs != null) {
                dpcPrefs.edit()
                        .putBoolean(KEY_START_ON_BOOT, startOnBoot)
                        .putBoolean(KEY_SHOW_FLOATING, showFloating)
                        .putInt(KEY_MODE, mode)
                        .putBoolean(KEY_SETUP_COMPLETE, setupComplete)
                        .apply();
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to restore settings from " + file + ": " + t.getMessage());
            return false;
        }
    }

    private static void copyPrefs(SharedPreferences from, SharedPreferences to) {
        SharedPreferences.Editor ed = to.edit();
        for (String k : from.getAll().keySet()) {
            Object v = from.getAll().get(k);
            if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) ed.putInt(k, (Integer) v);
            else if (v instanceof Long) ed.putLong(k, (Long) v);
            else if (v instanceof Float) ed.putFloat(k, (Float) v);
            else if (v instanceof String) ed.putString(k, (String) v);
        }
        ed.apply();
    }

    public void ensureDefaults() {
        SharedPreferences.Editor e = prefs.edit();
        boolean changed = false;
        if (!prefs.contains(KEY_START_ON_BOOT)) {
            e.putBoolean(KEY_START_ON_BOOT, SettingsRules.DEFAULT_START_ON_BOOT);
            changed = true;
        }
        if (!prefs.contains(KEY_MODE)) {
            e.putInt(KEY_MODE, SettingsRules.DEFAULT_MODE);
            changed = true;
        }
        if (!prefs.contains(KEY_SHOW_FLOATING)) {
            e.putBoolean(KEY_SHOW_FLOATING, SettingsRules.DEFAULT_SHOW_FLOATING);
            changed = true;
        }
        if (changed) {
            e.apply();
            if (dpcPrefs != null) copyPrefs(prefs, dpcPrefs);
        }
    }

    public void migrateLegacy() {
        if (!prefs.contains(KEY_LEGACY_DISABLE)) return;
        int mode = SettingsRules.migrateLegacy(raw(KEY_LEGACY_DISABLE), raw(KEY_MODE));
        prefs.edit().putInt(KEY_MODE, mode).remove(KEY_LEGACY_DISABLE).apply();
        if (dpcPrefs != null) {
            dpcPrefs.edit().putInt(KEY_MODE, mode).remove(KEY_LEGACY_DISABLE).apply();
        }
    }

    public boolean consumeFirstRun() {
        if (!SettingsRules.flag(raw(KEY_FIRST_RUN), true)) return false;
        prefs.edit().putBoolean(KEY_FIRST_RUN, false).apply();
        if (dpcPrefs != null) dpcPrefs.edit().putBoolean(KEY_FIRST_RUN, false).apply();
        return true;
    }

    // =========================================================================
    // STORAGE DIAGNOSTICS & TELEMETRY
    // =========================================================================

    public static class StorageLocationReport {
        public final String name;
        public final String path;
        public final boolean isSurviving;
        public final int launchCount;
        public final String statusText;

        public StorageLocationReport(String name, String path, boolean isSurviving, int launchCount, String statusText) {
            this.name = name;
            this.path = path;
            this.isSurviving = isSurviving;
            this.launchCount = launchCount;
            this.statusText = statusText;
        }
    }

    public List<StorageLocationReport> runStorageDiagnostics() {
        List<StorageLocationReport> reports = new ArrayList<>();
        String currentBootId = getKernelBootId();

        // 1. Normal private storage
        File dirA = context.getFilesDir();
        reports.add(checkLocation("Özel Dahili Depolama (filesDir)", dirA, currentBootId));

        // 2. Device Protected Storage
        File dirB = null;
        try {
            Context dpc = context.createDeviceProtectedStorageContext();
            if (dpc != null) dirB = dpc.getFilesDir();
        } catch (Throwable ignored) {}
        reports.add(checkLocation("Cihaz Korumalı Depolama (DirectBoot)", dirB, currentBootId));

        // 3. App External Files Dir
        File dirC = context.getExternalFilesDir(null);
        reports.add(checkLocation("Uygulama Harici Depolama (externalFilesDir)", dirC, currentBootId));

        // 4. App External Media Dir
        File dirD = null;
        try {
            File[] medias = context.getExternalMediaDirs();
            if (medias != null && medias.length > 0) dirD = medias[0];
        } catch (Throwable ignored) {}
        reports.add(checkLocation("Harici Medya Dizini (externalMediaDirs)", dirD, currentBootId));

        // 5. Public SDCard
        File dirE = new File("/sdcard/AFSKapat");
        reports.add(checkLocation("Genel SDCard (/sdcard/AFSKapat)", dirE, currentBootId));

        for (StorageLocationReport r : reports) {
            StateStore.event("tanılama [" + r.name + "]: " + r.statusText + " (" + r.path + ")");
        }
        return reports;
    }

    private StorageLocationReport checkLocation(String name, File dir, String bootId) {
        if (dir == null) {
            return new StorageLocationReport(name, "mevcut değil", false, 0, "erişilemiyor (null)");
        }
        try {
            if (!dir.exists()) dir.mkdirs();
            File marker = new File(dir, "launch_marker.json");
            int count = 1;
            boolean surviving = false;

            if (marker.exists() && marker.length() > 0) {
                try (FileInputStream fis = new FileInputStream(marker);
                     BufferedReader br = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = br.readLine()) != null) sb.append(l);
                    JSONObject json = new JSONObject(sb.toString());
                    count = json.optInt("launch_count", 0) + 1;
                    surviving = true;
                } catch (Throwable ignored) {}
            }

            // Write updated marker
            JSONObject out = new JSONObject();
            out.put("launch_count", count);
            out.put("boot_id", bootId);
            out.put("last_launch", System.currentTimeMillis());
            writeFile(marker, out.toString().getBytes(StandardCharsets.UTF_8));

            String status = surviving ? "devam ediyor (" + count + ". başlatma)" : "yeni (1. başlatma)";
            return new StorageLocationReport(name, dir.getAbsolutePath(), surviving, count, status);
        } catch (Throwable t) {
            return new StorageLocationReport(name, dir.getAbsolutePath(), false, 0, "hata: " + t.getMessage());
        }
    }

    public String getSystemTelemetry() {
        StringBuilder sb = new StringBuilder();
        sb.append("Açılış Kimliği (boot_id): ").append(getKernelBootId()).append("\n");
        sb.append("Kullanıcı Kimliği: ").append(Process.myUserHandle()).append("\n");
        try {
            PackageInfo p = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            sb.append("İlk Kurulum: ").append(new Date(p.firstInstallTime)).append("\n");
            sb.append("Son Güncelleme: ").append(new Date(p.lastUpdateTime)).append("\n");
        } catch (Throwable t) {
            sb.append("Kurulum Bilgisi: alınamadı\n");
        }

        try {
            File dataDir = context.getDataDir();
            if (dataDir != null && dataDir.exists()) {
                File[] list = dataDir.listFiles();
                int cnt = (list != null) ? list.length : 0;
                sb.append("dataDir Dizin Öğeleri (").append(cnt).append("):\n");
                if (list != null) {
                    for (File f : list) {
                        sb.append("  - ").append(f.getName()).append(" (")
                          .append(f.isDirectory() ? "dizin" : f.length() + " B")
                          .append(", ").append(new Date(f.lastModified())).append(")\n");
                    }
                }
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static void writeFile(File target, byte[] data) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(data);
            fos.flush();
        }
    }
}
