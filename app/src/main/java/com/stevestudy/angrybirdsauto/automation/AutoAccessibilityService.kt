package com.stevestudy.angrybirdsauto.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.stevestudy.angrybirdsauto.data.SharedData

/**
 * Accessibility Service that dispatches touch gestures on the screen.
 *
 * This is the core of on-device input injection — it uses Android's
 * AccessibilityService.dispatchGesture() to send tap, swipe, and scroll
 * gestures to any app on the screen (including inside Angry Birds).
 *
 * No ADB or PC is required — the service runs directly on the phone.
 */
@SuppressLint("AccessibilityPolicy")
class AutoAccessibilityService : AccessibilityService() {

    private lateinit var myContext: Context

    companion object {
        val tag: String = "${SharedData.loggerTag}AutoAccessibilityService"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AutoAccessibilityService? = null

        // Flag to control whether gestures are allowed to be dispatched.
        @Volatile
        var isGestureAllowed: Boolean = true

        @Volatile
        var isServiceConnected: Boolean = false

        /**
         * Returns the static reference to this service.
         */
        fun getInstance(): AutoAccessibilityService {
            return instance ?: throw IllegalStateException(
                "Accessibility Service not initialized. Please enable it in Settings."
            )
        }

        /**
         * Check if the accessibility service is enabled/running.
         */
        fun isEnabled(context: Context): Boolean {
            return isServiceConnected
        }

        /**
         * Disable gestures to prevent them from being dispatched.
         */
        fun disableGestures() {
            isGestureAllowed = false
            Log.d(tag, "Gestures have been disabled.")
        }

        /**
         * Re-enable gestures.
         */
        fun enableGestures() {
            isGestureAllowed = true
            Log.d(tag, "Gestures have been enabled.")
        }

        /**
         * Check if the current thread has been interrupted.
         */
        fun checkInterruption() {
            if (Thread.currentThread().isInterrupted) {
                Log.d(tag, "Thread interrupt detected.")
                throw InterruptedException("Thread was interrupted.")
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        myContext = this
        isServiceConnected = true
        Log.d(tag, "Accessibility Service is now connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to listen for specific events; gestures are dispatched directly.
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceConnected = false
        isGestureAllowed = false
        Log.d(tag, "Accessibility Service is now stopped.")
    }

    /**
     * Wait for the specified number of seconds using Thread.sleep.
     */
    private fun Double.wait() {
        Thread.sleep((this * 1000).toLong())
    }

    /**
     * Dispatch a tap gesture at the specified screen coordinates.
     *
     * @param x The x coordinate.
     * @param y The y coordinate.
     * @param longPress Whether to long-press instead of tap.
     * @param pressDuration How long to hold the press (for long press).
     * @param taps Number of taps to execute.
     * @return True if the gesture was dispatched successfully.
     */
    fun tap(
        x: Double,
        y: Double,
        longPress: Boolean = false,
        pressDuration: Double = 1.0,
        taps: Int = 1
    ): Boolean {
        if (!isGestureAllowed) {
            Log.w(tag, "Gestures disabled. Skipping tap at ($x, $y).")
            return false
        }

        if (Thread.currentThread().isInterrupted) {
            Log.w(tag, "Thread interrupted. Skipping tap.")
            throw InterruptedException("Thread was interrupted.")
        }

        Log.d(tag, if (longPress) {
            "Long pressing at ($x, $y) for ${pressDuration}s"
        } else {
            "Tapping at ($x, $y)"
        })

        val tapPath = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture: GestureDescription = if (longPress) {
            val durationMs = (pressDuration * 1000).toLong()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(tapPath, 0, durationMs, false))
                    .build()
            } else {
                @Suppress("DEPRECATION")
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(tapPath, 0, durationMs))
                    .build()
            }
        } else {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(tapPath, 0, 1))
                .build()
        }

        val dispatchResult = dispatchGesture(gesture, null, null)

        if (longPress) {
            pressDuration.wait()
        } else {
            0.10.wait()
        }

        // Additional taps
        var remainingTaps = taps - 1
        while (remainingTaps > 0) {
            checkInterruption()
            if (!isGestureAllowed) {
                Log.d(tag, "Gestures disabled during tap loop. Stopping.")
                break
            }
            dispatchGesture(gesture, null, null)
            if (longPress) {
                pressDuration.wait()
            } else {
                0.10.wait()
            }
            remainingTaps--
        }

        return dispatchResult
    }

    /**
     * Dispatch a swipe/drag gesture from (oldX, oldY) to (newX, newY).
     * This is used to pull the bird backwards on the slingshot and release.
     *
     * @param oldX Start X coordinate (where the bird sits on the slingshot).
     * @param oldY Start Y coordinate.
     * @param newX End X coordinate (where the drag releases).
     * @param newY End Y coordinate.
     * @param duration How long the swipe takes in milliseconds.
     * @return True if the gesture was dispatched successfully.
     */
    fun swipe(oldX: Float, oldY: Float, newX: Float, newY: Float, duration: Long = 500L): Boolean {
        if (!isGestureAllowed) {
            Log.w(tag, "Gestures disabled. Skipping swipe from ($oldX,$oldY) to ($newX,$newY).")
            return false
        }

        if (Thread.currentThread().isInterrupted) {
            Log.w(tag, "Thread interrupted. Skipping swipe.")
            throw InterruptedException("Thread was interrupted.")
        }

        val swipePath = Path().apply {
            moveTo(oldX, oldY)
            lineTo(newX, newY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, duration))
            .build()

        val dispatchResult = dispatchGesture(gesture, null, null)
        (duration.toDouble() / 1000).wait()

        return dispatchResult
    }

    /**
     * Dispatch a swipe gesture with a release at the end (for the slingshot pull-and-release).
     * First drags from start to end, then lifts the finger (release).
     *
     * @param startX Start X (slingshot position).
     * @param startY Start Y (slingshot position).
     * @param endX End X (pull-back position).
     * @param endY End Y (pull-back position).
     * @param dragDuration How long the drag takes.
     * @param releaseDelay Delay before releasing (ms).
     */
    fun swipeWithRelease(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        dragDuration: Long = 300L,
        releaseDelay: Long = 100L
    ): Boolean {
        if (!isGestureAllowed) return false

        // Drag from start to end
        val dragPath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val dragGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(dragPath, 0, dragDuration))
            .build()

        val dragResult = dispatchGesture(dragGesture, null, null)

        if (!dragResult) {
            Log.e(tag, "Failed to dispatch drag gesture.")
            return false
        }

        // Wait for the drag to complete, then briefly hold before release
        (dragDuration.toDouble() / 1000 + releaseDelay / 1000.0).wait()

        // Release (tap at end point to simulate finger lift)
        // Actually, GestureDescription with a stroke that ends will auto-release.
        // But for Angry Birds, the bird releases when the touch is lifted.
        // The stroke ending = touch up, so the drag gesture ends with a release automatically.

        return true
    }
}
