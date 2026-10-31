package com.example.zoomhundred.imaging

import java.io.File
import java.io.InputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal, dependency-free Adobe `.cube` LUT support.
 *
 * Both 3D (`LUT_3D_SIZE`, red index fastest) and 1D (`LUT_1D_SIZE`) LUTs are parsed.
 * Nothing is bundled with the app: LUTs are imported by the user and stored in
 * app-private storage (see [LutStore]).
 */
sealed class CubeLut {
    abstract val name: String

    abstract fun map(r: Float, g: Float, b: Float, out: FloatArray)
}

class Lut3D(
    override val name: String,
    val size: Int,
    private val data: FloatArray, // rgb triples, index = ((b * size + g) * size + r) * 3
    private val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    private val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f)
) : CubeLut() {

    private fun at(r: Int, g: Int, b: Int, c: Int): Float {
        val i = ((b * size + g) * size + r) * 3 + c
        return data[i]
    }

    override fun map(r: Float, g: Float, b: Float, out: FloatArray) {
        val nr = normalise(r, 0)
        val ng = normalise(g, 1)
        val nb = normalise(b, 2)
        val fr = nr * (size - 1)
        val fg = ng * (size - 1)
        val fb = nb * (size - 1)
        val r0 = floor(fr).toInt().coerceIn(0, size - 1)
        val g0 = floor(fg).toInt().coerceIn(0, size - 1)
        val b0 = floor(fb).toInt().coerceIn(0, size - 1)
        val r1 = min(r0 + 1, size - 1)
        val g1 = min(g0 + 1, size - 1)
        val b1 = min(b0 + 1, size - 1)
        val dr = (fr - r0).coerceIn(0f, 1f)
        val dg = (fg - g0).coerceIn(0f, 1f)
        val db = (fb - b0).coerceIn(0f, 1f)

        for (c in 0..2) {
            val c000 = at(r0, g0, b0, c)
            val c100 = at(r1, g0, b0, c)
            val c010 = at(r0, g1, b0, c)
            val c110 = at(r1, g1, b0, c)
            val c001 = at(r0, g0, b1, c)
            val c101 = at(r1, g0, b1, c)
            val c011 = at(r0, g1, b1, c)
            val c111 = at(r1, g1, b1, c)
            val c00 = c000 + (c100 - c000) * dr
            val c10 = c010 + (c110 - c010) * dr
            val c01 = c001 + (c101 - c001) * dr
            val c11 = c011 + (c111 - c011) * dr
            val c0 = c00 + (c10 - c00) * dg
            val c1 = c01 + (c11 - c01) * dg
            out[c] = c0 + (c1 - c0) * db
        }
    }

    private fun normalise(value: Float, channel: Int): Float {
        val lo = domainMin[channel]
        val hi = domainMax[channel]
        val span = if (hi - lo <= 0f) 1f else hi - lo
        return ((value - lo) / span).coerceIn(0f, 1f)
    }
}

class Lut1D(
    override val name: String,
    val size: Int,
    private val red: FloatArray,
    private val green: FloatArray,
    private val blue: FloatArray
) : CubeLut() {

    override fun map(r: Float, g: Float, b: Float, out: FloatArray) {
        out[0] = sample(red, r)
        out[1] = sample(green, g)
        out[2] = sample(blue, b)
    }

    private fun sample(table: FloatArray, value: Float): Float {
        val pos = value.coerceIn(0f, 1f) * (size - 1)
        val i0 = floor(pos).toInt().coerceIn(0, size - 1)
        val i1 = min(i0 + 1, size - 1)
        val t = (pos - i0).coerceIn(0f, 1f)
        return table[i0] + (table[i1] - table[i0]) * t
    }
}

object LutLoader {

    /** Parses a .cube stream. Returns null for anything malformed (never throws). */
    fun parse(stream: InputStream, name: String): CubeLut? = try {
        var size3d = 0
        var size1d = 0
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<Float>(1 shl 16)

        stream.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE")) return@forEach
                when {
                    line.startsWith("LUT_3D_SIZE") -> size3d = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0
                    line.startsWith("LUT_1D_SIZE") -> size1d = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0
                    line.startsWith("DOMAIN_MIN") -> domainMin = parseTriple(line) ?: domainMin
                    line.startsWith("DOMAIN_MAX") -> domainMax = parseTriple(line) ?: domainMax
                    line[0].isDigit() || line[0] == '-' || line[0] == '.' || line[0] == '+' -> {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 3) {
                            values.add(parts[0].toFloatOrNull() ?: return@forEach)
                            values.add(parts[1].toFloatOrNull() ?: return@forEach)
                            values.add(parts[2].toFloatOrNull() ?: return@forEach)
                        }
                    }
                }
            }
        }

        when {
            size3d in 2..128 && values.size >= size3d * size3d * size3d * 3 ->
                Lut3D(name, size3d, values.toFloatArray(), domainMin, domainMax)
            size1d in 2..65536 && values.size >= size1d * 3 -> {
                val r = FloatArray(size1d)
                val g = FloatArray(size1d)
                val b = FloatArray(size1d)
                for (i in 0 until size1d) {
                    r[i] = values[i * 3]
                    g[i] = values[i * 3 + 1]
                    b[i] = values[i * 3 + 2]
                }
                Lut1D(name, size1d, r, g, b)
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun parseTriple(line: String): FloatArray? {
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 4) return null
        return floatArrayOf(
            parts[1].toFloatOrNull() ?: return null,
            parts[2].toFloatOrNull() ?: return null,
            parts[3].toFloatOrNull() ?: return null
        )
    }

    fun load(file: File): CubeLut? = try {
        file.inputStream().use { parse(it, file.nameWithoutExtension) }
    } catch (_: Exception) {
        null
    }
}

/** Import/list/delete user LUTs. Files live in `filesDir/luts`. */
object LutStore {

    fun dir(baseDir: File): File = File(baseDir, "luts").apply { if (!exists()) mkdirs() }

    fun list(baseDir: File): List<File> = dir(baseDir).listFiles()
        ?.filter { it.isFile && it.name.endsWith(".cube", ignoreCase = true) }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()

    fun importStream(baseDir: File, suggestedName: String, stream: InputStream): File? {
        val safe = suggestedName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_\\- ]"), "_")
            .ifBlank { "lut" }
        val target = File(dir(baseDir), "$safe.cube")
        return try {
            stream.use { input -> target.outputStream().use { input.copyTo(it) } }
            // Validate: refuse to keep a file we cannot parse.
            if (LutLoader.load(target) == null) {
                target.delete()
                null
            } else {
                target
            }
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    fun delete(baseDir: File, name: String): Boolean {
        val file = File(dir(baseDir), name)
        return file.exists() && file.delete()
    }

    fun loadByName(baseDir: File, name: String?): CubeLut? {
        if (name.isNullOrBlank()) return null
        val f = list(baseDir).firstOrNull { it.name.equals(name, true) || it.nameWithoutExtension == name }
            ?: return null
        return LutLoader.load(f)
    }

    /** Deletes LUTs beyond a sane count so storage cannot creep. */
    fun prune(baseDir: File, keep: Int = 24) {
        list(baseDir).dropLast(max(0, list(baseDir).size - keep)).forEach { it.delete() }
    }
}
