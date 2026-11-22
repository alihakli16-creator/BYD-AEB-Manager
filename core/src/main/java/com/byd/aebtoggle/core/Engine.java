package com.byd.aebtoggle.core;

/**
 * The 9-state actor engine, ported from AebService (v18.22) WITHOUT any Android type.
 *
 * Behaviour is decision D2 and must stay identical to AebService v18.22: same states, same delays,
 * same counters. Every difference from the old class is listed in REFACTOR_PLAN.md appendix C.
 *
 * Threading: start(), requestDisableNow() and every scheduled task run on ONE thread (the
 * Scheduler's thread). stop() and snapshot() may be called from any thread.
 *
 * States:
 *   CONNECTING            read until the first usable reply (tick every 3 s)
 *   DISCOVERING           3 consecutive usable reads over at least 5 s (tick every 3 s)
 *   ENFORCING_BOOT_EARLY  first disable after start
 *   VERIFYING_BOOT        confirm OFF after 1 s, then 3 s, then 6 s (each delay counted from the previous read)
 *   BACKOFF_SETTLING      wait 10 s
 *   STEADY                read every 10 s; Auto Disable re-enforces after 2 consecutive ON reads
 *   RECOVERY_ENFORCE      runtime re-disable
 *   VERIFYING_RUNTIME     confirm OFF after 2 s, then 5 s
 *   DEGRADED_RECOVERY     wait 30 s (doubling to 5 min), then CONNECTING
 */
public final class Engine {

    public enum State {
        CONNECTING,
        DISCOVERING,
        ENFORCING_BOOT_EARLY,
        VERIFYING_BOOT,
        BACKOFF_SETTLING,
        STEADY,
        RECOVERY_ENFORCE,
        VERIFYING_RUNTIME,
        DEGRADED_RECOVERY
    }

    // ---- timing and limits: identical to AebService v18.22 ----
    private static final long CONNECT_TICK_MS = 3000;
    private static final long DISCOVER_TICK_MS = 3000;
    private static final long STEADY_TICK_MS = 10_000;
    private static final long SETTLE_MS = 10_000;
    private static final int DISCOVERY_STREAK_REQUIRED = 3;
    private static final long DISCOVERY_MIN_WINDOW_MS = 5000;
    private static final long[] BOOT_VERIFY_DELAYS_MS = {1000, 3000, 6000};
    private static final long[] RUNTIME_VERIFY_DELAYS_MS = {2000, 5000};
    private static final int MAX_ENFORCE_RETRIES = 3;
    private static final int STEADY_DEBOUNCE = 2;
    private static final long DEGRADED_MIN_BACKOFF_MS = 30_000;
    private static final long DEGRADED_MAX_BACKOFF_MS = 300_000;
    private static final int STEADY_HEALTHY_RESET_READS = 6;
    private static final int MAX_WRITE_FAILURES = 3;
    private static final long[] AUTH_FAIL_BACKOFF_MS = {3000, 15_000, 60_000, 300_000};
    /** Duplicate boot/ignition signals arriving within this window of an active boot session are ignored. */
    public static final long DUPLICATE_IGNITION_WINDOW_MS = 30_000;

    private final VehicleLink link;
    private final Clock clock;
    private final Scheduler scheduler;
    private final SettingsView settings;
    private final SnapshotSink sink;
    private final EngineLog log;

    private final Object publishLock = new Object();
    private volatile boolean destroyed = false;
    /** Bumped on every transition and on stop(); a task from an older generation does nothing. */
    private volatile long generation = 0;
    private volatile Snapshot current = Snapshot.initial();

    // ---- actor-thread state ----
    private State state = State.CONNECTING;
    private int discoveryStreak = 0;
    private long discoveryFirstReadTime = 0;
    private int verifyStepIndex = 0;
    private int enforceRetries = 0;
    private int steadyOnCount = 0;
    private long degradedBackoffMs = DEGRADED_MIN_BACKOFF_MS;
    private int steadyHealthyReads = 0;
    private int writeFailures = 0;
    private int faultCount = 0;
    private int authFailCount = 0;
    private long nextConnectDelayMs = 0;
    private long nextTickDelayMs = 0;
    private long lastIgnitionMs = -1;

    // ---- the published view (written under publishLock) ----
    private Aeb aeb = Aeb.UNKNOWN;
    private LinkStatus linkStatus = LinkStatus.CLOSED;
    private String phase = "IDLE";
    private long observedAtMs = -1;
    private long sequence = 0;

