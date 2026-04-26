package com.virtualfittingroom.warp

import android.graphics.Bitmap
import com.virtualfittingroom.model.ClothingCategory
import com.virtualfittingroom.model.ClothingItem
import com.virtualfittingroom.pose.PoseTracker
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Warps clothing images to match body pose and blends onto camera frame.
 * Combines perspective warp + alpha blending + arm occlusion in one class.
 */
class ClothingWarpEngine {

    fun warpAndBlend(
        cameraFrame: Bitmap,
        pose: PoseTracker.BodyPose,
        segmentationMask: Bitmap?,
        topItem: ClothingItem?,
        pantsItem: ClothingItem?
    ): Bitmap {
        val w = cameraFrame.width
        val h = cameraFrame.height

        // Start with camera frame
        var result = cameraFrame

        // Process pants first (lower layer)
        if (pantsItem != null && pantsItem.imageBitmap != null) {
            val warped = warpClothing(pantsItem, pose, w, h)
            if (warped != null) {
                val blended = blend(result, warped, segmentationMask, pose, w, h)
                if (result !== cameraFrame) result.recycle()
                result = blended
                warped.recycle()
            }
        }

        // Process top (upper layer)
        if (topItem != null && topItem.imageBitmap != null) {
            val warped = warpClothing(topItem, pose, w, h)
            if (warped != null) {
                val blended = blend(result, warped, segmentationMask, pose, w, h)
                if (result !== cameraFrame) result.recycle()
                result = blended
                warped.recycle()
            }
        }

        return result
    }

    // === Warping ===

