package com.stevestudy.angrybirdsauto.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import android.annotation.SuppressLint
import com.stevestudy.angrybirdsauto.R
import com.stevestudy.angrybirdsauto.data.SharedData
import kotlin.math.abs

/**
 * Manages the floating overlay button that hovers over the game.
 *
 * The button appears as a floating play/stop icon that the user can drag
 * to position over the Angry Birds game. Tapping it starts/stops the
 * automation loop.
 */
class OverlayManager(
    private val context: Context,
    private val windowManager: WindowManager
) {

    private val tag: String = "${SharedData.loggerTag}OverlayManager"

    val layoutParamsType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private lateinit var overlayView: View
    private lateinit var overlayButton: ImageButton

    private val overlayLayoutParams = WindowManager.LayoutParams().apply {
        type = layoutParamsType
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        format = PixelFormat.TRANSLUCENT
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        windowAnimations = android.R.style.Animation_Toast
        gravity = Gravity.TOP or Gravity.START
    }

    private var isDragging: Boolean = false
    private val touchSlop: Int

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    private var onOverlayClickListener: (() -> Unit)? = null

    init {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 2
        createOverlayButton()
    }

    @SuppressLint("InflateParams")
    private fun createOverlayButton() {
        overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_button, null)
        overlayButton = overlayView.findViewById(R.id.overlay_button)

        val buttonSizePx = dpToPx(SharedData.overlayButtonSizeDP)
        overlayButton.layoutParams.width = buttonSizePx
        overlayButton.layoutParams.height = buttonSizePx
        overlayButton.requestLayout()

        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        overlayLayoutParams.x = (screenWidth - buttonSizePx) / 2
        overlayLayoutParams.y = screenHeight / 2 - buttonSizePx

        val prefs = getPrefs()
        overlayLayoutParams.x = prefs.getInt("overlay_x", overlayLayoutParams.x)
        overlayLayoutParams.y = prefs.getInt("overlay_y", overlayLayoutParams.y)

        windowManager.addView(overlayView, overlayLayoutParams)
        setupTouchListener()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        overlayButton.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (event == null) return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = overlayLayoutParams.x
                        initialY = overlayLayoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val xDiff = event.rawX - initialTouchX
                        val yDiff = event.rawY - initialTouchY

                        if (!isDragging && (abs(xDiff) > touchSlop || abs(yDiff) > touchSlop)) {
                            isDragging = true
                        }

                        if (isDragging) {
                            overlayLayoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                            overlayLayoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(overlayView, overlayLayoutParams)
                        }
                        return false
                    }
                    MotionEvent.ACTION_UP -> {
                        val prefs = getPrefs()
                        prefs.edit()
                            .putInt("overlay_x", overlayLayoutParams.x)
                            .putInt("overlay_y", overlayLayoutParams.y)
                            .apply()

                        if (!isDragging) {
                            onOverlayClickListener?.invoke()
                        }
                        isDragging = false
                        return false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                    }
                }
                return false
            }
        })
    }

    fun setOnClickListener(listener: () -> Unit) {
        onOverlayClickListener = listener
    }

    fun setRunningState(running: Boolean) {
        if (running) {
            overlayButton.setImageResource(R.drawable.ic_stop)
        } else {
            overlayButton.setImageResource(R.drawable.ic_play)
        }
    }

    fun showMessage(message: String) {
        Log.d(tag, message)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun showError(message: String) {
        Log.e(tag, message)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Error: $message", Toast.LENGTH_LONG).show()
        }
    }

    fun cleanup() {
        try {
            if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
                windowManager.removeView(overlayView)
            }
        } catch (e: IllegalArgumentException) {
            // Already removed
        }
    }

    private fun getPrefs(): SharedPreferences {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
