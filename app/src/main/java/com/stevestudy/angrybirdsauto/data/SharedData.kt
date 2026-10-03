package com.stevestudy.angrybirdsauto.data

/**
 * Global shared data used across the app's services and utilities.
 * Values are populated at runtime from the device's DisplayMetrics.
 */
object SharedData {
    const val loggerTag: String = "AngryBirdsAuto::"

    // Device display metrics.
    var displayWidth: Int = 0
    var displayHeight: Int = 0
    var displayDPI: Int = 0
    var displayDensity: Float = 0f

    // Baseline resolution used for relative coordinate conversion.
    val baselineWidth: Int = 1080
    val baselineHeight: Int = 2400

    // Template matching settings.
    var templateSubfolderPathName: String = "templates"
    var templateImageExt: String = "png"
    var matchMethod: Int = org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED
    var confidence: Double = 0.75
    var confidenceAll: Double = 0.65
    var customScale: Double = 1.0
    var debugMode: Boolean = false

    // Bot runtime state.
    @Volatile
    var isRunning: Boolean = false
    var isAccessibilityServiceConnected: Boolean = false
    @Volatile
    var isCaptureReady: Boolean = false
    var mainPackagePath: String = ""

    // Overlay button size in DP.
    var overlayButtonSizeDP: Float = 72f
    var overlayDismissButtonSizeDP: Float = 64f
}
