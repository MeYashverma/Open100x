package com.example.zoomhundred

import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation smoke tests for the surfaces that do not need camera hardware or media
 * permissions: the settings screen must build its twelve categories, and the gallery must
 * open even when the album is empty.
 *
 * Run with `./gradlew connectedDebugAndroidTest` on a device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class ScreenSmokeTest {

    @Test
    fun settingsScreenRendersItsCategories() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            onView(withId(R.id.settingsTitle)).check(matches(isDisplayed()))
            onView(withId(R.id.settingsBack)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val container = activity.findViewById<LinearLayout>(R.id.settingsContainer)
                assertTrue("settings container should be populated", container.childCount > 0)
            }
        }
    }

    @Test
    fun galleryScreenOpensWithEmptyState() {
        ActivityScenario.launch(GalleryActivity::class.java).use {
            onView(withId(R.id.galleryTitle)).check(matches(isDisplayed()))
            onView(withId(R.id.galleryGrid)).check(matches(isDisplayed()))
        }
    }
}
