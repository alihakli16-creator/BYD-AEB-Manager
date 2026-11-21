package com.byd.afskapat.util

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.byd.aebtoggle.R

class OverlayHelper(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null

    fun showMessage(message: String, isSuccess: Boolean = true, durationMs: Long = 3500) {
        mainHandler.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                return@post
            }

            try {
                if (overlayView == null) {
                    val inflater = LayoutInflater.from(context)
                    overlayView = inflater.inflate(R.layout.layout_overlay_status, null)

                    val layoutParams = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else
                            @Suppress("DEPRECATION")
                            WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = 60
                    }

                    windowManager.addView(overlayView, layoutParams)
                }

                val tvText = overlayView?.findViewById<TextView>(R.id.tvOverlayText)
                tvText?.text = message

                val ivIcon = overlayView?.findViewById<android.widget.ImageView>(R.id.ivOverlayIcon)
                ivIcon?.setImageResource(if (isSuccess) R.drawable.ic_shield else R.drawable.ic_info)

                dismissRunnable?.let { mainHandler.removeCallbacks(it) }
                dismissRunnable = Runnable { dismiss() }
                mainHandler.postDelayed(dismissRunnable!!, durationMs)

            } catch (e: Exception) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            try {
                if (overlayView != null) {
                    windowManager.removeView(overlayView)
                    overlayView = null
                }
            } catch (ignored: Exception) {}
        }
    }
}