    public Engine(VehicleLink link, Clock clock, Scheduler scheduler, SettingsView settings,
                  SnapshotSink sink, EngineLog log) {
        this.link = link;
        this.clock = clock;
        this.scheduler = scheduler;
        this.settings = settings;
        this.sink = sink;
        this.log = log;
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /** Begins at CONNECTING. Call on the engine thread (old code: scheduler.execute in onCreate). */
    public void start() {
        if (destroyed) return;
        if (!settings.isBootEnforceConsumed() && clock.nowMs() < DUPLICATE_IGNITION_WINDOW_MS) {
            lastIgnitionMs = clock.nowMs();
        }
        transitionTo(State.CONNECTING);
    }

    /**
     * Begins at CONNECTING when started by a boot-bound component (e.g. AccessibilityService).
     * Marks the boot session active when Once-on-Start has not yet been consumed so a trailing
     * boot broadcast seconds later does not restart discovery, even on a slow cold boot (>30 s).
     */
    public void onBootStart() {
        if (destroyed) return;
        if (!settings.isBootEnforceConsumed()) {
            lastIgnitionMs = clock.nowMs();
        }
        transitionTo(State.CONNECTING);
    }

    /**
     * Stops everything. Any thread. After this call no task runs and no result of a worker that is
     * still blocked in I/O is published; the last snapshot is (UNKNOWN, CLOSED, "STOPPED").
     */
    public void stop() {
        synchronized (publishLock) {
            if (destroyed) return;
            destroyed = true;
            generation++;
            aeb = Aeb.UNKNOWN;
            linkStatus = LinkStatus.CLOSED;
            phase = "STOPPED";
            observedAtMs = -1;
            sequence++;
            current = new Snapshot(aeb, linkStatus, phase, observedAtMs, sequence);
            deliver(current);
        }
        scheduler.cancelAll();
    }

    /**
     * Manual "Disable AEB now" (the button). Runs on the engine thread so it can never overlap an
     * automatic write. It does not change the state machine and does not touch the AEB reading:
     * the next regular read shows what really happened (old fix H02). It only publishes the
     * transport status of the write.
     */
    public void requestDisableNow() {
        if (destroyed) return;
        try {
            WriteResult w = link.writeDisable();
            publishLink(w.link);
            log.d("MANUAL: disable now -> " + w.link);
        } catch (Throwable t) {
            log.e("MANUAL: disable now failed", t);
            publishLink(LinkStatus.ERROR);
        }
    }

    /** Called on vehicle boot/ignition broadcast to start fresh for the new trip. */
    public void onIgnitionCycle() {
        if (destroyed) return;
        long now = clock.nowMs();
        if (SettingsRules.isDuplicateBootSignal(lastIgnitionMs, now)) {
            log.d("IGNITION CYCLE: duplicate boot signal after " + (now - lastIgnitionMs) + " ms - ignored");
            return;
        }
        lastIgnitionMs = now;
        log.d("IGNITION CYCLE: resetting Once-on-Start state and restarting discovery");
        settings.setBootEnforceConsumed(false);
        settings.setManualOverrideActive(false);
        steadyOnCount = 0;
        steadyHealthyReads = 0;
        enforceRetries = 0;
        degradedBackoffMs = DEGRADED_MIN_BACKOFF_MS;
        writeFailures = 0;
        faultCount = 0;
        authFailCount = 0;
        nextConnectDelayMs = 0;
        nextTickDelayMs = 0;
        transitionTo(State.CONNECTING);
    }

    /**
     * Called after "Grant Permission" succeeds so the engine wakes immediately instead of remaining
     * asleep in an AUTH_FAIL or DEGRADED backoff for up to 5 minutes.
     */
    public void onPermissionGranted() {
        if (destroyed) return;
        authFailCount = 0;
        if (state == State.CONNECTING || state == State.DEGRADED_RECOVERY) {
            log.d("PERMISSION GRANTED: waking engine from " + state.name());
            faultCount = 0;
            writeFailures = 0;
            degradedBackoffMs = DEGRADED_MIN_BACKOFF_MS;
            nextConnectDelayMs = 0;
            nextTickDelayMs = 0;
            transitionTo(State.CONNECTING);
        }
    }

    /** Latest published snapshot. Any thread. */
    public Snapshot snapshot() {
        return current;
    }

    /** Current state. Engine thread (and tests) only. */
    public State state() {
        return state;
    }

    // =========================================================================
    // TRANSITIONS
    // =========================================================================

    private void transitionTo(State newState) {
        if (destroyed) return;
        scheduler.cancelAll();
        generation++;
        state = newState;
        log.d("-> STATE: " + newState.name());

        try {
            switch (newState) {
                case CONNECTING: {
                    discoveryStreak = 0;
                    discoveryFirstReadTime = 0;
                    long connectDelayMs = nextConnectDelayMs; // 0 normally; >0 right after a safety-net fault
                    nextConnectDelayMs = 0;
                    nextTickDelayMs = 0;
                    setPhase("CONNECTING");
                    scheduleTick(connectDelayMs, CONNECT_TICK_MS);
                    break;
                }
                case DISCOVERING:
                    setPhase("DISCOVERING");
                    scheduleTick(DISCOVER_TICK_MS, DISCOVER_TICK_MS);
                    break;

                case ENFORCING_BOOT_EARLY:
                    setPhase("ENFORCING_BOOT_EARLY");
                    scheduleOnce(0, new Runnable() {
                        @Override
                        public void run() {
                            tickEnforcingBootEarly();
                        }
                    });
                    break;

                case VERIFYING_BOOT:
                    verifyStepIndex = 0;
                    scheduleVerifyBoot();
                    break;

                case BACKOFF_SETTLING:
                    setPhase("SETTLING");
                    scheduleOnce(SETTLE_MS, new Runnable() {
                        @Override
                        public void run() {
                            transitionTo(State.STEADY);
                        }
                    });
                    break;

                case STEADY:
                    steadyOnCount = 0;
                    steadyHealthyReads = 0;
                    setPhase("STEADY");
                    scheduleTick(0, STEADY_TICK_MS);
                    break;

                case RECOVERY_ENFORCE:
                    setPhase("RECOVERY_ENFORCE");
                    scheduleOnce(0, new Runnable() {
                        @Override
                        public void run() {
                            tickRecoveryEnforce();
                        }
                    });
                    break;

                case VERIFYING_RUNTIME:
                    verifyStepIndex = 0;
                    scheduleVerifyRuntime();
                    break;

                case DEGRADED_RECOVERY:
                    setPhase("DEGRADED (" + (degradedBackoffMs / 1000) + "s)");
                    log.w("DEGRADED: backing off for " + degradedBackoffMs + "ms");
                    scheduleOnce(degradedBackoffMs, new Runnable() {
                        @Override
                        public void run() {
                            // Double the backoff for next time, capped at the maximum.
                            degradedBackoffMs = Math.min(degradedBackoffMs * 2, DEGRADED_MAX_BACKOFF_MS);
                            transitionTo(State.CONNECTING);
                        }
                    });
                    break;
            }
        } catch (RuntimeException e) {
            log.w("Scheduler rejected transition: " + e.getMessage());
        }
    }

    // =========================================================================
    // SCHEDULING (generation-guarded)
    // =========================================================================

    /** One task, ignored if a transition happened after it was scheduled. */
    private void scheduleOnce(long delayMs, final Runnable task) {
        final long gen = generation;
        scheduler.schedule(delayMs, new Runnable() {
            @Override
            public void run() {
                if (gen != generation || destroyed) return;
                task.run();
            }
        });
    }

    /**
     * Same timing as ScheduledExecutorService.scheduleWithFixedDelay(tick, initialDelay, interval):
     * the next run starts `interval` AFTER the previous run finished, and the chain ends as soon
     * as a transition happens.
     */
    private void scheduleTick(final long initialDelayMs, final long intervalMs) {
        final long gen = generation;
        scheduler.schedule(initialDelayMs, new Runnable() {
            @Override
            public void run() {
                if (gen != generation || destroyed) return;
                tick();
                if (gen == generation && !destroyed) {
                    long delay = nextTickDelayMs > 0 ? nextTickDelayMs : intervalMs;
                    nextTickDelayMs = 0;
                    scheduler.schedule(delay, this);
                }
            }
        });
    }

    private void tick() {
        try {
            switch (state) {
                case CONNECTING:
                    tickConnecting();
                    break;
                case DISCOVERING:
                    tickDiscovering();
                    break;
                case STEADY:
                    tickSteady();
                    break;
                default:
                    // other states use one-shot scheduling, not the tick loop
                    break;
            }
        } catch (Throwable t) {
            recoverFromFault("tick()", t);
        }
    }

    /**
     * Common handler for every safety net. Logs, then re-enters CONNECTING after a growing pause
     * (4 s, 8 s, 16 s, 32 s, then 60 s). faultCount returns to 0 after the first good read.
     */
    private void recoverFromFault(String where, Throwable t) {
        faultCount++;
        long pauseMs = Math.min(60_000L, 2_000L << Math.min(faultCount, 5));
        nextConnectDelayMs = pauseMs;
        log.e("ACTOR SAFETY NET in " + where + " (fault #" + faultCount + "), pausing "
                + pauseMs + " ms before reconnecting", t);
        transitionTo(State.CONNECTING);
    }

    /** A disable command failed at transport level. After 3 in a row: DEGRADED_RECOVERY. */
    private void handleWriteFailure(String where) {
        writeFailures++;
        if (writeFailures >= MAX_WRITE_FAILURES) {
            log.w(where + ": " + writeFailures + " consecutive write failures -> DEGRADED_RECOVERY");
            writeFailures = 0;
            transitionTo(State.DEGRADED_RECOVERY);
        } else {
            transitionTo(State.CONNECTING);
        }
    }

    // =========================================================================
    // STATE HANDLERS
    // =========================================================================

    /** CONNECTING: a plain read; the first usable reply starts discovery. */
    private void tickConnecting() {
        ReadResult r = link.read();
        applyReading(r);
        if (!r.isFailure()) {
            faultCount = 0; // a good read clears the safety-net pause growth
            authFailCount = 0;
            discoveryStreak = 1;
            discoveryFirstReadTime = clock.nowMs();
            transitionTo(State.DISCOVERING);
        } else if (r.link == LinkStatus.AUTH_FAIL) {
            int idx = Math.min(authFailCount, AUTH_FAIL_BACKOFF_MS.length - 1);
            long delay = AUTH_FAIL_BACKOFF_MS[idx];
            authFailCount++;
            nextTickDelayMs = delay;
            log.d("CONNECTING: auth failed, next attempt in " + (delay / 1000) + " s (count=" + authFailCount + ")");
        } else {
            authFailCount = 0;
        }
        // else: stay in CONNECTING, retry on the next tick
    }

    /** DISCOVERING: 3 consecutive usable reads spread over at least 5 s before anything is trusted. */
    private void tickDiscovering() {
        ReadResult r = link.read();
        applyReading(r);

        if (r.isFailure()) {
            discoveryStreak = 0; // lost the connection - start again
            transitionTo(State.CONNECTING);
            return;
        }

        discoveryStreak++;
        long elapsed = clock.nowMs() - discoveryFirstReadTime;

        if (discoveryStreak >= DISCOVERY_STREAK_REQUIRED && elapsed >= DISCOVERY_MIN_WINDOW_MS) {
            int mode = settings.mode();

            if (r.aeb == Aeb.ON) {
                // Mode 2: always enforce. Mode 1: enforce only if not already consumed this boot session (C3).
                boolean consumed = settings.isBootEnforceConsumed();
                if (mode == Mode.AUTO_DISABLE || (mode == Mode.ONCE_ON_START && !consumed)) {
                    log.d("DISCOVERING: AEB ON + mode=" + mode + " -> ENFORCING_BOOT_EARLY");
                    enforceRetries = 0;
                    transitionTo(State.ENFORCING_BOOT_EARLY);
                } else {
                    log.d("DISCOVERING: stable, AEB=ON mode=" + mode + " consumed=" + consumed + " -> STEADY");
                    transitionTo(State.STEADY);
                }
            } else {
                log.d("DISCOVERING: stable, AEB=OFF -> STEADY");
                if (mode == Mode.ONCE_ON_START) {
                    settings.setBootEnforceConsumed(true);
                }
                transitionTo(State.STEADY);
            }
        }
        // else: keep accumulating the streak
    }

    /** ENFORCING_BOOT_EARLY: fire the disable command, then verify. */
    private void tickEnforcingBootEarly() {
        try {
            // Re-check the setting right before writing: if the driver switched to No Action while this
            // write was pending (or being retried), do not send it.
            int mode = settings.mode();
            if (mode != Mode.ONCE_ON_START && mode != Mode.AUTO_DISABLE) {
                log.w("ENFORCING_BOOT_EARLY: mode is now " + mode + " - write cancelled");
                transitionTo(State.STEADY);
                return;
            }
            log.d("ENFORCING_BOOT_EARLY: sending disable command (attempt " + (enforceRetries + 1) + ")");
            WriteResult w = link.writeDisable();
            if (!w.isSuccess()) {
                publishLink(w.link);
                handleWriteFailure("ENFORCING_BOOT_EARLY");
                return;
            }
            writeFailures = 0;
            transitionTo(State.VERIFYING_BOOT);
        } catch (Throwable t) {
            recoverFromFault("tickEnforcingBootEarly()", t);
        }
    }

    /** VERIFYING_BOOT: progressive confirmation, 1 s then 3 s then 6 s (relative to the previous read). */
    private void scheduleVerifyBoot() {
        if (verifyStepIndex >= BOOT_VERIFY_DELAYS_MS.length) {
            log.d("VERIFYING_BOOT: all checks passed -> BACKOFF_SETTLING");
            if (settings.mode() == Mode.ONCE_ON_START) {
                settings.setBootEnforceConsumed(true);
            }
            transitionTo(State.BACKOFF_SETTLING);
            return;
        }
        long delayMs = BOOT_VERIFY_DELAYS_MS[verifyStepIndex];
        setPhase("VERIFY_BOOT (" + delayMs + "ms)");
        scheduleOnce(delayMs, new Runnable() {
            @Override
            public void run() {
                tickVerifyBoot();
            }
        });
    }

    private void tickVerifyBoot() {
        try {
            ReadResult r = link.read();
            applyReading(r);

            if (r.isFailure()) {
                log.w("VERIFYING_BOOT: link failure -> CONNECTING");
                transitionTo(State.CONNECTING);
                return;
            }

            if (r.aeb == Aeb.OFF) {
                verifyStepIndex++; // this step passed - next delay
                scheduleVerifyBoot();
            } else {
                enforceRetries++; // still ON - write again
                if (enforceRetries < MAX_ENFORCE_RETRIES) {
                    log.d("VERIFYING_BOOT: still ON, retry " + enforceRetries + "/" + MAX_ENFORCE_RETRIES);
                    transitionTo(State.ENFORCING_BOOT_EARLY);
                } else {
                    log.w("VERIFYING_BOOT: retries exhausted -> DEGRADED_RECOVERY");
                    if (settings.mode() == Mode.ONCE_ON_START) settings.setBootEnforceConsumed(true);
                    transitionTo(State.DEGRADED_RECOVERY);
                }
            }
        } catch (Throwable t) {
            recoverFromFault("tickVerifyBoot()", t);
        }
    }

    /** STEADY: low-frequency watchdog with debounced re-enable detection. */
    private void tickSteady() {
        ReadResult r = link.read();
        applyReading(r);

        if (r.isFailure()) {
            steadyHealthyReads = 0;
            transitionTo(State.CONNECTING);
            return;
        }

        // M01: after about a minute of healthy STEADY reads the DEGRADED backoff goes back to its floor.
        steadyHealthyReads++;
        if (steadyHealthyReads >= STEADY_HEALTHY_RESET_READS && degradedBackoffMs != DEGRADED_MIN_BACKOFF_MS) {
            degradedBackoffMs = DEGRADED_MIN_BACKOFF_MS;
            log.d("STEADY: healthy for " + steadyHealthyReads + " reads, backoff reset to floor");
        }

        int mode = settings.mode();
        if (mode != Mode.AUTO_DISABLE) steadyOnCount = 0; // debounce evidence must belong to the current setting

        if (r.aeb == Aeb.ON) {
            if (mode == Mode.AUTO_DISABLE) {
                if (settings.isManualOverrideActive()) {
                    log.d("STEADY: manual override active, skipping auto-disable until next ignition");
                } else {
                    steadyOnCount++;
                    if (steadyOnCount >= STEADY_DEBOUNCE) {
                        log.d("STEADY: debounced AEB re-enable detected -> RECOVERY_ENFORCE");
                        enforceRetries = 0;
                        transitionTo(State.RECOVERY_ENFORCE);
                    } else {
                        log.d("STEADY: AEB ON detected (" + steadyOnCount + "/" + STEADY_DEBOUNCE + "), awaiting confirmation");
                    }
                }
            }
            // mode 0 or 1 (once-on-start already fired at boot): just report, do not enforce
        } else {
            steadyOnCount = 0; // reset the debounce on any OFF read
        }
    }

    /** RECOVERY_ENFORCE: runtime re-disable after AEB switched itself (or was switched) back on. */
    private void tickRecoveryEnforce() {
        try {
            // Re-check the setting right before writing: runtime re-disable is only for Auto Disable.
            int mode = settings.mode();
            if (mode != Mode.AUTO_DISABLE) {
                log.w("RECOVERY_ENFORCE: mode is now " + mode + " - write cancelled");
                transitionTo(State.STEADY);
                return;
            }
            log.d("RECOVERY_ENFORCE: sending disable command (attempt " + (enforceRetries + 1) + ")");
            WriteResult w = link.writeDisable();
            if (!w.isSuccess()) {
                publishLink(w.link);
                handleWriteFailure("RECOVERY_ENFORCE");
                return;
            }
            writeFailures = 0;
            transitionTo(State.VERIFYING_RUNTIME);
        } catch (Throwable t) {
            recoverFromFault("tickRecoveryEnforce()", t);
        }
    }

    /** VERIFYING_RUNTIME: progressive confirmation, 2 s then 5 s. */
    private void scheduleVerifyRuntime() {
        if (verifyStepIndex >= RUNTIME_VERIFY_DELAYS_MS.length) {
            log.d("VERIFYING_RUNTIME: all checks passed -> STEADY");
            if (settings.mode() == Mode.ONCE_ON_START) {
                settings.setBootEnforceConsumed(true);
            }
            transitionTo(State.STEADY);
            return;
        }
        long delayMs = RUNTIME_VERIFY_DELAYS_MS[verifyStepIndex];
        setPhase("VERIFY_RUNTIME (" + delayMs + "ms)");
        scheduleOnce(delayMs, new Runnable() {
            @Override
            public void run() {
                tickVerifyRuntime();
            }
        });
    }

    private void tickVerifyRuntime() {
        try {
            ReadResult r = link.read();
            applyReading(r);

            if (r.isFailure()) {
                log.w("VERIFYING_RUNTIME: link failure -> CONNECTING");
                transitionTo(State.CONNECTING);
                return;
            }

            if (r.aeb == Aeb.OFF) {
                verifyStepIndex++;
                scheduleVerifyRuntime();
            } else {
                enforceRetries++;
                if (enforceRetries < MAX_ENFORCE_RETRIES) {
                    log.d("VERIFYING_RUNTIME: still ON, retry " + enforceRetries + "/" + MAX_ENFORCE_RETRIES);
                    transitionTo(State.RECOVERY_ENFORCE);
                } else {
                    log.w("VERIFYING_RUNTIME: retries exhausted -> DEGRADED_RECOVERY");
                    transitionTo(State.DEGRADED_RECOVERY);
                }
            }
        } catch (Throwable t) {
            recoverFromFault("tickVerifyRuntime()", t);
        }
    }

    // =========================================================================
    // PUBLISHING
    // =========================================================================

    /** Old updatePublicState(): OFF/ON -> SUCCESS; failure -> UNKNOWN plus the raw transport status. */
    private void applyReading(ReadResult r) {
        if (destroyed) return;
        if (r.isFailure()) {
            aeb = Aeb.UNKNOWN;
            linkStatus = r.link;
        } else {
            aeb = r.aeb;
            linkStatus = LinkStatus.SUCCESS;
            observedAtMs = clock.nowMs();
        }
        publishNow();
    }

    /** Old publishAdbStatus(): only the transport status changes, the AEB reading stays. */
    private void publishLink(LinkStatus status) {
        if (destroyed) return;
        linkStatus = status;
        publishNow();
    }

    private void setPhase(String newPhase) {
        if (destroyed) return;
        phase = newPhase;
        publishNow();
    }

    private void publishNow() {
        synchronized (publishLock) {
            if (destroyed) return; // a worker that outlived stop() must not overwrite STOPPED
            sequence++;
            current = new Snapshot(aeb, linkStatus, phase, observedAtMs, sequence);
            deliver(current);
        }
    }

    private void deliver(Snapshot s) {
        try {
            sink.publish(s);
        } catch (RuntimeException e) {
            log.w("snapshot sink failed: " + e);
        }
    }
}
