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
import com.virtualfittingroom.camera.CameraHelper
import com.virtualfittingroom.databinding.ActivityMainBinding
import com.virtualfittingroom.model.ClothingCategory
import com.virtualfittingroom.model.ClothingItem
import com.virtualfittingroom.model.ClothingLoader
import com.virtualfittingroom.pose.PoseTracker
import com.virtualfittingroom.ui.ClothingAdapter
import com.virtualfittingroom.util.PermissionHelper
import com.virtualfittingroom.warp.ClothingWarpEngine
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

    // Core components
    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionHelper: PermissionHelper
    private lateinit var cameraHelper: CameraHelper
    private lateinit var poseTracker: PoseTracker
    private lateinit var warpEngine: ClothingWarpEngine
    private lateinit var clothingLoader: ClothingLoader

    // State
    private val latestFrame = AtomicReference<Bitmap>(null)
    private val selectedTop = AtomicReference<ClothingItem?>(null)
    private val selectedPants = AtomicReference<ClothingItem?>(null)
    private var lastBlendedBitmap: Bitmap? = null
    private var allClothingItems: List<ClothingItem> = emptyList()
    private lateinit var clothingAdapter: ClothingAdapter
    private var currentTab = ClothingCategory.TOP

    // Frame counter for MediaPipe timestamps
    private val frameCounter = AtomicLong(0)

    // Single processing job — only one blend operation at a time
    private var processingJob: Job? = null

    // FPS tracking
    private var fpsFrameCount = 0
    private var fpsLastTime = System.currentTimeMillis()
    private var currentFps = 0

    // Debug log throttling
    private var lastLogTime = 0L

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
        cameraHelper = CameraHelper(this, this)
        poseTracker = PoseTracker(this)
        warpEngine = ClothingWarpEngine()
        clothingLoader = ClothingLoader(this)

        allClothingItems = clothingLoader.loadAll()

        binding.debugLogView.log("App started")
        binding.debugLogView.log("Clothes: ${allClothingItems.size} items loaded")
    }

    private fun setupUI() {
        // Clothing adapter
        clothingAdapter = ClothingAdapter { item ->
            if (item == null) {
                if (currentTab == ClothingCategory.TOP) selectedTop.set(null)
                else selectedPants.set(null)
            } else {
                if (currentTab == ClothingCategory.TOP) selectedTop.set(item)
                else selectedPants.set(item)
            }
            if (selectedTop.get() == null && selectedPants.get() == null) {
                binding.resultView.visibility = View.GONE
            }
            logDebug()
        }

        binding.recyclerClothing.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = clothingAdapter
        }

        // Tabs
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
            cameraHelper.switchCamera()
            binding.debugLogView.log("Camera: ${if (cameraHelper.isFrontCamera()) "front" else "back"}")
        }

        binding.btnCapture.setOnClickListener {
            capturePhoto()
        }

        binding.btnToggleDebug.setOnClickListener {
            binding.debugLogView.toggle()
        }
    }

    private fun updateClothingList() {
        val items = allClothingItems.filter { it.category == currentTab }
        clothingAdapter.updateItems(items)
    }

    private fun requestCameraPermission() {
        permissionHelper.requestCameraPermission { granted ->
            if (granted) {
                initPoseTracker()
                startCamera()
            } else {
                binding.debugLogView.log("ERROR: camera denied")
            }
        }
    }

    private fun initPoseTracker() {
        val success = poseTracker.init()
        if (!success) {
            Toast.makeText(this, "姿态检测模型加载失败", Toast.LENGTH_LONG).show()
            binding.debugLogView.log("ERROR: model load failed")
            return
        }

        binding.debugLogView.log("Model: ${poseTracker.delegateName}")

        poseTracker.onPoseResult = { poseResult ->
            handlePoseResult(poseResult)
        }
    }

    private fun startCamera() {
        cameraHelper.onFrameAvailable = { imageProxy ->
            processFrame(imageProxy)
        }
        cameraHelper.start(binding.previewView)
        binding.debugLogView.log("Camera: started")
    }

    // === Frame Processing Pipeline ===

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxyToBitmap(imageProxy) ?: return

        // Rotate to portrait orientation based on sensor rotation
        val rotation = imageProxy.imageInfo.rotationDegrees
        val rotated = if (rotation != 0) rotateBitmap(bitmap, rotation) else bitmap

        val processed = if (cameraHelper.isFrontCamera()) {
            flipHorizontal(rotated)
        } else {
            rotated
        }

        latestFrame.set(processed)
        poseTracker.detectAsync(processed, frameCounter.incrementAndGet())

        // FPS calculation
        fpsFrameCount++
        val now = System.currentTimeMillis()
        if (now - fpsLastTime >= 1000) {
            currentFps = fpsFrameCount
            fpsFrameCount = 0
            fpsLastTime = now
        }
    }

    private fun handlePoseResult(result: PoseTracker.PoseResult) {
        val frame = latestFrame.get() ?: return
        val topItem = selectedTop.get()
        val pantsItem = selectedPants.get()

        // Update skeleton overlay
        runOnUiThread {
            binding.skeletonView.updateLandmarks(poseTracker.latestXY)
            binding.statusText.visibility = View.GONE
        }

        // Update debug log (throttled to ~4 updates/sec)
        val now = System.currentTimeMillis()
        if (now - lastLogTime > 250) {
            lastLogTime = now
            logDebug(result.bodyPose.topReady, result.bodyPose.pantsReady)
        }

        if (topItem == null && pantsItem == null) return

        // Cancel previous blend, start new one
        processingJob?.cancel()
        processingJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                val blendStart = System.currentTimeMillis()
                val blended = warpEngine.warpAndBlend(
                    frame, result.bodyPose, result.segmentationMask,
                    topItem, pantsItem
                )
                val blendMs = System.currentTimeMillis() - blendStart

                withContext(Dispatchers.Main) {
                    lastBlendedBitmap?.recycle()
                    lastBlendedBitmap = blended
                    binding.resultView.setImageBitmap(blended)
                    binding.resultView.visibility = View.VISIBLE
                    binding.debugLogView.log("Blend: ${blendMs}ms")
                }
            } catch (e: CancellationException) {
                // Normal — cancelled by next frame
            } catch (e: Exception) {
                Log.e(TAG, "Blend error", e)
                withContext(Dispatchers.Main) {
                    binding.debugLogView.log("ERR blend: ${e.message}")
                }
            }
        }
    }

    private fun logDebug(topReady: Boolean = false, pantsReady: Boolean = false) {
        val top = selectedTop.get()
        val pants = selectedPants.get()
        val clothesInfo = buildString {
            if (top != null) append(top.name)
            if (top != null && pants != null) append(" + ")
            if (pants != null) append(pants.name)
            if (top == null && pants == null) append("none")
        }
        val topFlag = if (topReady) "T:Y" else "T:N"
        val pantsFlag = if (pantsReady) "P:Y" else "P:N"
        binding.debugLogView.log("FPS:$currentFps | $topFlag $pantsFlag | $clothesInfo")
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
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VirtualFittingRoom")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.photo_saved, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
        }
    }

    // === Utility ===

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
            Log.e(TAG, "ImageProxy conversion failed", e)
            null
        }
    }

    private fun flipHorizontal(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionHelper.onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onDestroy() {
        super.onDestroy()
        processingJob?.cancel()
        poseTracker.release()
        cameraHelper.release()
        lastBlendedBitmap?.recycle()
    }
}
