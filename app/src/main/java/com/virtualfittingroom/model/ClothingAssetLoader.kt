package com.virtualfittingroom.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads clothing assets from the assets/clothing/ directory.
 */
class ClothingAssetLoader(private val context: Context) {

    private val clothingItems = mutableListOf<ClothingItem>()

    fun loadAll(): List<ClothingItem> {
        clothingItems.clear()

        // Load tops
        loadFromDirectory("clothing/tops", ClothingCategory.TOP)

        // Load pants
        loadFromDirectory("clothing/pants", ClothingCategory.PANTS)

        Log.i("AssetLoader", "Loaded ${clothingItems.size} clothing items")
        return clothingItems.toList()
    }

    private fun loadFromDirectory(directory: String, category: ClothingCategory) {
        try {
            val files = context.assets.list(directory) ?: return

            // Group files by base name (e.g., tshirt_blue.png and tshirt_blue_meta.json)
            val jsonFiles = files.filter { it.endsWith("_meta.json") }

            for (jsonFile in jsonFiles) {
                val baseName = jsonFile.removeSuffix("_meta.json")
                val jsonPath = "$directory/$jsonFile"

                // Read JSON config
                val jsonString = readAssetFile(jsonPath) ?: continue
                val item = ClothingConfigParser.parse(jsonString, baseName)

                if (item != null) {
                    // Load image bitmap
                    val imagePath = "$directory/${item.imageFileName}"
                    val bitmap = loadBitmap(imagePath)
                    if (bitmap != null) {
                        item.imageBitmap = bitmap

                        // Generate thumbnail
                        item.thumbnailBitmap = createThumbnail(bitmap)

                        // Pre-compute alpha mask
                        item.alphaMask = extractAlphaMask(bitmap)

                        clothingItems.add(item)
                        Log.i("AssetLoader", "Loaded: ${item.name} from $directory")
                    } else {
                        Log.w("AssetLoader", "Failed to load image: $imagePath")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AssetLoader", "Error loading from $directory", e)
        }
    }

    private fun readAssetFile(path: String): String? {
        return try {
            val inputStream = context.assets.open(path)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val builder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                builder.append(line)
            }
            reader.close()
            builder.toString()
        } catch (e: Exception) {
            Log.w("AssetLoader", "File not found: $path")
            null
        }
    }

    private fun loadBitmap(path: String): Bitmap? {
        return try {
            val inputStream = context.assets.open(path)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
        } catch (e: Exception) {
            null
        }
    }

    private fun createThumbnail(bitmap: Bitmap): Bitmap {
        val thumbWidth = 128
        val thumbHeight = (bitmap.height * 128f / bitmap.width).toInt()
        return Bitmap.createScaledBitmap(bitmap, thumbWidth, thumbHeight, true)
    }

    private fun extractAlphaMask(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val maskPixels = IntArray(width * height)
        for (i in pixels.indices) {
            val alpha = (pixels[i] shr 24) and 0xFF
            maskPixels[i] = (alpha shl 24) or 0x00FFFFFF
        }

        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        mask.setPixels(maskPixels, 0, width, 0, 0, width, height)
        return mask
    }
}
