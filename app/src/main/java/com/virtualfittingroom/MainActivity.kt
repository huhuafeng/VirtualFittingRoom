package com.virtualfittingroom

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
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
import com.google.mediapipe.framework.image.ByteBufferExtractor
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

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
    private val latestFrame = AtomicReference<Bitmap>(null)
    private val selectedTop = AtomicReference<ClothingItem?>(null)
    private val selectedPants = AtomicReference<ClothingItem?>(null)
    private var lastBlendedBitmap: Bitmap? = null
    private var allClothingItems: List<ClothingItem> = emptyList()
    private lateinit var clothingAdapter: ClothingPanelAdapter
    private var currentTab = ClothingCategory.TOP
    private var showDebug = false

    // Frame counter for MediaPipe timestamps
    private val frameCounter = AtomicLong(0)

    // Single processing job — cancel previous before starting new
    private var processingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

        allClothingItems = assetLoader.loadAll()
    }

    private fun setupUI() {
        clothingAdapter = ClothingPanelAdapter { item ->
            if (item == null) {
                // Deselected
                if (currentTab == ClothingCategory.TOP) {
                    selectedTop.set(null)
                } else {
                    selectedPants.set(null)
                }
            } else {
                if (currentTab == ClothingCategory.TOP) {
                    selectedTop.set(item)
                } else {
                    selectedPants.set(item)
                }
            }
            // Hide result view when no clothing selected
            if (selectedTop.get() == null && selectedPants.get() == null) {
                binding.resultView.visibility = View.GONE
            }
        }
        binding.recyclerClothing.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = clothingAdapter
        }

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

        updateClothingList()

        binding.btnSwitchCamera.setOnClickListener {
            cameraManager.switchCamera(binding.previewView)
        }

        binding.btnCapture.setOnClickListener {
            capturePhoto()
        }

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
            handlePoseResult(result)
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
        val timestamp = frameCounter.incrementAndGet()
        poseDetector.detectAsync(processedBitmap, timestamp)
    }

    private fun handlePoseResult(result: PoseLandmarkerResult) {
        // 1. Smooth landmarks
        landmarkSmoother.smooth(result)
        val smoothedLandmarks = landmarkSmoother.getSmoothedLandmarks()

        // 2. Map to BodyPose
        val pose = if (smoothedLandmarks != null && smoothedLandmarks.isNotEmpty()) {
            landmarkMapper.mapLandmarks(smoothedLandmarks)
        } else null

        val personDetected = pose != null

        // 3. Process segmentation mask
        var processedMask: Bitmap? = null
        val masksOpt = result.segmentationMasks()
        if (masksOpt.isPresent && personDetected) {
            try {
                val maskList = masksOpt.get()
                val maskBuffer = ByteBufferExtractor.extract(maskList[0]).asFloatBuffer()
                processedMask = maskProcessor.processMask(maskBuffer, maskList[0].width, maskList[0].height)
            } catch (e: Exception) {
                Log.w(TAG, "Mask processing error", e)
            }
        }

        // 4. Update debug overlay on UI thread
        runOnUiThread {
            if (personDetected) {
                binding.poseOverlayView.updatePose(smoothedLandmarks)
                if (processedMask != null) {
                    binding.poseOverlayView.updateSegmentationMask(processedMask)
                }
                binding.statusText.visibility = View.GONE
            } else {
                binding.poseOverlayView.updatePose(null)
                binding.statusText.visibility = View.VISIBLE
            }
        }

        // 5. Check if clothing overlay is needed
        val topItem = selectedTop.get()
        val pantsItem = selectedPants.get()
        if (!personDetected || (topItem == null && pantsItem == null) || processedMask == null) {
            return
        }

        val frame = latestFrame.get() ?: return

        // 6. Cancel previous processing, start new
        processingJob?.cancel()
        processingJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                val resultBitmap = blendClothing(frame, pose!!, processedMask, topItem, pantsItem)
                withContext(Dispatchers.Main) {
                    lastBlendedBitmap?.recycle()
                    lastBlendedBitmap = resultBitmap
                    binding.resultView.setImageBitmap(resultBitmap)
                    binding.resultView.visibility = View.VISIBLE
                }
            } catch (e: CancellationException) {
                // Normal — previous job cancelled by new frame
            } catch (e: Exception) {
                Log.e(TAG, "Clothing overlay error", e)
            }
        }
    }

    private fun blendClothing(
        frame: Bitmap,
        pose: LandmarkMapper.BodyPose,
        mask: Bitmap,
        topItem: ClothingItem?,
        pantsItem: ClothingItem?
    ): Bitmap {
        val frameWidth = frame.width
        val frameHeight = frame.height
        var resultBitmap: Bitmap = frame

        // Process pants first (lower layer)
        if (pantsItem != null && pantsItem.imageBitmap != null) {
            val warped = warpEngine.warpClothing(pantsItem, pose, frameWidth, frameHeight)
            if (warped != null) {
                val feathered = warpEngine.featherEdges(warped, 5)
                val blended = blendProcessor.blend(resultBitmap, feathered, mask, pose)
                if (resultBitmap !== frame) resultBitmap.recycle()
                resultBitmap = blended
                if (feathered !== warped) feathered.recycle()
                warped.recycle()
            }
        }

        // Process top (upper layer)
        if (topItem != null && topItem.imageBitmap != null) {
            val warped = warpEngine.warpClothing(topItem, pose, frameWidth, frameHeight)
            if (warped != null) {
                val feathered = warpEngine.featherEdges(warped, 5)
                val blended = blendProcessor.blend(resultBitmap, feathered, mask, pose)
                if (resultBitmap !== frame) resultBitmap.recycle()
                resultBitmap = blended
                if (feathered !== warped) feathered.recycle()
                warped.recycle()
            }
        }

        return resultBitmap
    }

    // === Photo Capture ===

    private fun capturePhoto() {
        val bitmap = lastBlendedBitmap ?: return
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
            Log.e(TAG, "Failed to save photo", e)
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
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap", e)
            null
        }
    }

    private fun flipHorizontal(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply {
            postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        processingJob?.cancel()
        poseDetector.release()
        maskProcessor.release()
        cameraManager.release()
        landmarkSmoother.reset()
        lastBlendedBitmap?.recycle()
    }
}
