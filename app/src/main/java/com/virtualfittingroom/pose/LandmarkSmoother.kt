package com.virtualfittingroom.pose

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Applies exponential moving average smoothing to pose landmarks
 * to reduce jitter between frames.
 */
class LandmarkSmoother(private val alpha: Float = 0.3f) {

    private var smoothedLandmarks: List<NormalizedLandmark>? = null

    fun smooth(result: PoseLandmarkerResult): PoseLandmarkerResult {
        val currentLandmarks = result.landmarks()
        if (currentLandmarks.isEmpty()) {
            smoothedLandmarks = null
            return result
        }

        val current = currentLandmarks[0]
        val prev = smoothedLandmarks

        if (prev == null || prev.size != current.size) {
            smoothedLandmarks = current.map { NormalizedLandmark.create(it.x(), it.y(), it.z()) }
            return result
        }

        val smoothed = current.mapIndexed { index, landmark ->
            val p = prev[index]
            val sx = alpha * landmark.x() + (1 - alpha) * p.x()
            val sy = alpha * landmark.y() + (1 - alpha) * p.y()
            val sz = alpha * landmark.z() + (1 - alpha) * p.z()
            NormalizedLandmark.create(sx, sy, sz)
        }

        smoothedLandmarks = smoothed

        // Return new result with smoothed landmarks
        // PoseLandmarkerResult is immutable, we create a wrapper approach
        // by storing smoothed landmarks separately for consumers
        return result
    }

    fun getSmoothedLandmarks(): List<NormalizedLandmark>? = smoothedLandmarks

    fun reset() {
        smoothedLandmarks = null
    }
}
