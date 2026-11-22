package com.byd.aebtoggle;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.byd.aebtoggle.core.StatusView;

/**
 * The floating HUD (refactor S6). Up to S5 this was a second service, AebFloatingService, started and
 * stopped by AebService; now AebService owns it directly, so the HUD lives and dies with the service
 * that produces its data. Look, position memory, dragging and the 1 s refresh are unchanged.
 * Main thread only.
 */
final class HudController {
    private static final String TAG = "AEB_Toggle";

    private final Context context;
    private final AebSettings settings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;
    private boolean isViewAdded = false;
    private StatusView.Kind lastKind = null;
    private boolean lastAttention = false;

    HudController(Context context) {
        this.context = context;
        this.settings = new AebSettings(context);
    }

    boolean isShown() {
        return isViewAdded;
    }

    void show() {
        if (isViewAdded) return;
        if (!Settings.canDrawOverlays(context)) {
            Log.e(TAG, "Floating Overlay permission missing. HUD not shown.");
            return;
        }
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        floatingView = LayoutInflater.from(context).inflate(R.layout.floating_widget, null);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = settings.hudX();
        params.y = settings.hudY();

        try {
            windowManager.addView(floatingView, params);
            isViewAdded = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to add floating view: " + e.getMessage());
            floatingView = null;
            return;
        }
        StateStore.event("HUD shown");

        // Draggable, position remembered, single-tap toggles AFS
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private long touchDownTime;
            private boolean isDrag = false;
            private final int touchSlop = 15;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchDownTime = SystemClock.elapsedRealtime();
                        isDrag = false;
                        StateStore.event("HUD: onTouch ACTION_DOWN");
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (!isDrag && Math.hypot(dx, dy) > touchSlop) {
                            isDrag = true;
                        }
                        if (isDrag) {
                            params.x = initialX + (int) dx;
                            params.y = initialY + (int) dy;
                            if (isViewAdded) {
                                try {
                                    windowManager.updateViewLayout(floatingView, params);
                                } catch (Exception ignored) {}
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        long duration = SystemClock.elapsedRealtime() - touchDownTime;
                        StateStore.event("HUD: onTouch ACTION_UP (drag=" + isDrag + ", duration=" + duration + "ms)");
                        if (!isDrag && duration < 500) {
                            onBubbleTapped();
                        } else if (isDrag) {
                            settings.saveHudPosition(params.x, params.y);
                        }
                        return true;
                }
                return false;
            }
        });

        lastKind = null; // first refresh always renders
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    private void onBubbleTapped() {
        StateStore.event("HUD: baloncuk dokunuldu, AFS aç/kapa tetikleniyor");
        StateStore.requestToggleAeb(context, new StateStore.ToggleCallback() {
            @Override
            public void onProgress() {
                if (floatingView != null && isViewAdded) {
                    TextView text = floatingView.findViewById(R.id.floating_text);
                    if (text != null) {
                        text.setText("...");
                        text.setTextColor(context.getColor(R.color.hud_neutral));
                    }
                }
            }

            @Override
            public void onDone(boolean confirmed, com.byd.aebtoggle.core.Aeb finalState, String error) {
                if (confirmed) {
                    Toast.makeText(context, finalState == com.byd.aebtoggle.core.Aeb.ON ?
                            R.string.status_aeb_on : R.string.status_aeb_off, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "Hata: " + (error != null ? error : "Doğrulanamadı"), Toast.LENGTH_SHORT).show();
                }
                if (isViewAdded) {
                    StatusView v = StatusView.of(StateStore.current(), SystemClock.elapsedRealtime());
                    lastKind = v.kind;
                    lastAttention = v.needsAttention();
                    render(v);
                }
            }
        });
    }

    void hide() {
        handler.removeCallbacks(refresh);
        if (floatingView != null && isViewAdded) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
            StateStore.event("HUD hidden");
        }
        isViewAdded = false;
        floatingView = null;
    }

    // Reads the engine snapshot once a second (this also re-evaluates staleness).
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!isViewAdded) return;
            StatusView v = StatusView.of(StateStore.current(), SystemClock.elapsedRealtime());
            if (v.kind != lastKind || v.needsAttention() != lastAttention) {
                lastKind = v.kind;
                lastAttention = v.needsAttention();
                render(v);
            }
            handler.postDelayed(this, 1000);
        }
    };

    private void render(StatusView v) {
        TextView text = floatingView.findViewById(R.id.floating_text);
        StatusView.Kind kind = v.kind;
        if (kind == StatusView.Kind.OFF) {
            text.setText(R.string.status_aeb_off);
            text.setTextColor(context.getColor(R.color.hud_off));
        } else if (kind == StatusView.Kind.ON) {
            text.setText(R.string.status_aeb_on);
            text.setTextColor(context.getColor(R.color.hud_on));
        } else if (kind == StatusView.Kind.UNVERIFIED_OFF) {
            text.setText(R.string.status_aeb_off); // D7: last reading, neutral until confirmed
            text.setTextColor(context.getColor(R.color.hud_neutral));
        } else if (kind == StatusView.Kind.UNVERIFIED_ON) {
            text.setText(R.string.status_aeb_on);
            text.setTextColor(context.getColor(R.color.hud_neutral));
        } else {
            text.setText(R.string.hud_unknown);
            text.setTextColor(context.getColor(R.color.hud_neutral));
        }
        // S5 (D6): a reported problem turns the HUD text amber, whatever it says
        if (v.needsAttention()) text.setTextColor(context.getColor(R.color.hud_attention));
    }
}
