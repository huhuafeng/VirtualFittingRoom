package com.virtualfittingroom.pose

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Overlay view that draws detected pose landmarks and segmentation mask
 * on top of the camera preview for debugging and visualization.
 */
class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var landmarks: List<NormalizedLandmark>? = null
    private var segmentationMask: Bitmap? = null
    private var showDebug = true

    private val landmarkPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val maskPaint = Paint().apply {
        colorFilter = PorterDuffColorFilter(Color.argb(80, 0, 255, 0), PorterDuff.Mode.SRC_ATOP)
    }

    // Skeleton connections
    private val connections = listOf(
        // Left arm
        intArrayOf(11, 13), intArrayOf(13, 15),
        // Right arm
        intArrayOf(12, 14), intArrayOf(14, 16),
        // Left leg
        intArrayOf(23, 25), intArrayOf(25, 27),
        // Right leg
        intArrayOf(24, 26), intArrayOf(26, 28),
        // Torso
        intArrayOf(11, 12), intArrayOf(11, 23), intArrayOf(12, 24), intArrayOf(23, 24),
        // Face
        intArrayOf(0, 11), intArrayOf(0, 12)
    )

    fun updatePose(landmarks: List<NormalizedLandmark>?) {
        this.landmarks = landmarks
        if (showDebug) {
            postInvalidate()
        }
    }

    fun updateSegmentationMask(mask: Bitmap?) {
        this.segmentationMask = mask
    }

    fun setShowDebug(show: Boolean) {
        showDebug = show
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showDebug) return

        // Draw segmentation mask
        segmentationMask?.let { mask ->
            val scaled = Bitmap.createScaledBitmap(mask, width, height, true)
            canvas.drawBitmap(scaled, 0f, 0f, maskPaint)
            scaled.recycle()
        }

        // Draw skeleton lines
        landmarks?.let { lm ->
            for (conn in connections) {
                val p1 = lm.getOrNull(conn[0])
                val p2 = lm.getOrNull(conn[1])
                if (p1 != null && p2 != null && p1.x() >= 0 && p2.x() >= 0) {
                    canvas.drawLine(
                        p1.x() * width, p1.y() * height,
                        p2.x() * width, p2.y() * height,
                        linePaint
                    )
                }
            }

            // Draw landmark points
            for (point in lm) {
                if (point.x() >= 0) {
                    canvas.drawCircle(
                        point.x() * width,
                        point.y() * height,
                        6f,
                        landmarkPaint
                    )
                }
            }
        }
    }
}
