package com.virtualfittingroom.overlay

import android.graphics.Bitmap
import android.graphics.PointF
import com.virtualfittingroom.model.AnchorPoint
import com.virtualfittingroom.model.ClothingCategory
import com.virtualfittingroom.model.ClothingItem
import com.virtualfittingroom.pose.LandmarkMapper
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Advanced piecewise affine warp using Delaunay triangulation.
 * Provides better results than single perspective transform for non-rigid body shapes.
 */
class PiecewiseAffineWarp {

    /**
     * Warp clothing using piecewise affine transformation.
     * Splits the clothing into triangular regions and warps each independently.
     */
    fun warp(
        clothingItem: ClothingItem,
        pose: LandmarkMapper.BodyPose,
        frameWidth: Int,
        frameHeight: Int
    ): Bitmap? {
        val srcBitmap = clothingItem.imageBitmap ?: return null
        val srcPoints = getSourcePoints(clothingItem) ?: return null
        val dstPoints = getDestinationPoints(clothingItem, pose, frameWidth, frameHeight) ?: return null

        if (srcPoints.size < 4 || srcPoints.size != dstPoints.size) return null

        return try {
            // Build Delaunay triangulation from source points
            val triangles = delaunayTriangulate(srcPoints, srcBitmap.width, srcBitmap.height)

            // Convert source bitmap to Mat
            val srcMat = Mat()
            Utils.bitmapToMat(srcBitmap, srcMat)
            Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_BGRA2RGBA)

            val dstMat = Mat(frameHeight, frameWidth, CvType.CV_8UC4, Scalar(0.0, 0.0, 0.0, 0.0))

            // Warp each triangle
            for (tri in triangles) {
                val srcTri = MatOfPoint2f(
                    Point(srcPoints[tri[0]].x.toDouble(), srcPoints[tri[0]].y.toDouble()),
                    Point(srcPoints[tri[1]].x.toDouble(), srcPoints[tri[1]].y.toDouble()),
                    Point(srcPoints[tri[2]].x.toDouble(), srcPoints[tri[2]].y.toDouble())
                )
                val dstTri = MatOfPoint2f(
                    Point(dstPoints[tri[0]].x.toDouble(), dstPoints[tri[0]].y.toDouble()),
                    Point(dstPoints[tri[1]].x.toDouble(), dstPoints[tri[1]].y.toDouble()),
                    Point(dstPoints[tri[2]].x.toDouble(), dstPoints[tri[2]].y.toDouble())
                )

                val affine = Imgproc.getAffineTransform(srcTri, dstTri)

                // Warp source triangle region
                val warpedTriangle = Mat(frameHeight, frameWidth, CvType.CV_8UC4, Scalar(0.0, 0.0, 0.0, 0.0))
                Imgproc.warpAffine(srcMat, warpedTriangle, affine,
                    Size(frameWidth.toDouble(), frameHeight.toDouble()),
                    Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0, 0.0))

                // Create triangle mask for blending
                val mask = Mat.zeros(frameHeight, frameWidth, CvType.CV_8UC4)
                val trianglePts = MatOfPoint(
                    Point(dstPoints[tri[0]].x.toDouble(), dstPoints[tri[0]].y.toDouble()),
                    Point(dstPoints[tri[1]].x.toDouble(), dstPoints[tri[1]].y.toDouble()),
                    Point(dstPoints[tri[2]].x.toDouble(), dstPoints[tri[2]].y.toDouble())
                )
                Imgproc.fillConvexPoly(mask, trianglePts, Scalar(255.0, 255.0, 255.0, 255.0))

                // Copy warped triangle to destination where mask is set
                warpedTriangle.copyTo(dstMat, mask)

                srcTri.release()
                dstTri.release()
                affine.release()
                warpedTriangle.release()
                mask.release()
                trianglePts.release()
            }

