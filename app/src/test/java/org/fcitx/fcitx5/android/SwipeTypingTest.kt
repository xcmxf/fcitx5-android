/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.swipe.SwipeKey
import org.fcitx.fcitx5.android.input.swipe.SwipeLayout
import org.fcitx.fcitx5.android.input.swipe.SwipePoint
import org.fcitx.fcitx5.android.input.swipe.SwipeRecognitionRequest
import org.fcitx.fcitx5.android.input.swipe.SwipeTypingMode
import org.fcitx.fcitx5.android.input.swipe.SwipeTypingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeTypingTest {

    @Test
    fun swipeLayoutUsesStableAlphabeticalKeyOrder() {
        val layout = SwipeLayout(
            listOf(
                SwipeKey("z", 0.8f, 0.5f),
                SwipeKey("a", 0.1f, 0.5f),
                SwipeKey("z", 0.8f, 0.5f)
            )
        )

        assertEquals("az", layout.letters)
        assertTrue(layout.centerX.contentEquals(floatArrayOf(0.1f, 0.8f)))
    }

    @Test
    fun swipeTypingModeRequiresAnExplicitSupportedIme() {
        assertEquals(SwipeTypingProfile.Unsupported, SwipeTypingMode.profileFor(null))
        assertFalse(SwipeTypingMode.usePinyinBridge(null))
        assertTrue(
            SwipeTypingMode.usePinyinBridge(
                ime(uniqueName = "pinyin", languageCode = "zh_CN", addon = "pinyin")
            )
        )
    }

    @Test
    fun swipeTypingModeKeepsEnglishKeyboardLatin() {
        assertTrue(
            !SwipeTypingMode.usePinyinBridge(
                ime(uniqueName = "keyboard-us", languageCode = "en", addon = "androidkeyboard")
            )
        )
    }

    @Test
    fun swipeTypingModeLeavesUnsupportedInputMethodsUntouched() {
        val ime = ime(uniqueName = "mozc", languageCode = "ja", addon = "mozc")

        assertEquals(SwipeTypingProfile.Unsupported, SwipeTypingMode.profileFor(ime))
        assertFalse(SwipeTypingMode.usePinyinBridge(ime))
    }

    @Test
    fun swipeRequestBoundsLongTracesAndKeepsEndpoints() {
        val layout = SwipeLayout(listOf(SwipeKey("a", 0.5f, 0.5f)))
        val request = SwipeRecognitionRequest(
            points = (0..8).map { index -> SwipePoint(index / 8f, 0.5f, index.toFloat()) },
            layout = layout,
            tracedLetters = "a"
        )

        val bounded = requireNotNull(request.boundedForDecoder(maxPoints = 3))
        assertEquals(3, bounded.points.size)
        assertEquals(request.points.first(), bounded.points.first())
        assertEquals(request.points.last(), bounded.points.last())
    }

    @Test
    fun swipeRequestRejectsInvalidPoints() {
        val layout = SwipeLayout(listOf(SwipeKey("a", 0.5f, 0.5f)))
        val invalid = SwipeRecognitionRequest(
            points = listOf(
                SwipePoint(0f, 0f, 0f),
                SwipePoint(Float.NaN, 0.5f, 1f),
                SwipePoint(1f, 1f, 2f)
            ),
            layout = layout,
            tracedLetters = "a"
        )

        assertNull(invalid.boundedForDecoder())
    }

    private fun ime(uniqueName: String, languageCode: String, addon: String) = InputMethodEntry(
        uniqueName = uniqueName,
        name = uniqueName,
        icon = "",
        nativeName = "",
        label = uniqueName,
        languageCode = languageCode,
        addon = addon,
        isConfigurable = false
    )
}
