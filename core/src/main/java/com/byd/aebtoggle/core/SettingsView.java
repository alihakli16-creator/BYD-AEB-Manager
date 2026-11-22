package com.byd.aebtoggle.core;

/** What the engine needs to know about the user's settings. */
public interface SettingsView {
    /**
     * The CURRENT protection mode, re-read every time it is needed (so a change takes effect
     * before the next write). Must return Mode.NO_ACTION, ONCE_ON_START or AUTO_DISABLE; the
     * adapter is responsible for mapping missing / invalid / wrong-type stored values.
     */
    int mode();

    /** Whether Once on Start has already been consumed for the current boot/ignition session. */
    default boolean isBootEnforceConsumed() { return false; }

    /** Marks Once on Start as consumed for the current boot/ignition session. */
    default void setBootEnforceConsumed(boolean consumed) {}

    /** Whether the user manually enabled AEB, suppressing Auto-Disable until next ignition cycle. */
    default boolean isManualOverrideActive() { return false; }

    default void setManualOverrideActive(boolean active) {}
}
