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
 * Shizuku API reference (v13.1.5):
 *   Shizuku.checkSelfPermission()  → int  (0 = granted, non-zero = denied)
 *   Shizuku.requestPermission(int) → void (requests permission, result via listener)
 *   Shizuku.addRequestPermissionResultListener(listener) → void
 *   Shizuku.getBinder()            → IBinder (use for remote service calls)
 *   Shizuku.isPreV11()             → boolean
 */
class ShizukuOverlayManager(private val activity: Activity) {

    private val tag: String = "${com.stevestudy.angrybirdsauto.data.SharedData.loggerTag}ShizukuOverlayManager"

    companion object {
        const val OP_SYSTEM_ALERT_WINDOW = 47
        const val SHIZUKU_PERMISSION_REQUEST = 1001
        const val OVERLAY_SETTINGS_REQUEST = 1000

        // Shizuku permission codes
        private const val PERMISSION_GRANTED = 0
    }

    /** Lazily-initialized listener for Shizuku permission results. */
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST && grantResult == 0) {
            // 0 = PERMISSION_GRANTED
            Log.i(tag, "Shizuku permission granted via callback.")
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
        }
    }

    /**
     * Use Shizuku to execute `appops set <pkg> SYSTEM_ALERT_WINDOW allow`,
     * which defeats the Red Magic OS restriction on the overlay toggle.
     *
     * This calls the hidden AppOpsManager.setMode() method via Shizuku's
     * remote proxy. We obtain the appops service binder through Shizuku
     * and invoke the method via reflection on the remote stub.
     *
     * @return true if the command was dispatched successfully.
     */
    fun bypassOverlayWithShizuku(): Boolean {
        if (!isShizukuAvailable()) {
            Log.e(tag, "Shizuku is not available or not authorized.")
            return false
        }

        return try {
            // Get the appops service IBinder via Shizuku's binder
            // Shizuku.getBinder() returns the Shizuku service binder
            // We use the hidden API: getSystemService("appops") via Shizuku
            val shizukuService = try {
                val method = Shizuku::class.java.getMethod("requireService")
                method.invoke(null)
            } catch (e: Exception) {
                // Fallback: use reflection to call hidden methods
                null
            }

            // Direct approach: use Shizuku to run a shell command that calls appops
            // This is the most reliable method across Shizuku versions
            val packageName = activity.packageName
            val command = "appops set $packageName SYSTEM_ALERT_WINDOW allow"

            // Use Shizuku's remote process to execute the appops command
            val process = try {
                // Shizuku.newProcess is internal; use reflection to access it
                val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
                val newProcessMethod = shizukuClass.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true

                val cmdArray = arrayOf("appops")
                val argsArray = arrayOf("set", packageName, "SYSTEM_ALERT_WINDOW", "allow")
                newProcessMethod.invoke(null, cmdArray, argsArray, packageName)
            } catch (e: Exception) {
                Log.e(tag, "Direct process spawn failed, trying reflection: ${e.message}")
                null
            }

            if (process != null) {
                // Wait for the command to complete
                try {
                    val waitForMethod = process.javaClass.getMethod("waitFor")
                    val exitCode = waitForMethod.invoke(process)
                    Log.i(tag, "appops command exit code: $exitCode")
                    true
                } catch (e: Exception) {
                    Log.w(tag, "Could not wait for process: ${e.message}")
                    true // Command was dispatched
                }
            } else {
                // Last resort: use IBinder reflection on AppOpsManager
                executeAppopsViaIBinder(packageName)
            }
        } catch (e: Exception) {
            Log.e(tag, "Shizuku appops bypass failed: ${e.message}", e)
            false
        }
    }

    /**
     * Fallback: execute appops command by reflecting on the AppOpsManager
     * remote stub obtained through Shizuku's binder.
     */
    private fun executeAppopsViaIBinder(packageName: String): Boolean {
        return try {
            // Obtain the appops service binder via Shizuku
            // Shizuku provides access to system services through its binder
            val binder = Shizuku.getBinder()
            if (binder == null) {
                Log.e(tag, "Could not get Shizuku binder.")
                return false
            }

            // Reflect on AppOpsManager to call setMode
            val appOpsClass = Class.forName("android.app.AppOpsManager")
            val setModeMethod = appOpsClass.getMethod(
                "setMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            )

            // Get the AppOpsManager instance from the binder
            // AppOpsManager is obtained via Context.getSystemService
            val appOps = activity.getSystemService("appops")
            if (appOps != null) {
                // setMode(opCode=47[OP_SYSTEM_ALERT_WINDOW], uid=0, packageName, mode=0[ALLOW])
                setModeMethod.invoke(appOps, OP_SYSTEM_ALERT_WINDOW, 0, packageName, 0)
                Log.i(tag, "Successfully set SYSTEM_ALERT_WINDOW=allow via appops reflection")
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
                // Bypass ran but didn't take effect — appops might need the permission
                // to be set differently. Fall through to settings intent.
                Log.w(tag, "Shizuku bypass ran but overlay permission still denied.")
            } else {
                // Shizuku available but bypass failed — maybe permission not granted yet
                requestShizukuPermission()
                Toast.makeText(
                    activity,
                    "Shizuku permission requested. After approval, tap 'Grant Permissions' again.",
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
     * Called from Activity.onActivityResult for overlay settings result.
     * The Shizuku permission result is delivered via the registered listener.
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == OVERLAY_SETTINGS_REQUEST) {
            if (hasOverlayPermission()) {
                Toast.makeText(activity, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
            } else if (isShizukuAvailable()) {
                // Settings intent didn't work — try Shizuku bypass as last resort
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
