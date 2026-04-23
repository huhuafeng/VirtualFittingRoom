package com.virtualfittingroom.pose

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Maps MediaPipe's 33 pose landmarks to named body parts and computes derived points.
 */
class LandmarkMapper {

    data class Point(val x: Float, val y: Float) {
        fun toPixel(imageWidth: Int, imageHeight: Int): PixelPoint {
            return PixelPoint((x * imageWidth).toInt(), (y * imageHeight).toInt())
        }
    }

    data class PixelPoint(val x: Int, val y: Int)

    data class BodyPose(
        // Raw landmarks (normalized 0~1)
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

        // Derived points
        val shoulderMidpoint: Point,
        val hipMidpoint: Point,
        val bodyTiltAngle: Float,        // radians
        val shoulderWidth: Float,         // normalized distance
        val torsoHeight: Float,           // normalized distance
        val isFrontFacing: Boolean,       // nose visibility check
    )

    fun mapLandmarks(landmarks: List<NormalizedLandmark>): BodyPose? {
        if (landmarks.size < 33) return null

        val nose = landmarks[0].toPoint()
        val leftShoulder = landmarks[11].toPoint()
        val rightShoulder = landmarks[12].toPoint()
        val leftElbow = landmarks[13].toPoint()
        val rightElbow = landmarks[14].toPoint()
        val leftWrist = landmarks[15].toPoint()
        val rightWrist = landmarks[16].toPoint()
        val leftHip = landmarks[23].toPoint()
        val rightHip = landmarks[24].toPoint()
        val leftKnee = landmarks[25].toPoint()
        val rightKnee = landmarks[26].toPoint()
        val leftAnkle = landmarks[27].toPoint()
        val rightAnkle = landmarks[28].toPoint()

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
        val bodyTiltAngle = atan2(dy, dx)
        val shoulderWidth = sqrt(dx * dx + dy * dy)

        val torsoDx = hipMidpoint.x - shoulderMidpoint.x
        val torsoDy = hipMidpoint.y - shoulderMidpoint.y
        val torsoHeight = sqrt(torsoDx * torsoDx + torsoDy * torsoDy)

        val isFrontFacing = nose.x > 0f && nose.y > 0f

        return BodyPose(
            nose = nose,
            leftShoulder = leftShoulder,
            rightShoulder = rightShoulder,
            leftElbow = leftElbow,
            rightElbow = rightElbow,
            leftWrist = leftWrist,
            rightWrist = rightWrist,
            leftHip = leftHip,
            rightHip = rightHip,
            leftKnee = leftKnee,
            rightKnee = rightKnee,
            leftAnkle = leftAnkle,
            rightAnkle = rightAnkle,
            shoulderMidpoint = shoulderMidpoint,
            hipMidpoint = hipMidpoint,
            bodyTiltAngle = bodyTiltAngle,
            shoulderWidth = shoulderWidth,
            torsoHeight = torsoHeight,
            isFrontFacing = isFrontFacing
        )
    }

    private fun NormalizedLandmark.toPoint(): LandmarkMapper.Point {
        return Point(this.x(), this.y())
    }
}
