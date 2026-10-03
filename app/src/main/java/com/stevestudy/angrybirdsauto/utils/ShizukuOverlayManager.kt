package com.stevestudy.angrybirdsauto.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import rikka.shizuku.Shizuku

/**
 * Manages SYSTEM_ALERT_WINDOW permission with a Shizuku-based fallback.
 *
 * On Red Magic OS (Android 15+), the standard overlay permission settings
 * toggle is permanently greyed out for sideloaded apps. This class detects
 * that condition and, if the user has Shizuku installed and authorized,
 * uses Shizuku to execute:
 *
 *   appops set com.stevestudy.angrybirdsauto SYSTEM_ALERT_WINDOW allow
 *
 * to natively bypass the system daemon restriction.
 *
 * Shizuku API (v13.1.5):
 *   Shizuku.checkSelfPermission()  → int  (0 = granted, non-zero = denied)
 *   Shizuku.requestPermission(int) → void (result delivered via listener)
 *   Shizuku.addRequestPermissionResultListener(listener) → void
 *   Shizuku.getBinder()            → IBinder (Shizuku service binder)
 *
 * The appops bypass works by spawning a root process via Shizuku that
 * executes the `appops` command, which directly modifies the AppOpsManager
 * mode for OP_SYSTEM_ALERT_WINDOW, bypassing the Settings UI restriction.
 */
class ShizukuOverlayManager(private val activity: Activity) {

    private val tag: String = "${com.stevestudy.angrybirdsauto.data.SharedData.loggerTag}ShizukuOverlayManager"

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 1001
        const val OVERLAY_SETTINGS_REQUEST = 1000

