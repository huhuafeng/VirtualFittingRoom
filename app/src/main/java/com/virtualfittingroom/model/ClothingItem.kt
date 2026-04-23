package com.virtualfittingroom.model

import android.graphics.Bitmap

enum class ClothingCategory { TOP, PANTS }

enum class WarpType { PERSPECTIVE, PIECEWISE_AFFINE }

data class AnchorPoint(val x: Float, val y: Float)

data class AnchorPoints(
    // For tops (7 points)
    val leftShoulder: AnchorPoint? = null,
    val rightShoulder: AnchorPoint? = null,
    val neckCenter: AnchorPoint? = null,
    val leftArmpit: AnchorPoint? = null,
    val rightArmpit: AnchorPoint? = null,
    val leftHem: AnchorPoint? = null,
    val rightHem: AnchorPoint? = null,
    // For pants (6 points)
    val leftWaist: AnchorPoint? = null,
    val rightWaist: AnchorPoint? = null,
    val leftKnee: AnchorPoint? = null,
    val rightKnee: AnchorPoint? = null
)

data class WarpConfig(
    val type: WarpType = WarpType.PERSPECTIVE,
    val verticalScale: Float = 1.1f,
    val horizontalScale: Float = 1.2f
)

data class ClothingItem(
    val id: String,
    val name: String,
    val category: ClothingCategory,
    val imageFileName: String,
    val thumbnailFileName: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val anchorPoints: AnchorPoints,
    val warpConfig: WarpConfig,
    // Runtime fields (loaded later)
    var imageBitmap: Bitmap? = null,
    var thumbnailBitmap: Bitmap? = null,
    var alphaMask: Bitmap? = null
)
