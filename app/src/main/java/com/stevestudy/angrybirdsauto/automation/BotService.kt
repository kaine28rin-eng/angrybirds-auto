package com.stevestudy.angrybirdsauto.automation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.stevestudy.angrybirdsauto.data.SharedData
import com.stevestudy.angrybirdsauto.ui.OverlayManager
import com.stevestudy.angrybirdsauto.ui.TrajectoryOverlay
import com.stevestudy.angrybirdsauto.utils.OpenCVUtils
import org.opencv.core.Point
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Background Service that manages the floating overlay button and runs
 * the Angry Birds automation loop on a background Thread.
 *
 * Architecture:
 * 1. On tap of the floating overlay button → starts the automation Thread
 * 2. Thread: captures screen via MediaProjection → detects pigs via OpenCV →
 *   computes optimal trajectory via physics → dispatches swipe gesture via AccessibilityService
 * 3. Overlays show trajectory preview and detected targets
 *
 * This is the Android-native equivalent of the Python ADB approach —
 * everything runs on the phone, no PC or Termux needed.
 */
class BotService : Service() {

    private val tag: String = "${SharedData.loggerTag}BotService"
    private lateinit var myContext: Context
    private lateinit var windowManager: WindowManager
    private lateinit var overlayManager: OverlayManager
    private lateinit var trajectoryOverlay: TrajectoryOverlay

    private val openCVUtils = OpenCVUtils()

    companion object {
        private var botThread: Thread? = null
        @Volatile
        var isRunning = false

        /**
         * Interrupt the bot thread if it's running.
         */
        fun interruptBotThread() {
            botThread?.interrupt()
        }
    }

    override fun onCreate() {
        super.onCreate()
        myContext = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Create the floating overlay button and trajectory preview
        overlayManager = OverlayManager(this, windowManager)
        trajectoryOverlay = TrajectoryOverlay(this, windowManager, overlayManager.layoutParamsType)

        // Set up overlay button click listener
        overlayManager.setOnClickListener {
            if (!isRunning) {
                startBot()
            } else {
                stopBot()
            }
        }
    }

    /**
     * Start the automation loop.
     */
    private fun startBot() {
        if (botThread?.isAlive == true) {
            Log.w(tag, "Bot thread already running.")
            return
        }

        isRunning = true
        SharedData.isRunning = true
        AutoAccessibilityService.enableGestures()
        overlayManager.setRunningState(true)

        // Start MediaProjection Service (needs permission from Activity first)
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val hasPermission = prefs.getBoolean("media_projection_permission", false)
        if (!hasPermission) {
            Log.e(tag, "MediaProjection permission not granted. Cannot start bot.")
            // In the real app, this would prompt the user. For now, show error.
            overlayManager.showError("Grant screen capture permission first!")
            isRunning = false
            SharedData.isRunning = false
            overlayManager.setRunningState(false)
            return
        }

        val resultCode = prefs.getInt("media_projection_result_code", -1)

        if (resultCode == -1) {
            Log.e(tag, "MediaProjection permission data missing.")
            overlayManager.showError("Screen capture permission expired. Restart the app.")
            isRunning = false
            SharedData.isRunning = false
            overlayManager.setRunningState(false)
            return
        }

        // The MediaProjection service needs to be running for screen capture.
        // In the real app, MediaProjectionService would already be started.
        if (!SharedData.isCaptureReady) {
            Log.w(tag, "MediaProjection not ready yet.")
            overlayManager.showError("Screen capture not ready. Wait or restart.")
            isRunning = false
            SharedData.isRunning = false
            overlayManager.setRunningState(false)
            return
        }

        Log.d(tag, "Starting Angry Birds automation loop...")
        overlayManager.showMessage("Starting automation...")

        botThread = thread {
            try {
                runAutomationLoop()
            } catch (e: Exception) {
                if (e is InterruptedException) {
                    Log.d(tag, "Bot thread interrupted — stopping.")
                    overlayManager.showError("Stopped by user.")
                } else {
                    Log.e(tag, "Bot encountered error: ${e.message}")
                    overlayManager.showError("Error: ${e.message}")
                }
            } finally {
                isRunning = false
                SharedData.isRunning = false
                AutoAccessibilityService.disableGestures()
                Handler(Looper.getMainLooper()).post {
                    overlayManager.setRunningState(false)
                }
                Log.d(tag, "Bot thread cleanup complete.")
            }
        }
    }

