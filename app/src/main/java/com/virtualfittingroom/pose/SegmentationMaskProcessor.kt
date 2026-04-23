package com.virtualfittingroom.pose

import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

/**
 * Processes the raw segmentation mask from MediaPipe PoseLandmarker
 * into a usable Bitmap for blending.
 */
class SegmentationMaskProcessor {

    private var prevMask1: Bitmap? = null
    private var prevMask2: Bitmap? = null

    /**
     * Convert FloatBuffer mask to Bitmap and apply post-processing.
     * @param maskBuffer The raw mask from PoseLandmarkerResult (values 0.0~1.0)
     * @param width Mask width
     * @param height Mask height
     * @return Processed binary mask Bitmap (ARGB_8888, white=person, black=background)
     */
    fun processMask(maskBuffer: FloatBuffer, width: Int, height: Int): Bitmap {
        val mask = floatBufferToBitmap(maskBuffer, width, height)
        val binary = applyThreshold(mask, THRESHOLD)
        val blurred = applyGaussianBlur(binary)
        val dilated = applyDilate(blurred)
        val smoothed = temporalSmooth(dilated)

        // Recycle intermediate bitmaps
        if (mask !== smoothed && mask !== binary) mask.recycle()
        if (binary !== smoothed && binary !== blurred) binary.recycle()
        if (blurred !== smoothed) blurred.recycle()
        if (dilated !== smoothed) dilated.recycle()

        return smoothed
    }

    private fun floatBufferToBitmap(buffer: FloatBuffer, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        buffer.rewind()
        for (i in 0 until width * height) {
            val value = buffer.get()
            val alpha = (value.coerceIn(0f, 1f) * 255).toInt()
            pixels[i] = (alpha shl 24) or 0x00FFFFFF
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun applyThreshold(mask: Bitmap, threshold: Float): Bitmap {
        val width = mask.width
        val height = mask.height
        val pixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)

        val thresholdAlpha = (threshold * 255).toInt()
        for (i in pixels.indices) {
            val alpha = (pixels[i] shr 24) and 0xFF
            pixels[i] = if (alpha > thresholdAlpha) {
                0xFFFFFFFF.toInt()
            } else {
                0x00000000
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun applyGaussianBlur(bitmap: Bitmap): Bitmap {
        // Simple box blur approximation (3x3 kernel)
        val width = bitmap.width
        val height = bitmap.height
        val src = IntArray(width * height)
        val dst = IntArray(width * height)
        bitmap.getPixels(src, 0, width, 0, 0, width, height)

        val radius = BLUR_RADIUS
        for (y in radius until height - radius) {
            for (x in radius until width - radius) {
                var sumAlpha = 0
                var count = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val idx = (y + dy) * width + (x + dx)
                        sumAlpha += (src[idx] shr 24) and 0xFF
                        count++
                    }
                }
                val avgAlpha = (sumAlpha / count).coerceIn(0, 255)
                dst[y * width + x] = (avgAlpha shl 24) or 0x00FFFFFF
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(dst, 0, width, 0, 0, width, height)
        return result
    }

    private fun applyDilate(bitmap: Bitmap): Bitmap {
        // Simple dilation (expand white regions)
        val width = bitmap.width
        val height = bitmap.height
        val src = IntArray(width * height)
        val dst = src.copyOf()
        bitmap.getPixels(src, 0, width, 0, 0, width, height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val hasPerson = ((src[idx] shr 24) and 0xFF) > 128
                if (hasPerson) {
                    // Dilate: set neighbors to white
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nIdx = (y + dy) * width + (x + dx)
                            if (nIdx in dst.indices) {
                                dst[nIdx] = 0xFFFFFFFF.toInt()
                            }
                        }
                    }
                }
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(dst, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Temporal smoothing: average with previous 2 frames to reduce flicker.
     */
    private fun temporalSmooth(current: Bitmap): Bitmap {
        val width = current.width
        val height = current.height

        if (prevMask1 == null || prevMask1!!.width != width || prevMask1!!.height != height) {
            prevMask1?.recycle()
            prevMask2?.recycle()
            prevMask1 = current.copy(Bitmap.Config.ARGB_8888, false)
            prevMask2 = null
            return current
        }

        val curPixels = IntArray(width * height)
        val p1Pixels = IntArray(width * height)
        current.getPixels(curPixels, 0, width, 0, 0, width, height)
        prevMask1!!.getPixels(p1Pixels, 0, width, 0, 0, width, height)

        val resultPixels = IntArray(width * height)
        val hasPrev2 = prevMask2 != null && prevMask2!!.width == width && prevMask2!!.height == height
        val p2Pixels = if (hasPrev2) IntArray(width * height) else null
        if (hasPrev2) {
            prevMask2!!.getPixels(p2Pixels, 0, width, 0, 0, width, height)
        }

        for (i in curPixels.indices) {
            val a0 = (curPixels[i] shr 24) and 0xFF
            val a1 = (p1Pixels[i] shr 24) and 0xFF
            val avg = if (hasPrev2) {
                val a2 = (p2Pixels!![i] shr 24) and 0xFF
                (a0 + a1 + a2) / 3
            } else {
                (a0 + a1) / 2
            }
            resultPixels[i] = (avg.coerceIn(0, 255) shl 24) or 0x00FFFFFF
        }

        // Shift buffers
        prevMask2?.recycle()
        prevMask2 = prevMask1
        prevMask1 = current.copy(Bitmap.Config.ARGB_8888, false)

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    fun release() {
        prevMask1?.recycle()
        prevMask2?.recycle()
        prevMask1 = null
        prevMask2 = null
    }

    companion object {
        private const val THRESHOLD = 0.5f
        private const val BLUR_RADIUS = 2
    }
}
