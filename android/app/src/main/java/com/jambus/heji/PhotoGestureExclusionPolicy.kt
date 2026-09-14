package com.jambus.heji

import android.graphics.Rect
import kotlin.math.roundToInt

data class ExclusionRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)
}

object PhotoGestureExclusionPolicy {
    const val GESTURE_EXCLUSION_RADIUS_DP = 40f

    fun computeExclusionRects(
        mode: PhotoEditMode,
        handles: List<Pair<Float, Float>>,
        imageLeft: Float,
        imageTop: Float,
        imageWidth: Float,
        imageHeight: Float,
        density: Float,
        viewWidth: Int,
        viewHeight: Int
    ): List<ExclusionRect> {
        if (mode != PhotoEditMode.PERSPECTIVE) {
            return emptyList()
        }
        if (handles.size < 4 || imageWidth <= 0f || imageHeight <= 0f || viewWidth <= 0 || viewHeight <= 0) {
            return emptyList()
        }
        fun viewX(imageX: Float): Float = imageLeft + imageX / imageWidth * imageWidth
        fun viewY(imageY: Float): Float = imageTop + imageY / imageHeight * imageHeight

        val radius = (GESTURE_EXCLUSION_RADIUS_DP * density).roundToInt()

        val topLeftX = (imageLeft + handles[0].first).roundToInt()
        val topLeftY = (imageTop + handles[0].second).roundToInt()

        val topRightX = (imageLeft + handles[1].first).roundToInt()
        val topRightY = (imageTop + handles[1].second).roundToInt()

        val bottomRightX = (imageLeft + handles[2].first).roundToInt()
        val bottomRightY = (imageTop + handles[2].second).roundToInt()

        val bottomLeftX = (imageLeft + handles[3].first).roundToInt()
        val bottomLeftY = (imageTop + handles[3].second).roundToInt()

        val rects = listOf(
            ExclusionRect(
                0,
                (topLeftY - radius).coerceIn(0, viewHeight),
                (topLeftX + radius).coerceIn(0, viewWidth),
                (topLeftY + radius).coerceIn(0, viewHeight)
            ),
            ExclusionRect(
                0,
                (bottomLeftY - radius).coerceIn(0, viewHeight),
                (bottomLeftX + radius).coerceIn(0, viewWidth),
                (bottomLeftY + radius).coerceIn(0, viewHeight)
            ),
            ExclusionRect(
                (topRightX - radius).coerceIn(0, viewWidth),
                (topRightY - radius).coerceIn(0, viewHeight),
                viewWidth,
                (topRightY + radius).coerceIn(0, viewHeight)
            ),
            ExclusionRect(
                (bottomRightX - radius).coerceIn(0, viewWidth),
                (bottomRightY - radius).coerceIn(0, viewHeight),
                viewWidth,
                (bottomRightY + radius).coerceIn(0, viewHeight)
            )
        )

        return rects.filter { it.width > 0 && it.height > 0 }.distinct()
    }
}
