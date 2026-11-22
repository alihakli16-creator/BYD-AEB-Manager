package com.byd.aebtoggle;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resilient Keep-Alive and Guarded ADB Auto-Confirmation Accessibility Service.
 *
 * 1. Keep-alive: Restarts AebService if killed by DiLink Task Manager.
 * 2. Guarded Auto-Confirm: Detects the system "Allow USB Debugging?" dialog and automatically
 *    checks "Always allow from this computer" and clicks "ALLOW / OK", ONLY IF:
 *      (a) Our app initiated an ADB connection attempt within the last 90 seconds.
 *      (b) The RSA MD5 fingerprint in the dialog matches our app's stored key.
 *      (c) Feature is enabled in settings (default: true).
 */
public class AebAccessibilityService extends AccessibilityService {

    private static final String TAG = "AebAccessibility";
    private static final Pattern MD5_PATTERN = Pattern.compile("([0-9A-Fa-f]{2}:){15}[0-9A-Fa-f]{2}");
    private static volatile long lastAutoConfirmClickMs = 0;

    @Override
    public void onInterrupt() {
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);

        boolean startOnBoot = new AebSettings(this).startOnBoot();
        StateStore.event("accessibility connected, auto-start " + (startOnBoot ? "on" : "off"));
        if (!startOnBoot) {
            Log.i(TAG, "Accessibility Service Connected; Start on Boot is off, skipping AebService start.");
            return;
        }
        Log.i(TAG, "Accessibility Service Connected. System is now monitoring AEB.");
        try {
            Intent serviceIntent = new Intent(this, AebService.class);
            serviceIntent.putExtra(AebService.EXTRA_BOOT_START, true);
            startForegroundService(serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AebService from Accessibility: " + e.getMessage());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        AebSettings settings = new AebSettings(this);
        if (!settings.isAutoAdbConfirmEnabled()) return;

        // Guard 1: Must be within 90s of our app's connection attempt
        if (!AdbHelper.isAdbConnectionRecent(90_000)) return;

        // Rate limit: 2 seconds between auto-confirm actions
        long now = SystemClock.elapsedRealtime();
        if (now - lastAutoConfirmClickMs < 2000) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            List<AccessibilityNodeInfo> allNodes = new ArrayList<>();
            collectNodes(root, allNodes);

            StringBuilder allText = new StringBuilder();
            AccessibilityNodeInfo checkBox = null;
            AccessibilityNodeInfo okButton = null;

            for (AccessibilityNodeInfo node : allNodes) {
                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();
                if (text != null && text.length() > 0) allText.append(text).append(" ");
                if (desc != null && desc.length() > 0) allText.append(desc).append(" ");

                // Detect CheckBox
                CharSequence className = node.getClassName();
                String textStr = text != null ? text.toString().toLowerCase() : "";
                String descStr = desc != null ? desc.toString().toLowerCase() : "";
                String viewId = node.getViewIdResourceName();
                if (viewId == null) viewId = "";

                if ((className != null && className.toString().contains("CheckBox")) ||
                        viewId.contains("always_allow") ||
                        textStr.contains("her zaman") || textStr.contains("always")) {
                    checkBox = node;
                }

                // Detect OK / Allow Button
                if (node.isClickable() &&
                        (textStr.equals("tamam") || textStr.equals("ok") || textStr.equals("izin ver") ||
                         textStr.equals("allow") || viewId.contains("button1"))) {
                    okButton = node;
                }
            }

            String dialogText = allText.toString();
            String lower = dialogText.toLowerCase();

            // Check if this window is indeed the USB Debugging Dialog
            boolean isUsbDialog = lower.contains("usb hata ayıklama") ||
                    lower.contains("hata ayıklamasına izin verilsin mi") ||
                    lower.contains("allow usb debugging") ||
                    lower.contains("rsa key fingerprint") ||
                    lower.contains("parmak izi");

            if (!isUsbDialog) return;

            StateStore.event("auto-adb: ADB diyalogu algılandı");

            // Guard 2: Extract RSA MD5 Fingerprint and verify match
            Matcher matcher = MD5_PATTERN.matcher(dialogText);
            String foundFp = null;
            if (matcher.find()) {
                foundFp = matcher.group();
            }

            String ourFp = AdbKeyStore.get().getFingerprintMd5();
            if (foundFp != null) {
                if (!foundFp.equalsIgnoreCase(ourFp)) {
                    StateStore.event("auto-adb: UYARI! Parmak izi eşleşmedi (Diyalog=" + foundFp + ", Uygulama=" + ourFp + "). İşlem yapılmadı.");
                    return;
                }
                StateStore.event("auto-adb: Parmak izi EŞLEŞTİ (" + ourFp + ")");
            } else {
                StateStore.event("auto-adb: Parmak izi metinde bulunamadı, güvenli eşleşme için bekleniyor");
                return;
            }

            // Step 1: Check the "Always allow" checkbox if not already checked
            if (checkBox != null && !checkBox.isChecked()) {
                boolean clicked = checkBox.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                StateStore.event("auto-adb: 'Bu bilgisayardan her zaman izin ver' tıklandı (başarı=" + clicked + ")");
            }

            // Step 2: Click the OK / Allow button
            if (okButton != null) {
                boolean clicked = okButton.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                lastAutoConfirmClickMs = SystemClock.elapsedRealtime();
                StateStore.event("auto-adb: 'TAMAM / İZİN VER' butonuna tıklandı (başarı=" + clicked + ")");
            } else {
                StateStore.event("auto-adb: Onay butonu bulunamadı");
            }

        } finally {
            root.recycle();
        }
    }

    private void collectNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> list) {
        if (node == null) return;
        list.add(node);
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectNodes(child, list);
            }
        }
    }
}
