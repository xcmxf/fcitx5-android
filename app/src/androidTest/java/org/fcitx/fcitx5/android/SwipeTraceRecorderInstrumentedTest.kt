/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.swipe.SwipeCandidate
import org.fcitx.fcitx5.android.input.swipe.SwipeKey
import org.fcitx.fcitx5.android.input.swipe.SwipeLayout
import org.fcitx.fcitx5.android.input.swipe.SwipePoint
import org.fcitx.fcitx5.android.input.swipe.SwipeRecognitionRequest
import org.fcitx.fcitx5.android.input.swipe.SwipeTraceFixtureProfile
import org.fcitx.fcitx5.android.input.swipe.SwipeTraceFixtures
import org.fcitx.fcitx5.android.input.swipe.SwipeTraceRecorder
import org.fcitx.fcitx5.android.input.swipe.SwipeTypingProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SwipeTraceRecorderInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(context) }
    private var wasEnabled = false

    @Before
    fun enableRecording() {
        wasEnabled = preferences.getBoolean(SwipeTraceRecorder.PREFERENCE_KEY, false)
        preferences.edit().putBoolean(SwipeTraceRecorder.PREFERENCE_KEY, true).commit()
    }

    @After
    fun restoreRecordingPreference() {
        preferences.edit().putBoolean(SwipeTraceRecorder.PREFERENCE_KEY, wasEnabled).commit()
    }

    @Test
    fun writesOptInPinyinFixtureWithoutEditorMetadata() {
        val directory = requireNotNull(SwipeTraceRecorder.directory(context))
        val before = directory.listFiles()?.mapTo(mutableSetOf()) { it.name }.orEmpty()
        val request = SwipeRecognitionRequest(
            points = listOf(
                SwipePoint(0.19f, 0.50f, 0f),
                SwipePoint(0.59f, 0.50f, 80f),
                SwipePoint(0.75f, 0.24f, 160f)
            ),
            layout = SwipeLayout(
                listOf(
                    SwipeKey("s", 0.19f, 0.50f),
                    SwipeKey("h", 0.59f, 0.50f),
                    SwipeKey("i", 0.75f, 0.24f)
                )
            ),
            tracedLetters = "shi"
        )

        SwipeTraceRecorder.record(
            context = context,
            profile = SwipeTypingProfile.Pinyin,
            request = request,
            keyboardWidthPx = 1080,
            keyboardHeightPx = 520,
            orientation = 1,
            candidates = listOf(SwipeCandidate("shi", 1f))
        )

        val written = directory.listFiles()
            .orEmpty()
            .filter { it.name !in before }
        try {
            assertEquals(1, written.size)
            val serialized = written.single().readText()
            val fixture = SwipeTraceFixtures.decode(serialized)
            assertNotNull(fixture)
            assertEquals(SwipeTraceFixtureProfile.Pinyin, fixture?.profile)
            assertEquals("shi", fixture?.tracedLetters)
            assertEquals(listOf("shi"), fixture?.pluginCandidates)
            assertFalse(serialized.contains("packageName"))
            assertFalse(serialized.contains("editorText"))
            assertTrue(SwipeTraceFixtures.toRecognitionRequest(requireNotNull(fixture)) != null)
        } finally {
            written.forEach { it.delete() }
        }
    }
}
