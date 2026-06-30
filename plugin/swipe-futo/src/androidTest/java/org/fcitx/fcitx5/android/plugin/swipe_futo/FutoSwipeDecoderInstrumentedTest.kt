/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.swipe_futo

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FutoSwipeDecoderInstrumentedTest {

    private lateinit var decoder: FutoSwipeDecoder

    @Before
    fun setUp() {
        decoder = FutoSwipeDecoder(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() {
        decoder.close()
    }

    @Test
    fun recognizesNiHaoFromSyntheticQwertySwipe() {
        val result = decodePinyin(
            geometricWord = "nihao",
            tracedLetters = "nihao"
        )

        assertTrue(result.take(3).contains("nihao"))
    }

    @Test
    fun recognizesShiFouFromSyntheticQwertySwipe() {
        val result = decodePinyin(
            geometricWord = "shifou",
            tracedLetters = "shifou"
        )

        assertTrue(result.take(4).contains("shifou"))
    }

    private fun decodePinyin(
        geometricWord: String,
        tracedLetters: String
    ): List<String> {
        val layout = qwertyLayout()
        val swipePoints = syntheticSwipePoints(geometricWord, layout.positions)

        return decoder.recognize(
            x = swipePoints.map { it.x }.toFloatArray(),
            y = swipePoints.map { it.y }.toFloatArray(),
            t = swipePoints.map { it.t }.toFloatArray(),
            letters = layout.letters,
            tracedLetters = tracedLetters,
            centerX = layout.centerX,
            centerY = layout.centerY,
            pinyinMode = true,
            topK = 4
        )
    }

    private fun qwertyLayout(): TestLayout {
        val row0 = "qwertyuiop".mapIndexed { index, letter ->
            letter to Point(
                x = 0.05f + index * 0.10f,
                y = 0.24f
            )
        }
        val row1 = "asdfghjkl".mapIndexed { index, letter ->
            letter to Point(
                x = 0.09f + index * 0.10f,
                y = 0.50f
            )
        }
        val row2 = "zxcvbnm".mapIndexed { index, letter ->
            letter to Point(
                x = 0.22f + index * 0.11f,
                y = 0.76f
            )
        }
        val positions = (row0 + row1 + row2).toMap()
        val letters = "abcdefghijklmnopqrstuvwxyz"
        return TestLayout(
            letters = letters,
            centerX = FloatArray(letters.length) { index -> positions.getValue(letters[index]).x },
            centerY = FloatArray(letters.length) { index -> positions.getValue(letters[index]).y },
            positions = positions
        )
    }

    private fun syntheticSwipePoints(
        word: String,
        positions: Map<Char, Point>
    ): List<TimedPoint> {
        val anchors = buildList {
            var previous: Char? = null
            word.forEach { char ->
                val normalized = char.lowercaseChar()
                if (normalized == previous) return@forEach
                add(positions.getValue(normalized))
                previous = normalized
            }
        }

        val points = mutableListOf<TimedPoint>()
        var timeMs = 0f
        anchors.forEachIndexed { index, point ->
            if (index == 0) {
                points += TimedPoint(point.x, point.y, timeMs)
                return@forEachIndexed
            }

            val previous = anchors[index - 1]
            for (step in 1..4) {
                val ratio = step / 4f
                val x = previous.x + (point.x - previous.x) * ratio
                val yBase = previous.y + (point.y - previous.y) * ratio
                val bend = if (index % 2 == 0) 0.0125f else -0.0125f
                val y = (yBase + bend * (1f - kotlin.math.abs(0.5f - ratio) * 2f))
                    .coerceIn(0f, 1f)
                timeMs += 22f
                points += TimedPoint(x, y, timeMs)
            }
        }
        return points
    }

    private data class TestLayout(
        val letters: String,
        val centerX: FloatArray,
        val centerY: FloatArray,
        val positions: Map<Char, Point>
    )

    private data class Point(val x: Float, val y: Float)
    private data class TimedPoint(val x: Float, val y: Float, val t: Float)
}
