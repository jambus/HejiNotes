package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoGestureExclusionPolicyTest {

    @Test
    fun `returns empty list when mode is RECTANGLE`() {
        val handles = listOf(
            0f to 0f,
            100f to 0f,
            100f to 100f,
            0f to 100f
        )
        val rects = PhotoGestureExclusionPolicy.computeExclusionRects(
            mode = PhotoEditMode.RECTANGLE,
            handles = handles,
            imageLeft = 0f,
            imageTop = 0f,
            imageWidth = 1000f,
            imageHeight = 1000f,
            density = 2f,
            viewWidth = 1000,
            viewHeight = 1000
        )
        assertTrue("Rectangle mode must not exclude system gestures", rects.isEmpty())
    }

    @Test
    fun `clears rects when switching out of PERSPECTIVE mode`() {
        val handles = listOf(
            10f to 10f,
            90f to 10f,
            90f to 90f,
            10f to 90f
        )

        val perspectiveRects = PhotoGestureExclusionPolicy.computeExclusionRects(
            mode = PhotoEditMode.PERSPECTIVE,
            handles = handles,
            imageLeft = 0f,
            imageTop = 0f,
            imageWidth = 500f,
            imageHeight = 500f,
            density = 2f,
            viewWidth = 500,
            viewHeight = 500
        )
        assertTrue("Perspective mode must produce exclusion rects", perspectiveRects.isNotEmpty())

        val rectangleRects = PhotoGestureExclusionPolicy.computeExclusionRects(
            mode = PhotoEditMode.RECTANGLE,
            handles = handles,
            imageLeft = 0f,
            imageTop = 0f,
            imageWidth = 500f,
            imageHeight = 500f,
            density = 2f,
            viewWidth = 500,
            viewHeight = 500
        )
        assertTrue("Switching to rectangle mode must clear exclusion rects", rectangleRects.isEmpty())
    }

    @Test
    fun `deduplicates identical exclusion rects and constrains to bounds`() {
        val handles = listOf(
            0f to 0f,
            0f to 0f,
            0f to 0f,
            0f to 0f
        )
        val rects = PhotoGestureExclusionPolicy.computeExclusionRects(
            mode = PhotoEditMode.PERSPECTIVE,
            handles = handles,
            imageLeft = 0f,
            imageTop = 0f,
            imageWidth = 400f,
            imageHeight = 400f,
            density = 2f,
            viewWidth = 400,
            viewHeight = 400
        )

        // All rects calculated from identical handles must be deduplicated
        val distinctCount = rects.distinct().size
        assertEquals(distinctCount, rects.size)

        rects.forEach { rect ->
            assertTrue("Rect left must be within bounds", rect.left >= 0)
            assertTrue("Rect top must be within bounds", rect.top >= 0)
            assertTrue("Rect right must be within bounds", rect.right <= 400)
            assertTrue("Rect bottom must be within bounds", rect.bottom <= 400)
        }
    }
}