            val resultBitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstMat, resultBitmap)

            srcMat.release()
            dstMat.release()

            resultBitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun getSourcePoints(item: ClothingItem): List<PointF>? {
        val anchors = item.anchorPoints
        val points = mutableListOf<PointF>()

        return when (item.category) {
            ClothingCategory.TOP -> {
                anchors.leftShoulder?.let { points.add(PointF(it.x, it.y)) }
                anchors.rightShoulder?.let { points.add(PointF(it.x, it.y)) }
                anchors.neckCenter?.let { points.add(PointF(it.x, it.y)) }
                anchors.leftArmpit?.let { points.add(PointF(it.x, it.y)) }
                anchors.rightArmpit?.let { points.add(PointF(it.x, it.y)) }
                anchors.leftHem?.let { points.add(PointF(it.x, it.y)) }
                anchors.rightHem?.let { points.add(PointF(it.x, it.y)) }
                if (points.size >= 4) points else null
            }
            ClothingCategory.PANTS -> {
                anchors.leftWaist?.let { points.add(PointF(it.x, it.y)) }
                anchors.rightWaist?.let { points.add(PointF(it.x, it.y)) }
                anchors.leftKnee?.let { points.add(PointF(it.x, it.y)) }
                anchors.rightKnee?.let { points.add(PointF(it.x, it.y)) }
                anchors.leftHem?.let { points.add(PointF(it.x, it.y)) }
                anchors.rightHem?.let { points.add(PointF(it.x, it.y)) }
                if (points.size >= 4) points else null
            }
        }
    }

    private fun getDestinationPoints(
        item: ClothingItem,
        pose: LandmarkMapper.BodyPose,
        frameWidth: Int,
        frameHeight: Int
    ): List<PointF>? {
        val config = item.warpConfig
        val hScale = config.horizontalScale
        val vScale = config.verticalScale
        val points = mutableListOf<PointF>()

        when (item.category) {
            ClothingCategory.TOP -> {
                val lsPx = pose.leftShoulder.toPixel(frameWidth, frameHeight)
                val rsPx = pose.rightShoulder.toPixel(frameWidth, frameHeight)
                val lhPx = pose.leftHip.toPixel(frameWidth, frameHeight)
                val rhPx = pose.rightHip.toPixel(frameWidth, frameHeight)
                val midX = (lsPx.x + rsPx.x) / 2f
                val midHipX = (lhPx.x + rhPx.x) / 2f
                val hemOffset = ((rhPx.y - rsPx.y) * (vScale - 1f) / 2f).toInt()

                // leftShoulder
                points.add(PointF(midX - (midX - lsPx.x) * hScale, lsPx.y.toFloat()))
                // rightShoulder
                points.add(PointF(midX + (rsPx.x - midX) * hScale, rsPx.y.toFloat()))
                // neckCenter = midpoint above shoulders
                points.add(PointF(midX, lsPx.y - (rsPx.y - lsPx.y) * 0.2f))
                // leftArmpit
                points.add(PointF(midHipX - (midHipX - lhPx.x) * hScale * 0.9f, lsPx.y + (lhPx.y - lsPx.y) * 0.3f))
                // rightArmpit
                points.add(PointF(midHipX + (rhPx.x - midHipX) * hScale * 0.9f, rsPx.y + (rhPx.y - rsPx.y) * 0.3f))
                // leftHem
                points.add(PointF(midHipX - (midHipX - lhPx.x) * hScale, lhPx.y + hemOffset.toFloat()))
                // rightHem
                points.add(PointF(midHipX + (rhPx.x - midHipX) * hScale, rhPx.y + hemOffset.toFloat()))
            }
            ClothingCategory.PANTS -> {
                val lhPx = pose.leftHip.toPixel(frameWidth, frameHeight)
                val rhPx = pose.rightHip.toPixel(frameWidth, frameHeight)
                val lkPx = pose.leftKnee.toPixel(frameWidth, frameHeight)
                val rkPx = pose.rightKnee.toPixel(frameWidth, frameHeight)
                val laPx = pose.leftAnkle.toPixel(frameWidth, frameHeight)
                val raPx = pose.rightAnkle.toPixel(frameWidth, frameHeight)
                val hipMidX = (lhPx.x + rhPx.x) / 2f
                val ankleMidX = (laPx.x + raPx.x) / 2f
                val kneeMidX = (lkPx.x + rkPx.x) / 2f

                points.add(PointF(hipMidX - (hipMidX - lhPx.x) * hScale, lhPx.y.toFloat()))
                points.add(PointF(hipMidX + (rhPx.x - hipMidX) * hScale, rhPx.y.toFloat()))
                points.add(PointF(kneeMidX - (kneeMidX - lkPx.x) * hScale, lkPx.y.toFloat()))
                points.add(PointF(kneeMidX + (rkPx.x - kneeMidX) * hScale, rkPx.y.toFloat()))
                points.add(PointF(ankleMidX - (ankleMidX - laPx.x) * hScale, laPx.y.toFloat()))
                points.add(PointF(ankleMidX + (raPx.x - ankleMidX) * hScale, raPx.y.toFloat()))
            }
        }

        return if (points.size >= 4) points else null
    }

    /**
     * Simple Delaunay triangulation for a set of 2D points.
     * For 4-7 points, we use a manual triangulation approach.
     */
    private fun delaunayTriangulate(points: List<PointF>, width: Int, height: Int): List<IntArray> {
        if (points.size < 3) return emptyList()

        // For small point sets, use manual triangulation
        // based on a fan from the centroid
        val triangles = mutableListOf<IntArray>()

        // Add convex hull boundary corners to ensure full coverage
        if (points.size == 4) {
            // Rectangle: 2 triangles
            triangles.add(intArrayOf(0, 1, 2))
            triangles.add(intArrayOf(1, 2, 3))
        } else if (points.size >= 5) {
            // Fan triangulation from first point
            for (i in 1 until points.size - 1) {
                triangles.add(intArrayOf(0, i, i + 1))
            }
        }

        return triangles
    }
}
