package com.example.zoomhundred.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the manual-control tables against silent desynchronisation. */
class ProSettingsTest {

    @Test
    fun isoTableIsAscendingAndWithinSensorPlausibility() {
        assertTrue(ProSettings.ISO_VALUES.size >= 5)
        assertEquals(ProSettings.ISO_VALUES.sorted().toList(), ProSettings.ISO_VALUES.toList())
        assertTrue(ProSettings.ISO_VALUES.first() >= 25)
        assertTrue(ProSettings.ISO_VALUES.last() <= 25600)
    }

    @Test
    fun shutterValuesDescendInDurationAsIndexGrows() {
        // Index 0 is the longest exposure (e.g. 1/2 s) and the last index the shortest.
        assertTrue(ProSettings.SHUTTER_VALUES.size == ProSettings.SHUTTER_LABELS.size)
        ProSettings.SHUTTER_VALUES.toList().zipWithNext().forEach { (longer, shorter) ->
            assertTrue("shutter table must be ordered longest → shortest", shorter <= longer)
        }
        assertTrue(ProSettings.SHUTTER_LABELS.isNotEmpty())
    }

    @Test
    fun shutterLabelsAreHumanReadable() {
        ProSettings.SHUTTER_LABELS.forEach { label ->
            assertTrue("unexpected shutter label: $label", label.contains("/") || label.endsWith("\""))
        }
    }

    @Test
    fun evTableIsSymmetricAroundZero() {
        val mid = ProSettings.EV_VALUES.size / 2
        assertEquals(0f, ProSettings.EV_VALUES[mid], 0.001f)
        assertEquals(-ProSettings.EV_VALUES.last(), ProSettings.EV_VALUES.first(), 0.001f)
    }

    @Test
    fun indexAccessorsClampInsteadOfThrowing() {
        val pro = ProSettings(isoIndex = 999, shutterIndex = -5, evIndex = 9999)
        assertEquals(ProSettings.ISO_VALUES.last(), pro.isoValue())
        assertEquals(ProSettings.SHUTTER_VALUES.first(), pro.shutterNanos())
        assertEquals(ProSettings.EV_VALUES.last(), pro.evValue(), 0.001f)
    }

    @Test
    fun defaultsAreNeutralAndSafe() {
        val pro = ProSettings()
        assertFalse(pro.manualMode)
        assertFalse(pro.exposureLocked)
        assertEquals(FocusMode.AUTO, pro.focusMode)
        assertEquals(WbMode.AUTO, pro.wbMode)
        assertEquals(0f, pro.evValue(), 0.001f)
    }

    @Test
    fun exposureOverrideRequiresManualModeWithoutLock() {
        assertFalse(ProSettings(manualMode = false).exposureOverrideActive())
        assertTrue(ProSettings(manualMode = true, exposureLocked = false).exposureOverrideActive())
        assertFalse(ProSettings(manualMode = true, exposureLocked = true).exposureOverrideActive())
    }

    @Test
    fun copyOfIsIndependentFromTheLivePanelState() {
        val live = ProSettings(manualMode = true, isoIndex = 1)
        val snapshot = live.copyOf()
        live.isoIndex = 7
        assertNotEquals(snapshot.isoIndex, live.isoIndex)
        assertEquals(1, snapshot.isoIndex)
    }

    @Test
    fun kelvinRangeIsUsable() {
        assertTrue(ProSettings.KELVIN_MIN in 1500..3000)
        assertTrue(ProSettings.KELVIN_MAX in 8000..12000)
    }
}
