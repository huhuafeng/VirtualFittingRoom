package com.virtualfittingroom.pose

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Draws pose skeleton overlay — keypoints + bone connections.
 * Always visible so user can instantly see if pose detection is working.
 */
class SkeletonOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Flat array: x0,y0, x1,y1, ... (normalized 0~1), null = no detection
    private var landmarks: FloatArray? = null

    // Camera frame dimensions passed from MainActivity for FILL_CENTER coordinate transform
    private var imageWidth = 0
    private var imageHeight = 0
    private var isMirrored = false

    private val pointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    fun setImageDimensions(w: Int, h: Int) {
        imageWidth = w
        imageHeight = h
        postInvalidate()
    }

    fun setMirrored(mirrored: Boolean) {
        isMirrored = mirrored
        postInvalidate()
    }

    // Skeleton connections (MediaPipe landmark indices)
    private val connections = listOf(
        // Left arm
        11 to 13, 13 to 15,
        // Right arm
        12 to 14, 14 to 16,
        // Left leg
        23 to 25, 25 to 27,
        // Right leg
        24 to 26, 26 to 28,
        // Torso
        11 to 12, 11 to 23, 12 to 24, 23 to 24,
        // Face hints
        0 to 11, 0 to 12
    )

    fun updateLandmarks(flat: FloatArray?) {
        landmarks = flat
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lm = landmarks ?: return
        if (lm.size < 33 * 2 || imageWidth == 0 || imageHeight == 0) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val imgW = imageWidth.toFloat()
        val imgH = imageHeight.toFloat()

        // FILL_CENTER: scale to fill view, maintain aspect ratio
        val scale = maxOf(viewW / imgW, viewH / imgH)
        val displayedW = imgW * scale
        val displayedH = imgH * scale
        val offsetX = (viewW - displayedW) / 2f
        val offsetY = (viewH - displayedH) / 2f

        fun toViewX(lx: Float): Float {
            val x = if (isMirrored) 1f - lx else lx
            return x * displayedW + offsetX
        }

        fun toViewY(ly: Float): Float {
            return ly * displayedH + offsetY
        }

        // Draw bone connections
        for ((a, b) in connections) {
            val ax = toViewX(lm[a * 2])
            val ay = toViewY(lm[a * 2 + 1])
            val bx = toViewX(lm[b * 2])
            val by = toViewY(lm[b * 2 + 1])
            if (ax > 0 && bx > 0) {
                canvas.drawLine(ax, ay, bx, by, linePaint)
            }
        }

        // Draw keypoints
        val radius = 5f
        for (i in 0 until minOf(lm.size / 2, 33)) {
            val x = toViewX(lm[i * 2])
            val y = toViewY(lm[i * 2 + 1])
            if (x > 0) {
                canvas.drawCircle(x, y, radius, pointPaint)
            }
        }
    }
}
