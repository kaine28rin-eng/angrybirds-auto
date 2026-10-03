package com.stevestudy.angrybirdsauto.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import com.stevestudy.angrybirdsauto.data.SharedData
import org.opencv.core.Point

/**
 * Draws a transparent overlay on top of the game screen that shows:
 * - The predicted trajectory arc (parabolic curve from slingshot)
 * - A line from slingshot to target pig
 * - Circles marking detected pigs
 * - A circle marking the slingshot position
 */
class TrajectoryOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val layoutParamsType: Int
) {

    private var overlayView: View? = null
    private val overlayLayoutParams = WindowManager.LayoutParams().apply {
        type = layoutParamsType
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        format = PixelFormat.TRANSLUCENT
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        gravity = Gravity.TOP or Gravity.START
    }

    private val paintTrajectory = Paint().apply {
        color = Color.parseColor("#FFBB33")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        alpha = 180
        isAntiAlias = true
    }

    private val paintLine = Paint().apply {
        color = Color.parseColor("#FF5722")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        alpha = 200
    }

    private val paintPigCircle = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        alpha = 200
    }

    private val paintSlingshotCircle = Paint().apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        alpha = 200
    }

    // State for drawing
    private var trajectoryPoints: List<Pair<Double, Double>>? = null
    private var slingshotPoint: Point? = null
    private var pigPoint: Point? = null

    @Suppress("InflateParams")
    private fun createOverlayView(): View {
        return object : View(context) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (trajectoryPoints != null && slingshotPoint != null) {
                    drawTrajectory(canvas)
                }
            }
        }.apply {
            layoutParams = overlayLayoutParams
        }
    }

    private fun drawTrajectory(canvas: Canvas) {
        // Draw trajectory arc
        val path = Path()
        val points = trajectoryPoints!!
        if (points.isNotEmpty()) {
            path.moveTo(points[0].first.toFloat(), points[0].second.toFloat())
            for (i in 1 until points.size) {
                path.lineTo(points[i].first.toFloat(), points[i].second.toFloat())
            }
        }
        canvas.drawPath(path, paintTrajectory)

        // Draw line from slingshot to pig
        slingshotPoint?.let { sling ->
            pigPoint?.let { pig ->
                canvas.drawLine(
                    sling.x.toFloat(), sling.y.toFloat(),
                    pig.x.toFloat(), pig.y.toFloat(),
                    paintLine
                )
            }
        }

        // Draw slingshot circle
        slingshotPoint?.let { sling ->
            canvas.drawCircle(sling.x.toFloat(), sling.y.toFloat(), 50f, paintSlingshotCircle)
        }

        // Draw pig circle
        pigPoint?.let { pig ->
            canvas.drawCircle(pig.x.toFloat(), pig.y.toFloat(), 60f, paintPigCircle)
        }
    }

    /**
     * Show the trajectory overlay with the given data.
     */
    fun show(
        screenshot: Bitmap,
        slingshot: Point,
        pig: Point,
        trajectoryPoints: List<Pair<Double, Double>>
    ) {
        this.slingshotPoint = slingshot
        this.pigPoint = pig
        this.trajectoryPoints = trajectoryPoints

        if (overlayView == null) {
            overlayView = createOverlayView()
            windowManager.addView(overlayView, overlayLayoutParams)
        } else {
            overlayView?.invalidate()
        }
    }

    /**
     * Hide the trajectory overlay.
     */
    fun hide() {
        this.trajectoryPoints = null
        this.slingshotPoint = null
        this.pigPoint = null
        overlayView?.invalidate()
    }

    /**
     * Clean up — remove from WindowManager.
     */
    fun cleanup() {
        overlayView?.let {
            try {
                if (it.isAttachedToWindow) {
                    windowManager.removeView(it)
                }
            } catch (e: IllegalArgumentException) {
                // Already removed
            }
        }
        overlayView = null
    }
}
