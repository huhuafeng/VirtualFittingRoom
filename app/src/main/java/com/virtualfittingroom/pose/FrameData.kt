package com.virtualfittingroom.pose

import android.graphics.Bitmap

/**
 * Holds all data for a single processed frame.
 * Used to pass data between pipeline threads.
 */
data class FrameData(
    val cameraBitmap: Bitmap,
    val pose: LandmarkMapper.BodyPose?,
    val segmentationMask: Bitmap?,
    val timestampMs: Long
)
