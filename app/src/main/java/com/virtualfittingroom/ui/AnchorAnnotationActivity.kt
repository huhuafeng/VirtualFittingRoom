package com.virtualfittingroom.ui

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.virtualfittingroom.model.ClothingCategory
import org.json.JSONObject
import java.io.FileOutputStream

class AnchorAnnotationActivity : AppCompatActivity() {

    private lateinit var annotationView: AnchorAnnotationView
    private lateinit var radioGroupCategory: RadioGroup
    private lateinit var btnSave: Button
    private lateinit var btnLoadImage: Button
    private lateinit var btnReset: Button
    private lateinit var tvStatus: TextView

    private var currentCategory = ClothingCategory.TOP

    // Top anchors order
    private val topAnchorLabels = listOf(
        "左肩" to "leftShoulder",
        "右肩" to "rightShoulder",
        "颈中" to "neckCenter",
        "左腋" to "leftArmpit",
        "右腋" to "rightArmpit",
        "左下摆" to "leftHem",
        "右下摆" to "rightHem"
    )

    // Pants anchors order
    private val pantsAnchorLabels = listOf(
        "左腰" to "leftWaist",
        "右腰" to "rightWaist",
        "左膝" to "leftKnee",
        "右膝" to "rightKnee",
        "左裤脚" to "leftHem",
        "右裤脚" to "rightHem"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create layout programmatically
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // Category selector
        radioGroupCategory = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbTop = RadioButton(this).apply { text = "上衣"; id = View.generateViewId() }
        val rbPants = RadioButton(this).apply { text = "裤子"; id = View.generateViewId() }
        radioGroupCategory.addView(rbTop)
        radioGroupCategory.addView(rbPants)
        radioGroupCategory.check(rbTop.id)
        radioGroupCategory.setOnCheckedChangeListener { _, checkedId ->
            currentCategory = if (checkedId == rbTop.id) ClothingCategory.TOP else ClothingCategory.PANTS
            annotationView.setCategory(currentCategory)
            updateStatus()
        }
        layout.addView(radioGroupCategory)

        // Load image button
        btnLoadImage = Button(this).apply {
            text = "选择图片"
            setOnClickListener { pickImage() }
        }
        layout.addView(btnLoadImage)

        // Annotation view
        annotationView = AnchorAnnotationView(this)
        layout.addView(annotationView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        // Status text
        tvStatus = TextView(this).apply {
            text = "请选择图片开始标注"
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        layout.addView(tvStatus)

        // Buttons row
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        btnSave = Button(this).apply {
            text = "保存JSON"
            setEnabled(false)
            setOnClickListener { saveJson() }
        }

        btnReset = Button(this).apply {
            text = "重置"
            setOnClickListener {
                annotationView.reset()
                updateStatus()
            }
        }

        btnRow.addView(btnSave, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(btnReset, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(btnRow)

        setContentView(layout)

        annotationView.setCategory(currentCategory)
        annotationView.onAnchorChanged = { updateStatus() }
    }

    private fun updateStatus() {
        val labels = if (currentCategory == ClothingCategory.TOP) topAnchorLabels else pantsAnchorLabels
        val placed = annotationView.getAnchorCount()
        val total = labels.size
        tvStatus.text = "已标注: $placed / $total"
        btnSave.isEnabled = placed >= total
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_PICK_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val bitmap = loadBitmapFromUri(uri)
            if (bitmap != null) {
                annotationView.setBitmap(bitmap)
                updateStatus()
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "加载图片失败", Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun saveJson() {
        val anchors = annotationView.getAnchors()
        if (anchors.isEmpty()) return

        val labels = if (currentCategory == ClothingCategory.TOP) topAnchorLabels else pantsAnchorLabels

        val anchorPointsJson = JSONObject()
        for ((label, key) in labels) {
            val point = anchors[key]
            if (point != null) {
                val pointJson = JSONObject().apply {
                    put("x", point.x.toInt())
                    put("y", point.y.toInt())
                }
                anchorPointsJson.put(key, pointJson)
            }
        }

        val bitmapWidth = annotationView.getBitmapWidth()
        val bitmapHeight = annotationView.getBitmapHeight()
        val fileName = "clothing_${System.currentTimeMillis()}_meta.json"

        val json = JSONObject().apply {
            put("id", fileName.removeSuffix("_meta.json"))
            put("name", "")
            put("category", if (currentCategory == ClothingCategory.TOP) "TOP" else "PANTS")
            put("image", "")
            put("thumbnail", "")
            put("imageWidth", bitmapWidth)
            put("imageHeight", bitmapHeight)
            put("anchorPoints", anchorPointsJson)
            put("warpConfig", JSONObject().apply {
                put("type", "PERSPECTIVE")
                put("verticalScale", 1.1)
                put("horizontalScale", 1.2)
            })
        }

        val jsonString = json.toString(2)

        // Show preview dialog
        AlertDialog.Builder(this)
            .setTitle("JSON 预览")
            .setMessage(jsonString)
            .setPositiveButton("复制到剪贴板") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("clothing_config", jsonString))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    companion object {
        private const val REQUEST_PICK_IMAGE = 1001
    }
}

class AnchorAnnotationView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private var bitmapRect = RectF()
    private var displayRect = RectF()
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val anchors = mutableMapOf<String, PointF>()
    private var currentCategory = ClothingCategory.TOP
    private var currentAnchorIndex = 0
    private var draggingKey: String? = null

    var onAnchorChanged: (() -> Unit)? = null

    private val anchorPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val anchorStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    private val nextAnchorPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.argb(150, 255, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val topLabels = listOf("leftShoulder", "rightShoulder", "neckCenter", "leftArmpit", "rightArmpit", "leftHem", "rightHem")
    private val topNames = listOf("左肩", "右肩", "颈中", "左腋", "右腋", "左下摆", "右下摆")

    private val pantsLabels = listOf("leftWaist", "rightWaist", "leftKnee", "rightKnee", "leftHem", "rightHem")
    private val pantsNames = listOf("左腰", "右腰", "左膝", "右膝", "左裤脚", "右裤脚")

    private val currentLabels: List<String> get() = if (currentCategory == ClothingCategory.TOP) topLabels else pantsLabels
    private val currentNames: List<String> get() = if (currentCategory == ClothingCategory.TOP) topNames else pantsNames

    fun setCategory(category: ClothingCategory) {
        currentCategory = category
        currentAnchorIndex = 0
        invalidate()
    }

    fun setBitmap(bm: Bitmap) {
        bitmap = bm
        calculateLayout()
        invalidate()
    }

    fun reset() {
        anchors.clear()
        currentAnchorIndex = 0
        invalidate()
        onAnchorChanged?.invoke()
    }

    fun getAnchors(): Map<String, PointF> = anchors.toMap()
    fun getAnchorCount(): Int = anchors.size
    fun getBitmapWidth(): Int = bitmap?.width ?: 0
    fun getBitmapHeight(): Int = bitmap?.height ?: 0

    private fun calculateLayout() {
        val bm = bitmap ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW == 0f || viewH == 0f) return

        val bmAspect = bm.width.toFloat() / bm.height.toFloat()
        val viewAspect = viewW / viewH

        if (bmAspect > viewAspect) {
            scale = viewW / bm.width
            offsetX = 0f
            offsetY = (viewH - bm.height * scale) / 2f
        } else {
            scale = viewH / bm.height
            offsetX = (viewW - bm.width * scale) / 2f
            offsetY = 0f
        }

        displayRect = RectF(offsetX, offsetY, offsetX + bm.width * scale, offsetY + bm.height * scale)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw bitmap
        bitmap?.let {
            canvas.drawBitmap(it, null, displayRect, null)
        }

        // Draw placed anchors
        for ((key, point) in anchors) {
            val screenX = point.x * scale + offsetX
            val screenY = point.y * scale + offsetY

            canvas.drawCircle(screenX, screenY, 16f, anchorPaint)
            canvas.drawCircle(screenX, screenY, 16f, anchorStrokePaint)

            val idx = currentLabels.indexOf(key)
            val name = if (idx >= 0) currentNames[idx] else key
            canvas.drawText(name, screenX + 20f, screenY - 10f, labelPaint)
        }

        // Draw guide for next anchor to place
        if (currentAnchorIndex < currentLabels.size && currentLabels[currentAnchorIndex] !in anchors) {
            val name = currentNames[currentAnchorIndex]
            canvas.drawText("点击放置: $name", 20f, 40f, labelPaint.apply { color = Color.YELLOW })
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = (event.x - offsetX) / scale
                val y = (event.y - offsetY) / scale

                // Check if touching existing anchor (for dragging)
                val existingKey = findNearestAnchor(event.x, event.y, 30f)
                if (existingKey != null) {
                    draggingKey = existingKey
                    return true
                }

                // Place new anchor
                if (bitmap != null && x >= 0 && y >= 0 && x <= (bitmap?.width ?: 0) && y <= (bitmap?.height ?: 0)) {
                    if (currentAnchorIndex < currentLabels.size) {
                        val key = currentLabels[currentAnchorIndex]
                        anchors[key] = PointF(x, y)
                        currentAnchorIndex++
                        invalidate()
                        onAnchorChanged?.invoke()
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                draggingKey?.let { key ->
                    val x = (event.x - offsetX) / scale
                    val y = (event.y - offsetY) / scale
                    anchors[key] = PointF(x, y)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                draggingKey = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findNearestAnchor(screenX: Float, screenY: Float, threshold: Float): String? {
        for ((key, point) in anchors) {
            val sx = point.x * scale + offsetX
            val sy = point.y * scale + offsetY
            val dist = kotlin.math.sqrt((screenX - sx) * (screenX - sx) + (screenY - sy) * (screenY - sy))
            if (dist < threshold) return key
        }
        return null
    }
}
