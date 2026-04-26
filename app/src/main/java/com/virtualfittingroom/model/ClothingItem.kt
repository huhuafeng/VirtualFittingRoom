package com.virtualfittingroom.model

import android.graphics.Bitmap

enum class ClothingCategory { TOP, PANTS }

data class AnchorPoint(val x: Float, val y: Float)

data class AnchorPoints(
    // Tops (7 points)
    val leftShoulder: AnchorPoint? = null,
    val rightShoulder: AnchorPoint? = null,
    val neckCenter: AnchorPoint? = null,
    val leftArmpit: AnchorPoint? = null,
    val rightArmpit: AnchorPoint? = null,
    val leftHem: AnchorPoint? = null,
    val rightHem: AnchorPoint? = null,
    // Pants (additional points)
    val leftWaist: AnchorPoint? = null,
    val rightWaist: AnchorPoint? = null,
    val leftKnee: AnchorPoint? = null,
    val rightKnee: AnchorPoint? = null
)

data class WarpConfig(
    val verticalScale: Float = 1.1f,
    val horizontalScale: Float = 1.2f
)

data class ClothingItem(
    val id: String,
    val name: String,
    val category: ClothingCategory,
    val anchorPoints: AnchorPoints,
    val warpConfig: WarpConfig,
    var imageBitmap: Bitmap? = null,
    var thumbnailBitmap: Bitmap? = null
)
