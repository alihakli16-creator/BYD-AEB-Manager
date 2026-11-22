package com.byd.aebtoggle.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * S6: what "Grant Permission" achieved, one line per capability instead of one yes/no.
 *
 * The commands, their order and the fast-fail rules stay in AdbHelper.grantSelfPermissions() exactly
 * as in v18.22; this class only collects the transport result of each one. "Delivered" means the car
 * ran the command (transport SUCCESS); whether Android accepted it is not visible from here, except
 * for the overlay, which the screen checks itself (Settings.canDrawOverlays).
 */
public final class ProvisioningReport {

    public enum Capability {
        OVERLAY("Overlay (HUD)"),
        BACKGROUND("Background running"),
        DOZE("Battery optimisation exemption"),
        STANDBY("App standby bucket"),
        ACCESSIBILITY("Accessibility keep-alive");

        public final String label;

        Capability(String label) {
            this.label = label;
        }
    }

    public enum Outcome { NOT_TRIED, DELIVERED, FAILED, LEFT_UNTOUCHED }

    private final Map<Capability, Outcome> outcome = new EnumMap<Capability, Outcome>(Capability.class);
    private final Map<Capability, String> detail = new EnumMap<Capability, String>(Capability.class);
    private String failureCode = null;
    private String failureMessage = null;

    public synchronized void recordFailureReason(String code, String message) {
        this.failureCode = code;
        this.failureMessage = message;
    }

    public synchronized String failureCode() {
        return failureCode;
    }

    public synchronized String failureMessage() {
        return failureMessage;
    }

    public ProvisioningReport() {
        for (Capability c : Capability.values()) outcome.put(c, Outcome.NOT_TRIED);
    }

    /**
     * The capability each entry of AdbHelper's command list belongs to (v18.22 order): 0-2 overlay,
     * 3-6 background / foreground / battery app-ops, 7-8 Doze whitelist, 9 standby bucket.
     */
    public static Capability forCommandIndex(int i) {
        if (i <= 2) return Capability.OVERLAY;
        if (i <= 6) return Capability.BACKGROUND;
        if (i <= 8) return Capability.DOZE;
        return Capability.STANDBY;
    }

    /** One command's transport result. A capability is DELIVERED only if all its commands were. */
    public synchronized void record(Capability c, LinkStatus status) {
        Outcome now = outcome.get(c);
        if (now == Outcome.FAILED || now == Outcome.LEFT_UNTOUCHED) return; // the first problem is kept
        if (status == LinkStatus.SUCCESS) {
            outcome.put(c, Outcome.DELIVERED);
        } else {
            outcome.put(c, Outcome.FAILED);
            detail.put(c, String.valueOf(status));
        }
    }

    /** The app chose not to touch this capability (for example an unexpected accessibility list). */
    public synchronized void leftUntouched(Capability c, String why) {
        outcome.put(c, Outcome.LEFT_UNTOUCHED);
        detail.put(c, why);
    }

    public synchronized Outcome outcome(Capability c) {
        return outcome.get(c);
    }

    public synchronized boolean allDelivered() {
        for (Outcome o : outcome.values()) if (o != Outcome.DELIVERED) return false;
        return true;
    }

    /** One line per capability, in a fixed order, for the dialog and the event log. */
    public synchronized List<String> lines() {
        List<String> out = new ArrayList<String>();
        for (Capability c : Capability.values()) {
            Outcome o = outcome.get(c);
            switch (o) {
                case DELIVERED:
                    out.add("OK   " + c.label);
                    break;
                case FAILED:
                    out.add("FAIL " + c.label + " (" + detail.get(c) + ")");
                    break;
                case LEFT_UNTOUCHED:
                    out.add("SKIP " + c.label + ": " + detail.get(c));
                    break;
                default:
                    out.add("--   " + c.label + ": not tried");
                    break;
            }
        }
        return out;
    }
}