    private fun warpClothing(
        item: ClothingItem,
        pose: PoseTracker.BodyPose,
        frameWidth: Int,
        frameHeight: Int
    ): Bitmap? {
        val srcBitmap = item.imageBitmap ?: return null

        return try {
            when (item.category) {
                ClothingCategory.TOP -> warpTop(srcBitmap, item, pose, frameWidth, frameHeight)
                ClothingCategory.PANTS -> warpPants(srcBitmap, item, pose, frameWidth, frameHeight)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun warpTop(
        srcBitmap: Bitmap,
        item: ClothingItem,
        pose: PoseTracker.BodyPose,
        fw: Int,
        fh: Int
    ): Bitmap? {
        val anchors = item.anchorPoints
        val config = item.warpConfig

        val lsAnchor = anchors.leftShoulder ?: return null
        val rsAnchor = anchors.rightShoulder ?: return null
        val lhAnchor = anchors.leftHem ?: return null
        val rhAnchor = anchors.rightHem ?: return null

        // Source points from clothing image
        val srcPts = MatOfPoint2f(
            Point(lsAnchor.x.toDouble(), lsAnchor.y.toDouble()),
            Point(rsAnchor.x.toDouble(), rsAnchor.y.toDouble()),
            Point(lhAnchor.x.toDouble(), lhAnchor.y.toDouble()),
            Point(rhAnchor.x.toDouble(), rhAnchor.y.toDouble())
        )

        // Destination points from body landmarks
        val lsPx = pose.leftShoulder.toPixel(fw, fh)
        val rsPx = pose.rightShoulder.toPixel(fw, fh)
        val lhPx = pose.leftHip.toPixel(fw, fh)
        val rhPx = pose.rightHip.toPixel(fw, fh)

        // Apply horizontal scale
        val hScale = config.horizontalScale
        val shoulderMidX = (lsPx.x + rsPx.x) / 2f
        val hipMidX = (lhPx.x + rhPx.x) / 2f

        val scaledLSx = shoulderMidX - (shoulderMidX - lsPx.x) * hScale
        val scaledRSx = shoulderMidX + (rsPx.x - shoulderMidX) * hScale
        val scaledLHx = hipMidX - (hipMidX - lhPx.x) * hScale
        val scaledRHx = hipMidX + (rhPx.x - hipMidX) * hScale

        // Apply vertical scale
        val vScale = config.verticalScale
        val torsoH = (rhPx.y - rsPx.y).toFloat()
        val hemOffset = (torsoH * (vScale - 1.0f) / 2f).toInt()

        val dstPts = MatOfPoint2f(
            Point(scaledLSx.toDouble(), lsPx.y.toDouble()),
            Point(scaledRSx.toDouble(), rsPx.y.toDouble()),
            Point(scaledLHx.toDouble(), (lhPx.y + hemOffset).toDouble()),
            Point(scaledRHx.toDouble(), (rhPx.y + hemOffset).toDouble())
        )

        return applyPerspectiveWarp(srcBitmap, srcPts, dstPts, fw, fh)
    }

    private fun warpPants(
        srcBitmap: Bitmap,
        item: ClothingItem,
        pose: PoseTracker.BodyPose,
        fw: Int,
        fh: Int
    ): Bitmap? {
        val anchors = item.anchorPoints
        val config = item.warpConfig

        val lwAnchor = anchors.leftWaist ?: return null
        val rwAnchor = anchors.rightWaist ?: return null
        val lhAnchor = anchors.leftHem ?: return null
        val rhAnchor = anchors.rightHem ?: return null

        val srcPts = MatOfPoint2f(
            Point(lwAnchor.x.toDouble(), lwAnchor.y.toDouble()),
            Point(rwAnchor.x.toDouble(), rwAnchor.y.toDouble()),
            Point(lhAnchor.x.toDouble(), lhAnchor.y.toDouble()),
            Point(rhAnchor.x.toDouble(), rhAnchor.y.toDouble())
        )

        val lhPx = pose.leftHip.toPixel(fw, fh)
        val rhPx = pose.rightHip.toPixel(fw, fh)
        val laPx = pose.leftAnkle.toPixel(fw, fh)
        val raPx = pose.rightAnkle.toPixel(fw, fh)

        val hScale = config.horizontalScale
        val hipMidX = (lhPx.x + rhPx.x) / 2f
        val ankleMidX = (laPx.x + raPx.x) / 2f

        val dstPts = MatOfPoint2f(
            Point((hipMidX - (hipMidX - lhPx.x) * hScale).toDouble(), lhPx.y.toDouble()),
            Point((hipMidX + (rhPx.x - hipMidX) * hScale).toDouble(), rhPx.y.toDouble()),
            Point((ankleMidX - (ankleMidX - laPx.x) * hScale).toDouble(), laPx.y.toDouble()),
            Point((ankleMidX + (raPx.x - ankleMidX) * hScale).toDouble(), raPx.y.toDouble())
        )

        return applyPerspectiveWarp(srcBitmap, srcPts, dstPts, fw, fh)
    }

    private fun applyPerspectiveWarp(
        srcBitmap: Bitmap,
        srcPts: MatOfPoint2f,
        dstPts: MatOfPoint2f,
        fw: Int,
        fh: Int
    ): Bitmap? {
        return try {
            val srcMat = Mat()
            Utils.bitmapToMat(srcBitmap, srcMat)
            Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_BGRA2RGBA)

            val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
            val dstMat = Mat(fh, fw, CvType.CV_8UC4, Scalar(0.0, 0.0, 0.0, 0.0))
            Imgproc.warpPerspective(
                srcMat, dstMat, transform,
                Size(fw.toDouble(), fh.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(0.0, 0.0, 0.0, 0.0)
            )

            // Feather alpha edges
            featherAlpha(dstMat, 7)

            val result = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, result)

            srcMat.release()
            dstMat.release()
            transform.release()
            srcPts.release()
            dstPts.release()

            result
        } catch (e: Exception) {
            srcPts.release()
            dstPts.release()
            null
        }
    }

    /** Apply Gaussian blur to the alpha channel for soft edges. */
    private fun featherAlpha(mat: Mat, radius: Int) {
        val channels = ArrayList<Mat>()
        Core.split(mat, channels)
        if (channels.size >= 4) {
            val alpha = channels[3]
            val blurred = Mat()
            Imgproc.GaussianBlur(alpha, blurred, Size((radius * 2 + 1).toDouble(), (radius * 2 + 1).toDouble()), 0.0)
            channels[3] = blurred
            alpha.release()
            Core.merge(channels, mat)
        }
        for (ch in channels) ch.release()
    }

    // === Blending ===

    private fun blend(
        cameraFrame: Bitmap,
        warpedClothing: Bitmap,
        segmentationMask: Bitmap?,
        pose: PoseTracker.BodyPose,
        w: Int,
        h: Int
    ): Bitmap {
        val cameraMat = Mat()
        val clothingMat = Mat()
        Utils.bitmapToMat(cameraFrame, cameraMat)
        Utils.bitmapToMat(warpedClothing, clothingMat)
        Imgproc.cvtColor(cameraMat, cameraMat, Imgproc.COLOR_BGRA2RGBA)
        Imgproc.cvtColor(clothingMat, clothingMat, Imgproc.COLOR_BGRA2RGBA)

        // Split channels and convert to CV_32F for consistent arithmetic
        val camChRaw = ArrayList<Mat>()
        val clothChRaw = ArrayList<Mat>()
        Core.split(cameraMat, camChRaw)
        Core.split(clothingMat, clothChRaw)

        val camCh = ArrayList<Mat>()
        val clothCh = ArrayList<Mat>()
        for (ch in camChRaw) {
            val f = Mat()
            ch.convertTo(f, CvType.CV_32F)
            camCh.add(f)
            ch.release()
        }
        for (ch in clothChRaw) {
            val f = Mat()
            ch.convertTo(f, CvType.CV_32F)
            clothCh.add(f)
            ch.release()
        }

        val clothingAlpha = clothCh[3] // clothing alpha (CV_32F)

        // Person mask: from segmentation or fallback to clothing alpha
        val personMask = if (segmentationMask != null) {
            val maskMat = Mat()
            val maskResized = Bitmap.createScaledBitmap(segmentationMask, w, h, true)
            Utils.bitmapToMat(maskResized, maskMat)
            Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_BGRA2RGBA)
            val maskCh = ArrayList<Mat>()
            Core.split(maskMat, maskCh)
            val m = Mat()
            maskCh[3].convertTo(m, CvType.CV_32F)
            maskMat.release()
            if (maskResized !== segmentationMask) maskResized.recycle()
            for (ch in maskCh) ch.release()
            m
        } else {
            // No segmentation mask — use clothing alpha as fallback
            Mat.ones(clothingAlpha.size(), CvType.CV_32F)
        }

        // Arm mask: subtract arm regions so arms appear in front of clothing
        val armMask = createArmMask(pose, w, h, clothingAlpha.size())

        // Effective alpha = clothing_alpha * person_mask * (1 - arm_mask)
        // All inputs are CV_32F, normalize to 0~1 range using convertTo scale
        val clothingAlphaN = Mat()
        clothingAlpha.convertTo(clothingAlphaN, CvType.CV_32F, 1.0 / 255.0)
        val personMaskN = Mat()
        personMask.convertTo(personMaskN, CvType.CV_32F, 1.0 / 255.0)

        val effectiveAlpha = Mat()
        Core.multiply(clothingAlphaN, personMaskN, effectiveAlpha)

        if (armMask != null) {
            val invArm = Mat()
            Core.subtract(Mat.ones(armMask.size(), armMask.type()), armMask, invArm)
            Core.multiply(effectiveAlpha, invArm, effectiveAlpha)
            invArm.release()
            armMask.release()
        }

        // Blend: result = camera * (1 - alpha) + clothing * alpha
        // camCh and clothCh are CV_32F in 0~255 range, effectiveAlpha is CV_32F in 0~1
        val resultCh = ArrayList<Mat>()
        for (i in 0 until 3) {
            val blended = Mat()
            val invAlpha = Mat()
            Core.subtract(Mat.ones(effectiveAlpha.size(), effectiveAlpha.type()), effectiveAlpha, invAlpha)

            val camC = Mat()
            val clothC = Mat()
            Core.multiply(camCh[i], invAlpha, camC)
            Core.multiply(clothCh[i], effectiveAlpha, clothC)

            Core.add(camC, clothC, blended)

            resultCh.add(blended)
            invAlpha.release()
            camC.release()
            clothC.release()
        }

        // Full alpha (255)
        resultCh.add(Mat.ones(effectiveAlpha.size(), CvType.CV_32F).setTo(Scalar(255.0)))

        val resultMat = Mat()
        Core.merge(resultCh, resultMat)

        val clipped = Mat()
        resultMat.convertTo(clipped, CvType.CV_8UC4)

        // Convert back for Bitmap
        Imgproc.cvtColor(clipped, clipped, Imgproc.COLOR_RGBA2BGRA)
        val resultBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(clipped, resultBitmap)

        // Cleanup
        cameraMat.release()
        clothingMat.release()
        effectiveAlpha.release()
        clothingAlphaN.release()
        personMaskN.release()
        clipped.release()
        resultMat.release()
        personMask.release()
        for (ch in camCh) ch.release()
        for (ch in clothCh) ch.release()
        for (ch in resultCh) ch.release()

        return resultBitmap
    }

    /**
     * Create arm occlusion mask — arms should render in front of clothing.
     */
    private fun createArmMask(pose: PoseTracker.BodyPose, w: Int, h: Int, targetSize: Size): Mat? {
        return try {
            val armMask = Mat.zeros(h, w, CvType.CV_64F)

            val armThickness = max(10, (pose.shoulderWidth * w * 0.1).toInt())
            val jointRadius = max(8, (pose.shoulderWidth * w * 0.06).toInt())

            // Left arm: shoulder → elbow → wrist
            val leftArm = listOf(
                pose.leftShoulder.toPixel(w, h),
                pose.leftElbow.toPixel(w, h),
                pose.leftWrist.toPixel(w, h)
            )
            // Right arm
            val rightArm = listOf(
                pose.rightShoulder.toPixel(w, h),
                pose.rightElbow.toPixel(w, h),
                pose.rightWrist.toPixel(w, h)
            )

            for (arm in listOf(leftArm, rightArm)) {
                for (i in 0 until arm.size - 1) {
                    Imgproc.line(
                        armMask,
                        Point(arm[i].x.toDouble(), arm[i].y.toDouble()),
                        Point(arm[i + 1].x.toDouble(), arm[i + 1].y.toDouble()),
                        Scalar(1.0),
                        armThickness
                    )
                }
                for (p in arm) {
                    Imgproc.circle(
                        armMask,
                        Point(p.x.toDouble(), p.y.toDouble()),
                        jointRadius,
                        Scalar(1.0),
                        -1
                    )
                }
            }

            // Blur for smooth transition
            val blurred = Mat()
            Imgproc.GaussianBlur(armMask, blurred, Size(15.0, 15.0), 0.0)
            armMask.release()

            blurred
        } catch (e: Exception) {
            null
        }
    }
}
