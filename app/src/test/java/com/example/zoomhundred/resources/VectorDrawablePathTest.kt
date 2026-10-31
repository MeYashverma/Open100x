package com.example.zoomhundred.resources

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Guards the vector drawables against path data that compiles but cannot be inflated.
 *
 * Android's `PathParser` rejects a path whose command parameters are not an exact multiple
 * of the command's arity, e.g. `a8 8 0 100-16`: the parser reads `100` as a single number
 * instead of the two arc flags `1` and `0`, so the arc ends up with 10 parameters instead of
 * 7. That throws `Resources$NotFoundException` → `InflateException` at runtime, which killed
 * the app on launch once already (`ic_timer.xml`). aapt does not validate path data, so this
 * test is the only place that can catch it before a device does.
 */
class VectorDrawablePathTest {

    private val arity = mapOf(
        'M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'C' to 6,
        'S' to 4, 'Q' to 4, 'T' to 2, 'A' to 7, 'Z' to 0
    )

    private val commands = "MmLlHhVvCcSsQqTtAaZz"

    @Test
    fun everyPathDataParsesLikeAndroidPathParser() {
        val resDirs = resRoots()
        assertTrue("no res directory found from ${File(".").absolutePath}", resDirs.isNotEmpty())

        var checked = 0
        val failures = ArrayList<String>()

        resDirs.forEach { res ->
            res.walkTopDown().filter { it.isFile && it.extension == "xml" }.forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                Regex("android:pathData=\"([^\"]*)\"").findAll(text).forEach { match ->
                    checked++
                    val path = match.groupValues[1]
                    commandParameterProblem(path)?.let { problem ->
                        failures += "${file.path}: $problem"
                    }
                }
            }
        }

        assertTrue("no vector path data found to validate", checked > 0)
        if (failures.isNotEmpty()) {
            fail("Invalid android:pathData (would crash at inflate time):\n" + failures.joinToString("\n"))
        }
    }

    /** Mirrors PathParser's tokenising rules; returns a description of the first problem. */
    internal fun commandParameterProblem(path: String): String? {
        val tokens = Regex("[MmLlHhVvCcSsQqTtAaZz]|[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")
            .findAll(path).map { it.value }.toList()
        var command: Char? = null
        var count = 0
        tokens.forEach { token ->
            if (token.length == 1 && token[0] in commands) {
                command?.let { current ->
                    val needed = arity.getValue(current.uppercaseChar())
                    if (needed != 0 && count % needed != 0) {
                        return "command '$current' received $count numbers, needs a multiple of $needed — $path"
                    }
                }
                command = token[0]
                count = 0
                if (token[0] in "Zz") command = null
            } else {
                count++
            }
        }
        command?.let { current ->
            val needed = arity.getValue(current.uppercaseChar())
            if (needed != 0 && count % needed != 0) {
                return "command '$current' received $count numbers, needs a multiple of $needed — $path"
            }
        }
        return null
    }

    /** Finds `app/src/main/res` from the Gradle test working directory (the module dir). */
    private fun resRoots(): List<File> {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File("../app/src/main/res")
        )
        return candidates.filter { it.isDirectory }
    }
}
