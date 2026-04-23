package com.virtualfittingroom.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseDetector(private val context: Context) {

    private var poseLandmarker: PoseLandmarker? = null
    private var useGpu = true

    var onPoseResult: ((PoseLandmarkerResult, Long) -> Unit)? = null

    fun init(): Boolean {
        return try {
            initWithDelegate(Delegate.GPU)
            true
        } catch (e: Exception) {
            Log.w("PoseDetector", "GPU delegate failed, falling back to CPU", e)
            useGpu = false
            try {
                initWithDelegate(Delegate.CPU)
                true
            } catch (e2: Exception) {
                Log.e("PoseDetector", "CPU delegate also failed", e2)
                false
            }
        }
    }

    private fun initWithDelegate(delegate: Delegate) {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("models/pose_landmarker_heavy.task")
            .setDelegate(delegate)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setOutputSegmentationMasks(true)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener(this::onResult)
            .setErrorListener(this::onError)
            .build()

        poseLandmarker = PoseLandmarker.createFromOptions(context, options)
        Log.i("PoseDetector", "PoseLandmarker initialized with ${if (useGpu) "GPU" else "CPU"} delegate")
    }

    fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        poseLandmarker?.detectAsync(mpImage, timestampMs)
    }

    private fun onResult(result: PoseLandmarkerResult, input: MPImage) {
        onPoseResult?.invoke(result, System.currentTimeMillis())
    }

    private fun onError(error: RuntimeException) {
        Log.e("PoseDetector", "Pose detection error: ${error.message}")
    }

    fun release() {
        poseLandmarker?.close()
        poseLandmarker = null
    }
}
