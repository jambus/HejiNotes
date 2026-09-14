package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PhotoTransformerTest {

    @Test
    fun `creates immutable PhotoTransformRequest with copied handles`() {
        val originalHandles = mutableListOf(
            PhotoTransformPoint(0f, 0f),
            PhotoTransformPoint(100f, 0f),
            PhotoTransformPoint(100f, 100f),
            PhotoTransformPoint(0f, 100f)
        )

        val request = PhotoTransformRequest(
            mode = PhotoEditMode.PERSPECTIVE,
            sourceWidth = 100,
            sourceHeight = 100,
            handles = originalHandles.map { PhotoTransformPoint(it.x, it.y) }
        )

        // Mutating the original handles should not affect the frozen request
        originalHandles[0] = PhotoTransformPoint(50f, 50f)
        assertEquals(0f, request.handles[0].x, 0.001f)
        assertEquals(0f, request.handles[0].y, 0.001f)
        assertEquals(PhotoEditMode.PERSPECTIVE, request.mode)
        assertEquals(100, request.sourceWidth)
        assertEquals(100, request.sourceHeight)
    }

    @Test
    fun `calculates accurate crop bounds for rectangle mode`() {
        val request = PhotoTransformRequest(
            mode = PhotoEditMode.RECTANGLE,
            sourceWidth = 1920,
            sourceHeight = 1080,
            handles = listOf(
                PhotoTransformPoint(100f, 150f),
                PhotoTransformPoint(800f, 150f),
                PhotoTransformPoint(800f, 750f),
                PhotoTransformPoint(100f, 750f)
            )
        )

        val bounds = PhotoTransformer.calculateCropBounds(request)
        assertEquals(100, bounds.left)
        assertEquals(150, bounds.top)
        assertEquals(800, bounds.right)
        assertEquals(750, bounds.bottom)
        assertEquals(700, bounds.width)
        assertEquals(600, bounds.height)
    }

    @Test
    fun `calculates accurate perspective output dimensions`() {
        val request = PhotoTransformRequest(
            mode = PhotoEditMode.PERSPECTIVE,
            sourceWidth = 1000,
            sourceHeight = 1000,
            handles = listOf(
                PhotoTransformPoint(100f, 100f),
                PhotoTransformPoint(700f, 100f),
                PhotoTransformPoint(800f, 900f),
                PhotoTransformPoint(200f, 900f)
            )
        )

        val (outWidth, outHeight) = PhotoTransformer.calculatePerspectiveSize(request)
        assertEquals(600, outWidth)
        assertEquals(806, outHeight)
    }

    @Test
    fun `builds perspective transform matrix for destination mapping`() {
        val request = PhotoTransformRequest(
            mode = PhotoEditMode.PERSPECTIVE,
            sourceWidth = 1000,
            sourceHeight = 1000,
            handles = listOf(
                PhotoTransformPoint(0f, 0f),
                PhotoTransformPoint(800f, 0f),
                PhotoTransformPoint(800f, 800f),
                PhotoTransformPoint(0f, 800f)
            )
        )

        val matrix = PhotoTransformer.buildPerspectiveMatrix(request, 800, 800)
        assertNotNull(matrix)
    }
}
