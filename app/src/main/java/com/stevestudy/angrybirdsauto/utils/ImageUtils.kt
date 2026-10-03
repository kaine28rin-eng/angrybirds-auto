package com.stevestudy.angrybirdsauto.utils

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

/**
 * Utility functions for image processing — converting MediaProjection captures to Bitmaps,
 * and providing helpers for image matching.
 */
object ImageUtils {

    /**
     * Convert a YUV_420_888 Image (from MediaProjection ImageReader) to an ARGB_8888 Bitmap.
     *
     * @param image The Image from ImageReader.
     * @return An ARGB_8888 Bitmap.
     */
    fun imageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height

        val yBuffer: ByteBuffer = image.planes[0].buffer  // Y
        val uBuffer: ByteBuffer = image.planes[1].buffer  // U
        val vBuffer: ByteBuffer = image.planes[2].buffer  // V

        val yPixelStride = image.planes[0].pixelStride
        val uPixelStride = image.planes[1].pixelStride
        val vPixelStride = image.planes[2].pixelStride

        val yRowStride = image.planes[0].rowStride
        val uRowStride = image.planes[1].rowStride
        val vRowStride = image.planes[2].rowStride

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Y plane
        yBuffer.get(nv21, 0, ySize)

        // VU plane (NV21 format expects V before U, interleaved)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        // Use YuvImage if available, or convert manually
        return yuv420ToBitmap(nv21, width, height, yRowStride, uRowStride, vRowStride, yPixelStride)
    }

    /**
     * Manually convert NV21 (Y + VU interleaved) to ARGB Bitmap.
     */
    private fun yuv420ToBitmap(
        nv21: ByteArray,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        vRowStride: Int,
        yPixelStride: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888)
        val pixels = IntArray(width * height)

        val ySize = width * height
        // NV21: Y plane is first, then VU interleaved (V first, U second)
        // uvRowStride for NV21 is typically width for Y and width for subsampled UV

        // For standard NV21 with equal strides:
        val chromaWidth = width / 2
        val chromaHeight = height / 2

        for (y in 0 until height) {
            for (x in 0 until width) {
                val yIndex = y * yRowStride + x * yPixelStride
                if (yIndex >= nv21.size) continue
                var ya = (0xff and nv21[yIndex].toInt())
                ya = (ya * 255) / 255  // Y is already 0-255 in this format

                // UV indices
                val uvY = y / 2
                val uvX = x / 2
                val vuIndex = ySize + uvY * uvRowStride + uvX * 2  // VU: V first

                if (vuIndex + 1 < nv21.size) {
                    var v = (0xff and nv21[vuIndex].toInt()) - 128
                    var u = (0xff and nv21[vuIndex + 1].toInt()) - 128

                    // BT.601 conversion
                    val y2 = ya - 16
                    val r = (298 * y2 + 409 * v + 128) / 256
                    val g = (298 * y2 - 100 * u - 208 * v + 128) / 256
                    val b = (298 * y2 + 516 * u + 128) / 256

                    val rFinal = r.coerceIn(0, 255)
                    val gFinal = g.coerceIn(0, 255)
                    val bFinal = b.coerceIn(0, 255)

                    pixels[y * width + x] = (0xff shl 24) or (rFinal shl 16) or (gFinal shl 8) or bFinal
                } else {
                    // Grayscale fallback
                    pixels[y * width + x] = (0xff shl 24) or (ya shl 16) or (ya shl 8) or ya
                }
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Convert direct RGBA bytes from a ByteBuffer to an ARGB Bitmap.
     * Used when the capture pipeline provides RGBA_8888 directly.
     */
    fun rgbaBufferToBitmap(rgba: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in 0 until width * height) {
            val r = rgba[i * 4].toInt() and 0xff
            val g = rgba[i * 4 + 1].toInt() and 0xff
            val b = rgba[i * 4 + 2].toInt() and 0xff
            val a = rgba[i * 4 + 3].toInt() and 0xff
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Scale a Bitmap by a given factor.
     */
    fun scaleBitmap(bitmap: Bitmap, scale: Double): Bitmap {
        val newW = Math.max(1, (bitmap.width * scale).toInt())
        val newH = Math.max(1, (bitmap.height * scale).toInt())
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    /**
     * Crop a region from a Bitmap safely with bounds clamping.
     */
    fun safeCrop(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap? {
        val clampedX = Math.max(0, x)
        val clampedY = Math.max(0, y)
        val clampedW = Math.min(w, bitmap.width - clampedX)
        val clampedH = Math.min(h, bitmap.height - clampedY)
        if (clampedW <= 0 || clampedH <= 0) return null
        return Bitmap.createBitmap(bitmap, clampedX, clampedY, clampedW, clampedH)
    }
}
