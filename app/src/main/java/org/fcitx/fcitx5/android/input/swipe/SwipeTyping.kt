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
) {
    /**
     * Keeps the Binder payload bounded and rejects malformed data before it reaches a decoder.
     * The first and final samples are retained when a long gesture is downsampled.
     */
    fun boundedForDecoder(maxPoints: Int = DEFAULT_MAX_SWIPE_POINTS): SwipeRecognitionRequest? {
        if (maxPoints < MIN_SWIPE_POINT_COUNT || points.size < MIN_SWIPE_POINT_COUNT) return null
        if (layout.letters.length != layout.centerX.size || layout.centerX.size != layout.centerY.size) {
            return null
        }
        if (layout.centerX.any { !it.isFinite() } || layout.centerY.any { !it.isFinite() }) {
            return null
        }
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || !it.t.isFinite() }) return null
        if (points.zipWithNext().any { (previous, next) -> next.t < previous.t }) return null

        if (points.size <= maxPoints) return this
        val lastIndex = points.lastIndex
        val sampled = List(maxPoints) { index ->
            points[(index * lastIndex) / (maxPoints - 1)]
        }
        return copy(points = sampled)
    }

    private companion object {
        const val MIN_SWIPE_POINT_COUNT = 3
        const val DEFAULT_MAX_SWIPE_POINTS = 96
    }
}

data class SwipeCandidate(
    val word: String,
    val score: Float
)

enum class SwipeDecoderState {
    Ready,
    MissingPlugin,
    Binding,
    Warming,
    ApiMismatch,
    NotReady,
    Error
}

data class SwipeDecoderStatus(
    val state: SwipeDecoderState,
    val message: String? = null
) {
    companion object {
        val Ready = SwipeDecoderStatus(SwipeDecoderState.Ready)
    }
}

interface SwipeTypingDecoder : AutoCloseable {
    fun warmUp() {
        // Optional: implementations may connect to an external decoder here.
    }

    fun status(): SwipeDecoderStatus = SwipeDecoderStatus.Ready

    fun recognize(request: SwipeRecognitionRequest, topK: Int = 4): List<SwipeCandidate>

    override fun close() {
        // Optional for remote decoder implementations.
    }
}

enum class SwipeTypingProfile {
    English,
    Pinyin,
    Unsupported;

    val usesPinyinBridge: Boolean
        get() = this == Pinyin
}

object SwipeTypingMode {
    fun profileFor(ime: InputMethodEntry?): SwipeTypingProfile {
        // A keyboard can be visible briefly before Fcitx reports its active IME. Do not
        // guess Pinyin in that gap: swipe is only enabled after a supported English or
        // Fcitx Pinyin profile has been identified explicitly.
        if (ime == null) return SwipeTypingProfile.Unsupported

        val uniqueName = ime.uniqueName.lowercase(Locale.ROOT)
        val languageCode = ime.languageCode.lowercase(Locale.ROOT)
        val addon = ime.addon.lowercase(Locale.ROOT)

        if (addon == "pinyin" || uniqueName == "pinyin" || uniqueName.startsWith("pinyin-")) {
            return SwipeTypingProfile.Pinyin
        }
        if (
            languageCode.startsWith("en") &&
            (addon == "androidkeyboard" || uniqueName.startsWith("keyboard-"))
        ) {
            return SwipeTypingProfile.English
        }
        return SwipeTypingProfile.Unsupported
    }

    fun usePinyinBridge(ime: InputMethodEntry?): Boolean {
        return profileFor(ime).usesPinyinBridge
    }
}
