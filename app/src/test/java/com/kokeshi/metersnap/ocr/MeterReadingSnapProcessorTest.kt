package com.kokeshi.metersnap.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterReadingSnapProcessorTest {

    @Test
    fun buildWindows_capsAtMaxAndKeepsCornerCoverage() {
        // The source bitmap is now always a square guidebox crop, so the full image
        // is covered by ≤ 24 windows.  Corner windows must always be present.
        val width = 1920
        val height = 1080

        val windows = MeterReadingSnapProcessor.buildWindows(width, height)

        assertTrue("Window count must be ≤ 24", windows.size <= 24)
        assertTrue("Top-left corner not covered",    windows.any { it.x == 0 && it.y == 0 })
        assertTrue("Top-right corner not covered",   windows.any { it.y == 0 && it.x + it.size == width })
        assertTrue("Bottom-left corner not covered", windows.any { it.x == 0 && it.y + it.size == height })
        assertTrue("Bottom-right corner not covered",windows.any { it.x + it.size == width && it.y + it.size == height })
    }

    @Test
    fun buildWindows_ordersCenterFirst() {
        val width = 1600
        val height = 900
        val centerX = width / 2f
        val centerY = height / 2f

        val windows = MeterReadingSnapProcessor.buildWindows(width, height)

        val first = windows.first()
        val last = windows.last()
        val firstDistance = distance(first.centerX, first.centerY, centerX, centerY)
        val lastDistance = distance(last.centerX, last.centerY, centerX, centerY)

        assertTrue(firstDistance <= lastDistance)
    }

    @Test
    fun assembleWindowReading_buildsCleanFiveDigitReading() {
        val window = WindowSpec(x = 100, y = 200, size = 640)
        val detections = listOf(
            SnapDetection(0, 0.95f, 100f, 220f, 600f, 320f),
            SnapDetection(2, 0.90f, 120f, 225f, 180f, 315f),
            SnapDetection(3, 0.91f, 220f, 225f, 280f, 315f),
            SnapDetection(4, 0.92f, 320f, 225f, 380f, 315f),
            SnapDetection(5, 0.93f, 420f, 225f, 480f, 315f),
            SnapDetection(6, 0.94f, 520f, 225f, 580f, 315f)
        )

        val reading = MeterReadingSnapProcessor.assembleWindowReading(detections, window, 0)

        assertEquals("12345", reading?.readingText)
        assertTrue(reading?.isComplete == true)
        assertTrue(reading?.hasUnreadable == false)
    }

    @Test
    fun assembleWindowReading_marksUnreadableAndMissingSlots() {
        val window = WindowSpec(x = 0, y = 0, size = 640)
        val detections = listOf(
            SnapDetection(0, 0.95f, 100f, 220f, 600f, 320f),
            SnapDetection(2, 0.90f, 120f, 225f, 180f, 315f),
            SnapDetection(11, 0.89f, 220f, 225f, 280f, 315f),
            SnapDetection(4, 0.88f, 320f, 225f, 380f, 315f),
            SnapDetection(6, 0.87f, 520f, 225f, 580f, 315f)
        )

        val reading = MeterReadingSnapProcessor.assembleWindowReading(detections, window, 0)

        assertEquals("1X3?5", reading?.readingText)
        assertTrue(reading?.isComplete == false)
        assertTrue(reading?.hasUnreadable == true)
    }

    @Test
    fun scoreWindowReading_prefersCleanResultOverPartial() {
        val window = WindowSpec(x = 0, y = 0, size = 640)
        val clean = WindowReading(
            readingText = "12345",
            isComplete = true,
            hasUnreadable = false,
            confidence = 3.0f,
            slotCount = 5,
            windowIndex = 0,
            window = window,
            strip = null,
            acceptedDigits = emptyList()
        )
        val partial = WindowReading(
            readingText = "12X4?",
            isComplete = false,
            hasUnreadable = true,
            confidence = 4.5f,
            slotCount = 4,
            windowIndex = 1,
            window = window,
            strip = null,
            acceptedDigits = emptyList()
        )

        assertTrue(MeterReadingSnapProcessor.scoreWindowReading(clean) >
            MeterReadingSnapProcessor.scoreWindowReading(partial))
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt((dx * dx) + (dy * dy))
    }
}
