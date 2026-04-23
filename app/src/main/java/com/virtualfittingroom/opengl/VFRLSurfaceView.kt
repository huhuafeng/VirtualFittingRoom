package com.virtualfittingroom.opengl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.virtualfittingroom.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VFRLSurfaceView(context: Context) : GLSurfaceView(context) {

    private val renderer: VFRGLRenderer

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)

        renderer = VFRGLRenderer(context)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun updateFrame(cameraBitmap: Bitmap?, clothingBitmap: Bitmap?, maskBitmap: Bitmap?) {
        renderer.updateTextures(cameraBitmap, clothingBitmap, maskBitmap)
        requestRender()
    }

    fun hasClothing(): Boolean = renderer.hasClothing

    fun setHasClothing(has: Boolean) {
        renderer.hasClothing = has
    }
}

class VFRGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // Shader handles
    private var program = 0
    private var aPositionHandle = 0
    private var aTexCoordHandle = 0
    private var uCameraTextureHandle = 0
    private var uClothingTextureHandle = 0
    private var uSegmentationMaskHandle = 0
    private var uTransformHandle = 0
    private var uHasClothingHandle = 0

    // Textures
    private var cameraTextureId = 0
    private var clothingTextureId = 0
    private var maskTextureId = 0

    // Geometry
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer

    var hasClothing = false

    // Pending texture updates
    private var pendingCameraBitmap: Bitmap? = null
    private var pendingClothingBitmap: Bitmap? = null
    private var pendingMaskBitmap: Bitmap? = null

    private val transformMatrix = FloatArray(16)

    companion object {
        // Full-screen quad vertices (clip space)
        private val VERTEX_DATA = floatArrayOf(
            -1f, -1f,  // bottom-left
             1f, -1f,  // bottom-right
            -1f,  1f,  // top-left
             1f,  1f   // top-right
        )

        // Texture coordinates (flipped vertically for camera)
        private val TEX_COORD_DATA = floatArrayOf(
            0f, 1f,  // bottom-left
            1f, 1f,  // bottom-right
            0f, 0f,  // top-left
            1f, 0f   // top-right
        )

        private const val FLOAT_SIZE = 4
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Compile shaders
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, readRawResource(R.raw.vertex_shader))
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, readRawResource(R.raw.fragment_shader))

        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        // Get handles
        aPositionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoordHandle = GLES30.glGetAttribLocation(program, "aTexCoord")
        uCameraTextureHandle = GLES30.glGetUniformLocation(program, "uCameraTexture")
        uClothingTextureHandle = GLES30.glGetUniformLocation(program, "uClothingTexture")
        uSegmentationMaskHandle = GLES30.glGetUniformLocation(program, "uSegmentationMask")
        uTransformHandle = GLES30.glGetUniformLocation(program, "uTransform")
        uHasClothingHandle = GLES30.glGetUniformLocation(program, "uHasClothing")

        // Setup vertex buffers
        vertexBuffer = ByteBuffer.allocateDirect(VERTEX_DATA.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(VERTEX_DATA)
        vertexBuffer.position(0)

        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORD_DATA.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(TEX_COORD_DATA)
        texCoordBuffer.position(0)

        // Create textures
        val textures = IntArray(3)
        GLES30.glGenTextures(3, textures, 0)
        cameraTextureId = textures[0]
        clothingTextureId = textures[1]
        maskTextureId = textures[2]

        setupTexture(cameraTextureId)
        setupTexture(clothingTextureId)
        setupTexture(maskTextureId)

        // Identity matrix
        Matrix.setIdentityM(transformMatrix, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Upload pending textures
        uploadPendingTextures()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // Set transform matrix
        GLES30.glUniformMatrix4fv(uTransformHandle, 1, false, transformMatrix, 0)

        // Set has clothing flag
        GLES30.glUniform1f(uHasClothingHandle, if (hasClothing) 1.0f else 0.0f)

        // Bind textures
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraTextureId)
        GLES30.glUniform1i(uCameraTextureHandle, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, clothingTextureId)
        GLES30.glUniform1i(uClothingTextureHandle, 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(uSegmentationMaskHandle, 2)

        // Draw quad
        GLES30.glEnableVertexAttribArray(aPositionHandle)
        GLES30.glVertexAttribPointer(aPositionHandle, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)

        GLES30.glEnableVertexAttribArray(aTexCoordHandle)
        GLES30.glVertexAttribPointer(aTexCoordHandle, 2, GLES30.GL_FLOAT, false, 0, texCoordBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPositionHandle)
        GLES30.glDisableVertexAttribArray(aTexCoordHandle)
    }

    fun updateTextures(camera: Bitmap?, clothing: Bitmap?, mask: Bitmap?) {
        pendingCameraBitmap = camera
        pendingClothingBitmap = clothing
        pendingMaskBitmap = mask
        hasClothing = clothing != null
    }

    private fun uploadPendingTextures() {
        pendingCameraBitmap?.let { uploadTexture(cameraTextureId, it) }
        pendingClothingBitmap?.let { uploadTexture(clothingTextureId, it) }
        pendingMaskBitmap?.let { uploadTexture(maskTextureId, it) }
        pendingCameraBitmap = null
        pendingClothingBitmap = null
        pendingMaskBitmap = null
    }

    private fun uploadTexture(textureId: Int, bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun setupTexture(textureId: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        return shader
    }

    private fun readRawResource(resId: Int): String {
        return context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
    }
}
