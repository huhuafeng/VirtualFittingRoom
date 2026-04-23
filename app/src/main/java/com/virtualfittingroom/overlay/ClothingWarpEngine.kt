package com.virtualfittingroom.overlay

import android.graphics.Bitmap
import android.graphics.PointF
import com.virtualfittingroom.model.ClothingCategory
import com.virtualfittingroom.model.ClothingItem
import com.virtualfittingroom.model.WarpConfig
import com.virtualfittingroom.pose.LandmarkMapper
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Core warping engine that maps clothing PNG images onto detected body poses
 * using OpenCV perspective transform.
 */
class ClothingWarpEngine {

    /**
     * Warp a clothing item to match the detected body pose.
     *
     * @param clothingItem The clothing item with image and anchor points
     * @param pose The detected body pose
     * @param frameWidth Camera frame width
     * @param frameHeight Camera frame height
     * @return Warped clothing Bitmap with alpha channel, or null if warping fails
     */
    fun warpClothing(
        clothingItem: ClothingItem,
        pose: LandmarkMapper.BodyPose,
        frameWidth: Int,
        frameHeight: Int
    ): Bitmap? {
        val srcBitmap = clothingItem.imageBitmap ?: return null

        return when (clothingItem.category) {
            ClothingCategory.TOP -> warpTop(srcBitmap, clothingItem, pose, frameWidth, frameHeight)
            ClothingCategory.PANTS -> warpPants(srcBitmap, clothingItem, pose, frameWidth, frameHeight)
        }
    }

    private fun warpTop(
        srcBitmap: Bitmap,
        item: ClothingItem,
        pose: LandmarkMapper.BodyPose,
        frameWidth: Int,
        frameHeight: Int
    ): Bitmap? {
        val anchors = item.anchorPoints
        val config = item.warpConfig

        // Source points from clothing image anchors
        val leftShoulderAnchor = anchors.leftShoulder ?: return null
        val rightShoulderAnchor = anchors.rightShoulder ?: return null
        val leftHemAnchor = anchors.leftHem ?: return null
        val rightHemAnchor = anchors.rightHem ?: return null

        val srcPoints = MatOfPoint2f(
            Point(leftShoulderAnchor.x.toDouble(), leftShoulderAnchor.y.toDouble()),
            Point(rightShoulderAnchor.x.toDouble(), rightShoulderAnchor.y.toDouble()),
            Point(leftHemAnchor.x.toDouble(), leftHemAnchor.y.toDouble()),
            Point(rightHemAnchor.x.toDouble(), rightHemAnchor.y.toDouble())
        )

        // Destination points from body landmarks
        val leftShoulderPx = pose.leftShoulder.toPixel(frameWidth, frameHeight)
        val rightShoulderPx = pose.rightShoulder.toPixel(frameWidth, frameHeight)
        val leftHipPx = pose.leftHip.toPixel(frameWidth, frameHeight)
        val rightHipPx = pose.rightHip.toPixel(frameWidth, frameHeight)

        // Apply horizontal scale (clothing is wider than shoulders)
        val hScale = config.horizontalScale
        val shoulderMidX = (leftShoulderPx.x + rightShoulderPx.x) / 2f
        val hipMidX = (leftHipPx.x + rightHipPx.x) / 2f

        val scaledLeftShoulderX = shoulderMidX - (shoulderMidX - leftShoulderPx.x) * hScale
        val scaledRightShoulderX = shoulderMidX + (rightShoulderPx.x - shoulderMidX) * hScale
        val scaledLeftHipX = hipMidX - (hipMidX - leftHipPx.x) * hScale
        val scaledRightHipX = hipMidX + (rightHipPx.x - hipMidX) * hScale

        // Apply vertical scale (extend hem downward)
        val vScale = config.verticalScale
        val torsoHeight = (rightHipPx.y - rightShoulderPx.y).toFloat()
        val hemOffset = (torsoHeight * (vScale - 1.0f) / 2f).toInt()

        val dstPoints = MatOfPoint2f(
            Point(scaledLeftShoulderX.toDouble(), leftShoulderPx.y.toDouble()),
            Point(scaledRightShoulderX.toDouble(), rightShoulderPx.y.toDouble()),
            Point(scaledLeftHipX.toDouble(), (leftHipPx.y + hemOffset).toDouble()),
            Point(scaledRightHipX.toDouble(), (rightHipPx.y + hemOffset).toDouble())
        )

        return applyPerspectiveWarp(srcBitmap, srcPoints, dstPoints, frameWidth, frameHeight)
    }

