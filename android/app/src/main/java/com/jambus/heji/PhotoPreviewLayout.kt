package com.jambus.heji

import kotlin.math.min

data class PhotoPreviewBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Fits the complete photo inside an inset viewport without changing source selection coordinates. */
object PhotoPreviewLayout {
    private const val INSET_DP = 32f

    fun fit(viewWidth: Int, viewHeight: Int, sourceWidth: Int, sourceHeight: Int, density: Float): PhotoPreviewBounds {
        if (viewWidth <= 0 || viewHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0 ||
            !density.isFinite() || density <= 0f) return PhotoPreviewBounds(0f, 0f, 0f, 0f)

        val horizontalInset = min(INSET_DP * density, viewWidth / 4f)
        val verticalInset = min(INSET_DP * density, viewHeight / 4f)
        val scale = min((viewWidth - 2f * horizontalInset) / sourceWidth,
            (viewHeight - 2f * verticalInset) / sourceHeight)
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        val left = (viewWidth - width) / 2f
        val top = (viewHeight - height) / 2f
        return PhotoPreviewBounds(left, top, left + width, top + height)
    }
}
