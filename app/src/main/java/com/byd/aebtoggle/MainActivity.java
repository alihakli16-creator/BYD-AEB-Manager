package com.byd.aebtoggle;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.util.Log;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.byd.aebtoggle.core.LinkStatus;
import com.byd.aebtoggle.core.Mode;
import com.byd.aebtoggle.core.ProvisioningReport;
import com.byd.aebtoggle.core.Snapshot;
import com.byd.aebtoggle.core.StatusView;

import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private static final AtomicBoolean GRANT_IN_FLIGHT = new AtomicBoolean(false);

    private TextView tvStatus;
    private View vStatusDot;
    private CheckBox cbStartOnBoot, cbFloating;
    private RadioGroup rgProtection;
    private Button btnDisableNow;
    private Button btnGrant;
    private Handler statusHandler = new Handler(Looper.getMainLooper());
    private boolean isVisible = false;
    private AebSettings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = new AebSettings(this);
        initViews();
        settings.ensureDefaults();
        loadSettings();
        handleFirstRun();
    }

    /** S3: the in-memory event log with Copy (to the clipboard, by the owner's tap) and Clear. Nothing is written to a file. */
    private void showEventLog() {
        Snapshot s = StateStore.current();
        String header = getString(R.string.log_header, BuildConfig.VERSION_NAME, s.phase, s.aeb.name(),
                s.link.name(), Mode.label(settings.mode()));
        String body = StateStore.log().dump(TimeZone.getDefault());
        final String text = header + "\n\n" + (body.isEmpty() ? getString(R.string.log_empty) : body);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextIsSelectable(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); // newest at the bottom

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.log_title, StateStore.log().size()))
                .setView(scroll)
                .setPositiveButton(R.string.log_copy, (d, w) -> {
                    ClipboardManager cm = getSystemService(ClipboardManager.class);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("AEB event log", text));
                        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.log_clear, (d, w) -> {
                    StateStore.log().clear();
                    Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.log_close, null)
                .show();
    }

    private void handleFirstRun() {
        if (settings.consumeFirstRun()) {
            triggerGrantPermission();
        }
    }

    private void initViews() {
        tvStatus = findViewById(R.id.status_label);
        vStatusDot = findViewById(R.id.status_dot);
        cbStartOnBoot = findViewById(R.id.cb_boot);
        cbFloating = findViewById(R.id.cb_floating);
        rgProtection = findViewById(R.id.rg_protection);
        btnDisableNow = findViewById(R.id.btn_disable_now);

        // S3: long press on the status text or the orb opens the event log (no layout change needed)
        View.OnLongClickListener openLog = v -> {
            showEventLog();
            return true;
        };
        tvStatus.setOnLongClickListener(openLog);
        vStatusDot.setOnLongClickListener(openLog);

        btnDisableNow.setOnClickListener(v -> {
            saveSettings();
            // S2: the write runs on the engine's thread (never overlapping an automatic write).
            // The AEB reading is NOT changed here; the engine's next read confirms it (H02 fix).
            boolean queued = StateStore.requestDisableNow(status -> {
                if (isFinishing() || isDestroyed()) return;
                if (status == LinkStatus.SUCCESS) {
                    Toast.makeText(this, R.string.toast_disable_sent, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.toast_disable_failed, status.name()), Toast.LENGTH_SHORT).show();
                }
                render();
            });
            if (queued) {
                Toast.makeText(this, R.string.toast_disabling, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.toast_service_not_running, Toast.LENGTH_SHORT).show();
                syncService();
            }
        });

        View.OnClickListener settingsListener = v -> {
            saveSettings();
            syncService();
            updateUIStates();
        };

        cbStartOnBoot.setOnClickListener(settingsListener);
        cbFloating.setOnClickListener(v -> {
            if (cbFloating.isChecked() && !Settings.canDrawOverlays(this)) {
                if (!GRANT_IN_FLIGHT.compareAndSet(false, true)) {
                    cbFloating.setChecked(settings.showFloating());
                    return;
                }
                updateUIStates();
                Toast.makeText(this, R.string.toast_granting_hud, Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    boolean authorized = false;
                    try {
                        authorized = AdbHelper.grantSelfPermissions();
                    } finally {
                        GRANT_IN_FLIGHT.set(false);
                    }
                    final boolean authResult = authorized;
                    runOnUiThread(() -> {
                        if (authResult) {
                            StateStore.notifyPermissionGranted();
                        }
                        if (isFinishing() || isDestroyed()) return;
                        updateUIStates();
                        // Poll for up to 5 s (same as triggerGrantPermission)
                        final int[] attempts = {0};
                        final Handler h = new Handler(Looper.getMainLooper());
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing() || isDestroyed()) return;
                                if (Settings.canDrawOverlays(MainActivity.this)) {
                                    saveSettings();
                                    syncService();
                                } else if (attempts[0] < 10) {
                                    attempts[0]++;
                                    h.postDelayed(this, 500);
                                } else {
                                    // Still not granted after 5 s — open system settings
                                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:" + getPackageName()));
                                    startActivity(intent);
                                }
                            }
                        });
                    });
                }).start();
            } else {
                saveSettings();
                syncService();
            }
        });

        btnGrant = findViewById(R.id.btn_grant_permissions);
        btnGrant.setOnClickListener(v -> triggerGrantPermission());

        rgProtection.setOnCheckedChangeListener((group, checkedId) -> {
            saveSettings();
            syncService();
            updateProtectionPillColors();
        });

        updateUIStates();
    }

    private void triggerGrantPermission() {
        if (!GRANT_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        updateUIStates();

        final android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
        progress.setTitle(getString(R.string.prov_title));
        progress.setMessage("ADB bağlantısı kuruluyor (127.0.0.1:5555)...");
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            // S6: one result per capability (same commands as before), logged and shown as a checklist
            final ProvisioningReport report = new ProvisioningReport();
            boolean authorized = false;
            boolean allDelivered = false;
            try {
                authorized = AdbHelper.grantSelfPermissions(report, msg -> {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && progress.isShowing()) {
                            progress.setMessage(msg);
                        }
                    });
                });
                allDelivered = report.allDelivered();
                StateStore.event("grant permission: " + (authorized ? "car accepted the key" : "not authorized"));
                for (String line : report.lines()) StateStore.event("grant: " + line);
            } finally {
                GRANT_IN_FLIGHT.set(false);
            }
            final boolean authResult = authorized;
            final boolean allOk = allDelivered;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && progress.isShowing()) {
                    try { progress.dismiss(); } catch (Exception ignored) {}
                }
                if (authResult) {
                    StateStore.notifyPermissionGranted();
                }
                if (isFinishing() || isDestroyed()) return;
                if (authResult) {
                    if (allOk) {
                        Toast.makeText(this, R.string.toast_granted, Toast.LENGTH_SHORT).show();
                    }
                    syncService();
                    updateUIStates();
                    showProvisioningResult(report, true); // its button opens the auto-start screen

                    // v18.8: Force-poll permission for 5 seconds to kickstart HUD immediately
                    final int[] attempts = {0};
                    final Handler h = new Handler(Looper.getMainLooper());
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            if (Settings.canDrawOverlays(MainActivity.this)) {
                                Log.d("AEB_Toggle", "HUD Permission detected! Kickstarting service.");
                                syncService();
                                updateUIStates();
                            } else if (attempts[0] < 10) {
                                attempts[0]++;
                                h.postDelayed(this, 500);
                            }
                        }
                    });
                } else {
                    Toast.makeText(this, R.string.toast_unauthorized, Toast.LENGTH_LONG).show();
                    updateUIStates();
                    showProvisioningResult(report, false);
                }
            });
        }).start();
    }

    /** S6: the checklist of what "Grant Permission" achieved. No layout change: a plain dialog. */
    private void showProvisioningResult(ProvisioningReport report, boolean authorized) {
        StringBuilder text = new StringBuilder();
        if (!authorized) {
            String code = report.failureCode();
            String msg = report.failureMessage();
            if ("PORT_CLOSED".equals(code)) {
                text.append("⚠️ ADB PORTU (5555) KAPALI!\n\n");
                text.append("Araçta yerel ADB servisi dinlemiyor.\n");
                text.append("Lütfen şu adımları uygulayın:\n");
                text.append("1. Araç Ayarları > Sistem > Yazılım Sürüm Numarasına 7 kez ard arda dokunarak Geliştirici Seçeneklerini açın.\n");
                text.append("2. Geliştirici Seçeneklerine girin ve 'Kablosuz Hata Ayıklama' (Wireless Debugging) veya 'Ağ Üzerinden ADB' seçeneğini AKTİF hale getirin.\n");
                text.append("3. Bu ekrana dönüp tekrar 'İZİNLERİ TANIMLA' butonuna dokunun.\n\n");
            } else if ("TIMEOUT".equals(code)) {
                text.append("⏳ ZAMAN AŞIMI (60 sn)!\n\n");
                text.append("Araç ekranında çıkan 'USB hata ayıklamaya izin verilsin mi?' penceresi 60 saniye içinde onaylanmadı.\n");
                text.append("Lütfen tekrar deneyin ve ekranda beliren onay penceresinde 'Bu bilgisayardan her zaman izin ver' kutusunu işaretleyip 'Tamam / İzin Ver'e dokunun.\n\n");
            } else if ("AUTH_FAIL".equals(code)) {
                text.append("❌ ADB YETKİLENDİRMESİ REDDEDİLDİ!\n\n");
                text.append("Araç ekranındaki ADB izin penceresi reddedildi. Tekrar deneyip onay verin.\n\n");
            } else {
                text.append(getString(R.string.prov_not_authorized)).append("\n");
                if (msg != null && !msg.isEmpty()) {
                    text.append(msg).append("\n\n");
                } else {
                    text.append("\n");
                }
            }
        }
        for (String line : report.lines()) text.append(line).append('\n');
        text.append('\n').append(getString(R.string.prov_overlay_now,
                getString(Settings.canDrawOverlays(this) ? R.string.prov_granted : R.string.prov_missing)));
        text.append("\n\n").append(getString(R.string.prov_note));

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(authorized ? R.string.prov_title : R.string.prov_title_failed)
                .setView(scroll);
        if (authorized) {
            b.setPositiveButton(R.string.prov_next, (d, w) -> openAutoStartSettings());
            b.setNegativeButton(R.string.prov_close, null);
        } else {
            b.setPositiveButton(R.string.prov_close, null);
        }
        b.show();
    }

    private void updateUIStates() {
        StatusView view = currentView();
        boolean isAuthorized = view.authorized; // a reading exists and the last call succeeded
        boolean adbConnected  = view.linkUp;    // a reading exists (not CONNECTING)

        View row2 = findViewById(R.id.row_toggles);
        View row3 = findViewById(R.id.row_protection);
        if (btnGrant == null) btnGrant = findViewById(R.id.btn_grant_permissions);

        // W3: Row 3 is active whenever ADB is connected, independent of whether Auto-Start on boot is enabled
        boolean row3Active = adbConnected;
        if (row3 != null) {
            row3.setAlpha(row3Active ? 1.0f : 0.4f);
            setViewGroupEnabled(row3, row3Active);
        }
        // When inactive: force all pills to neutral gray so no color bleeds through
        if (!row3Active) {
            android.widget.RadioButton rbNo   = findViewById(R.id.rb_no_action);
            android.widget.RadioButton rbOnce = findViewById(R.id.rb_once_on_start);
            android.widget.RadioButton rbAuto = findViewById(R.id.rb_auto_disable);
            if (rbNo != null)   { rbNo.setBackgroundResource(R.drawable.segmented_pill_inactive_bg);   rbNo.setBackgroundTintList(null); }
            if (rbOnce != null) { rbOnce.setBackgroundResource(R.drawable.segmented_pill_inactive_bg); rbOnce.setBackgroundTintList(null); }
            if (rbAuto != null) { rbAuto.setBackgroundResource(R.drawable.segmented_pill_inactive_bg); rbAuto.setBackgroundTintList(null); }
        } else {
            updateProtectionPillColors(); // restore correct color for selected pill
        }


        if (!isAuthorized) {
            if (row2 != null) { row2.setAlpha(1.0f); setViewGroupEnabled(row2, true); }
            btnGrant.setText(R.string.btn_grant);
            btnGrant.setAlpha(1.0f);
            btnGrant.setEnabled(true);
            btnGrant.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.grant_active)));
            if (btnGrant.getAnimation() == null) {
                android.view.animation.Animation glow = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.glow_pulse);
                btnGrant.startAnimation(glow);
            }
        } else {
            // UNLOCKED STATE
            btnGrant.clearAnimation();
            if (row2 != null) { row2.setAlpha(1.0f); setViewGroupEnabled(row2, true); }
            btnGrant.setText(R.string.btn_grant);
            btnGrant.setAlpha(0.5f);
            btnGrant.setEnabled(true);
            btnGrant.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.grant_idle)));
        }

        boolean grantBusy = GRANT_IN_FLIGHT.get();
        if (btnGrant != null) {
            btnGrant.setEnabled(!grantBusy);
        }
        if (cbFloating != null) {
            cbFloating.setEnabled(!grantBusy);
            if (cbFloating.getParent() instanceof View) {
                ((View) cbFloating.getParent()).setEnabled(!grantBusy);
            }
        }
    }

    /** Dynamically colors the 3 protection pills based on selection. */
    private void updateProtectionPillColors() {
        android.widget.RadioButton rbNo    = findViewById(R.id.rb_no_action);
        android.widget.RadioButton rbOnce  = findViewById(R.id.rb_once_on_start);
        android.widget.RadioButton rbAuto  = findViewById(R.id.rb_auto_disable);
        if (rbNo == null || rbOnce == null || rbAuto == null) return;

        // Reset all to neutral
        rbNo.setBackgroundResource(R.drawable.segmented_pill_bg);
        rbOnce.setBackgroundResource(R.drawable.segmented_pill_bg);
        rbAuto.setBackgroundResource(R.drawable.segmented_pill_bg);

        int checked = rgProtection.getCheckedRadioButtonId();
        if (checked == R.id.rb_no_action) {
            rbNo.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.pill_no_action)));   // Gray
        } else if (checked == R.id.rb_once_on_start) {
            rbOnce.setBackgroundResource(R.drawable.segmented_pill_blue_bg);                      // Blue
        } else if (checked == R.id.rb_auto_disable) {
            rbAuto.setBackgroundResource(R.drawable.segmented_pill_green_bg);                     // Green
        }
    }

    private void setViewGroupEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setViewGroupEnabled(group.getChildAt(i), enabled);
            }
        }
    }

    private void syncService() {
        startForegroundService(new Intent(this, AebService.class)); // minSdk 31
    }

    /**
     * Opens the BYD AppStartManagement screen.
     * The user MUST toggle our app OFF to allow it to auto-start on ignition.
     */
    private void openAutoStartSettings() {
        try {
            Intent intent = new Intent();
            intent.setAction("android.intent.action.MAIN");
            intent.setClassName("com.byd.appstartmanagement", "com.byd.appstartmanagement.frame.AppStartManagement");
            startActivity(intent);
            Toast.makeText(this, getString(R.string.toast_autostart, BuildConfig.APP_DISPLAY_NAME), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            try {
                // Fallback to general settings
                startActivity(new Intent(Settings.ACTION_SETTINGS));
                Toast.makeText(this, getString(R.string.toast_autostart_manual, BuildConfig.APP_DISPLAY_NAME), Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                e2.printStackTrace();
            }
        }
    }

    /**
     * Opens the Android Accessibility Settings.
     * (Deprecated: Automation handles this now since DiLink 5.0 hides the menu)
     */
    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            // Ignored
        }
    }

    // S2: the 1 s timer only re-evaluates staleness (age text, "unverified"); new snapshots arrive via the listener.
    private Runnable statusRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isVisible) return;
            render();
            statusHandler.postDelayed(this, 1000); // no socket usage
        }
    };

    private final StateStore.Listener snapshotListener = snapshot -> {
        if (isVisible) render();
    };

    /** The single render path: everything on the status card comes from the latest engine snapshot. */
    private void render() {
        updateStatusUI(currentView());
        updateUIStates();
    }

    private static StatusView currentView() {
        Snapshot s = StateStore.current();
        return StatusView.of(s, SystemClock.elapsedRealtime());
    }

    private void updateStatusUI(StatusView v) {
        TextView tvSub = findViewById(R.id.status_subtext);
        int neutral = getColor(R.color.status_neutral);

        switch (v.kind) {
            case OFF:
                tvStatus.setText(R.string.status_aeb_off);
                tvStatus.setTextColor(getColor(R.color.status_off));
                vStatusDot.setBackgroundResource(R.drawable.status_orb_green);
                tvSub.setText(getString(R.string.sub_last_read, v.ageSeconds));
                tvSub.setTextColor(neutral);
                break;
            case ON:
                tvStatus.setText(R.string.status_aeb_on);
                tvStatus.setTextColor(getColor(R.color.status_on));
                vStatusDot.setBackgroundResource(R.drawable.status_orb_red);
                tvSub.setText(getString(R.string.sub_last_read, v.ageSeconds));
                tvSub.setTextColor(neutral);
                break;
            case UNVERIFIED_OFF:
            case UNVERIFIED_ON:
                // D7: the last reading is shown, but neutral, until a fresh read confirms it
                tvStatus.setText(v.kind == StatusView.Kind.UNVERIFIED_ON ? R.string.status_aeb_on : R.string.status_aeb_off);
                tvStatus.setTextColor(neutral);
                vStatusDot.setBackgroundResource(R.drawable.status_orb_gray);
                if (v.ageSeconds >= 0) tvSub.setText(getString(R.string.sub_unverified, v.ageSeconds));
                else tvSub.setText(R.string.sub_unverified_no_age);
                tvSub.setTextColor(neutral);
                break;
            case NEEDS_PERMISSION:
                showConnecting(tvSub, R.string.sub_permission_required, getColor(R.color.status_attention));
                break;
            case TIMEOUT:
                showConnecting(tvSub, R.string.sub_timeout, getColor(R.color.status_error));
                break;
            default:
                showConnecting(tvSub, R.string.sub_waiting, neutral);
                break;
        }

        // S5 (D6): a reported problem replaces the sub line; the main text keeps the last reading
        if (v.attention == StatusView.Attention.DEGRADED) {
            tvSub.setText(R.string.sub_attention_degraded);
            tvSub.setTextColor(getColor(R.color.status_attention));
        } else if (v.attention == StatusView.Attention.LAST_CALL_FAILED) {
            tvSub.setText(getString(R.string.sub_attention_failed, v.link.name()));
            tvSub.setTextColor(getColor(R.color.status_attention));
        }

        if (v.canDisableNow) {
            btnDisableNow.setBackgroundTintList(null); // Restore original Green
            btnDisableNow.setAlpha(1.0f);
            btnDisableNow.setEnabled(true);
        } else {
            btnDisableNow.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.button_disabled))); // Grayed
            btnDisableNow.setAlpha(0.5f);
            btnDisableNow.setEnabled(false);
        }
        tvSub.setVisibility(View.VISIBLE);
    }

    private void showConnecting(TextView tvSub, int subText, int subColor) {
        tvStatus.setText(R.string.status_connecting);
        tvStatus.setTextColor(getColor(R.color.status_neutral));
        vStatusDot.setBackgroundResource(R.drawable.status_orb_gray);
        tvSub.setText(subText);
        tvSub.setTextColor(subColor);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isVisible = true;
        StateStore.addListener(snapshotListener);
        statusHandler.removeCallbacks(statusRunnable);
        statusHandler.post(statusRunnable);
        syncService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isVisible = false;
        StateStore.removeListener(snapshotListener);
        statusHandler.removeCallbacks(statusRunnable);
        if (btnGrant != null) btnGrant.clearAnimation();
        saveSettings();
    }

    private void loadSettings() {
        cbStartOnBoot.setChecked(settings.startOnBoot());
        cbFloating.setChecked(settings.showFloating());

        // ── Migration: old 2-key system → new single protection_mode ──
        settings.migrateLegacy();

        int mode = settings.mode(); // same rule as the service: missing = Once on Start, invalid = No Action
        if      (mode == 0) rgProtection.check(R.id.rb_no_action);
        else if (mode == 1) rgProtection.check(R.id.rb_once_on_start);
        else                rgProtection.check(R.id.rb_auto_disable);

        updateProtectionPillColors();
        updateUIStates();
    }

    private void saveSettings() {
        try {
            int checkedId = rgProtection.getCheckedRadioButtonId();
            int mode;
            if      (checkedId == R.id.rb_no_action)      mode = 0;
            else if (checkedId == R.id.rb_once_on_start)  mode = 1;
            else if (checkedId == R.id.rb_auto_disable)   mode = 2;
            else    mode = settings.mode(); // M07: preserve the current effective mode

            settings.save(cbStartOnBoot.isChecked(), cbFloating.isChecked(), mode);
        } catch (Exception e) {}
    }

    public void onCardClick(View view) {
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof CheckBox) {
                    if (child.isEnabled()) {
                        child.performClick();
                    }
                    break;
                }
            }
        }
    }
}


