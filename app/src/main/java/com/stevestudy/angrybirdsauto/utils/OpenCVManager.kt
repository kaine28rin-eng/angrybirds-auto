package com.stevestudy.angrybirdsauto.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core

/**
 * Manages OpenCV static initialization for the Angry Birds Auto bot.
 *
 * The REDMAGIC 11 Pro ships Android 15+ with a pure 64-bit (arm64-v8a)
 * processor. The previous build crashed with "OpenCV failed to load!"
 * because:
 *   1. 32-bit (armeabi-v7a) .so files were bundled and the system tried
 *      the fallback, which failed on a 64-bit-only device.
 *   2. OpenCV initialization relied on the external OpenCV Manager app
 *      via OpenCVLoader.initAsync(), which may not be installed on
 *      Chinese ROMs (Red Magic OS) due to missing Google Play Services.
 *
 * This manager:
 *   - Uses OpenCVLoader.initDebug() for static initialization (no external
 *     app dependency).
 *   - Only runs on arm64-v8a — the build.gradle.kts NDK config enforces
 *     abiFilters("arm64-v8a") so libopencv_java4.so is the only native
 *     library packaged.
 */
object OpenCVManager {

    private val tag = "${com.stevestudy.angrybirdsauto.data.SharedData.loggerTag}OpenCVManager"

    /**
     * Initialize OpenCV with static loading.
     *
     * Call this from the main activity's onCreate() before any OpenCV
     * operations (e.g., Mat processing, template matching).
     *
     * @param context      The application or activity context.
     * @param onReady      Callback invoked when OpenCV is ready (or fails).
     */
    fun init(context: Context, onReady: (Boolean) -> Unit) {
        Log.i(tag, "Initializing OpenCV with static loader (initDebug) for arm64-v8a...")

        try {
            // OpenCVLoader.initDebug() loads libopencv_java4.so statically
            // from the APK's jniLibs. No external OpenCV Manager app needed.
            // This is the recommended approach per OpenCV Android docs.
            val loaded = OpenCVLoader.initDebug()

            if (loaded) {
                Log.i(tag, "OpenCV loaded successfully via initDebug(). OpenCV version: ${Core.VERSION}")
                // Verify Core is functional
                val version = Core.VERSION
                Log.d(tag, "OpenCV runtime version: $version")
                Toast.makeText(context, "OpenCV loaded: v$version", Toast.LENGTH_SHORT).show()
                onReady(true)
            } else {
                Log.e(tag, "OpenCVLoader.initDebug() returned false — native library failed to load.")
                Toast.makeText(
                    context,
                    "OpenCV failed to load! Ensure libopencv_java4.so is in jniLibs/arm64-v8a/.",
                    Toast.LENGTH_LONG
                ).show()
                onReady(false)
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(tag, "UnsatisfiedLinkError: OpenCV native library not found.", e)
            Toast.makeText(
                context,
                "OpenCV native library missing! Check jniLibs/arm64-v8a/.",
                Toast.LENGTH_LONG
            ).show()
            onReady(false)
        } catch (e: Exception) {
            Log.e(tag, "OpenCV initialization error: ${e.message}", e)
            Toast.makeText(context, "OpenCV init error: ${e.message}", Toast.LENGTH_LONG).show()
            onReady(false)
        }
    }
}
