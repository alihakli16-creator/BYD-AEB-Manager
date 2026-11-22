package com.byd.aebtoggle;

import com.byd.aebtoggle.core.Aeb;
import com.byd.aebtoggle.core.AdbShell;
import com.byd.aebtoggle.core.EventLog;
import com.byd.aebtoggle.core.LinkStatus;
import com.byd.aebtoggle.core.ParcelParser;
import com.byd.aebtoggle.core.ProvisioningReport;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AdbHelper {

    public enum AdbStatus { SUCCESS, AUTH_FAIL, TIMEOUT, CLOSED, ERROR, PORT_CLOSED }
    public static class AdbResponse {
        public AdbStatus status;
        public String output;
        public AdbResponse(AdbStatus s, String o) { this.status = s; this.output = o; }
    }

    public interface AdbProgressCallback {
        void onProgress(String message);
    }

    public static boolean grantSelfPermissions() {
        return grantSelfPermissions(new ProvisioningReport(), null);
    }

    public static boolean grantSelfPermissions(ProvisioningReport report) {
        return grantSelfPermissions(report, null);
    }

    /** S6: same commands, order and fast-fail rules with 60s pairing window and diagnostics. */
    public static boolean grantSelfPermissions(ProvisioningReport report, AdbProgressCallback callback) {
        String pkg = BuildConfig.APPLICATION_ID;

        // 1. ADIM: ADB Yetkilendirme ve Handshake (60 saniye süreyle araç ekranında onay bekle)
        StateStore.event("grant: 127.0.0.1:5555 baglantisi ve yetki kontrolu baslatiliyor...");
        if (callback != null) callback.onProgress("ADB baglantisi kuruluyor (127.0.0.1:5555)...");

        AdbResponse handshake = executeRemoteShellWithResult("", 15000, 60000, callback);
        if (handshake.status == AdbStatus.PORT_CLOSED) {
            report.recordFailureReason("PORT_CLOSED", "Port 5555 kapali! Arac gelistirici seceneklerinde kablosuz/ag ADB'yi acin.");
            StateStore.event("grant: HATA - Port 5555 kapali, adbd dinlenmiyor.");
            return false;
        }
        if (handshake.status == AdbStatus.AUTH_FAIL) {
            report.recordFailureReason("AUTH_FAIL", "ADB yetkilendirmesi yapilmadi veya arac ekraninda reddedildi.");
            StateStore.event("grant: HATA - ADB yetkilendirmesi reddedildi.");
            return false;
        }
        if (handshake.status == AdbStatus.TIMEOUT) {
            report.recordFailureReason("TIMEOUT", "Zaman asimi: Arac ekranindaki 'Her zaman izin ver' penceresine 60 saniye icinde dokunulmadi.");
            StateStore.event("grant: HATA - 60 saniye icinde onay verilmedi (Zaman asimi).");
            return false;
        }
        if (handshake.status != AdbStatus.SUCCESS) {
            report.recordFailureReason("ERROR", "ADB baglanti hatasi: " + handshake.output);
            StateStore.event("grant: HATA - " + handshake.output);
            return false;
        }

        StateStore.event("grant: ADB yetkilendirmesi BASARILI! Sistem izinleri tanimlaniyor...");

        // 2. ADIM: Izin Komutlarini Sirayla Calistir
        String[] cmds = {
            // Core permissions
            "pm grant " + pkg + " android.permission.SYSTEM_ALERT_WINDOW",
            "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
            "appops set " + pkg + " android:system_alert_window allow",
            // Background keep-alive — prevents "Close All" from killing the service
            "appops set " + pkg + " RUN_IN_BACKGROUND allow",
            "appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow",
            "appops set " + pkg + " START_FOREGROUND allow",
            "appops set " + pkg + " REQUEST_IGNORE_BATTERY_OPTIMIZATIONS allow",
            // Whitelist in Doze + standby bucket
            "cmd deviceidle whitelist +" + pkg,
            "dumpsys deviceidle whitelist +" + pkg,
            "am set-standby-bucket " + pkg + " active"
        };

        for (int i = 0; i < cmds.length; i++) {
            if (callback != null) callback.onProgress("Izinler tanimlaniyor (" + (i + 1) + "/" + (cmds.length + 1) + ")...");
            AdbResponse res = executeRemoteShellWithResult(cmds[i]);
            report.record(ProvisioningReport.forCommandIndex(i), toLink(res.status));
            StateStore.event("grant cmd " + (i + 1) + ": " + cmds[i] + " -> " + res.status);
            android.util.Log.d("AdbHelper", "grant cmd " + (i + 1) + ": " + cmds[i] + " -> " + res.status);
        }

        // 3. ADIM: Erisilebilirlik Servisini Yapilandir
        if (callback != null) callback.onProgress("Erisilebilirlik servisi aktif ediliyor...");
        try {
            AdbResponse currentAccRes = executeRemoteShellWithResult("settings get secure enabled_accessibility_services");
            if (currentAccRes == null || currentAccRes.status != AdbStatus.SUCCESS || currentAccRes.output == null) {
                report.record(ProvisioningReport.Capability.ACCESSIBILITY,
                        currentAccRes == null ? LinkStatus.ERROR : toLink(currentAccRes.status));
                StateStore.event("grant: Erisilebilirlik listesi okunamadi");
            } else {
                String currentAcc = currentAccRes.output.trim();
                String ourService = pkg + "/" + AebAccessibilityService.class.getName();
                if (!isSafeAccessibilityList(currentAcc)) {
                    report.leftUntouched(ProvisioningReport.Capability.ACCESSIBILITY, "beklenmeyen liste formati");
                    StateStore.event("grant: Erisilebilirlik listesi formati beklenmeyen yapida");
                } else {
                    boolean listIsEmpty = currentAcc.isEmpty() || currentAcc.equals("null");
                    boolean alreadyEnabled = false;
                    if (!listIsEmpty) {
                        for (String entry : currentAcc.split(":")) {
                            if (entry.equals(ourService)) { alreadyEnabled = true; break; }
                        }
                    }
                    if (!alreadyEnabled) {
                        String newAcc = listIsEmpty ? ourService : currentAcc + ":" + ourService;
                        AdbResponse w = executeRemoteShellWithResult("settings put secure enabled_accessibility_services '" + newAcc + "'");
                        report.record(ProvisioningReport.Capability.ACCESSIBILITY, toLink(w.status));
                        StateStore.event("grant: Erisilebilirlik servisi listeye eklendi: " + w.status);
                    }
                    AdbResponse m = executeRemoteShellWithResult("settings put secure accessibility_enabled 1");
                    report.record(ProvisioningReport.Capability.ACCESSIBILITY, toLink(m.status));
                    StateStore.event("grant: Ana erisilebilirlik anahtari acildi: " + m.status);
                }
            }
        } catch (Exception e) {
            report.record(ProvisioningReport.Capability.ACCESSIBILITY, LinkStatus.ERROR);
            StateStore.event("grant: Erisilebilirlik hatasi: " + e.getMessage());
        }

        // 4. ADIM: Ilk Kurulum Tamamlandi Bayragini Kaydet
        android.content.Context appContext = MainApplication.getAppContext();
        if (appContext != null) {
            new AebSettings(appContext).setSetupComplete(true);
            StateStore.event("grant: first_time_setup_complete = true kaydedildi.");
        }

        return true;
    }

    private static LinkStatus toLink(AdbStatus s) {
        if (s == null || s == AdbStatus.PORT_CLOSED) return LinkStatus.ERROR;
        return LinkStatus.valueOf(s.name());
    }

    /** True for an empty list, the literal "null", or a colon separated list of component names. */
    private static boolean isSafeAccessibilityList(String s) {
        return s != null && s.matches("[A-Za-z0-9_.$/:\\-]*");
    }

    public static AdbResponse getAebState() {
        String cmd = "service call autoservice 5 i32 1038 i32 852492332";
        AdbResponse response = executeRemoteShellWithResult(cmd);
        lastReadOutput = response.output; // S4: raw reply, for the event log (AdbVehicleLink)
        if (response.status == AdbStatus.SUCCESS && response.output != null) {
            Aeb parsed = ParcelParser.parseRead(response.output);
            if (parsed == Aeb.ON)  return new AdbResponse(AdbStatus.SUCCESS, "00000001");
            if (parsed == Aeb.OFF) return new AdbResponse(AdbStatus.SUCCESS, "00000000");
        }
        return response;
    }

    // === v18.4: Typed verification for 9-state engine ===

    public enum VerificationResult {
        DISABLED_CONFIRMED,   // AEB is OFF
        ENABLED_DETECTED,     // AEB is ON
        TRANSPORT_FAILURE     // ADB error, timeout, or unparseable response
    }

    public static volatile AdbStatus lastRawStatus = AdbStatus.CLOSED;
    /** S4: raw text of the last read reply (null when the call produced none). */
    public static volatile String lastReadOutput = null;
    /** S4: CNXN banner of the last connection; logged once per process and when it changes. */
    private static volatile String lastBanner = null;

    /**
     * Typed query that wraps getAebState() into a deterministic 3-value result.
     * Used by the Actor loop to make clean state transitions without string parsing.
     */
    public static VerificationResult queryAebStatus() {
        AdbResponse r = getAebState();
        lastRawStatus = r.status;
        if (r.status != AdbStatus.SUCCESS) return VerificationResult.TRANSPORT_FAILURE;
        if ("00000001".equals(r.output)) return VerificationResult.ENABLED_DETECTED;
        if ("00000000".equals(r.output)) return VerificationResult.DISABLED_CONFIRMED;
        return VerificationResult.TRANSPORT_FAILURE;
    }

    public static AdbResponse setAebState(boolean on) {
        int val = on ? 1 : 0;
        String cmd1 = "service call autoservice 6 i32 1038 i32 852492332 i32 " + val;
        String cmd2 = "service call autoservice 6 i32 1038 i32 944767020 i32 " + val;
        
        AdbResponse res1 = executeRemoteShellWithResult(cmd1);
        if (res1.status != AdbStatus.SUCCESS) {
            return res1;
        }
        AdbResponse res2 = executeRemoteShellWithResult(cmd2);
        // S4: the second command's reply is logged too (never checked; see REFACTOR_PLAN S4)
        StateStore.event("write cmd 2: " + res2.status + "; reply: " + EventLog.oneLine(res2.output, 120));
        if (res2.status != AdbStatus.SUCCESS) {
            return new AdbResponse(AdbStatus.ERROR, "First write OK, second write failed: " + res2.status);
        }
        return res1;
    }

    // =========================================================================
    // S4: transport. The protocol itself is core AdbShell (tested without a car); this part owns
    // the socket and the overall deadline of one call.
    // =========================================================================

    /** Longest one call may take (connect + handshake + shell). Offering the key adds its 15 s wait. */
    private static final long CALL_DEADLINE_MS = 8000;

    /** Closes the socket of a call that runs past its deadline (a blocked read then fails at once). */
    private static final ScheduledThreadPoolExecutor WATCHDOG = newWatchdog();

    private static ScheduledThreadPoolExecutor newWatchdog() {
        ScheduledThreadPoolExecutor w = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "AdbWatchdog");
            t.setDaemon(true);
            return t;
        });
        w.setRemoveOnCancelPolicy(true);
        return w;
    }

    private static final AdbShell.Auth AUTH = new AdbShell.Auth() {
        @Override
        public byte[] sign(byte[] token) throws Exception {
            return AdbKeyStore.get().sign(token);
        }

        @Override
        public byte[] publicKeyPayload() {
            return AdbKeyStore.get().getPublicKeyPayload();
        }
    };

    public static volatile long lastConnectionAttemptTimeMs = 0;

    public static boolean isAdbConnectionRecent(long withinMs) {
        return (android.os.SystemClock.elapsedRealtime() - lastConnectionAttemptTimeMs) <= withinMs;
    }

    public static AdbResponse executeRemoteShellWithResult(String command) {
        return executeRemoteShellWithResult(command, CALL_DEADLINE_MS, AdbShell.PAIRING_TIMEOUT_MS, null);
    }

    public static AdbResponse executeRemoteShellWithResult(String command, long deadlineMs, int pairingTimeoutMs, AdbProgressCallback callback) {
        lastConnectionAttemptTimeMs = android.os.SystemClock.elapsedRealtime();
        final Socket socket = new Socket();
        final AtomicBoolean expired = new AtomicBoolean(false);
        final Runnable expire = () -> {
            expired.set(true);
            try { socket.close(); } catch (Exception ignored) {}
        };
        final Object lock = new Object();
        @SuppressWarnings("unchecked")
        final ScheduledFuture<?>[] deadline = new ScheduledFuture<?>[]{
                WATCHDOG.schedule(expire, deadlineMs, TimeUnit.MILLISECONDS)};
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            AdbShell.ProgressListener pl = (callback != null) ? callback::onProgress : null;
            AdbShell.Result r = AdbShell.run(new AdbShell.Channel() {
                @Override
                public InputStream in() throws java.io.IOException {
                    return socket.getInputStream();
                }

                @Override
                public OutputStream out() throws java.io.IOException {
                    return socket.getOutputStream();
                }

                @Override
                public void setReadTimeoutMs(int ms) throws java.io.IOException {
                    socket.setSoTimeout(ms);
                }

                @Override
                public void extendDeadlineMs(int ms) {
                    synchronized (lock) {
                        if (deadline[0].cancel(false)) {
                            deadline[0] = WATCHDOG.schedule(expire, ms + CALL_DEADLINE_MS, TimeUnit.MILLISECONDS);
                        }
                    }
                }
            }, command, AUTH, pairingTimeoutMs, pl);
            noteBanner(r.banner);
            LinkStatus status = (expired.get() && r.status != LinkStatus.SUCCESS) ? LinkStatus.TIMEOUT : r.status;
            return new AdbResponse(AdbStatus.valueOf(status.name()), r.output);
        } catch (java.net.ConnectException e) {
            return new AdbResponse(AdbStatus.PORT_CLOSED, "Connection refused (127.0.0.1:5555 port kapali)");
        } catch (java.net.SocketTimeoutException e) {
            return new AdbResponse(AdbStatus.TIMEOUT, "Socket timeout");
        } catch (SecurityException e) {
            return new AdbResponse(AdbStatus.ERROR, "SecurityException: " + e.getMessage());
        } catch (Exception e) {
            return expired.get() ? new AdbResponse(AdbStatus.TIMEOUT, null) : new AdbResponse(AdbStatus.ERROR, e.getMessage());
        } finally {
            synchronized (lock) {
                deadline[0].cancel(false);
            }
            try { socket.close(); } catch (Exception e) {}
        }
    }

    /** S4: the car's CNXN banner (model, features) goes to the event log once; it is not checked. */
    private static void noteBanner(String banner) {
        if (banner == null || banner.equals(lastBanner)) return;
        lastBanner = banner;
        StateStore.event("adb connected; shell_v2 " + (AdbShell.hasShellV2(banner) ? "yes" : "no")
                + "; banner: " + EventLog.oneLine(banner, 200));
    }
}
