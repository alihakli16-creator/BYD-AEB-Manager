package com.byd.aebtoggle;

import android.content.Context;
import android.content.SharedPreferences;

import com.byd.aebtoggle.core.Mode;
import com.byd.aebtoggle.core.SettingsRules;
import com.byd.aebtoggle.core.SettingsView;

/**
 * The only class that knows the preferences file "AEB_Prefs" and its keys (v19 refactor S1).
 * Values are interpreted by core SettingsRules, so the service, the screen, the boot receiver and
 * the HUD can no longer disagree. Reading never throws: a stored value of the wrong type counts as
 * missing (for the mode: as No Action, exactly like AebService v18.22).
 */
final class AebSettings implements SettingsView {

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

    private final Context context;
    private final SharedPreferences prefs;

    AebSettings(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Raw stored value, null when missing. */
    private Object raw(String key) {
        return prefs.getAll().get(key);
    }

    /** Effective protection mode, re-read on every call (the engine calls it right before each write). */
    @Override
    public int mode() {
        return SettingsRules.mode(raw(KEY_MODE));
    }

    boolean startOnBoot() {
        return SettingsRules.flag(raw(KEY_START_ON_BOOT), SettingsRules.DEFAULT_START_ON_BOOT);
    }

    boolean showFloating() {
        return SettingsRules.flag(raw(KEY_SHOW_FLOATING), SettingsRules.DEFAULT_SHOW_FLOATING);
    }

    int hudX() {
        return SettingsRules.number(raw(KEY_HUD_X), 0);
    }

    int hudY() {
        return SettingsRules.number(raw(KEY_HUD_Y), 100);
    }

    void saveHudPosition(int x, int y) {
        prefs.edit().putInt(KEY_HUD_X, x).putInt(KEY_HUD_Y, y).apply();
    }

    private long currentBootId() {
        try {
            int bootCount = android.provider.Settings.Global.getInt(
                    context.getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT, -1);
            if (bootCount > 0) return (long) bootCount;
        } catch (Throwable ignored) {}
        long bootTimeMs = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
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
            prefs.edit().putLong(KEY_BOOT_CONSUMED_ID, currentBootId()).apply();
        } else {
            prefs.edit().remove(KEY_BOOT_CONSUMED_ID).apply();
        }
    }

    boolean isSetupComplete() {
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false);
    }

    void setSetupComplete(boolean complete) {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETE, complete).apply();
    }

    void save(boolean startOnBoot, boolean showFloating, int mode) {
        int before = mode();
        if (before != mode) {
            StateStore.event("settings: mode " + Mode.label(before) + " -> " + Mode.label(mode));
            // Mode change resets the Once-on-Start consumption so switching into mode 1 acts on the new mode
            setBootEnforceConsumed(false);
        }
        prefs.edit()
                .putBoolean(KEY_START_ON_BOOT, startOnBoot)
                .putBoolean(KEY_SHOW_FLOATING, showFloating)
                .putInt(KEY_MODE, mode)
                .apply();
    }

    /** Writes the default of every missing key (was MainActivity.ensureDefaultSettings). */
    void ensureDefaults() {
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
        if (changed) e.apply();
    }

    /** Old two-key system to protection_mode (was in MainActivity.loadSettings). */
    void migrateLegacy() {
        if (!prefs.contains(KEY_LEGACY_DISABLE)) return;
        int mode = SettingsRules.migrateLegacy(raw(KEY_LEGACY_DISABLE), raw(KEY_MODE));
        prefs.edit().putInt(KEY_MODE, mode).remove(KEY_LEGACY_DISABLE).apply();
    }

    /** True exactly once per install (key is_first_run_v18_9, unchanged so existing installs are not asked again). */
    boolean consumeFirstRun() {
        if (!SettingsRules.flag(raw(KEY_FIRST_RUN), true)) return false;
        prefs.edit().putBoolean(KEY_FIRST_RUN, false).apply();
        return true;
    }
}
