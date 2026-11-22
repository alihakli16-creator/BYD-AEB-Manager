package com.byd.aebtoggle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.byd.aebtoggle.core.Aeb;
import com.byd.aebtoggle.core.Engine;
import com.byd.aebtoggle.core.EventLog;
import com.byd.aebtoggle.core.LinkStatus;
import com.byd.aebtoggle.core.ParcelParser;
import com.byd.aebtoggle.core.ReadResult;
import com.byd.aebtoggle.core.Snapshot;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one place the screen and the HUD get state from (refactor S2). It replaces the three public
 * static fields AebService.lastAebState / lastAdbStatus / lastPhase.
 *
 * - The engine publishes every snapshot here (any thread); listeners are told on the main thread.
 * - "Disable AEB Now" is forwarded to the engine's own thread, so a manual write can never overlap
 *   an automatic one and an exception cannot kill the process (it becomes status ERROR).
 * - S3: the in-memory event log (last 300 entries, this process only; never written to a file).
 */
final class StateStore {

    interface Listener {
        /** Main thread. */
        void onSnapshot(Snapshot snapshot);
    }

    interface DisableCallback {
        /** Main thread. The transport status of the manual write (CLOSED if the service stopped meanwhile). */
        void done(LinkStatus status);
    }

    private static final EventLog LOG = new EventLog(EventLog.DEFAULT_CAPACITY);
    private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<Snapshot>(Snapshot.initial());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable NOTIFY = new Runnable() {
        @Override
        public void run() {
            Snapshot s = CURRENT.get();
            for (Listener l : LISTENERS) l.onSnapshot(s);
        }
    };

    // guarded by StateStore.class
    private static Engine engine;
    private static Handler engineHandler;
    private static long attachedAtElapsedMs = -1L;

    private StateStore() {
    }

    /** Adds one line to the event log, stamped with the wall clock. Any thread. */
    static void event(String text) {
        LOG.add(System.currentTimeMillis(), text);
    }

    /** The event log (for the log dialog). */
    static EventLog log() {
        return LOG;
    }

    /** Latest snapshot. Any thread. */
    static Snapshot current() {
        return CURRENT.get();
    }

    /** The engine's SnapshotSink. Any thread; cheap (one store, one post). */
    static void publish(Snapshot snapshot) {
        CURRENT.set(snapshot);
        MAIN.removeCallbacks(NOTIFY); // listeners only need the newest one
        MAIN.post(NOTIFY);
    }

    static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /** Called by AebService when its engine exists. */
    static synchronized void attach(Engine e, Handler handler) {
        engine = e;
        engineHandler = handler;
        attachedAtElapsedMs = SystemClock.elapsedRealtime();
    }

    /** Called by AebService after engine.stop(); ignores an engine that is no longer the current one. */
    static synchronized void detach(Engine e) {
        if (engine == e) {
            engine = null;
            engineHandler = null;
            attachedAtElapsedMs = -1L;
        }
    }

    /** SystemClock.elapsedRealtime() when the active AebService engine attached, or -1 if none is running. */
    static synchronized long engineAttachedAtElapsedMs() {
        return (engine != null && engineHandler != null) ? attachedAtElapsedMs : -1L;
    }

    /** Dispatches an ignition cycle directly to an already-running Engine (e.g. if background FGS start threw). */
    static boolean notifyIgnitionCycle() {
        final Engine e;
        final Handler h;
        synchronized (StateStore.class) {
            e = engine;
            h = engineHandler;
        }
        if (e == null || h == null) return false;
        return h.post(new Runnable() {
            @Override
            public void run() {
                e.onIgnitionCycle();
            }
        });
    }

    /** Wakes the engine out of AUTH_FAIL / DEGRADED backoff immediately after "Grant Permission" succeeds. */
    static boolean notifyPermissionGranted() {
        final Engine e;
        final Handler h;
        synchronized (StateStore.class) {
            e = engine;
            h = engineHandler;
        }
        if (e == null || h == null) return false;
        return h.post(new Runnable() {
            @Override
            public void run() {
                e.onPermissionGranted();
            }
        });
    }

