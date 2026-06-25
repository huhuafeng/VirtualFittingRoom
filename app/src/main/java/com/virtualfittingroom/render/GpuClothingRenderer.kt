package com.virtualfittingroom.render

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.virtualfittingroom.model.ClothingCategory
import com.virtualfittingroom.model.ClothingItem
import com.virtualfittingroom.pose.PoseTracker
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

class GpuClothingRenderer(
    private val context: Context
) : GLSurfaceView.Renderer {

    companion object {
        private const val GRID = 24
        private const val FS = 4
        private val VERT_TEX = """
            uniform mat4 uProj;
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { gl_Position = uProj * aPos; vUv = aUv; }
        """.trimIndent()
        private val FRAG_TEX = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """.trimIndent()
        private val VERT_LINE = """
            uniform mat4 uProj;
            attribute vec4 aPos;
            void main() { gl_Position = uProj * aPos; }
        """.trimIndent()
        private val FRAG_LINE = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """.trimIndent()
    }

    private var texProgram = 0
    private var lineProgram = 0
    private var aPosLoc = 0
    private var aUvLoc = 0
    private var uProjLoc = 0
    private var uTexLoc = 0
    private var lineProjLoc = 0
    private var lineColorLoc = 0
    private var linePosLoc = 0

    private var viewW = 0f
    private var viewH = 0f
    private val projMat = FloatArray(16)
    private val clothingTex = mutableMapOf<String, Int>()

    @Volatile var landmarks: FloatArray? = null
    @Volatile var topItem: ClothingItem? = null
    @Volatile var pantsItem: ClothingItem? = null
    @Volatile var pose: PoseTracker.BodyPose? = null
    @Volatile var frameW = 0
    @Volatile var frameH = 0
    @Volatile var isMirrored = false

    fun loadTexture(item: ClothingItem) {
        val id = item.id
        if (clothingTex.containsKey(id)) return
        val bmp = item.imageBitmap ?: return
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        clothingTex[id] = tex[0]
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        texProgram = buildProgram(VERT_TEX, FRAG_TEX)
        lineProgram = buildProgram(VERT_LINE, FRAG_LINE)
        aPosLoc = GLES20.glGetAttribLocation(texProgram, "aPos")
        aUvLoc = GLES20.glGetAttribLocation(texProgram, "aUv")
        uProjLoc = GLES20.glGetUniformLocation(texProgram, "uProj")
        uTexLoc = GLES20.glGetUniformLocation(texProgram, "uTex")
        lineProjLoc = GLES20.glGetUniformLocation(lineProgram, "uProj")
        lineColorLoc = GLES20.glGetUniformLocation(lineProgram, "uColor")
        linePosLoc = GLES20.glGetAttribLocation(lineProgram, "aPos")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        viewW = w.toFloat()
        viewH = h.toFloat()
        GLES20.glViewport(0, 0, w, h)
        Matrix.orthoM(projMat, 0, 0f, viewW, viewH, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Debug: draw a visible red rectangle to confirm GL rendering works
        drawDebugRect()

        val lm = landmarks
        val t = topItem
        val p = pantsItem
        val bp = pose
        val fw = frameW
        val fh = frameH
        val mir = isMirrored
        if (fw == 0 || fh == 0) return

        if (bp != null && (t != null || p != null)) {
            android.util.Log.d("GpuClothing", "drawClothing topReady=${bp.topReady} pantsReady=${bp.pantsReady} " +
                "topTex=${t?.id?.let { clothingTex.containsKey(it) }} " +
                "pantsTex=${p?.id?.let { clothingTex.containsKey(it) }}")
            drawClothing(t, p, bp, fw, fh)
        }
        if (lm != null && lm.size >= 33 * 2) {
            drawSkeleton(lm, fw, fh, mir)
        }
    }

    private fun drawDebugRect() {
        GLES20.glUseProgram(lineProgram)
        GLES20.glUniformMatrix4fv(lineProjLoc, 1, false, projMat, 0)
        GLES20.glUniform4f(lineColorLoc, 1f, 0f, 0f, 1f)
        val buf = floatArrayToBuf(floatArrayOf(
            100f, 100f, 400f, 100f,
            400f, 100f, 400f, 400f,
            400f, 400f, 100f, 400f,
            100f, 400f, 100f, 100f
        ))
        GLES20.glVertexAttribPointer(linePosLoc, 2, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glEnableVertexAttribArray(linePosLoc)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 8)
    }

    // ── Clothing ──

    private fun drawClothing(top: ClothingItem?, pants: ClothingItem?, bp: PoseTracker.BodyPose, fw: Int, fh: Int) {
        if (texProgram == 0) return
        GLES20.glUseProgram(texProgram)
        GLES20.glUniformMatrix4fv(uProjLoc, 1, false, projMat, 0)
        GLES20.glUniform1i(uTexLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        if (pants != null && bp.pantsReady && clothingTex.containsKey(pants.id)) {
            drawItem(pants, bp, fw, fh)
        }
        if (top != null && bp.topReady && clothingTex.containsKey(top.id)) {
            drawItem(top, bp, fw, fh)
        }
    }

    private data class Pt(val x: Float, val y: Float)

    private fun drawItem(item: ClothingItem, bp: PoseTracker.BodyPose, fw: Int, fh: Int) {
        val anchors = item.anchorPoints
        val config = item.warpConfig
        val bmp = item.imageBitmap ?: return

        val (srcCorners, dstCorners) = when (item.category) {
            ClothingCategory.TOP -> topCorners(anchors, config, bp, fw, fh) ?: return
            ClothingCategory.PANTS -> pantsCorners(anchors, config, bp, fw, fh) ?: return
        }

        val srcMat = MatOfPoint2f(
            Point(srcCorners[0].x.toDouble(), srcCorners[0].y.toDouble()),
            Point(srcCorners[1].x.toDouble(), srcCorners[1].y.toDouble()),
            Point(srcCorners[2].x.toDouble(), srcCorners[2].y.toDouble()),
            Point(srcCorners[3].x.toDouble(), srcCorners[3].y.toDouble())
        )
        val dstMat = MatOfPoint2f(
            Point(dstCorners[0].x.toDouble(), dstCorners[0].y.toDouble()),
            Point(dstCorners[1].x.toDouble(), dstCorners[1].y.toDouble()),
            Point(dstCorners[2].x.toDouble(), dstCorners[2].y.toDouble()),
            Point(dstCorners[3].x.toDouble(), dstCorners[3].y.toDouble())
        )
        val H = Imgproc.getPerspectiveTransform(srcMat, dstMat)

        val scale = maxOf(viewW / fw, viewH / fh)
        val dispW = fw * scale
        val dispH = fh * scale
        val ox = (viewW - dispW) / 2f
        val oy = (viewH - dispH) / 2f
        val imgW = bmp.width.toFloat()
        val imgH = bmp.height.toFloat()
        val step = 1f / GRID
        val vpr = GRID + 1
        val total = vpr * vpr

        val buf = ByteBuffer.allocateDirect(total * 4 * FS)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val hd = DoubleArray(9)
        H.get(0, 0, hd)

        for (iy in 0..GRID) {
            for (ix in 0..GRID) {
                val u = ix * step; val v = iy * step
                val sx = u * imgW; val sy = v * imgH
                val w0 = hd[6] * sx + hd[7] * sy + hd[8]
                val dx = ((hd[0] * sx + hd[1] * sy + hd[2]) / w0).toFloat()
                val dy = ((hd[3] * sx + hd[4] * sy + hd[5]) / w0).toFloat()
                buf.put(dx * scale + ox).put(dy * scale + oy).put(u).put(v)
            }
        }
        buf.position(0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, clothingTex[item.id] ?: return)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 4 * FS, buf)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        buf.position(2)
        GLES20.glVertexAttribPointer(aUvLoc, 2, GLES20.GL_FLOAT, false, 4 * FS, buf)
        GLES20.glEnableVertexAttribArray(aUvLoc)

        for (iy in 0 until GRID) {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, iy * vpr, vpr * 2)
        }

        srcMat.release(); dstMat.release(); H.release()
    }

    private fun topCorners(
        a: com.virtualfittingroom.model.AnchorPoints,
        c: com.virtualfittingroom.model.WarpConfig,
        bp: PoseTracker.BodyPose,
        fw: Int, fh: Int
    ): Pair<List<Pt>, List<Pt>>? {
        val ls = a.leftShoulder ?: return null
        val rs = a.rightShoulder ?: return null
        val lhA = a.leftHem ?: return null
        val rhA = a.rightHem ?: return null
        val lsP = bp.leftShoulder.toPixel(fw, fh)
        val rsP = bp.rightShoulder.toPixel(fw, fh)
        val lhP = bp.leftHip.toPixel(fw, fh)
        val rhP = bp.rightHip.toPixel(fw, fh)
        val hs = c.horizontalScale; val vs = c.verticalScale
        val smx = (lsP.x + rsP.x) / 2f; val hmx = (lhP.x + rhP.x) / 2f
        val ho = ((rhP.y - rsP.y) * (vs - 1f) / 2f).toInt()
        return Pair(
            listOf(Pt(ls.x, ls.y), Pt(rs.x, rs.y), Pt(lhA.x, lhA.y), Pt(rhA.x, rhA.y)),
            listOf(
                Pt(smx - (smx - lsP.x) * hs, lsP.y.toFloat()),
                Pt(smx + (rsP.x - smx) * hs, rsP.y.toFloat()),
                Pt(hmx - (hmx - lhP.x) * hs, (lhP.y + ho).toFloat()),
                Pt(hmx + (rhP.x - hmx) * hs, (rhP.y + ho).toFloat())
            )
        )
    }

    private fun pantsCorners(
        a: com.virtualfittingroom.model.AnchorPoints,
        c: com.virtualfittingroom.model.WarpConfig,
        bp: PoseTracker.BodyPose,
        fw: Int, fh: Int
    ): Pair<List<Pt>, List<Pt>>? {
        val lw = a.leftWaist ?: return null
        val rw = a.rightWaist ?: return null
        val lhA = a.leftHem ?: return null
        val rhA = a.rightHem ?: return null
        val lhP = bp.leftHip.toPixel(fw, fh)
        val rhP = bp.rightHip.toPixel(fw, fh)
        val laP = bp.leftAnkle.toPixel(fw, fh)
        val raP = bp.rightAnkle.toPixel(fw, fh)
        val hs = c.horizontalScale
        val hmx = (lhP.x + rhP.x) / 2f; val amx = (laP.x + raP.x) / 2f
        return Pair(
            listOf(Pt(lw.x, lw.y), Pt(rw.x, rw.y), Pt(lhA.x, lhA.y), Pt(rhA.x, rhA.y)),
            listOf(
                Pt(hmx - (hmx - lhP.x) * hs, lhP.y.toFloat()),
                Pt(hmx + (rhP.x - hmx) * hs, rhP.y.toFloat()),
                Pt(amx - (amx - laP.x) * hs, laP.y.toFloat()),
                Pt(amx + (raP.x - amx) * hs, raP.y.toFloat())
            )
        )
    }

    // ── Skeleton (drawn using line program, no texture) ──

    private val SKEL_CONNECTIONS = listOf(
        11 to 13, 13 to 15, 12 to 14, 14 to 16,
        23 to 25, 25 to 27, 24 to 26, 26 to 28,
        11 to 12, 11 to 23, 12 to 24, 23 to 24,
        0 to 11, 0 to 12
    )

    private fun drawSkeleton(lm: FloatArray, fw: Int, fh: Int, mir: Boolean) {
        if (lineProgram == 0) return
        val scale = maxOf(viewW / fw, viewH / fh)
        val dispW = fw * scale; val dispH = fh * scale
        val ox = (viewW - dispW) / 2f; val oy = (viewH - dispH) / 2f
        fun vx(lx: Float) = (if (mir) 1f - lx else lx) * dispW + ox
        fun vy(ly: Float) = ly * dispH + oy

        GLES20.glUseProgram(lineProgram)
        GLES20.glUniformMatrix4fv(lineProjLoc, 1, false, projMat, 0)

        // Lines
        GLES20.glUniform4f(lineColorLoc, 0f, 1f, 0f, 1f)
        GLES20.glLineWidth(4f)
        val lineVerts = mutableListOf<Float>()
        for ((a, b) in SKEL_CONNECTIONS) {
            val ax = vx(lm[a * 2]); val ay = vy(lm[a * 2 + 1])
            val bx = vx(lm[b * 2]); val by = vy(lm[b * 2 + 1])
            if (ax <= 0 || bx <= 0) continue
            lineVerts.add(ax); lineVerts.add(ay); lineVerts.add(bx); lineVerts.add(by)
        }
        if (lineVerts.isNotEmpty()) {
            val buf = floatArrayToBuf(lineVerts.toFloatArray())
            GLES20.glVertexAttribPointer(linePosLoc, 2, GLES20.GL_FLOAT, false, 0, buf)
            GLES20.glEnableVertexAttribArray(linePosLoc)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineVerts.size / 2)
        }

        // Points (drawn as small squares using GL_TRIANGLE_STRIP)
        GLES20.glUniform4f(lineColorLoc, 1f, 0f, 0f, 1f)
        val ptSize = 6f
        val ptVerts = mutableListOf<Float>()
        for (i in 0 until minOf(lm.size / 2, 33)) {
            val cx = vx(lm[i * 2]); val cy = vy(lm[i * 2 + 1])
            if (cx <= 0) continue
            ptVerts.addAll(listOf(
                cx - ptSize, cy - ptSize, cx + ptSize, cy - ptSize,
                cx - ptSize, cy + ptSize, cx + ptSize, cy + ptSize
            ))
        }
        if (ptVerts.isNotEmpty()) {
            val buf = floatArrayToBuf(ptVerts.toFloatArray())
            GLES20.glVertexAttribPointer(linePosLoc, 2, GLES20.GL_FLOAT, false, 0, buf)
            GLES20.glEnableVertexAttribArray(linePosLoc)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, ptVerts.size / 2)
        }
    }

    private fun floatArrayToBuf(arr: FloatArray): FloatBuffer {
        val buf = ByteBuffer.allocateDirect(arr.size * FS)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(arr); buf.position(0); return buf
    }

    private fun buildProgram(vSrc: String, fSrc: String): Int {
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        if (vs == 0) return 0
        GLES20.glShaderSource(vs, vSrc)
        GLES20.glCompileShader(vs)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(vs, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) { GLES20.glDeleteShader(vs); return 0 }

        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        if (fs == 0) return 0
        GLES20.glShaderSource(fs, fSrc)
        GLES20.glCompileShader(fs)
        GLES20.glGetShaderiv(fs, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) { GLES20.glDeleteShader(fs); return 0 }

        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) { GLES20.glDeleteProgram(prog); return 0 }
        return prog
    }
}
