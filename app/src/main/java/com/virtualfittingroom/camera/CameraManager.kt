package com.virtualfittingroom.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var onFrameAvailable: ((imageProxy: ImageProxy) -> Unit)? = null

    fun initCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(previewView: PreviewView) {
        val provider = cameraProvider ?: return

        // Unbind previous use cases
        provider.unbindAll()

        // Preview
        preview = Preview.Builder()
            .setTargetResolution(android.util.Size(1280, 720))
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // ImageAnalysis
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor) { imageProxy ->
                    onFrameAvailable?.invoke(imageProxy)
                    imageProxy.close()
                }
            }

        // Bind to lifecycle
        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (e: Exception) {
            Log.e("CameraManager", "Failed to bind camera use cases", e)
        }
    }

    fun switchCamera(previewView: PreviewView) {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        bindCameraUseCases(previewView)
    }

    fun isFrontCamera(): Boolean {
        return cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
    }

    fun release() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }
}