    /**
     * Stop the automation loop.
     */
    private fun stopBot() {
        if (!isRunning) return
        Log.d(tag, "Stopping bot...")
        isRunning = false
        SharedData.isRunning = false
        AutoAccessibilityService.disableGestures()
        botThread?.interrupt()
        botThread = null
        overlayManager.setRunningState(false)
        trajectoryOverlay.hide()
    }

    /**
     * The main automation loop:
     * 1. Capture screen
     * 2. Detect slingshot position
     * 3. Detect all pigs
     * 4. For each pig, calculate optimal trajectory
     * 5. Fire the best shot using a swipe gesture
     * 6. Wait for bird to land, then repeat
     */
    private fun runAutomationLoop() {
        val trajectoryCalc = TrajectoryCalculator()
        var shotsFired = 0

        while (!Thread.currentThread().isInterrupted && isRunning) {
            AutoAccessibilityService.checkInterruption()

            // Step 1: Capture the screen
            val screenshot = MediaProjectionService.takeScreenshotNow()
            if (screenshot == null) {
                Log.w(tag, "Screenshot was null. Retrying...")
                overlayManager.showMessage("Waiting for screen capture...")
                Thread.sleep(500)
                continue
            }

            overlayManager.showMessage("Screen captured. Searching for targets...")

            // Step 2: Detect slingshot
            val slingshotResult = detectSlingshot(screenshot)
            val slingshot = slingshotResult.first
            val slingshotBitmap = slingshotResult.second

            if (slingshot == null) {
                Log.w(tag, "Slingshot not found. Trying color detection...")
                // Fallback to color detection
                val colorResults = openCVUtils.detectByColor(screenshot, "slingshot")
                if (colorResults.isNotEmpty()) {
                    val avgX = colorResults.map { it.x }.average()
                    val avgY = colorResults.map { it.y }.average()
                    val slingshotPoint = Point(avgX, avgY)
                    overlayManager.showMessage("Slingshot found via color at (${avgX.toInt()}, ${avgY.toInt()})")
                    processShot(screenshot, slingshotPoint, trajectoryCalc, screenshot.width, screenshot.height, shotsFired)
                } else {
                    overlayManager.showError("Slingshot not found on screen!")
                    Thread.sleep(1000)
                    continue
                }
            } else {
                overlayManager.showMessage("Slingshot found at (${slingshot.x.toInt()}, ${slingshot.y.toInt()})")
                processShot(screenshot, slingshot, trajectoryCalc, screenshot.width, screenshot.height, shotsFired)
            }

            // Step 7: Wait for the bird to finish flying/landing
            Thread.sleep(4000)

            // Check if level is complete (all pigs destroyed)
            val checkScreenshot = MediaProjectionService.takeScreenshotNow()
            if (checkScreenshot != null) {
                val pigResults = detectPigs(checkScreenshot, slingshotBitmap)
                if (pigResults.isEmpty()) {
                    overlayManager.showMessage("All pigs destroyed! Level complete. Waiting...")
                    Thread.sleep(5000) // Wait for level transition
                    continue
                }
            }
        }
    }

