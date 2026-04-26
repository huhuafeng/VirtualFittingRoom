package com.virtualfittingroom.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Combined pose detection, landmark mapping, smoothing, and segmentation mask processing.
 */
class PoseTracker(private val context: Context) {

    companion object {
        private const val TAG = "PoseTracker"
        private const val MODEL_PATH = "models/pose_landmarker_heavy.task"
        private const val SMOOTH_ALPHA = 0.35f
        private const val MAX_MISSING_FRAMES = 5
    }

    // Data classes for pose output
    data class Point(val x: Float, val y: Float) {
        fun toPixel(w: Int, h: Int) = PixelPoint((x * w).toInt(), (y * h).toInt())
    }

    data class PixelPoint(val x: Int, val y: Int)

    data class BodyPose(
        val nose: Point,
        val leftShoulder: Point,
        val rightShoulder: Point,
        val leftElbow: Point,
        val rightElbow: Point,
        val leftWrist: Point,
        val rightWrist: Point,
        val leftHip: Point,
        val rightHip: Point,
        val leftKnee: Point,
        val rightKnee: Point,
        val leftAnkle: Point,
        val rightAnkle: Point,
        val shoulderMidpoint: Point,
        val hipMidpoint: Point,
        val bodyTiltAngle: Float,
        val shoulderWidth: Float,
        val torsoHeight: Float,
        val isFrontFacing: Boolean,
        // Visibility — determines if clothing can be overlaid
        val topReady: Boolean,    // shoulders + hips visible → can overlay top
        val pantsReady: Boolean   // hips + ankles visible → can overlay pants
    )

    data class PoseResult(
        val bodyPose: BodyPose,
        val segmentationMask: Bitmap?
    )

    private var poseLandmarker: PoseLandmarker? = null
    private var smoothedLandmarks: FloatArray? = null // flat: x0,y0,z0, x1,y1,z1, ...
    private var latestVisibility: FloatArray? = null   // flat: v0, v1, ... (per landmark)
    private var missingFrameCount = 0

    /** Latest smoothed landmarks as flat array (x0,y0, x1,y1, ...) for skeleton overlay. */
    var latestXY: FloatArray? = null
        private set

    /** Which delegate is being used (for debug display). */
    var delegateName: String = "unknown"
        private set

    var onPoseResult: ((PoseResult) -> Unit)? = null

