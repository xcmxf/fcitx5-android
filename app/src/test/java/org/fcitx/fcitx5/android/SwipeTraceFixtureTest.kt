/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.swipe.SwipeCandidate
import org.fcitx.fcitx5.android.input.swipe.SwipeKey
import org.fcitx.fcitx5.android.input.swipe.SwipeLayout
import org.fcitx.fcitx5.android.input.swipe.SwipePoint
import org.fcitx.fcitx5.android.input.swipe.SwipeRecognitionRequest
import org.fcitx.fcitx5.android.input.swipe.SwipeTraceFixtureProfile
import org.fcitx.fcitx5.android.input.swipe.SwipeTraceFixtures
import org.fcitx.fcitx5.android.input.swipe.SwipeTypingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeTraceFixtureTest {

    @Test
    fun fixtureRoundTripPreservesReplayInputsAndOnlyDebugMetadata() {
        val request = SwipeRecognitionRequest(
            points = listOf(
                SwipePoint(0.1f, 0.2f, 0f),
                SwipePoint(0.4f, 0.4f, 24f),
                SwipePoint(0.8f, 0.5f, 48f)
            ),
            layout = SwipeLayout(
                listOf(
                    SwipeKey("a", 0.1f, 0.5f),
                    SwipeKey("b", 0.4f, 0.5f),
                    SwipeKey("c", 0.8f, 0.5f)
                )
            ),
            tracedLetters = "A-bc!"
        )

        val fixture = requireNotNull(
            SwipeTraceFixtures.create(
                id = "pinyin-replay",
                profile = SwipeTypingProfile.Pinyin,
                request = request,
                keyboardWidthPx = 1080,
                keyboardHeightPx = 520,
                orientation = 1,
                candidates = listOf(
                    SwipeCandidate("NiHao", 1f),
                    SwipeCandidate("nihao", 0.9f),
                    SwipeCandidate("", 0.8f)
                )
            )
        )

        val serialized = SwipeTraceFixtures.encode(fixture)
        val decoded = requireNotNull(SwipeTraceFixtures.decode(serialized))
        val replay = requireNotNull(SwipeTraceFixtures.toRecognitionRequest(decoded))

        assertEquals(SwipeTraceFixtureProfile.Pinyin, decoded.profile)
        assertEquals("abc", decoded.tracedLetters)
        assertEquals(listOf("nihao"), decoded.pluginCandidates)
        assertEquals(request.points, replay.points)
        assertEquals(request.layout.letters, replay.layout.letters)
        assertFalse(serialized.contains("packageName"))
        assertFalse(serialized.contains("editorText"))
    }

    @Test
    fun fixtureRejectsUnknownSchemaAndUnsupportedProfiles() {
        val request = SwipeRecognitionRequest(
            points = listOf(
                SwipePoint(0.1f, 0.2f, 0f),
                SwipePoint(0.4f, 0.4f, 24f),
                SwipePoint(0.8f, 0.5f, 48f)
            ),
            layout = SwipeLayout(listOf(SwipeKey("a", 0.1f, 0.5f))),
            tracedLetters = "a"
        )
        assertNull(
            SwipeTraceFixtures.create(
                id = "unsupported",
                profile = SwipeTypingProfile.Unsupported,
                request = request,
                keyboardWidthPx = 1080,
                keyboardHeightPx = 520,
                orientation = 1,
                candidates = emptyList()
            )
        )

        val valid = requireNotNull(
            SwipeTraceFixtures.create(
                id = "valid",
                profile = SwipeTypingProfile.English,
                request = request,
                keyboardWidthPx = 1080,
                keyboardHeightPx = 520,
                orientation = 1,
                candidates = emptyList()
            )
        )
        assertNull(SwipeTraceFixtures.toRecognitionRequest(valid.copy(schemaVersion = 99)))
        assertTrue(SwipeTraceFixtures.toRecognitionRequest(valid) != null)
    }
}
