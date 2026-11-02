package com.qishimeng.game.floatball

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingBallLayoutTest {
    @Test
    fun configuredSizeUsesDpWithoutViewportWidthScaling() {
        assertEquals(60, FloatingBallLayout.toPixels(20, density = 3f))
        assertEquals(40, FloatingBallLayout.toPixels(20, density = 2f))
        assertEquals(30, FloatingBallLayout.toPixels(20, density = 1.5f))
    }

    @Test
    fun oneAndLargerOffsetsUseDpWhileRatiosIgnoreDensity() {
        assertEquals(3, FloatingBallLayout.resolveTop(1.0, null, 1000, 100, density = 3f))
        assertEquals(897, FloatingBallLayout.resolveTop(null, 1.0, 1000, 100, density = 3f))
        assertEquals(60, FloatingBallLayout.resolveTop(20.0, null, 1000, 100, density = 3f))
        assertEquals(840, FloatingBallLayout.resolveTop(null, 20.0, 1000, 100, density = 3f))
        assertEquals(200, FloatingBallLayout.resolveTop(0.2, null, 1000, 100, density = 3f))
        assertEquals(700, FloatingBallLayout.resolveTop(null, 0.2, 1000, 100, density = 3f))
        assertEquals(60, FloatingBallLayout.resolveTop(20.0, null, 600, 100, density = 3f))
        assertEquals(0, FloatingBallLayout.resolveTop(0.0, 20.0, 1000, 100, density = 3f))
        assertEquals(3, FloatingBallLayout.resolveTop(2.5, null, 1000, 100, density = 1.5f))
        assertEquals(900, FloatingBallLayout.resolveTop(Double.MAX_VALUE, null, 1000, 100, density = 3f))
        assertEquals(0, FloatingBallLayout.resolveTop(null, Double.MAX_VALUE, 1000, 100, density = 3f))
    }

    @Test
    fun topWinsOverBottomIncludingZero() {
        assertEquals(200, FloatingBallLayout.resolveTop(0.2, 10.0, 1000, 100))
        assertEquals(0, FloatingBallLayout.resolveTop(0.0, 0.2, 1000, 100))
    }

    @Test
    fun missingOffsetsStartAtTopAndBottomZeroAlignsBottomEdge() {
        assertEquals(0, FloatingBallLayout.resolveTop(null, null, 1000, 100))
        assertEquals(900, FloatingBallLayout.resolveTop(null, 0.0, 1000, 100))
    }

    @Test
    fun fractionsUseFullContainerHeightAndBottomMeasuresFromBallBottomEdge() {
        assertEquals(200, FloatingBallLayout.resolveTop(0.2, null, 1000, 100))
        assertEquals(700, FloatingBallLayout.resolveTop(null, 0.2, 1000, 100))
        assertEquals(60, FloatingBallLayout.resolveTop(0.2, null, 300, 100))
        assertEquals(140, FloatingBallLayout.resolveTop(null, 0.2, 300, 100))
    }

    @Test
    fun oneAndLargerOffsetsUseDpAtUnitDensity() {
        assertEquals(1, FloatingBallLayout.resolveTop(1.0, null, 1000, 100))
        assertEquals(899, FloatingBallLayout.resolveTop(null, 1.0, 1000, 100))
        assertEquals(200, FloatingBallLayout.resolveTop(200.0, null, 1000, 100))
        assertEquals(200, FloatingBallLayout.resolveTop(200.0, null, 600, 100))
        assertEquals(700, FloatingBallLayout.resolveTop(null, 200.0, 1000, 100))
        assertEquals(2, FloatingBallLayout.resolveTop(2.9, null, 1000, 100))
    }

    @Test
    fun positionsAreClampedWithoutOverflowAndFractionalCoordinatesAreTruncated() {
        assertEquals(900, FloatingBallLayout.resolveTop(0.99, null, 1000, 100))
        assertEquals(0, FloatingBallLayout.resolveTop(null, 0.99, 1000, 100))
        assertEquals(900, FloatingBallLayout.resolveTop(Double.MAX_VALUE, null, 1000, 100))
        assertEquals(0, FloatingBallLayout.resolveTop(null, Double.MAX_VALUE, 1000, 100))
        assertEquals(0, FloatingBallLayout.resolveTop(0.2, null, 100, 200))
        assertEquals(66, FloatingBallLayout.resolveTop(0.2, null, 333, 100))
        assertEquals(166, FloatingBallLayout.resolveTop(null, 0.2, 333, 100))
    }
}