        private const val PERMISSION_GRANTED = 0
    }

    /** Lazily-initialized listener for Shizuku permission results. */
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST && grantResult == PERMISSION_GRANTED) {
            Log.i(tag, "Shizuku permission granted via callback.")
            // Retry the appops bypass now that Shizuku permission is granted
            if (bypassOverlayWithShizuku() && hasOverlayPermission()) {
                Toast.makeText(activity, "Overlay permission granted via Shizuku!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(activity, "Shizuku granted but appops bypass failed.", Toast.LENGTH_LONG).show()
            }
        }
    }

    init {
        // Register the permission result listener
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (e: Exception) {
            Log.w(tag, "Could not register Shizuku permission listener: ${e.message}")
        }
    }

    /**
     * Returns true if the app currently has SYSTEM_ALERT_WINDOW permission.
     */
    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(activity)

    /**
     * Returns true if Shizuku is installed and this app has permission to use it.
     * Uses checkSelfPermission() which returns PERMISSION_GRANTED (0) if available.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(tag, "Shizuku availability check failed: ${e.message}")
            false
        }
    }

    /**
     * Request Shizuku permission for this app.
     * In Shizuku 13.x, requestPermission(requestCode) internally handles
     * the Activity context. The result is delivered via the registered listener.
     */
    fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
        } catch (e: Exception) {
            Log.e(tag, "Failed to request Shizuku permission: ${e.message}")
            // Fallback: open Shizuku settings via intent
            try {
                val intent = Intent("moe.shizuku.intent.action.REQUEST_PERMISSION")
                activity.startActivityForResult(intent, SHIZUKU_PERMISSION_REQUEST)
            } catch (e2: Exception) {
                Log.e(tag, "Could not open Shizuku settings: ${e2.message}")
            }
        }
    }

    /**
     * Use Shizuku to execute `appops set <pkg> SYSTEM_ALERT_WINDOW allow`,
     * which defeats the Red Magic OS restriction on the overlay toggle.
     *
     * This spawns a root process via Shizuku that runs the appops command,
     * directly modifying the AppOpsManager mode for the app.
     *
     * @return true if the command was dispatched and exited successfully.
     */
    fun bypassOverlayWithShizuku(): Boolean {
        if (!isShizukuAvailable()) {
            Log.e(tag, "Shizuku is not available or not authorized.")
            return false
        }

        val packageName = activity.packageName
        val command = "appops set $packageName SYSTEM_ALERT_WINDOW allow"

        return try {
            // Shizuku.newProcess() spawns a process as root with Shizuku's
            // permissions. This is the recommended way to run shell commands
            // via Shizuku (the process runs with root privileges).
            //
            // We use reflection because newProcess is not part of the public API
            // in Shizuku 13.1.5, but it's stable across versions.
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true

            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh"),          // cmd: shell
                arrayOf("-c", command), // args: -c "appops set ... allow"
                "appops-bypass"         // processName
            )

            if (process != null) {
                // Wait for the command to complete
                val waitForMethod = process.javaClass.getMethod("waitFor")
                val exitCode = waitForMethod.invoke(process) as Int
                Log.i(tag, "appops command exit code: $exitCode")
                exitCode == 0
            } else {
                Log.e(tag, "Shizuku.newProcess returned null.")
                false
            }
        } catch (e: Exception) {
            Log.e(tag, "Shizuku appops bypass failed: ${e.message}", e)
            // Fallback: try direct AppOpsManager.setMode() via reflection
            // This requires the app to have the shizuku server access to
            // the hidden API, which may work if Shizuku has set up the
            // proper permissions.
            executeAppopsViaReflection(packageName)
        }
    }

    /**
     * Fallback: execute appops by reflecting on AppOpsManager.setMode() directly.
     *
     * This approach calls the hidden AppOpsManager.setMode(int op, int uid,
     * String pkg, int mode) method via reflection. It works when the calling
     * app has been granted the SHIZUKU permission which allows access to
     * otherwise-hidden system APIs.
     *
     * @param packageName The app package name to set SYSTEM_ALERT_WINDOW for.
     * @return true if the reflection call succeeded.
     */
    private fun executeAppopsViaReflection(packageName: String): Boolean {
        return try {
            // AppOpsManager.setMode is a hidden API (signature|system)
            // Shizuku grants access to hidden APIs via its server process
            val appOpsClass = Class.forName("android.app.AppOpsManager")
            val setModeMethod = appOpsClass.getMethod(
                "setMode",
                Int::class.javaPrimitiveType,    // op: OP_SYSTEM_ALERT_WINDOW = 47
                Int::class.javaPrimitiveType,    // uid: 0 (applies to package)
                String::class.java,              // pkg: package name
                Int::class.javaPrimitiveType     // mode: 0 = ALLOW, 1 = IGNORE, 2 = ERROR
            )

            // AppOpsManager is a system service accessible via Context.getSystemService("appops")
            // Shizuku allows access to this hidden service when permission is granted
            val appOps = activity.getSystemService("appops")
            if (appOps != null) {
                // OP_SYSTEM_ALERT_WINDOW = 47
                // mode 0 = MODE_ALLOWED
                setModeMethod.invoke(appOps, 47, 0, packageName, 0)
                Log.i(tag, "Successfully set SYSTEM_ALERT_WINDOW=allow via AppOpsManager reflection")
                true
            } else {
                Log.e(tag, "Could not get AppOpsManager instance.")
                false
            }
        } catch (e: Exception) {
            Log.e(tag, "AppOps reflection approach failed: ${e.message}", e)
            false
        }
    }

    /**
     * Full overlay permission flow with Shizuku fallback.
     *
     * 1. If already granted → return
     * 2. If on Red Magic OS + Shizuku available → try appops bypass
     * 3. If Shizuku permission needed → request it
     * 4. If Shizuku not available → open standard settings intent
     */
    fun requestOverlayPermission() {
        if (hasOverlayPermission()) {
            return
        }

        // Try Shizuku bypass first (works on Red Magic OS where toggle is blocked)
        if (isShizukuAvailable()) {
            if (bypassOverlayWithShizuku()) {
                if (hasOverlayPermission()) {
                    Toast.makeText(
                        activity,
                        "Overlay permission granted via Shizuku bypass!",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                Log.w(tag, "Shizuku bypass ran but overlay permission still denied.")
            } else {
                // Shizuku available but bypass failed — maybe permission not granted
                requestShizukuPermission()
                Toast.makeText(
                    activity,
                    "Shizuku permission requested. After approval, tap again.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        // Fallback to standard settings intent
        Log.d(tag, "Shizuku bypass unavailable. Opening standard overlay settings.")
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivityForResult(intent, OVERLAY_SETTINGS_REQUEST)
    }

    /**
     * Check if we're on Red Magic OS (where the overlay toggle is blocked).
     */
    fun isRedMagicOS(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val device = Build.DEVICE.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("nxp") ||
            device.contains("redmagic") ||
            device.contains("rm") ||
            brand.contains("redmagic")
    }

    /**
     * Called from Activity.onActivityResult for overlay settings / Shizuku result handling.
     * The Shizuku permission result is delivered via the registered listener
     * (OnRequestPermissionResultListener), so we only need to handle the
     * standard overlay settings intent result here.
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == OVERLAY_SETTINGS_REQUEST) {
            if (hasOverlayPermission()) {
                Toast.makeText(activity, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
            } else if (isShizukuAvailable()) {
                // Settings intent didn't work — try Shizuku bypass as last resort
                shizukuOverlayRetry()
            }
        } else if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
            // The listener handles the actual result, but we also check here
            // in case the listener was triggered but bypass hadn't run yet
            if (isShizukuAvailable()) {
                shizukuOverlayRetry()
            }
        }
    }

    /**
     * Retry the Shizuku bypass after the user has been prompted to grant
     * Shizuku permission.
     */
    fun shizukuOverlayRetry() {
        if (bypassOverlayWithShizuku() && hasOverlayPermission()) {
            Toast.makeText(activity, "Overlay permission granted via Shizuku!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                activity,
                "Could not bypass overlay restriction. Install Shizuku and grant permission.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
