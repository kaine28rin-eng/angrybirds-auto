package com.stevestudy.angrybirdsauto.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.stevestudy.angrybirdsauto.R
import com.stevestudy.angrybirdsauto.automation.AutoAccessibilityService
import com.stevestudy.angrybirdsauto.automation.BotService
import com.stevestudy.angrybirdsauto.automation.MediaProjectionService
import com.stevestudy.angrybirdsauto.data.SharedData
import org.opencv.android.OpenCVLoader

/**
 * Main Activity — handles permission flow and starts the automation services.
 *
 * Flow:
 * 1. Load OpenCV
 * 2. Grant Permissions → overlay + screen capture + accessibility
 * 3. Start Bot Service → creates floating overlay button
 * 4. Open Angry Birds → tap the floating play button to auto-play
 */
class MainActivity : AppCompatActivity() {

    // The MediaProjection data Intent is held in a static field so it survives
    // activity restarts. It's passed directly to the service when starting it.
    companion object {
        @JvmStatic
        private var mediaProjectionIntent: Intent? = null

        @JvmStatic
        private var mediaProjectionResultCode: Int = 0
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data != null) {
                mediaProjectionIntent = data
                mediaProjectionResultCode = result.resultCode

                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("media_projection_permission", true)
                    .apply()

                // Start MediaProjectionService with the captured Intent
                val serviceIntent = MediaProjectionService.getStartIntent(
                    this, mediaProjectionResultCode, mediaProjectionIntent!!
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                // Start BotService (creates the floating overlay button)
                val botIntent = Intent(this, BotService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(botIntent)
                } else {
                    startService(botIntent)
                }

                Toast.makeText(
                    this,
                    "Permissions granted! Open Angry Birds and tap the floating play button.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
        }
    }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkAccessibilityAndRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        setContentView(if (isLandscape) R.layout.activity_main_land else R.layout.activity_main_port)

        // Load OpenCV
        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV failed to load!", Toast.LENGTH_LONG).show()
        }

        // Initialize SharedData with device metrics
        val metrics = resources.displayMetrics
        SharedData.displayWidth = metrics.widthPixels
        SharedData.displayHeight = metrics.heightPixels
        SharedData.displayDPI = metrics.densityDpi
        SharedData.displayDensity = metrics.density

        // Restore MediaProjection intent if it was saved
        if (mediaProjectionIntent == null) {
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            if (prefs.getBoolean("media_projection_permission", false)) {
                // MediaProjection permission was granted before but Intent wasn't retained
                // User needs to re-grant it
                prefs.edit().putBoolean("media_projection_permission", false).apply()
            }
        }

        setupButtons()
        refreshStatus()
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btn_setup).setOnClickListener { startPermissionFlow() }
        findViewById<Button>(R.id.btn_start_bot).setOnClickListener { startBotServices() }
        findViewById<Button>(R.id.btn_stop_bot).setOnClickListener { stopBotServices() }
    }

    private fun refreshStatus() {
        val statusText = findViewById<TextView>(R.id.tv_status)
        statusText.text = buildStatusString()
    }

    private fun buildStatusString(): String {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk = AutoAccessibilityService.isServiceConnected
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val mediaOk = prefs.getBoolean("media_projection_permission", false) && mediaProjectionIntent != null

        return "Overlay: ${if (overlayOk) "✓" else "✗"}\n" +
            "Accessibility: ${if (accessOk) "✓" else "✗"}\n" +
            "Screen Capture: ${if (mediaOk) "✓" else "✗"}"
    }

    private fun startPermissionFlow() {
        // 1. Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
            )
        }

        // 2. Accessibility service
        if (!AutoAccessibilityService.isServiceConnected) {
            Toast.makeText(this, "Enable Accessibility Service in Settings", Toast.LENGTH_LONG).show()
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 3. Screen capture permission — this is the last step
        // It must be requested after the other permissions, because the
        // user needs to see the confirmation dialog
        if (!Settings.canDrawOverlays(this) || !AutoAccessibilityService.isServiceConnected) {
            Toast.makeText(
                this,
                "First grant overlay + accessibility, then tap 'Grant Permissions' again for screen capture.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Request screen capture
        val captureIntent = MediaProjectionService.getScreenCaptureIntent(this)
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startBotServices() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val hasMediaPermission = prefs.getBoolean("media_projection_permission", false) && mediaProjectionIntent != null

        if (!hasMediaPermission) {
            Toast.makeText(this, "Grant screen capture permission first!", Toast.LENGTH_SHORT).show()
            startPermissionFlow()
            return
        }

        // Start MediaProjection service if not already running
        val mpIntent = MediaProjectionService.getStartIntent(this, mediaProjectionResultCode, mediaProjectionIntent!!)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(mpIntent)
        } else {
            startService(mpIntent)
        }

        // Start BotService
        val botIntent = Intent(this, BotService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(botIntent)
        } else {
            startService(botIntent)
        }

        Toast.makeText(
            this,
            "Bot services started! Open Angry Birds and tap the floating play button.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun stopBotServices() {
        stopService(Intent(this, BotService::class.java))
        stopService(Intent(this, MediaProjectionService::class.java))
        Toast.makeText(this, "Bot services stopped.", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun checkAccessibilityAndRefresh() {
        Toast.makeText(this, "Accessibility service status updated.", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }
}
