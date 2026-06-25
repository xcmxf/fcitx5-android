/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.swipe.SwipeKey
import org.fcitx.fcitx5.android.input.swipe.SwipeLayout
import org.fcitx.fcitx5.android.input.swipe.SwipeTypingMode
import org.junit.Assert.assertEquals
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
    fun swipeTypingModeDefaultsToPinyinBridge() {
        assertTrue(SwipeTypingMode.usePinyinBridge(null))
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
