package com.jambus.heji

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

enum class PhotoEditMode { RECTANGLE, PERSPECTIVE }

class PhotoEditorView(context: android.content.Context, private val bitmap: Bitmap) : View(context) {
    var mode: PhotoEditMode = PhotoEditMode.RECTANGLE
        set(value) {
            val modeChanged = field != value
            field = value
            selectionVisible = true
            if (value == PhotoEditMode.PERSPECTIVE && modeChanged) positionPerspectiveHandlesInsideEdges()
            updateGestureExclusion()
            invalidate()
        }

    private val handles = arrayOf(
        PointF(0f, 0f),
        PointF(bitmap.width.toFloat(), 0f),
        PointF(bitmap.width.toFloat(), bitmap.height.toFloat()),
        PointF(0f, bitmap.height.toFloat())
    )
    private val imageRect = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(216, 87, 60) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(118, 0, 0, 0) }
    private var activeHandle = -1
    private var selectionVisible = true

    private var lastAppliedExclusionRects: List<ExclusionRect> = emptyList()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val preview = PhotoPreviewLayout.fit(w, h, bitmap.width, bitmap.height, resources.displayMetrics.density)
        imageRect.set(preview.left, preview.top, preview.right, preview.bottom)
        updateGestureExclusion()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, null, imageRect, imagePaint)

        if (selectionVisible) {
            val path = selectionPath()
            if (mode == PhotoEditMode.PERSPECTIVE) {
                canvas.save()
                canvas.clipOutPath(path)
                canvas.drawRect(imageRect, dimPaint)
                canvas.restore()
            }
            canvas.drawPath(path, linePaint)
            handles.forEach { point ->
                canvas.drawCircle(viewX(point.x), viewY(point.y), HANDLE_RADIUS_DP * resources.displayMetrics.density, handlePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!selectionVisible) return false
                activeHandle = nearestHandle(event.x, event.y)
                return activeHandle >= 0
            }
            MotionEvent.ACTION_MOVE -> if (activeHandle >= 0) {
                updateHandle(activeHandle, imageX(event.x), imageY(event.y))
                updateGestureExclusion()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = -1
                updateGestureExclusion()
                return true
            }
        }
        return true
    }

    fun freezeTransformRequest(): PhotoTransformRequest = PhotoTransformRequest(
        mode = mode,
        sourceWidth = bitmap.width,
        sourceHeight = bitmap.height,
        handles = handles.map { PhotoTransformPoint(it.x, it.y) }
    )

    fun getHandles(): List<PointF> = handles.map { PointF(it.x, it.y) }

    fun restoreHandles(points: List<PointF>) {
        if (points.size == 4) {
            points.forEachIndexed { index, point ->
                handles[index].set(
                    point.x.coerceIn(0f, bitmap.width.toFloat()),
                    point.y.coerceIn(0f, bitmap.height.toFloat())
                )
            }
            selectionVisible = true
            updateGestureExclusion()
            invalidate()
        }
    }

    fun outputJpeg(quality: Int = 92): ByteArray {
        return PhotoTransformer.transform(bitmap, freezeTransformRequest(), quality)
    }

    private fun updateHandle(index: Int, x: Float, y: Float) {
        val clampedX = x.coerceIn(0f, bitmap.width.toFloat())
        val clampedY = y.coerceIn(0f, bitmap.height.toFloat())
        if (mode == PhotoEditMode.PERSPECTIVE) {
            handles[index].set(clampedX, clampedY)
            return
        }
        handles[index].set(clampedX, clampedY)
        when (index) {
            0 -> { handles[1].y = clampedY; handles[3].x = clampedX }
            1 -> { handles[0].y = clampedY; handles[2].x = clampedX }
            2 -> { handles[1].x = clampedX; handles[3].y = clampedY }
            3 -> { handles[0].x = clampedX; handles[2].y = clampedY }
        }
    }

    private fun positionPerspectiveHandlesInsideEdges() {
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return
        val horizontalInset = (EDGE_SAFE_INSET_DP * resources.displayMetrics.density /
            imageRect.width() * bitmap.width).coerceAtMost(bitmap.width / 4f)
        val verticalInset = (EDGE_SAFE_INSET_DP * resources.displayMetrics.density /
            imageRect.height() * bitmap.height).coerceAtMost(bitmap.height / 4f)
        handles[0].set(horizontalInset, verticalInset)
        handles[1].set(bitmap.width - horizontalInset, verticalInset)
        handles[2].set(bitmap.width - horizontalInset, bitmap.height - verticalInset)
        handles[3].set(horizontalInset, bitmap.height - verticalInset)
    }

    private fun selectionPath(): Path = Path().apply {
        moveTo(viewX(handles[0].x), viewY(handles[0].y))
        for (index in 1..3) lineTo(viewX(handles[index].x), viewY(handles[index].y))
        close()
    }

    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val newRects = PhotoGestureExclusionPolicy.computeExclusionRects(
            mode = mode,
            handles = handles.map { viewX(it.x) - imageRect.left to viewY(it.y) - imageRect.top },
            imageLeft = imageRect.left,
            imageTop = imageRect.top,
            imageWidth = imageRect.width(),
            imageHeight = imageRect.height(),
            density = resources.displayMetrics.density,
            viewWidth = width,
            viewHeight = height
        )
        if (newRects != lastAppliedExclusionRects) {
            lastAppliedExclusionRects = newRects
            systemGestureExclusionRects = newRects.map { it.toAndroidRect() }
        }
    }

    private fun nearestHandle(x: Float, y: Float): Int {
        var nearest = -1
        var distance = Float.MAX_VALUE
        handles.forEachIndexed { index, point ->
            val candidate = hypot(viewX(point.x) - x, viewY(point.y) - y)
            if (candidate < distance && candidate <= HANDLE_TOUCH_RADIUS_DP * resources.displayMetrics.density) {
                distance = candidate
                nearest = index
            }
        }
        return nearest
    }

    private fun viewX(imageX: Float): Float = imageRect.left + imageX / bitmap.width * imageRect.width()
    private fun viewY(imageY: Float): Float = imageRect.top + imageY / bitmap.height * imageRect.height()
    private fun imageX(viewX: Float): Float = ((viewX - imageRect.left) / imageRect.width() * bitmap.width)
    private fun imageY(viewY: Float): Float = ((viewY - imageRect.top) / imageRect.height() * bitmap.height)

    companion object {
        private const val EDGE_SAFE_INSET_DP = 40f
        private const val HANDLE_RADIUS_DP = 6f
        private const val HANDLE_TOUCH_RADIUS_DP = 24f
    }
}