    /**
     * Manual "Disable AEB now". Runs Engine.requestDisableNow() on the engine thread and reports the
     * write's transport status on the main thread. Returns false when the service is not running.
     */
    static boolean requestDisableNow(final DisableCallback callback) {
        final Engine e;
        final Handler h;
        synchronized (StateStore.class) {
            e = engine;
            h = engineHandler;
        }
        if (e == null || h == null) return false;
        return h.post(new Runnable() {
            @Override
            public void run() {
                e.requestDisableNow();
                final LinkStatus status = e.snapshot().link;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.done(status);
                    }
                });
            }
        });
    }

    public interface ToggleCallback {
        void onProgress();
        void onDone(boolean confirmed, Aeb finalState, String error);
    }

    private static volatile long lastToggleTimeMs = 0;

    /**
     * Toggles AEB (if ON -> turns OFF; if OFF -> turns ON), verifies read-back, debounces 1.5s,
     * updates StateStore and sets manualOverrideUntilIgnition if turned ON.
     */
    static void requestToggleAeb(final Context context, final ToggleCallback callback) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastToggleTimeMs < 1500) {
            event("toggle: 1.5 sn debounce engeli");
            return;
        }
        lastToggleTimeMs = now;

        if (callback != null) {
            MAIN.post(callback::onProgress);
        }

        new Thread(() -> {
            Snapshot currentSnapshot = CURRENT.get();
            Aeb currentAeb = currentSnapshot != null ? currentSnapshot.aeb : Aeb.UNKNOWN;
            boolean targetOn = (currentAeb == Aeb.OFF);
            event("toggle: AFS hedef = " + (targetOn ? "AÇIK" : "KAPALI"));

            // 1. Send state write command
            AdbHelper.AdbResponse writeRes = AdbHelper.setAebState(targetOn);
            if (writeRes.status != AdbHelper.AdbStatus.SUCCESS) {
                event("toggle: HATA - komut gönderilemedi: " + writeRes.status);
                if (callback != null) {
                    MAIN.post(() -> callback.onDone(false, currentAeb, "Komut iletilemedi: " + writeRes.status));
                }
                return;
            }

            // 2. Read back & verify
            AdbHelper.AdbResponse readRes = AdbHelper.getAebState();
            Aeb parsed = Aeb.UNKNOWN;
            boolean confirmed = false;
            if (readRes.status == AdbHelper.AdbStatus.SUCCESS && readRes.output != null) {
                parsed = ParcelParser.parseRead(readRes.output);
                if ((targetOn && parsed == Aeb.ON) || (!targetOn && parsed == Aeb.OFF)) {
                    confirmed = true;
                }
            }

            if (confirmed) {
                event("toggle: AFS başarıyla " + (targetOn ? "AÇILDI" : "KAPATILDI") + " (doğrulandı)");
                new AebSettings(context).setManualOverrideActive(targetOn);
                // Update StateStore snapshot
                Snapshot updated = new Snapshot(parsed, LinkStatus.SUCCESS,
                        currentSnapshot != null ? currentSnapshot.phase : "STEADY",
                        SystemClock.elapsedRealtime(),
                        currentSnapshot != null ? currentSnapshot.sequence + 1 : 1L);
                publish(updated);
                final Aeb finalAeb = parsed;
                if (callback != null) {
                    MAIN.post(() -> callback.onDone(true, finalAeb, null));
                }
            } else {
                event("toggle: AFS durumu doğrulanamadı! (okunan=" + parsed + ")");
                final Aeb finalAeb = parsed;
                if (callback != null) {
                    MAIN.post(() -> callback.onDone(false, finalAeb, "Doğrulanamadı (Okunan: " + finalAeb + ")"));
                }
            }
        }, "AebToggleWorker").start();
    }
}
