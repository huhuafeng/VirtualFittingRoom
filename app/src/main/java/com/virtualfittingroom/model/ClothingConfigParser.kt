package com.virtualfittingroom.model

import org.json.JSONObject

/**
 * Parses clothing JSON configuration files.
 */
object ClothingConfigParser {

    fun parse(jsonString: String, id: String): ClothingItem? {
        return try {
            val json = JSONObject(jsonString)

            val category = when (json.optString("category", "TOP")) {
                "PANTS" -> ClothingCategory.PANTS
                else -> ClothingCategory.TOP
            }

            val anchorsJson = json.getJSONObject("anchorPoints")
            val anchorPoints = parseAnchorPoints(anchorsJson, category)

            val warpJson = json.optJSONObject("warpConfig")
            val warpConfig = if (warpJson != null) {
                WarpConfig(
                    type = when (warpJson.optString("type", "PERSPECTIVE")) {
                        "PIECEWISE_AFFINE" -> WarpType.PIECEWISE_AFFINE
                        else -> WarpType.PERSPECTIVE
                    },
                    verticalScale = warpJson.optDouble("verticalScale", 1.1).toFloat(),
                    horizontalScale = warpJson.optDouble("horizontalScale", 1.2).toFloat()
                )
            } else {
                WarpConfig()
            }

            ClothingItem(
                id = id,
                name = json.optString("name", id),
                category = category,
                imageFileName = json.optString("image", ""),
                thumbnailFileName = json.optString("thumbnail", ""),
                imageWidth = json.optInt("imageWidth", 512),
                imageHeight = json.optInt("imageHeight", 600),
                anchorPoints = anchorPoints,
                warpConfig = warpConfig
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAnchorPoints(json: JSONObject, category: ClothingCategory): AnchorPoints {
        return if (category == ClothingCategory.TOP) {
            AnchorPoints(
                leftShoulder = parsePoint(json, "leftShoulder"),
                rightShoulder = parsePoint(json, "rightShoulder"),
                neckCenter = parsePoint(json, "neckCenter"),
                leftArmpit = parsePoint(json, "leftArmpit"),
                rightArmpit = parsePoint(json, "rightArmpit"),
                leftHem = parsePoint(json, "leftHem"),
                rightHem = parsePoint(json, "rightHem")
            )
        } else {
            AnchorPoints(
                leftWaist = parsePoint(json, "leftWaist"),
                rightWaist = parsePoint(json, "rightWaist"),
                leftKnee = parsePoint(json, "leftKnee"),
                rightKnee = parsePoint(json, "rightKnee"),
                leftHem = parsePoint(json, "leftHem"),
                rightHem = parsePoint(json, "rightHem")
            )
        }
    }

    private fun parsePoint(json: JSONObject, key: String): AnchorPoint? {
        return try {
            val pointJson = json.getJSONObject(key)
            AnchorPoint(
                x = pointJson.optDouble("x", 0.0).toFloat(),
                y = pointJson.optDouble("y", 0.0).toFloat()
            )
        } catch (e: Exception) {
            null
        }
    }
}
