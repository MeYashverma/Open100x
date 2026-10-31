package com.example.zoomhundred.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Verifies the dependency-free .cube parser and its trilinear interpolation. */
class LutLoaderTest {

    private val identityCube = """
        # identity 2x2x2 LUT
        TITLE "Identity"
        LUT_3D_SIZE 2
        DOMAIN_MIN 0.0 0.0 0.0
        DOMAIN_MAX 1.0 1.0 1.0
        0.0 0.0 0.0
        1.0 0.0 0.0
        0.0 1.0 0.0
        1.0 1.0 0.0
        0.0 0.0 1.0
        1.0 0.0 1.0
        0.0 1.0 1.0
        1.0 1.0 1.0
    """.trimIndent()

    @Test
    fun parses3dCubeWithDeclaredSize() {
        val lut = LutLoader.parse(ByteArrayInputStream(identityCube.toByteArray()), "identity")
        assertNotNull(lut)
        assertTrue(lut is Lut3D)
        assertEquals(2, (lut as Lut3D).size)
        assertEquals("identity", lut.name)
    }

    @Test
    fun identityCubeMapsExtremesExactly() {
        val lut = LutLoader.parse(ByteArrayInputStream(identityCube.toByteArray()), "identity") as Lut3D
        val out = FloatArray(3)

        lut.map(0f, 0f, 0f, out)
        assertEquals(0f, out[0], 0.001f)
        assertEquals(0f, out[1], 0.001f)
        assertEquals(0f, out[2], 0.001f)

        lut.map(1f, 1f, 1f, out)
        assertEquals(1f, out[0], 0.001f)
        assertEquals(1f, out[1], 0.001f)
        assertEquals(1f, out[2], 0.001f)
    }

    @Test
    fun intermediateValuesAreInterpolated() {
        val lut = LutLoader.parse(ByteArrayInputStream(identityCube.toByteArray()), "identity") as Lut3D
        val out = FloatArray(3)
        lut.map(0.5f, 0.5f, 0.5f, out)
        assertEquals(0.5f, out[0], 0.01f)
        assertEquals(0.5f, out[1], 0.01f)
        assertEquals(0.5f, out[2], 0.01f)
    }

    @Test
    fun parses1dCube() {
        val cube1d = """
            LUT_1D_SIZE 2
            0.0 0.0 0.0
            1.0 1.0 1.0
        """.trimIndent()
        val lut = LutLoader.parse(ByteArrayInputStream(cube1d.toByteArray()), "mono")
        assertNotNull(lut)
        assertTrue(lut is Lut1D)
        val out = FloatArray(3)
        (lut as Lut1D).map(0.5f, 0.5f, 0.5f, out)
        assertEquals(0.5f, out[0], 0.01f)
    }

    @Test
    fun malformedInputReturnsNullInsteadOfThrowing() {
        assertNull(LutLoader.parse(ByteArrayInputStream("not a lut at all".toByteArray()), "broken"))
        assertNull(LutLoader.parse(ByteArrayInputStream(ByteArray(0)), "empty"))
        assertNull(LutLoader.parse(ByteArrayInputStream("LUT_3D_SIZE 2\n0 0 0\n".toByteArray()), "short"))
    }

    @Test
    fun domainRangeIsRespected() {
        val logCube = """
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 2.0 2.0 2.0
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()
        val lut = LutLoader.parse(ByteArrayInputStream(logCube.toByteArray()), "wide-domain") as Lut3D
        val out = FloatArray(3)
        lut.map(1f, 1f, 1f, out)
        // 1.0 inside a 0..2 domain lands mid-LUT, so the identity table returns ~0.5.
        assertEquals(0.5f, out[0], 0.05f)
    }
}
