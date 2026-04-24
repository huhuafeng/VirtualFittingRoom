package com.virtualfittingroom

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageProxy
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.virtualfittingroom.camera.CameraManager
import com.virtualfittingroom.databinding.ActivityMainBinding
import com.virtualfittingroom.model.ClothingAssetLoader
import com.virtualfittingroom.model.ClothingCategory
import com.virtualfittingroom.model.ClothingItem
import com.virtualfittingroom.overlay.BlendProcessor
import com.virtualfittingroom.overlay.ClothingWarpEngine
import com.virtualfittingroom.pose.*
import com.virtualfittingroom.ui.ClothingPanelAdapter
import com.virtualfittingroom.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    // Core components
    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionHelper: PermissionHelper
    private lateinit var cameraManager: CameraManager
    private lateinit var poseDetector: PoseDetector
    private lateinit var landmarkMapper: LandmarkMapper
    private lateinit var maskProcessor: SegmentationMaskProcessor
    private lateinit var landmarkSmoother: LandmarkSmoother
    private lateinit var warpEngine: ClothingWarpEngine
    private lateinit var blendProcessor: BlendProcessor
    private lateinit var assetLoader: ClothingAssetLoader

    // State
    private val latestPose = AtomicReference<LandmarkMapper.BodyPose?>(null)
    private val latestMask = AtomicReference<Bitmap?>(null)
    private val latestFrame = AtomicReference<Bitmap?>(null)
    private val selectedTop = AtomicReference<ClothingItem?>(null)
    private val selectedPants = AtomicReference<ClothingItem?>(null)
    private val blendedResult = AtomicReference<Bitmap?>(null)
    private var personDetected = false
    private var allClothingItems: List<ClothingItem> = emptyList()
    private lateinit var clothingAdapter: ClothingPanelAdapter
    private var currentTab = ClothingCategory.TOP

    // Debug
    private var showDebug = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initComponents()
        setupUI()
        requestCameraPermission()
    }

    private fun initComponents() {
        permissionHelper = PermissionHelper(this)
        cameraManager = CameraManager(this, this)
        poseDetector = PoseDetector(this)
        landmarkMapper = LandmarkMapper()
        maskProcessor = SegmentationMaskProcessor()
        landmarkSmoother = LandmarkSmoother(alpha = 0.3f)
        warpEngine = ClothingWarpEngine()
        blendProcessor = BlendProcessor()
        assetLoader = ClothingAssetLoader(this)

        // Load clothing assets
        allClothingItems = assetLoader.loadAll()
    }

    private fun setupUI() {
        // Clothing adapter
        clothingAdapter = ClothingPanelAdapter { item ->
            if (currentTab == ClothingCategory.TOP) {
                selectedTop.set(item)
            } else {
                selectedPants.set(item)
            }
        }
        binding.recyclerClothing.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = clothingAdapter
        }

        // Tab layout
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_tops))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_pants))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = if (tab?.position == 0) ClothingCategory.TOP else ClothingCategory.PANTS
                updateClothingList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Show tops initially
        updateClothingList()

        // Camera switch
        binding.btnSwitchCamera.setOnClickListener {
            cameraManager.switchCamera(binding.previewView)
        }

        // Capture photo
        binding.btnCapture.setOnClickListener {
            capturePhoto()
        }

        // Debug toggle (long press on status text)
        binding.statusText.setOnLongClickListener {
            showDebug = !showDebug
            binding.poseOverlayView.setShowDebug(showDebug)
            Toast.makeText(this, "调试模式: ${if (showDebug) "开" else "关"}", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun updateClothingList() {
        val items = allClothingItems.filter { it.category == currentTab }
        clothingAdapter.updateItems(items)

        // Restore selection
        val selectedId = if (currentTab == ClothingCategory.TOP) {
            selectedTop.get()?.id
        } else {
            selectedPants.get()?.id
        }
        if (selectedId != null) {
            clothingAdapter.updateItems(items) // resets selection
        }
    }

    private fun requestCameraPermission() {
        permissionHelper.requestCameraPermission { granted ->
            if (granted) {
                initPoseDetector()
                startCamera()
            }
        }
    }

    private fun initPoseDetector() {
        val success = poseDetector.init()
        if (!success) {
            Toast.makeText(this, "姿态检测模型加载失败", Toast.LENGTH_LONG).show()
            return
        }

        poseDetector.onPoseResult = { result, timestamp ->
            handlePoseResult(result, timestamp)
        }
    }

    private fun startCamera() {
        cameraManager.onFrameAvailable = { imageProxy ->
            processFrame(imageProxy)
        }
        cameraManager.initCamera(binding.previewView)
    }

    // === Frame Processing Pipeline ===

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxyToBitmap(imageProxy) ?: return

        val processedBitmap = if (cameraManager.isFrontCamera()) {
            flipHorizontal(bitmap)
        } else {
            bitmap
        }

        latestFrame.set(processedBitmap)
        poseDetector.detectAsync(processedBitmap, System.currentTimeMillis())
    }

    private fun handlePoseResult(result: PoseLandmarkerResult, timestampMs: Long) {
        // Smooth landmarks
        landmarkSmoother.smooth(result)
        val smoothedLandmarks = landmarkSmoother.getSmoothedLandmarks()

        // Map landmarks
        if (smoothedLandmarks != null && smoothedLandmarks.isNotEmpty()) {
            val pose = landmarkMapper.mapLandmarks(smoothedLandmarks)
            latestPose.set(pose)
            personDetected = true

            runOnUiThread {
                binding.poseOverlayView.updatePose(smoothedLandmarks)
                binding.statusText.visibility = View.GONE
            }
        } else {
            personDetected = false
            latestPose.set(null)
            runOnUiThread {
                binding.statusText.visibility = View.VISIBLE
            }
        }

        // Process segmentation mask
        val masksOpt = result.segmentationMasks()
        if (masksOpt.isPresent) {
            try {
                val maskList = masksOpt.get()
                val maskBuffer = maskList[0].contents().asFloatBuffer()
                val maskWidth = maskList[0].width()
                val maskHeight = maskList[0].height()
                val processedMask = maskProcessor.processMask(maskBuffer, maskWidth, maskHeight)
                latestMask.set(processedMask)

                runOnUiThread {
                    binding.poseOverlayView.updateSegmentationMask(processedMask)
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Mask processing error", e)
            }
        }

        // Warp and blend clothing
        processClothingOverlay()
    }

    private fun processClothingOverlay() {
        val pose = latestPose.get() ?: return
        val frame = latestFrame.get() ?: return
        if (!personDetected) return

        val topItem = selectedTop.get()
        val pantsItem = selectedPants.get()

        if (topItem == null && pantsItem == null) return

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val frameWidth = frame.width
                val frameHeight = frame.height
                var resultBitmap: Bitmap = frame

                // Process top clothing
                if (topItem != null && topItem.imageBitmap != null) {
                    val warpedTop = warpEngine.warpClothing(topItem, pose, frameWidth, frameHeight)
                    if (warpedTop != null) {
                        val feathered = warpEngine.featherEdges(warpedTop, 5)
                        val mask = latestMask.get()
                        if (mask != null) {
                            resultBitmap = blendProcessor.blend(resultBitmap, feathered, mask, pose)
                        }
                        if (feathered !== warpedTop) feathered.recycle()
                        warpedTop.recycle()
                    }
                }

                // Process pants
                if (pantsItem != null && pantsItem.imageBitmap != null) {
                    val warpedPants = warpEngine.warpClothing(pantsItem, pose, frameWidth, frameHeight)
                    if (warpedPants != null) {
                        val feathered = warpEngine.featherEdges(warpedPants, 5)
                        val mask = latestMask.get()
                        if (mask != null) {
                            resultBitmap = blendProcessor.blend(resultBitmap, feathered, mask, pose)
                        }
                        if (feathered !== warpedPants) feathered.recycle()
                        warpedPants.recycle()
                    }
                }

                blendedResult.set(resultBitmap)
            } catch (e: Exception) {
                Log.e("MainActivity", "Clothing overlay error", e)
            }
        }
    }

    // === Photo Capture ===

    private fun capturePhoto() {
        val bitmap = blendedResult.get() ?: latestFrame.get() ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            saveToGallery(bitmap)
        }
    }

    private suspend fun saveToGallery(bitmap: Bitmap) {
        try {
            val filename = "VFR_${System.currentTimeMillis()}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VirtualFittingRoom")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.photo_saved, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to save photo", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // === Utility Methods ===

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val buffer = imageProxy.planes[0].buffer
            val width = imageProxy.width
            val height = imageProxy.height
            val pixelStride = imageProxy.planes[0].pixelStride
            val rowStride = imageProxy.planes[0].rowStride

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val rowPadding = rowStride - pixelStride * width

            if (rowPadding == 0) {
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                val rowBuffer = ByteArray(rowStride)
                val pixels = IntArray(width)
                for (y in 0 until height) {
                    buffer.get(rowBuffer)
                    for (x in 0 until width) {
                        val offset = x * pixelStride
                        val r = rowBuffer[offset].toInt() and 0xFF
                        val g = rowBuffer[offset + 1].toInt() and 0xFF
                        val b = rowBuffer[offset + 2].toInt() and 0xFF
                        val a = rowBuffer[offset + 3].toInt() and 0xFF
                        pixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    bitmap.setPixels(pixels, 0, width, 0, y, width, 1)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to convert ImageProxy to Bitmap", e)
            null
        }
    }

    private fun flipHorizontal(bitmap: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix().apply {
            postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        poseDetector.release()
        maskProcessor.release()
        cameraManager.release()
        landmarkSmoother.reset()
    }
}