    /**
     * Process a single shot: detect pigs, calculate trajectory, execute via swipe.
     */
    private fun processShot(
        screenshot: Bitmap,
        slingshot: Point,
        trajectoryCalc: TrajectoryCalculator,
        screenW: Int,
        screenH: Int,
        shotNumber: Int
    ) {
        // Step 3: Detect pigs
        val pigBitmap = loadTemplate("pig")
        val pigs = if (pigBitmap != null) {
            openCVUtils.findAllMatches(screenshot, pigBitmap)
        } else {
            openCVUtils.detectByColor(screenshot, "pig")
        }

        if (pigs.isEmpty()) {
            overlayManager.showMessage("No pigs found on screen.")
            Thread.sleep(500)
            return
        }

        overlayManager.showMessage("Found ${pigs.size} pig(s). Calculating trajectories...")
        Log.d(tag, "Found ${pigs.size} pigs at: ${pigs.joinToString { "(${it.x.toInt()}, ${it.y.toInt()})" }}")

        // Step 4: For each pig, find the best shot
        var bestShot: TrajectoryCalculator.BestShot? = null
        var bestPig: Point? = null
        var bestAngle: Double = 0.0
        var bestPower: Double = 0.0

        for (pig in pigs) {
            val shot = trajectoryCalc.findBestShot(
                slingshot.x, slingshot.y,
                pig.x, pig.y,
                screenW, screenH
            )

            if (shot.shot != null) {
                if (bestShot == null || shot.shot.missDistance < (bestShot.shot?.missDistance ?: Double.MAX_VALUE)) {
                    bestShot = shot
                    bestPig = pig
                    bestAngle = shot.shot.angleDegrees
                    bestPower = shot.shot.power
                }
            }
        }

        if (bestShot == null || bestShot.shot == null) {
            overlayManager.showError("Could not calculate trajectory!")
            Thread.sleep(1000)
            return
        }

        val shot = bestShot.shot!!
        val hit = bestShot.isHit

        overlayManager.showMessage(
            if (hit) "DIRECT HIT! Angle=${shot.angleDegrees}°, Power=${shot.power}"
            else "Best shot: miss=${shot.missDistance.toInt()}px, Angle=${shot.angleDegrees}°, Power=${shot.power}"
        )

        // Show trajectory overlay
        val trajectoryPoints = trajectoryCalc.calculateTrajectoryPoints(
            slingshot.x, slingshot.y, shot.angleDegrees, shot.power, screenW, screenH
        )
        trajectoryOverlay.show(screenshot, slingshot, bestPig!!, trajectoryPoints)

        // Step 5: Execute the swipe
        val swipeVector = trajectoryCalc.calculateSwipeVector(slingshot.x, slingshot.y, shot.angleDegrees, shot.power)
        val dragDuration = (300 + shot.power * 400).toLong() // Longer pull = more power

        AutoAccessibilityService.checkInterruption()
        if (!AutoAccessibilityService.isGestureAllowed) {
            Log.w(tag, "Gestures disabled during shot execution.")
            return
        }

        overlayManager.showMessage("Firing shot #${shotNumber + 1}!")

        // Dispatch the drag-and-release gesture
        val success = AutoAccessibilityService.getInstance().swipe(
            swipeVector.startX.toFloat(),
            swipeVector.startY.toFloat(),
            swipeVector.endX.toFloat(),
            swipeVector.endY.toFloat(),
            dragDuration
        )

        if (success) {
            Log.d(tag, "Shot dispatched successfully.")
        } else {
            Log.e(tag, "Failed to dispatch shot gesture.")
            overlayManager.showError("Failed to fire shot!")
        }

        // Step 6: Hide trajectory overlay after shot
        trajectoryOverlay.hide()
    }

    /**
     * Detect the slingshot position on the screen.
     * First tries template matching, then falls back to color detection.
     */
    private fun detectSlingshot(screenshot: Bitmap): Pair<Point?, Bitmap?> {
        val slingshotBitmap = loadTemplate("slingshot")

        if (slingshotBitmap != null) {
            val result = openCVUtils.matchBitmap(screenshot, slingshotBitmap)
            if (result.first && result.second != null) {
                saveScreenshot(screenshot, "slingshot_match")
                return Pair(result.second, slingshotBitmap)
            }
        }

        // Fallback: detect the slingshot base color (brown/wooden)
        val colorResults = openCVUtils.detectByColor(screenshot, "slingshot")
        if (colorResults.isNotEmpty()) {
            // The slingshot is typically at the bottom-left
            val sorted = colorResults.sortedBy { it.y }
            val slingshotPoint = sorted.last() // Lowest (closest to bottom of screen)
            return Pair(slingshotPoint, slingshotBitmap)
        }

        return Pair(null, slingshotBitmap)
    }

    /**
     * Detect all pigs on the screen using template matching.
     */
    private fun detectPigs(screenshot: Bitmap, pigBitmap: Bitmap?): List<Point> {
        return if (pigBitmap != null) {
            openCVUtils.findAllMatches(screenshot, pigBitmap)
        } else {
            openCVUtils.detectByColor(screenshot, "pig")
        }
    }

    /**
     * Load a template image from the assets/templates folder.
     */
    private fun loadTemplate(name: String): Bitmap? {
        return try {
            val assetPath = "templates/${name}.png"
            assets.open(assetPath).use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to load template '$name': ${e.message}")
            null
        }
    }

    /**
     * Save a screenshot to internal storage for debugging.
     */
    private fun saveScreenshot(bitmap: Bitmap, name: String) {
        try {
            val dir = File(filesDir, "debug")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${name}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, it)
            }
            Log.d(tag, "Saved debug screenshot: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(tag, "Failed to save screenshot: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) {
            botThread?.interrupt()
        }
        overlayManager.cleanup()
        trajectoryOverlay.cleanup()
        Log.d(tag, "BotService destroyed.")
    }
}