    private fun warpPants(
        srcBitmap: Bitmap,
        item: ClothingItem,
        pose: LandmarkMapper.BodyPose,
        frameWidth: Int,
        frameHeight: Int
    ): Bitmap? {
        val anchors = item.anchorPoints
        val config = item.warpConfig

        // Source points from pants image anchors
        val leftWaistAnchor = anchors.leftWaist ?: return null
        val rightWaistAnchor = anchors.rightWaist ?: return null
        val leftHemAnchor = anchors.leftHem ?: return null
        val rightHemAnchor = anchors.rightHem ?: return null

        val srcPoints = MatOfPoint2f(
            Point(leftWaistAnchor.x.toDouble(), leftWaistAnchor.y.toDouble()),
            Point(rightWaistAnchor.x.toDouble(), rightWaistAnchor.y.toDouble()),
            Point(leftHemAnchor.x.toDouble(), leftHemAnchor.y.toDouble()),
            Point(rightHemAnchor.x.toDouble(), rightHemAnchor.y.toDouble())
        )

        // Destination points from body landmarks
        val leftHipPx = pose.leftHip.toPixel(frameWidth, frameHeight)
        val rightHipPx = pose.rightHip.toPixel(frameWidth, frameHeight)
        val leftAnklePx = pose.leftAnkle.toPixel(frameWidth, frameHeight)
        val rightAnklePx = pose.rightAnkle.toPixel(frameWidth, frameHeight)

        // Apply horizontal scale
        val hScale = config.horizontalScale
        val hipMidX = (leftHipPx.x + rightHipPx.x) / 2f
        val ankleMidX = (leftAnklePx.x + rightAnklePx.x) / 2f

        val scaledLeftHipX = hipMidX - (hipMidX - leftHipPx.x) * hScale
        val scaledRightHipX = hipMidX + (rightHipPx.x - hipMidX) * hScale
        val scaledLeftAnkleX = ankleMidX - (ankleMidX - leftAnklePx.x) * hScale
        val scaledRightAnkleX = ankleMidX + (rightAnklePx.x - ankleMidX) * hScale

        val dstPoints = MatOfPoint2f(
            Point(scaledLeftHipX.toDouble(), leftHipPx.y.toDouble()),
            Point(scaledRightHipX.toDouble(), rightHipPx.y.toDouble()),
            Point(scaledLeftAnkleX.toDouble(), leftAnklePx.y.toDouble()),
            Point(scaledRightAnkleX.toDouble(), rightAnklePx.y.toDouble())
        )

        return applyPerspectiveWarp(srcBitmap, srcPoints, dstPoints, frameWidth, frameHeight)
    }

    private fun applyPerspectiveWarp(
        srcBitmap: Bitmap,
        srcPoints: MatOfPoint2f,
        dstPoints: MatOfPoint2f,
        frameWidth: Int,
        frameHeight: Int
    ): Bitmap? {
        return try {
            // Convert Bitmap to Mat
            val srcMat = Mat()
            Utils.bitmapToMat(srcBitmap, srcMat)

            // Convert from BGRA to RGBA (OpenCV uses BGR)
            val rgbaMat = Mat()
            Imgproc.cvtColor(srcMat, rgbaMat, Imgproc.COLOR_BGRA2RGBA)

            // Calculate perspective transform
            val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)

            // Apply warp
            val dstMat = Mat(frameHeight, frameWidth, CvType.CV_8UC4, Scalar(0.0, 0.0, 0.0, 0.0))
            Imgproc.warpPerspective(
                rgbaMat, dstMat, transform,
                Size(frameWidth.toDouble(), frameHeight.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(0.0, 0.0, 0.0, 0.0)
            )

            // Convert back to Bitmap
            val resultBitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, resultBitmap)

            // Release mats
            srcMat.release()
            rgbaMat.release()
            dstMat.release()
            transform.release()
            srcPoints.release()
            dstPoints.release()

            resultBitmap
        } catch (e: Exception) {
            srcPoints.release()
            dstPoints.release()
            null
        }
    }

    /**
     * Calculate body side angle from shoulder positions.
     * Returns angle in degrees.
     */
    fun getBodySideAngle(pose: LandmarkMapper.BodyPose): Float {
        val dy = abs(pose.leftShoulder.y - pose.rightShoulder.y)
        val dx = abs(pose.leftShoulder.x - pose.rightShoulder.x)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    /**
     * Check if person is facing away (back view).
     */
    fun isBackView(pose: LandmarkMapper.BodyPose): Boolean {
        return !pose.isFrontFacing
    }

    /**
     * Apply edge feathering to the warped clothing bitmap's alpha channel.
     */
    fun featherEdges(bitmap: Bitmap, radius: Int = 5): Bitmap {
        return try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            // Extract alpha channel
            val channels = ArrayList<Mat>()
            Core.split(mat, channels)
            val alpha = channels[3]

            // Gaussian blur on alpha for feathering
            val blurred = Mat()
            Imgproc.GaussianBlur(alpha, blurred, Size((radius * 2 + 1).toDouble(), (radius * 2 + 1).toDouble()), 0.0)
            channels[3] = blurred

            // Merge back
            val result = Mat()
            Core.merge(channels, result)

            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(result, resultBitmap)

            mat.release()
            alpha.release()
            blurred.release()
            result.release()
            for (ch in channels) ch.release()

            resultBitmap
        } catch (e: Exception) {
            bitmap
        }
    }
}
