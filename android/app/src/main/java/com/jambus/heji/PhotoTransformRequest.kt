package com.jambus.heji

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

data class PhotoTransformPoint(val x: Float, val y: Float)

data class PhotoTransformRequest(
    val mode: PhotoEditMode,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val handles: List<PhotoTransformPoint>
)

object PhotoTransformer {
    fun calculateCropBounds(request: PhotoTransformRequest): ExclusionRect {
        val handles = request.handles
        val left = handles.minOf { it.x }.roundToInt().coerceIn(0, request.sourceWidth - 1)
        val top = handles.minOf { it.y }.roundToInt().coerceIn(0, request.sourceHeight - 1)
        val right = handles.maxOf { it.x }.roundToInt().coerceIn(left + 1, request.sourceWidth)
        val bottom = handles.maxOf { it.y }.roundToInt().coerceIn(top + 1, request.sourceHeight)
        return ExclusionRect(left, top, right, bottom)
    }

    fun calculatePerspectiveSize(request: PhotoTransformRequest): Pair<Int, Int> {
        val handles = request.handles
        val topWidth = distance(handles[0], handles[1])
        val bottomWidth = distance(handles[3], handles[2])
        val leftHeight = distance(handles[0], handles[3])
        val rightHeight = distance(handles[1], handles[2])
        val outWidth = max(1, ((topWidth + bottomWidth) / 2f).roundToInt())
        val outHeight = max(1, ((leftHeight + rightHeight) / 2f).roundToInt())
        return outWidth to outHeight
    }

    fun transform(
        bitmap: Bitmap,
        request: PhotoTransformRequest,
        quality: Int = 92,
        compressor: ((Bitmap, Int) -> ByteArray)? = null
    ): ByteArray {
        val output = when (request.mode) {
            PhotoEditMode.RECTANGLE -> cropBitmap(bitmap, request)
            PhotoEditMode.PERSPECTIVE -> perspectiveBitmap(bitmap, request)
        }
        if (compressor != null) {
            val bytes = compressor(output, quality)
            if (output !== bitmap) output.recycle()
            return bytes
        }
        return ByteArrayOutputStream().use { stream ->
            output.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            if (output !== bitmap) output.recycle()
            stream.toByteArray()
        }
    }

    fun buildPerspectiveMatrix(request: PhotoTransformRequest, outWidth: Int, outHeight: Int): Matrix {
        val handles = request.handles
        val matrix = Matrix()
        val source = floatArrayOf(
            handles[0].x, handles[0].y,
            handles[1].x, handles[1].y,
            handles[2].x, handles[2].y,
            handles[3].x, handles[3].y
        )
        val destination = floatArrayOf(
            0f, 0f,
            outWidth.toFloat(), 0f,
            outWidth.toFloat(), outHeight.toFloat(),
            0f, outHeight.toFloat()
        )
        matrix.setPolyToPoly(source, 0, destination, 0, 4)
        return matrix
    }

    private fun cropBitmap(bitmap: Bitmap, request: PhotoTransformRequest): Bitmap {
        val bounds = calculateCropBounds(request)
        return Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width, bounds.height)
    }

    private fun perspectiveBitmap(bitmap: Bitmap, request: PhotoTransformRequest): Bitmap {
        val (outWidth, outHeight) = calculatePerspectiveSize(request)
        val result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val matrix = buildPerspectiveMatrix(request, outWidth, outHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        Canvas(result).drawBitmap(bitmap, matrix, paint)
        return result
    }

    private fun distance(a: PhotoTransformPoint, b: PhotoTransformPoint): Float = hypot(a.x - b.x, a.y - b.y)
}
