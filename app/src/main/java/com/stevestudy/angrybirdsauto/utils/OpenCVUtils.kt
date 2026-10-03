package com.stevestudy.angrybirdsauto.utils

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.core.MatOfPoint

/**
 * Performs OpenCV-based template matching on Android Bitmaps.
 * Uses the same TM_CCOEFF_NORMED approach as the reference implementation.
 */
class OpenCVUtils {

    /**
     * Match a template against the source bitmap, returning the best match location.
     *
     * @param source The full screenshot.
     * @param template The template image (from assets).
     * @param region Optional region (x, y, width, height) to crop the source. Pass null for full image.
     * @param scale Target scale to resize the template to (1.0 = original size).
     * @return Pair of (matched: Boolean, location: Point? — center of match).
     */
    fun matchBitmap(
        source: Bitmap,
        template: Bitmap,
        region: IntArray? = null,
        scale: Double = 1.0
    ): Pair<Boolean, Point?> {
        val srcBitmap = if (region != null && region.size == 4) {
            ImageUtils.safeCrop(source, region[0], region[1], region[2], region[3]) ?: source
        } else {
            source
        }

        val srcMat = Mat()
        var tmplMat = Mat()
        Utils.bitmapToMat(srcBitmap, srcMat)
        Utils.bitmapToMat(template, tmplMat)

        // Scale template if needed
        if (scale != 1.0) {
            Imgproc.resize(tmplMat, tmplMat, Size(tmplMat.cols() * scale, tmplMat.rows() * scale))
        }

        // Clamp template dimensions
        if (tmplMat.cols() > srcMat.cols() || tmplMat.rows() > srcMat.rows()) {
            val clampedW = Math.min(tmplMat.cols(), srcMat.cols())
            val clampedH = Math.min(tmplMat.rows(), srcMat.rows())
            val roi = Rect(0, 0, clampedW, clampedH)
            tmplMat = tmplMat.submat(roi)
        }

        // Convert to grayscale
        Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_BGR2GRAY)
        if (tmplMat.channels() > 1) {
            Imgproc.cvtColor(tmplMat, tmplMat, Imgproc.COLOR_BGR2GRAY)
        }

        // MatchTemplate
        val resultCols = srcMat.cols() - tmplMat.cols() + 1
        val resultRows = srcMat.rows() - tmplMat.rows() + 1
        if (resultCols <= 0 || resultRows <= 0) {
            releaseMats(srcMat, tmplMat)
            return Pair(false, null)
        }

        val resultMat = Mat(resultRows, resultCols, CvType.CV_32FC1)
        Imgproc.matchTemplate(srcMat, tmplMat, resultMat, Imgproc.TM_CCOEFF_NORMED)

        val mmr = Core.minMaxLoc(resultMat)
        val threshold = 0.75

        val matchLocation = if (mmr.maxVal >= threshold) {
            // Center the match location
            Point(mmr.maxLoc.x + tmplMat.cols() / 2.0, mmr.maxLoc.y + tmplMat.rows() / 2.0)
        } else {
            null
        }

