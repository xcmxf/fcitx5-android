/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.swipe.SwipeKey
import org.fcitx.fcitx5.android.input.swipe.SwipeLayout
import org.fcitx.fcitx5.android.input.swipe.SwipePoint
import org.fcitx.fcitx5.android.input.swipe.SwipeRecognitionRequest
import org.fcitx.fcitx5.android.input.swipe.TraceShapeSwipeDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeTypingTest {

    private val layout = SwipeLayout(
        listOf(
            "qwertyuiop" to 0.167f,
            "asdfghjkl" to 0.5f,
            "zxcvbnm" to 0.833f
        ).flatMap { (row, y) ->
            row.mapIndexed { index, letter ->
                SwipeKey(
                    letter.toString(),
                    (index + 0.5f) / row.length.toFloat(),
                    y
                )
            }
        }
    )

    @Test
    fun traceShapeDecoderRecognizesEnglishSmokeWord() {
        val results = TraceShapeSwipeDecoder.recognize(requestFor("hello"), topK = 4)

        assertTrue(results.isNotEmpty())
        assertEquals("hello", results.first().word)
    }

    @Test
    fun traceShapeDecoderRecognizesPinyinBridgeSmokeWord() {
        val results = TraceShapeSwipeDecoder.recognize(requestFor("nihao"), topK = 4)

        assertTrue(results.isNotEmpty())
        assertEquals("nihao", results.first().word)
    }

    private fun requestFor(word: String): SwipeRecognitionRequest {
        val centers = word.map { letter ->
            layout.centerOf(letter) ?: error("No key for $letter")
        }
        val points = mutableListOf<SwipePoint>()
        var time = 0f
        centers.zipWithNext().forEach { (from, to) ->
            for (i in 0 until 5) {
                val ratio = i / 5f
                points.add(
                    SwipePoint(
                        from.first + (to.first - from.first) * ratio,
                        from.second + (to.second - from.second) * ratio,
                        time
                    )
                )
                time += 8f
            }
        }
        points.add(SwipePoint(centers.last().first, centers.last().second, time))
        return SwipeRecognitionRequest(points, layout, word)
    }
}
