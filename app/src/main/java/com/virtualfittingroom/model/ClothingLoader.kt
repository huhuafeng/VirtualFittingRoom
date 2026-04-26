package com.virtualfittingroom.model

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject

class ClothingLoader(private val context: Context) {

    companion object {
        private const val TAG = "ClothingLoader"
        private const val CLOTHING_DIR = "clothing"
        private const val TOPS_DIR = "tops"
        private const val PANTS_DIR = "pants"
    }

    fun loadAll(): List<ClothingItem> {
        val items = mutableListOf<ClothingItem>()
        items.addAll(loadCategory(TOPS_DIR, ClothingCategory.TOP))
        items.addAll(loadCategory(PANTS_DIR, ClothingCategory.PANTS))
        Log.i(TAG, "Loaded ${items.size} clothing items")
        return items
    }

    private fun loadCategory(dirName: String, category: ClothingCategory): List<ClothingItem> {
        val items = mutableListOf<ClothingItem>()
        val assets = context.assets

        try {
            val files = assets.list("$CLOTHING_DIR/$dirName") ?: return emptyList()
            val jsonFiles = files.filter { it.endsWith("_meta.json") }

            for (jsonFile in jsonFiles) {
                try {
                    val item = loadItem(assets, "$CLOTHING_DIR/$dirName", jsonFile, category)
                    if (item != null) items.add(item)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load $jsonFile", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list $dirName", e)
        }

        return items
    }

    private fun loadItem(
        assets: AssetManager,
        basePath: String,
        jsonFile: String,
        category: ClothingCategory
    ): ClothingItem? {
        val json = assets.open("$basePath/$jsonFile").bufferedReader().use { it.readText() }
        val root = JSONObject(json)

        val id = root.getString("id")
        val name = root.getString("name")
        val imageWidth = root.optInt("imageWidth", 512)
        val imageHeight = root.optInt("imageHeight", 600)

        // Load image PNG
        val imageFile = root.optString("image", "$id.png")
        val imageBitmap = loadBitmap(assets, "$basePath/$imageFile") ?: return null

        // Generate thumbnail
        val thumbWidth = 128
        val thumbHeight = (thumbWidth.toDouble() * imageHeight / imageWidth).toInt()
        val thumbnail = Bitmap.createScaledBitmap(imageBitmap, thumbWidth, thumbHeight, true)

        // Parse anchor points
        val anchorsObj = root.getJSONObject("anchorPoints")
        val anchors = parseAnchors(anchorsObj, category)

        // Parse warp config
        val warpObj = root.optJSONObject("warpConfig") ?: JSONObject()
        val warpConfig = WarpConfig(
            verticalScale = warpObj.optDouble("verticalScale", 1.1).toFloat(),
            horizontalScale = warpObj.optDouble("horizontalScale", 1.2).toFloat()
        )

        return ClothingItem(
            id = id,
            name = name,
            category = category,
            anchorPoints = anchors,
            warpConfig = warpConfig,
            imageBitmap = imageBitmap,
            thumbnailBitmap = thumbnail
        )
    }

    private fun parseAnchors(json: JSONObject, category: ClothingCategory): AnchorPoints {
        fun point(key: String): AnchorPoint? {
            val obj = json.optJSONObject(key) ?: return null
            return AnchorPoint(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
        }

        return AnchorPoints(
            leftShoulder = point("leftShoulder"),
            rightShoulder = point("rightShoulder"),
            neckCenter = point("neckCenter"),
            leftArmpit = point("leftArmpit"),
            rightArmpit = point("rightArmpit"),
            leftHem = point("leftHem"),
            rightHem = point("rightHem"),
            leftWaist = point("leftWaist"),
            rightWaist = point("rightWaist"),
            leftKnee = point("leftKnee"),
            rightKnee = point("rightKnee")
        )
    }

    private fun loadBitmap(assets: AssetManager, path: String): Bitmap? {
        return try {
            assets.open(path).use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap: $path", e)
            null
        }
    }
}
