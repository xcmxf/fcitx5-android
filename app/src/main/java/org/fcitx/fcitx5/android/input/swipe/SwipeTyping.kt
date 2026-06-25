/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.swipe

import org.fcitx.fcitx5.android.core.InputMethodEntry
import java.util.Locale

/** Transport types only: decoding lives in a separately installed plugin. */
data class SwipePoint(
    val x: Float,
    val y: Float,
    val t: Float
)

data class SwipeKey(
    val letter: String,
    val centerX: Float,
    val centerY: Float
)

class SwipeLayout(keys: List<SwipeKey>) {
    val keys: List<SwipeKey> = keys
        .filter { it.letter.isNotEmpty() }
        .distinctBy { it.letter }
        .sortedBy { it.letter }

    val letters: String = this.keys.joinToString("") { it.letter }
    val centerX: FloatArray = FloatArray(this.keys.size) { this.keys[it].centerX }
    val centerY: FloatArray = FloatArray(this.keys.size) { this.keys[it].centerY }

    init {
        require(this.keys.isNotEmpty()) { "Swipe layout must contain at least one key" }
    }
}

data class SwipeRecognitionRequest(
    val points: List<SwipePoint>,
    val layout: SwipeLayout,
    val tracedLetters: String
)

data class SwipeCandidate(
    val word: String,
    val score: Float
)

interface SwipeTypingDecoder : AutoCloseable {
    fun warmUp() {
        // Optional: implementations may connect to an external decoder here.
    }

    fun recognize(request: SwipeRecognitionRequest, topK: Int = 4): List<SwipeCandidate>

    override fun close() {
        // Optional for remote decoder implementations.
    }
}

object SwipeTypingMode {
    fun usePinyinBridge(ime: InputMethodEntry?): Boolean {
        if (ime == null) return true

        val uniqueName = ime.uniqueName.lowercase(Locale.ROOT)
        val languageCode = ime.languageCode.lowercase(Locale.ROOT)
        val addon = ime.addon.lowercase(Locale.ROOT)

        return uniqueName != "keyboard-us" &&
                addon != "androidkeyboard" &&
                !(languageCode == "en" && uniqueName.startsWith("keyboard-"))
    }
}
