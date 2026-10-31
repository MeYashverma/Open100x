package com.example.zoomhundred.resources

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two `activity_main` configurations against drift.
 *
 * ViewBinding generates `ActivityMainBinding` from `layout/activity_main.xml` and resolves
 * ids with `findViewById` at inflation time. If a configuration variant (landscape, sw600dp,
 * …) is missing one of those ids, inflation throws
 * `NullPointerException: Missing required view with ID …` and the screen dies. Keeping the id
 * *sets* identical across variants is therefore a hard requirement, not a style preference.
 */
class LayoutParityTest {

    @Test
    fun everyActivityMainVariantDeclaresTheSameViews() {
        val root = resourceRoot()
        val base = File(root, "layout/activity_main.xml")
        assertTrue("missing ${base.path}", base.isFile)

        val baseIds = ids(base)
        assertTrue("no ids found in ${base.path}", baseIds.isNotEmpty())

        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("layout") && it.name != "layout" }
            .map { File(it, "activity_main.xml") }
            .filter { it.isFile }
            .forEach { variant ->
                assertEquals(
                    "view ids differ between layout/ and ${variant.parentFile.name}/activity_main.xml",
                    baseIds.sorted(),
                    ids(variant).sorted()
                )
            }
    }

    private fun ids(file: File): Set<String> =
        Regex("android:id=\"@\\+id/([A-Za-z0-9_]+)\"").findAll(file.readText())
            .map { it.groupValues[1] }.toSet()

    private fun resourceRoot(): File =
        listOf(File("src/main/res"), File("app/src/main/res"), File("../app/src/main/res"))
            .firstOrNull { it.isDirectory }
            ?: throw AssertionError("res directory not found from ${File(".").absolutePath}")
}
