package com.byd.aebtoggle;

import android.os.SystemClock;

import com.byd.aebtoggle.core.Aeb;
import com.byd.aebtoggle.core.EventLog;
import com.byd.aebtoggle.core.LinkStatus;
import com.byd.aebtoggle.core.ParcelParser;
import com.byd.aebtoggle.core.ReadResult;
import com.byd.aebtoggle.core.VehicleLink;
import com.byd.aebtoggle.core.WriteResult;

/**
 * The engine's door to the car: a thin adapter over the unchanged AdbHelper calls that
 * AebService v18.22 made itself. Exceptions are NOT caught here on purpose: they reach the
 * engine's safety nets exactly as they reached the old service's.
 *
 * S3: every read and write is written to the in-app event log (writes with their status, time
 * taken and the car's raw reply, cut to 120 characters; that reply shape is what S4 needs).
 */
final class AdbVehicleLink implements VehicleLink {

    /** Reads slower than this get their duration in the log line (faster ones fold into one line). */
    private static final long SLOW_READ_MS = 1500;
    private static final int REPLY_CHARS = 120;

    /** AdbHelper.queryAebStatus() plus AdbHelper.lastRawStatus, as one value. */
    @Override
    public ReadResult read() {
        long t0 = SystemClock.elapsedRealtime();
        AdbHelper.VerificationResult v = AdbHelper.queryAebStatus();
        long ms = SystemClock.elapsedRealtime() - t0;
        ReadResult r;
        String text;
        if (v == AdbHelper.VerificationResult.DISABLED_CONFIRMED) {
            r = ReadResult.off();
            text = "read: AEB OFF";
        } else if (v == AdbHelper.VerificationResult.ENABLED_DETECTED) {
            r = ReadResult.on();
            text = "read: AEB ON";
        } else {
            r = ReadResult.failed(toLink(AdbHelper.lastRawStatus));
            text = r.link == LinkStatus.SUCCESS ? "read: reply not understood" : "read failed: " + r.link;
        }
        StateStore.event(ms >= SLOW_READ_MS ? text + " (slow: " + ms + " ms)" : text);
        if (AdbHelper.lastRawStatus == AdbHelper.AdbStatus.SUCCESS) noteReadReply(AdbHelper.lastReadOutput, r.aeb);
        return r;
    }

    private static volatile boolean readReplyShown = false;
    private static volatile boolean disagreementShown = false;

    /**
     * S4: the raw read reply goes to the log once per process, so the real shape is on record.
     * The strict core ParcelParser agrees with today's parser on all observed car replies.
     * If they ever disagree, the disagreement is logged once.
     */
    private static void noteReadReply(String raw, Aeb used) {
        Aeb strict = ParcelParser.parseRead(raw);
        boolean disagree = strict != used;
        if (readReplyShown && (!disagree || disagreementShown)) return;
        readReplyShown = true;
        if (disagree) disagreementShown = true;
        StateStore.event("read reply: " + EventLog.oneLine(raw, REPLY_CHARS)
                + (disagree ? " (strict parser would say " + strict + ")" : ""));
    }

    /** Turns AEB OFF. There is deliberately no method here that turns it on. */
    @Override
    public WriteResult writeDisable() {
        long t0 = SystemClock.elapsedRealtime();
        AdbHelper.AdbResponse res = AdbHelper.setAebState(false);
        long ms = SystemClock.elapsedRealtime() - t0;
        StateStore.event("write AEB off: " + res.status + " in " + ms + " ms; reply: "
                + EventLog.oneLine(res.output, REPLY_CHARS));
        return WriteResult.of(toLink(res.status));
    }

    /** The two enums have identical names by design. */
    static LinkStatus toLink(AdbHelper.AdbStatus status) {
        if (status == null || status == AdbHelper.AdbStatus.PORT_CLOSED) return LinkStatus.ERROR;
        return LinkStatus.valueOf(status.name());
    }
}
