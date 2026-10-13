package com.swarmos.app.hands.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * A small chip on top of other apps while a run is active: "Ustaad is working" and a Stop target (48 dp tall).
 * An accessibility overlay window, so it needs no permission of its own; it exists only while the accessibility
 * service is connected. Only its own area takes touches; the app below keeps the rest. Plain views, main thread.
 */
object StopOverlay {
    private const val TAG = "StopOverlay"
    private val main = Handler(Looper.getMainLooper())

    // main thread only
    private var view: View? = null
    private var windows: WindowManager? = null

    /** onStop runs on the main thread when the person taps Stop. Does nothing while the service is off. */
    fun show(onStop: () -> Unit) {
        main.post { showNow(onStop) }
    }

    fun hide() {
        main.post { hideNow() }
    }

    // status_bar_height: there is no public inset before API 30 for a window that is not an activity; 24 dp if it is missing
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun showNow(onStop: () -> Unit) {
        val svc = HandsAccessibilityService.instance ?: return
        hideNow()
        try {
            val density = svc.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).roundToInt()
            val ink = Color.parseColor("#15171A")
            val marker = Color.parseColor("#E08A3C")

            val label = TextView(svc).apply {
                text = "Ustaad is working"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(12), 0)
            }
            val stop = TextView(svc).apply {
                text = "Stop"
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minHeight = dp(48)
                minWidth = dp(72)
                setPadding(dp(20), 0, dp(20), 0)
                background = rounded(marker, dp(24))
                contentDescription = "Stop Ustaad"
                isClickable = true
                setOnClickListener {
                    try { onStop() } catch (e: Exception) { Log.w(TAG, "stop handler failed: ${e.javaClass.simpleName}") }
                }
            }
            val chip = LinearLayout(svc).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(ink, dp(24))
                addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
                addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
            }

            val statusBar = svc.resources.getIdentifier("status_bar_height", "dimen", "android")
                .let { if (it > 0) svc.resources.getDimensionPixelSize(it) else dp(24) }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = statusBar + dp(8)
                title = "Ustaad Stop"
            }
            // the service's own window manager carries the token an accessibility overlay needs
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(chip, lp)
            windows = wm
            view = chip
        } catch (e: Exception) {
            Log.w(TAG, "overlay failed: ${e.javaClass.simpleName}")
            view = null
        }
    }

    private fun hideNow() {
        val v = view ?: return
        view = null
        try { windows?.removeView(v) } catch (e: Exception) { Log.w(TAG, "remove failed: ${e.javaClass.simpleName}") }
        windows = null
    }

    private fun rounded(color: Int, radiusPx: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx.toFloat()
    }
}
