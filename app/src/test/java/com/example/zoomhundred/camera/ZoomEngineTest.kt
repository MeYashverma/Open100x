package com.example.zoomhundred.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the hybrid zoom arithmetic that the 100× engine depends on.
 * No Android classes are touched, so this runs on every CI push.
 */
class ZoomEngineTest {

    private val engine = ZoomEngine(minNative = 1f, maxNative = 5f, maxTotal = 100f)

    @Test
    fun nativeRangeIsUsedBeforeAnyDigitalCrop() {
        val mapping = engine.map(3f)
        assertEquals(3f, mapping.native, 0.001f)
        assertEquals(1f, mapping.digital, 0.001f)
        assertFalse(mapping.isDigital)
    }

    @Test
    fun beyondNativeRatioTheDifferenceBecomesDigitalCrop() {
        val mapping = engine.map(50f)
        assertEquals(5f, mapping.native, 0.001f)
        assertEquals(10f, mapping.digital, 0.001f)
        assertTrue(mapping.isDigital)
    }

    @Test
    fun totalZoomIsCappedAtOneHundred() {
        assertEquals(100f, engine.maxZoom, 0.001f)
        assertEquals(100f, engine.clamp(5000f), 0.001f)
        assertEquals(100f, engine.map(250f).total, 0.001f)
    }

    @Test
    fun zoomBelowOneIsClampedForNonUltraWideLenses() {
        assertEquals(1f, engine.clamp(0.1f), 0.001f)
    }

    @Test
    fun ultraWideLensCanGoBelowOne() {
        val ultraWide = ZoomEngine(minNative = 0.5f, maxNative = 4f, maxTotal = 100f)
        assertEquals(0.5f, ultraWide.clamp(0.4f), 0.001f)
        assertEquals(0.5f, ultraWide.map(0.5f).native, 0.001f)
    }

    @Test
    fun sliderMappingRoundTripsAcrossTheWholeRange() {
        listOf(1f, 2.5f, 10f, 37f, 100f).forEach { zoom ->
            val fraction = engine.zoomToSlider(zoom)
            assertTrue("fraction for $zoom out of range: $fraction", fraction in 0f..1f)
            assertEquals(zoom, engine.sliderToZoom(fraction), 0.25f)
        }
    }

    @Test
    fun presetsNeverOfferUltraWideOnASingleLensCamera() {
        val presets = engine.presets(ZoomEngine.DEFAULT_STOPS, hasUltraWide = false)
        assertFalse(presets.any { it.ratio < 0.99f })
    }

    @Test
    fun presetsIncludeUltraWideWhenTheHardwareHasIt() {
        val presets = engine.presets(ZoomEngine.DEFAULT_STOPS, hasUltraWide = true, ultraWideMinZoom = 0.5f)
        assertTrue(presets.any { it.ratio < 0.75f })
    }

    @Test
    fun presetsAlwaysReachTheLongEndAndStayUnique() {
        val presets = engine.presets(ZoomEngine.DEFAULT_STOPS, hasUltraWide = false)
        assertEquals(100f, presets.last().ratio, 0.01f)
        assertEquals(presets.size, presets.distinctBy { it.label }.size)
        assertEquals(presets.map { it.ratio }, presets.map { it.ratio }.sorted())
    }

    @Test
    fun dragSensitivityIncreasesWithMagnification() {
        val levels = listOf(1f, 5f, 20f, 45f, 90f).map { engine.dragSensitivity(it) }
        levels.zipWithNext().forEach { (low, high) ->
            assertTrue("sensitivity should grow: $low -> $high", high > low)
        }
    }

    @Test
    fun readoutFormattingIsStableForUiAndExif() {
        assertEquals("1.0×", ZoomEngine.formatReadout(1f))
        assertEquals("12.5×", ZoomEngine.formatReadout(12.5f))
        assertEquals("100×", ZoomEngine.formatReadout(100f))
        assertEquals("0.5×", ZoomEngine.formatLabel(0.5f))
    }

    @Test
    fun focalLengthScalesWithZoom() {
        assertEquals(48f, ZoomEngine.focalLength35mm(2f), 0.001f)
    }
}
