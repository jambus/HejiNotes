package com.jambus.heji

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoTransformStateTest {

    @Test
    fun `handles serialization roundtrip preserves coordinates`() {
        val points = listOf(
            PhotoTransformPoint(10.5f, 20.25f),
            PhotoTransformPoint(100f, 15f),
            PhotoTransformPoint(95.75f, 120.5f),
            PhotoTransformPoint(5f, 110f)
        )
        val array = FloatArray(points.size * 2)
        points.forEachIndexed { i, pt ->
            array[i * 2] = pt.x
            array[i * 2 + 1] = pt.y
        }
        val reconstructed = mutableListOf<PhotoTransformPoint>()
        for (i in 0 until array.size / 2) {
            reconstructed.add(PhotoTransformPoint(array[i * 2], array[i * 2 + 1]))
        }
        assertEquals(4, reconstructed.size)
        for (i in points.indices) {
            assertEquals(points[i].x, reconstructed[i].x, 0.001f)
            assertEquals(points[i].y, reconstructed[i].y, 0.001f)
        }
    }

    @Test
    fun `handle coordinates are clamped within bitmap bounds`() {
        val width = 1920f
        val height = 1080f
        val outOfBounds = listOf(
            -10f to -50f,
            2000f to -10f,
            2500f to 1200f,
            -30f to 1500f
        )
        val clamped = outOfBounds.map { (x, y) ->
            PhotoTransformPoint(x.coerceIn(0f, width), y.coerceIn(0f, height))
        }
        assertEquals(0f, clamped[0].x, 0.001f)
        assertEquals(0f, clamped[0].y, 0.001f)
        assertEquals(1920f, clamped[1].x, 0.001f)
        assertEquals(0f, clamped[1].y, 0.001f)
        assertEquals(1920f, clamped[2].x, 0.001f)
        assertEquals(1080f, clamped[2].y, 0.001f)
        assertEquals(0f, clamped[3].x, 0.001f)
        assertEquals(1080f, clamped[3].y, 0.001f)
    }
}
