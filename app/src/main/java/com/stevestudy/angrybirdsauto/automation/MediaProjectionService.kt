package com.stevestudy.angrybirdsauto.automation

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.OrientationEventListener
import android.view.WindowManager
import com.stevestudy.angrybirdsauto.data.SharedData
import com.stevestudy.angrybirdsauto.utils.ImageUtils

/**
 * The MediaProjection service that captures the device's screen.
 *
 * This is the on-device equivalent of ADB screencap — no PC required.
 * Uses Android's MediaProjection API to capture the full screen as a Bitmap
 * that can be processed by OpenCV for pig detection.
 */
class MediaProjectionService : Service() {

    private lateinit var myContext: Context

    companion object {
        val tag: String = "${SharedData.loggerTag}MediaProjectionService"

        @Volatile
        private var mediaProjection: MediaProjection? = null
        private var orientationChangeCallback: OrientationEventListener? = null
        private lateinit var tempDirectory: String
        private lateinit var threadHandler: Handler

        private var virtualDisplay: VirtualDisplay? = null
        private lateinit var defaultDisplay: Display
        private lateinit var windowManager: WindowManager
        private var oldRotation: Int = 0
        private var imageReader: ImageReader? = null

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        private var lastBitmap: Bitmap? = null

        /**
         * Start this service with the MediaProjection permission result.
         * Call this from your Activity after getting the result from the permission dialog.
         */
        fun getStartIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, MediaProjectionService::class.java).apply {
                putExtra("ACTION", "START")
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
        }

        fun getStopIntent(context: Context): Intent {
            return Intent(context, MediaProjectionService::class.java).apply {
                putExtra("ACTION", "STOP")
            }
        }

        /**
         * Create the Intent for requesting screen capture permission.
         */
        fun getScreenCaptureIntent(context: Context): Intent {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val config = MediaProjectionConfig.createConfigForDefaultDisplay()
                manager.createScreenCaptureIntent(config)
            } else {
                manager.createScreenCaptureIntent()
            }
        }

        /**
         * Take a screenshot of the full screen.
         *
         * @return Bitmap of the current screen, or null if capture failed.
         */
        fun takeScreenshotNow(): Bitmap? {
            if (!SharedData.isCaptureReady) {
                Log.w(tag, "MediaProjection is not ready yet.")
                return null
            }

            var image: Image? = imageReader?.acquireLatestImage()
            // Retry for up to 50ms if no image is available
            if (image == null) {
                var retries = 5
                while (retries > 0) {
                    Thread.sleep(10)
                    image = imageReader?.acquireLatestImage()
                    if (image != null) break
                    retries--
                }
            }

            if (image != null) {
                try {
                    val bitmap = ImageUtils.imageToBitmap(image)
                    lastBitmap = bitmap
                    return bitmap
                } catch (e: Exception) {
                    Log.e(tag, "Failed to convert Image to Bitmap: ${e.message}")
                } finally {
                    image.close()
                }
            } else {
                Log.w(tag, "ImageReader returned null. Using cached bitmap.")
                return lastBitmap
            }

            return lastBitmap
        }

        /**
         * Get the cached last screenshot.
         */
        fun getLastBitmap(): Bitmap? = lastBitmap
    }

    override fun onCreate() {
        super.onCreate()
        myContext = this
        threadHandler = Handler(Looper.getMainLooper())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        myContext = this
        isRunning = true
        SharedData.mainPackagePath = myContext.packageName

        // Create temp directory
        tempDirectory = filesDir.absolutePath + "/temp/"
        val newTempDirectory = java.io.File(tempDirectory)
        if (!newTempDirectory.exists()) {
            newTempDirectory.mkdirs()
        }

        if (isStartCommand(intent)) {
            Log.d(tag, "MediaProjection Service received START Intent.")

            val resultCode = intent.getIntExtra("RESULT_CODE", 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("DATA")
            }

            if (data != null) {
                startMediaProjection(resultCode, data)
            }
        } else if (isStopCommand(intent)) {
            Log.d(tag, "Received STOP Intent for MediaProjection.")
            stopMediaProjection()
            isRunning = false
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun isStartCommand(intent: Intent): Boolean {
        return intent.hasExtra("ACTION") && intent.getStringExtra("ACTION") == "START" &&
            intent.hasExtra("RESULT_CODE") && intent.hasExtra("DATA")
    }

    private fun isStopCommand(intent: Intent): Boolean {
        return intent.hasExtra("ACTION") && intent.getStringExtra("ACTION") == "STOP"
    }

    /**
     * Creates the VirtualDisplay and ImageReader.
     */
    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay() {
        val metrics = DisplayMetrics()
        defaultDisplay.getRealMetrics(metrics)
        SharedData.displayWidth = metrics.widthPixels
        SharedData.displayHeight = metrics.heightPixels
        SharedData.displayDPI = metrics.densityDpi
        SharedData.displayDensity = metrics.density

        Log.d(tag, "Current Virtual Display at ${SharedData.displayWidth}x${SharedData.displayHeight}, DPI: ${SharedData.displayDPI}")

        imageReader = ImageReader.newInstance(
            SharedData.displayWidth,
            SharedData.displayHeight,
            PixelFormat.RGBA_8888,
            5
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            SharedData.displayWidth,
            SharedData.displayHeight,
            SharedData.displayDPI,
            getVirtualDisplayFlags(),
            imageReader?.surface,
            null,
            threadHandler
        )!!

        SharedData.isCaptureReady = true
    }

    private fun getVirtualDisplayFlags(): Int {
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    }

    private fun startMediaProjection(resultCode: Int, data: Intent) {
        Log.d(tag, "Creating and starting the MediaProjection now.")

        if (mediaProjection == null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        defaultDisplay = windowManager.defaultDisplay

        createVirtualDisplay()

        orientationChangeCallback = OrientationChangeCallback(this)
        if (orientationChangeCallback?.canDetectOrientation() == true) {
            orientationChangeCallback?.enable()
        }

        Log.d(tag, "MediaProjection Service is now running.")
    }

    private fun stopMediaProjection() {
        threadHandler.post {
            mediaProjection?.stop()
        }
    }

    /**
     * Callback for device orientation changes — recreates the VirtualDisplay.
     */
    private inner class OrientationChangeCallback(context: Context) : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            val newRotation = (windowManager.defaultDisplay.rotation)
            if (newRotation != oldRotation) {
                oldRotation = newRotation
                try {
                    virtualDisplay?.release()
                    createVirtualDisplay()
                } catch (e: Exception) {
                    Log.e(tag, "Failed to recreate VirtualDisplay after rotation: ${e.message}")
                }
            }
        }
    }

    private inner class MediaProjectionStopCallback : MediaProjection.Callback() {
        override fun onStop() {
            threadHandler.post {
                SharedData.isCaptureReady = false
                mediaProjection?.stop()
                virtualDisplay?.release()
                orientationChangeCallback?.disable()
                mediaProjection?.unregisterCallback(this)
                mediaProjection = null
                lastBitmap = null
                Log.d(tag, "MediaProjection Service has stopped.")
            }
        }
    }
}