    fun init(): Boolean {
        return try {
            createLandmarker(Delegate.GPU)
            true
        } catch (e: Exception) {
            Log.w(TAG, "GPU delegate failed, falling back to CPU", e)
            try {
                createLandmarker(Delegate.CPU)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "CPU delegate also failed", e2)
                false
            }
        }
    }

    private fun createLandmarker(delegate: Delegate) {
        delegateName = if (delegate == Delegate.GPU) "GPU" else "CPU"
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_PATH)
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
            .setResultListener(this::onRawResult)
            .setErrorListener { error -> Log.e(TAG, "Pose error: ${error.message}") }
            .build()

        poseLandmarker = PoseLandmarker.createFromOptions(context, options)
        Log.i(TAG, "PoseLandmarker ready with ${if (delegate == Delegate.GPU) "GPU" else "CPU"}")
    }

    fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        poseLandmarker?.detectAsync(mpImage, timestampMs)
    }

    private fun onRawResult(result: PoseLandmarkerResult, input: MPImage) {
        val landmarks = result.landmarks()
        if (landmarks.isEmpty() || landmarks[0].size < 33) {
            missingFrameCount++
            if (missingFrameCount >= MAX_MISSING_FRAMES) {
                smoothedLandmarks = null
            }
            return
        }

        missingFrameCount = 0
        val current = landmarks[0]

        // Adaptive smoothing
        val smoothed = smoothLandmarks(current)

        // Extract visibility from raw landmarks
        val vis = FloatArray(current.size)
        for (i in current.indices) {
            vis[i] = if ((current[i].visibility() ?: 0f) > 0.5f) 1f else 0f
        }
        latestVisibility = vis

        // Export XY for skeleton overlay (flat: x0,y0, x1,y1, ...)
        val xy = FloatArray(current.size * 2)
        for (i in current.indices) {
            xy[i * 2] = smoothed[i * 3]
            xy[i * 2 + 1] = smoothed[i * 3 + 1]
        }
        latestXY = xy

        // Map to BodyPose
        val bodyPose = mapToBodyPose(smoothed, vis) ?: return

        // Process segmentation mask
        val mask = processSegmentationMask(result)

        onPoseResult?.invoke(PoseResult(bodyPose, mask))
    }

    /**
     * Adaptive exponential moving average smoothing.
     * When landmarks move slowly → strong smoothing (low alpha).
     * When landmarks move quickly → less smoothing (high alpha) to reduce lag.
     */
    private fun smoothLandmarks(current: List<NormalizedLandmark>): FloatArray {
        val flat = FloatArray(current.size * 3)
        for (i in current.indices) {
            flat[i * 3] = current[i].x()
            flat[i * 3 + 1] = current[i].y()
            flat[i * 3 + 2] = current[i].z()
        }

        val prev = smoothedLandmarks
        if (prev == null || prev.size != flat.size) {
            smoothedLandmarks = flat
            return flat
        }

        for (i in flat.indices) {
            val delta = abs(flat[i] - prev[i])
            // Adaptive: alpha ranges from SMOOTH_ALPHA (slow movement) to 0.7 (fast movement)
            val alpha = if (delta > 0.02f) {
                (SMOOTH_ALPHA + (0.7f - SMOOTH_ALPHA) * minOf(delta / 0.1f, 1f))
            } else {
                SMOOTH_ALPHA
            }
            flat[i] = alpha * flat[i] + (1 - alpha) * prev[i]
        }

        smoothedLandmarks = flat
        return flat
    }

    private fun mapToBodyPose(flat: FloatArray, vis: FloatArray): BodyPose? {
        if (flat.size < 33 * 3) return null

        fun pt(idx: Int): Point {
            val i = idx * 3
            return Point(flat[i], flat[i + 1])
        }

        fun visible(idx: Int): Boolean {
            if (idx >= vis.size || vis[idx] < 0.5f) return false
            val x = flat[idx * 3]
            val y = flat[idx * 3 + 1]
            return x in 0.02f..0.98f && y in 0.02f..0.98f
        }

        val leftShoulder = pt(11)
        val rightShoulder = pt(12)
        val leftHip = pt(23)
        val rightHip = pt(24)
        val nose = pt(0)

        // Visibility checks
        val shoulderVisible = visible(11) && visible(12)
        val hipVisible = visible(23) && visible(24)
        val ankleVisible = visible(27) && visible(28)

        val shoulderMidpoint = Point(
            (leftShoulder.x + rightShoulder.x) / 2f,
            (leftShoulder.y + rightShoulder.y) / 2f
        )
        val hipMidpoint = Point(
            (leftHip.x + rightHip.x) / 2f,
            (leftHip.y + rightHip.y) / 2f
        )

        val dx = rightShoulder.x - leftShoulder.x
        val dy = rightShoulder.y - leftShoulder.y
        val tiltAngle = atan2(dy, dx)
        val shoulderWidth = sqrt(dx * dx + dy * dy)

        val tdx = hipMidpoint.x - shoulderMidpoint.x
        val tdy = hipMidpoint.y - shoulderMidpoint.y
        val torsoHeight = sqrt(tdx * tdx + tdy * tdy)

        val isFrontFacing = nose.x > 0.01f && nose.y > 0.01f

        return BodyPose(
            nose = nose,
            leftShoulder = leftShoulder,
            rightShoulder = rightShoulder,
            leftElbow = pt(13),
            rightElbow = pt(14),
            leftWrist = pt(15),
            rightWrist = pt(16),
            leftHip = leftHip,
            rightHip = rightHip,
            leftKnee = pt(25),
            rightKnee = pt(26),
            leftAnkle = pt(27),
            rightAnkle = pt(28),
            shoulderMidpoint = shoulderMidpoint,
            hipMidpoint = hipMidpoint,
            bodyTiltAngle = tiltAngle,
            shoulderWidth = shoulderWidth,
            torsoHeight = torsoHeight,
            isFrontFacing = isFrontFacing,
            topReady = shoulderVisible && hipVisible,
            pantsReady = hipVisible && ankleVisible
        )
    }

    /**
     * Process segmentation mask: float buffer → binary bitmap with Gaussian blur.
     */
    private fun processSegmentationMask(result: PoseLandmarkerResult): Bitmap? {
        val masks = result.segmentationMasks()
        if (!masks.isPresent) return null

        return try {
            val maskList = masks.get()
            val mpImage = maskList[0]
            val buffer = ByteBufferExtractor.extract(mpImage).asFloatBuffer()
            val width = mpImage.width
            val height = mpImage.height

            floatBufferToMaskBitmap(buffer, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "Mask processing error", e)
            null
        }
    }

    private fun floatBufferToMaskBitmap(buffer: FloatBuffer, width: Int, height: Int): Bitmap {
        val mat = Mat(height, width, CvType.CV_8UC1)
        val bytes = ByteArray(width * height)

        for (i in 0 until width * height) {
            bytes[i] = if (buffer.get(i) > 0.5f) 255.toByte() else 0.toByte()
        }
        mat.put(0, 0, bytes)

        // Gaussian blur for soft edges
        val blurred = Mat()
        Imgproc.GaussianBlur(mat, blurred, Size(7.0, 7.0), 0.0)

        // Dilate to ensure coverage of clothing edges
        val dilated = Mat()
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(5.0, 5.0)
        )
        Imgproc.dilate(blurred, dilated, kernel)

        // Convert single-channel mask to 4-channel for ARGB_8888 Bitmap
        Imgproc.cvtColor(dilated, dilated, Imgproc.COLOR_GRAY2BGRA)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dilated, bitmap)

        mat.release()
        blurred.release()
        dilated.release()
        kernel.release()

        return bitmap
    }

    fun release() {
        poseLandmarker?.close()
        poseLandmarker = null
        smoothedLandmarks = null
    }
}
