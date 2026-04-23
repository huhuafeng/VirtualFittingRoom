package com.virtualfittingroom.overlay

import android.graphics.Bitmap
import android.graphics.PointF
import com.virtualfittingroom.pose.LandmarkMapper
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Blends warped clothing onto the camera frame using segmentation mask
 * and handles arm occlusion.
 */
class BlendProcessor {

    /**
     * Blend clothing overlay onto camera frame.
     *
     * @param cameraFrame The original camera frame
     * @param warpedClothing The warped clothing bitmap (with alpha)
     * @param segmentationMask Person segmentation mask (white = person)
     * @param pose Detected body pose for arm occlusion
     * @return Blended result bitmap
     */
    fun blend(
        cameraFrame: Bitmap,
        warpedClothing: Bitmap,
        segmentationMask: Bitmap,
        pose: LandmarkMapper.BodyPose
    ): Bitmap {
        val width = cameraFrame.width
        val height = cameraFrame.height

        // Ensure all bitmaps are same size
        val resizedClothing = resizeIfNeeded(warpedClothing, width, height)
        val resizedMask = resizeIfNeeded(segmentationMask, width, height)

        // Convert to OpenCV Mats
        val cameraMat = Mat()
        val clothingMat = Mat()
        val maskMat = Mat()
        Utils.bitmapToMat(cameraFrame, cameraMat)
        Utils.bitmapToMat(resizedClothing, clothingMat)
        Utils.bitmapToMat(resizedMask, maskMat)

        // Convert BGRA to RGBA for processing
        Imgproc.cvtColor(cameraMat, cameraMat, Imgproc.COLOR_BGRA2RGBA)
        Imgproc.cvtColor(clothingMat, clothingMat, Imgproc.COLOR_BGRA2RGBA)
        Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_BGRA2RGBA)

        // Extract channels
        val cameraChannels = ArrayList<Mat>()
        val clothingChannels = ArrayList<Mat>()
        val maskChannels = ArrayList<Mat>()
        Core.split(cameraMat, cameraChannels)
        Core.split(clothingMat, clothingChannels)
        Core.split(maskMat, maskChannels)

        val clothingAlpha = clothingChannels[3] // Alpha channel of clothing
        val maskAlpha = maskChannels[3]          // Alpha channel of mask (person region)

        // Create arm mask (regions where arms should show in front of clothing)
        val armMask = createArmMask(pose, width, height)

        // Effective alpha = clothing_alpha * mask_alpha * (1 - arm_mask)
        val effectiveAlpha = Mat()
        Core.multiply(clothingAlpha, maskAlpha, effectiveAlpha, 1.0 / 255.0)

        // Subtract arm regions from effective alpha
        if (armMask != null) {
            Core.subtract(Mat.ones(effectiveAlpha.size(), effectiveAlpha.type()), armMask, armMask)
            Core.multiply(effectiveAlpha, armMask, effectiveAlpha)
            armMask.release()
        }

        // Blend: result = camera * (1 - alpha) + clothing * alpha
        val resultChannels = ArrayList<Mat>()
        for (i in 0 until 3) {
            val blended = Mat()
            val invAlpha = Mat()
            Core.subtract(Mat.ones(effectiveAlpha.size(), effectiveAlpha.type()), effectiveAlpha, invAlpha)

            val camContrib = Mat()
            val clothContrib = Mat()
            Core.multiply(cameraChannels[i], invAlpha, camContrib, 1.0 / 255.0)
            Core.multiply(clothingChannels[i], effectiveAlpha, clothContrib, 1.0 / 255.0)

            Core.add(camContrib, clothContrib, blended)

            resultChannels.add(blended)
            invAlpha.release()
            camContrib.release()
            clothContrib.release()
        }

        // Add full alpha channel
        resultChannels.add(Mat.ones(effectiveAlpha.size(), CvType.CV_64F).setTo(Scalar(255.0)))

        // Merge and convert back to bitmap
        val resultMat = Mat()
        Core.merge(resultChannels, resultMat)

        // Clip values to 0-255 range
        val clipped = Mat()
        resultMat.convertTo(clipped, CvType.CV_8UC4)

        // Convert RGBA back to BGRA for bitmap
        Imgproc.cvtColor(clipped, clipped, Imgproc.COLOR_RGBA2BGRA)

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(clipped, resultBitmap)

        // Cleanup
        cameraMat.release()
        clothingMat.release()
        maskMat.release()
        effectiveAlpha.release()
        clipped.release()
        resultMat.release()
        for (ch in cameraChannels) ch.release()
        for (ch in clothingChannels) ch.release()
        for (ch in maskChannels) ch.release()
        for (ch in resultChannels) ch.release()

        if (resizedClothing !== warpedClothing) resizedClothing.recycle()
        if (resizedMask !== segmentationMask) resizedMask.recycle()

        return resultBitmap
    }

    /**
     * Create a mask for arm regions based on pose landmarks.
     * Arms should appear in front of clothing, so we create a mask
     * that marks arm regions (which will be subtracted from clothing alpha).
     */
    private fun createArmMask(pose: LandmarkMapper.BodyPose, width: Int, height: Int): Mat? {
        try {
            val armMask = Mat.zeros(height, width, CvType.CV_64F)

            // Left arm: shoulder(11) -> elbow(13) -> wrist(15)
            val leftArmPoints = listOf(
                pose.leftShoulder.toPixel(width, height),
                pose.leftElbow.toPixel(width, height),
                pose.leftWrist.toPixel(width, height)
            )

            // Right arm: shoulder(12) -> elbow(14) -> wrist(16)
            val rightArmPoints = listOf(
                pose.rightShoulder.toPixel(width, height),
                pose.rightElbow.toPixel(width, height),
                pose.rightWrist.toPixel(width, height)
            )

            // Draw thick lines for arms
            for (points in listOf(leftArmPoints, rightArmPoints)) {
                for (i in 0 until points.size - 1) {
                    val p1 = points[i]
                    val p2 = points[i + 1]
                    // Arm width proportional to shoulder width
                    val thickness = max(8, (pose.shoulderWidth * width * 0.08).toInt())
                    Imgproc.line(
                        armMask,
                        Point(p1.x.toDouble(), p1.y.toDouble()),
                        Point(p2.x.toDouble(), p2.y.toDouble()),
                        Scalar(1.0),
                        thickness
                    )
                }
                // Circle at joints for smoother look
                for (p in points) {
                    val radius = max(6, (pose.shoulderWidth * width * 0.05).toInt())
                    Imgproc.circle(
                        armMask,
                        Point(p.x.toDouble(), p.y.toDouble()),
                        radius,
                        Scalar(1.0),
                        -1
                    )
                }
            }

            // Blur the arm mask for smooth edges
            val blurred = Mat()
            Imgproc.GaussianBlur(armMask, blurred, Size(11.0, 11.0), 0.0)
            armMask.release()
            return blurred
        } catch (e: Exception) {
            return null
        }
    }

    private fun resizeIfNeeded(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }
    }
}
