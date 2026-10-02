package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPreviewLayoutTest {
    @Test
    fun `full image corners stay inside safe margin across orientations and densities`() {
        for (density in listOf(1f, 2.75f, 3f)) {
            for ((viewWidthDp, viewHeightDp) in listOf(360 to 560, 560 to 240)) {
                val viewWidth = (viewWidthDp * density).toInt()
                val viewHeight = (viewHeightDp * density).toInt()
                for ((sourceWidth, sourceHeight) in listOf(3000 to 4000, 4000 to 3000, 8000 to 500, 500 to 8000)) {
                    val bounds = PhotoPreviewLayout.fit(viewWidth, viewHeight, sourceWidth, sourceHeight, density)
                    val margin = 32f * density - 0.001f
                    assertTrue(bounds.left >= margin && bounds.top >= margin)
                    assertTrue(viewWidth - bounds.right >= margin && viewHeight - bounds.bottom >= margin)
                    assertEquals(sourceWidth.toFloat() / sourceHeight, bounds.width / bounds.height, 0.001f)
                    assertEquals(viewWidth / 2f, (bounds.left + bounds.right) / 2f, 0.001f)
                    assertEquals(viewHeight / 2f, (bounds.top + bounds.bottom) / 2f, 0.001f)
                    // Full source corners and an interior selection retain their source coordinates.
                    for (fraction in listOf(0f, 0.23f, 1f)) {
                        val x = sourceWidth * fraction
                        val y = sourceHeight * fraction
                        val viewX = bounds.left + x / sourceWidth * bounds.width
                        val viewY = bounds.top + y / sourceHeight * bounds.height
                        assertEquals(x, (viewX - bounds.left) / bounds.width * sourceWidth, 0.01f)
                        assertEquals(y, (viewY - bounds.top) / bounds.height * sourceHeight, 0.01f)
                    }
                }
            }
        }
    }

    @Test
    fun `tiny viewport retains positive centered preview`() {
        val bounds = PhotoPreviewLayout.fit(40, 20, 4000, 2000, 3f)
        assertEquals(PhotoPreviewBounds(10f, 5f, 30f, 15f), bounds)
    }

    @Test
    fun `unmeasured or invalid inputs produce empty preview`() {
        for ((width, height) in listOf(0 to 500, 500 to 0, -1 to 500)) {
            assertEquals(0f, PhotoPreviewLayout.fit(width, height, 4000, 3000, 2f).width, 0f)
        }
        assertEquals(0f, PhotoPreviewLayout.fit(500, 500, 0, 3000, 2f).height, 0f)
        assertEquals(0f, PhotoPreviewLayout.fit(500, 500, 4000, 3000, Float.NaN).width, 0f)
    }
}