        releaseMats(srcMat, tmplMat, resultMat)
        return Pair(matchLocation != null, matchLocation)
    }

    /**
     * Find ALL matches of a template in the source bitmap, above a confidence threshold.
     * Uses the same rectangle-blocking approach as the reference implementation.
     *
     * @param source The full screenshot.
     * @param template The template image.
     * @param minConfidence Minimum match confidence (0.0 to 1.0).
     * @return List of Point objects (center of each match).
     */
    fun findAllMatches(
        source: Bitmap,
        template: Bitmap,
        minConfidence: Double = 0.65
    ): List<Point> {
        val srcMat = Mat()
        val tmplMat = Mat()
        Utils.bitmapToMat(source, srcMat)
        Utils.bitmapToMat(template, tmplMat)

        if (tmplMat.cols() > srcMat.cols() || tmplMat.rows() > srcMat.rows()) {
            releaseMats(srcMat, tmplMat)
            return emptyList()
        }

        Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_BGR2GRAY)
        if (tmplMat.channels() > 1) {
            Imgproc.cvtColor(tmplMat, tmplMat, Imgproc.COLOR_BGR2GRAY)
        }

        val resultCols = srcMat.cols() - tmplMat.cols() + 1
        val resultRows = srcMat.rows() - tmplMat.rows() + 1
        if (resultCols <= 0 || resultRows <= 0) {
            releaseMats(srcMat, tmplMat)
            return emptyList()
        }

        val resultMat = Mat(resultRows, resultCols, CvType.CV_32FC1)
        Imgproc.matchTemplate(srcMat, tmplMat, resultMat, Imgproc.TM_CCOEFF_NORMED)

        val matches = mutableListOf<Point>()

        while (true) {
            val mmr = Core.minMaxLoc(resultMat)
            if (mmr.maxVal < minConfidence) break

            // Center the match
            val matchCenter = Point(
                mmr.maxLoc.x + tmplMat.cols() / 2.0,
                mmr.maxLoc.y + tmplMat.rows() / 2.0
            )
            matches.add(matchCenter)

            // Draw a black rectangle over the match area to prevent re-matching
            Imgproc.rectangle(
                srcMat,
                mmr.maxLoc,
                Point(mmr.maxLoc.x + tmplMat.cols(), mmr.maxLoc.y + tmplMat.rows()),
                Scalar(0.0, 0.0, 0.0),
                20
            )
        }

        releaseMats(srcMat, tmplMat, resultMat)
        return matches
    }

    /**
     * Perform color-based heuristic detection for pigs.
     * Pigs in Angry Birds are green — detect pixels in the green HSV range.
     * Also detects the slingshot (brown wooden color).
     *
     * @param source The full screenshot.
     * @param targetType "pig" or "slingshot"
     * @return List of Point center coordinates for detected objects.
     */
    fun detectByColor(source: Bitmap, targetType: String): List<Point> {
        val srcMat = Mat()
        Utils.bitmapToMat(source, srcMat)

        Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGB2HSV)

        val hsvMat = Mat()
        val lowerBound: Scalar
        val upperBound: Scalar
        val minArea: Double

        when (targetType) {
            "pig" -> {
                // Green range: Hue 35-85, Saturation 40-255, Value 40-255
                lowerBound = Scalar(35.0, 40.0, 40.0)
                upperBound = Scalar(85.0, 255.0, 255.0)
                minArea = 200.0
            }
            "slingshot" -> {
                // Brown/wood range: Hue 10-20, Saturation 50-200, Value 20-150
                lowerBound = Scalar(10.0, 50.0, 20.0)
                upperBound = Scalar(20.0, 200.0, 150.0)
                minArea = 500.0
            }
            "bird" -> {
                // Birds vary by type; common colors: red (Hue 0-10), blue (100-130), yellow (20-35)
                lowerBound = Scalar(0.0, 50.0, 50.0)
                upperBound = Scalar(35.0, 255.0, 255.0)
                minArea = 300.0
            }
            else -> {
                releaseMats(srcMat)
                return emptyList()
            }
        }

        Core.inRange(srcMat, lowerBound, upperBound, hsvMat)

        // Find contours
        val hierarchy = Mat()
        val contourList = mutableListOf<MatOfPoint>()
        Imgproc.findContours(hsvMat, contourList, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val results = mutableListOf<Point>()
        for (contour in contourList) {
            val area = Imgproc.contourArea(contour)
            if (area >= minArea) {
                val moments = Imgproc.moments(contour)
                if (moments.m00 != 0.0) {
                    val cx = moments.m10 / moments.m00
                    val cy = moments.m01 / moments.m00
                    results.add(Point(cx, cy))
                }
            }
        }

        releaseMats(srcMat, hsvMat)
        return results
    }

    private fun releaseMats(vararg mats: Mat) {
        for (mat in mats) {
            if (mat != null && !mat.empty()) {
                mat.release()
            }
        }
    }
}
