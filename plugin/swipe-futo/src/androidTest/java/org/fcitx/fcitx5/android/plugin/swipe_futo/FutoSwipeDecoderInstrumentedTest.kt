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
        decoder.warmUp(pinyinMode = true)
    }

    @After
    fun tearDown() {
        decoder.close()
    }

    @Test
    fun recognizesCommonPinyinFromSyntheticQwertySwipe() {
        val samples = listOf(
            PinyinSwipeSample(expected = "nihao", topN = 3),
            PinyinSwipeSample(expected = "xiexie"),
            PinyinSwipeSample(expected = "zaijian"),
            PinyinSwipeSample(expected = "zhongguo"),
            PinyinSwipeSample(expected = "chongxin"),
            PinyinSwipeSample(expected = "shuoshi"),
            PinyinSwipeSample(expected = "shifou"),
            PinyinSwipeSample(expected = "shifoushi"),
            PinyinSwipeSample(expected = "zhongguoren"),
            PinyinSwipeSample(expected = "meiyou"),
            PinyinSwipeSample(expected = "keyi")
        )

        samples.forEach { sample ->
            val result = decodePinyin(
                geometricWord = sample.geometricWord,
                tracedLetters = sample.tracedLetters
            )

            assertTrue(
                "Expected ${sample.expected} in top ${sample.topN}, got $result",
                result.take(sample.topN).contains(sample.expected)
            )
        }
    }

    @Test
    fun ranksHighConfidencePinyinFirst() {
        val samples = listOf(
            PinyinSwipeSample(expected = "nihao", topN = 1),
            PinyinSwipeSample(expected = "shifou", topN = 1)
        )

        samples.forEach { sample ->
            val result = decodePinyin(
                geometricWord = sample.geometricWord,
                tracedLetters = sample.tracedLetters
            )

            assertTrue(
                "Expected ${sample.expected} as first result, got $result",
                result.firstOrNull() == sample.expected
            )
        }
    }

    @Test
    fun recognizesStrongPinyinWhenDirectTraceIsMissing() {
        val samples = listOf(
            PinyinSwipeSample(expected = "shifoushi", tracedLetters = ""),
            PinyinSwipeSample(expected = "zhongguo", tracedLetters = "")
        )

        samples.forEach { sample ->
            val result = decodePinyin(
                geometricWord = sample.geometricWord,
                tracedLetters = sample.tracedLetters
            )

            assertTrue(
                "Expected ${sample.expected} in top ${sample.topN}, got $result",
                result.take(sample.topN).contains(sample.expected)
            )
        }
    }

    @Test
    fun ranksUserReferenceFuoTraceFirst() {
        val result = decodePinyin(
            geometricWord = "shifuosi",
            tracedLetters = "shifuosi"
        )

        assertTrue(
            "Expected shifoushi as the first result for shi-fuo-si, got $result",
            result.firstOrNull() == "shifoushi"
        )
    }

    @Test
    fun recognizesCommonEnglishFromSyntheticQwertySwipe() {
        decoder.warmUp(pinyinMode = false)
        listOf("hello", "world").forEach { expected ->
            val result = decodeEnglish(expected)

            assertTrue(
                "Expected $expected in top 3, got $result",
                result.take(3).contains(expected)
            )
        }
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

    private fun decodeEnglish(word: String): List<String> {
        val layout = qwertyLayout()
        val swipePoints = syntheticSwipePoints(word, layout.positions)

        return decoder.recognize(
            x = swipePoints.map { it.x }.toFloatArray(),
            y = swipePoints.map { it.y }.toFloatArray(),
            t = swipePoints.map { it.t }.toFloatArray(),
            letters = layout.letters,
            tracedLetters = word,
            centerX = layout.centerX,
            centerY = layout.centerY,
            pinyinMode = false,
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

    private data class PinyinSwipeSample(
        val expected: String,
        val geometricWord: String = expected,
        val tracedLetters: String = expected,
        val topN: Int = 4
    )

    private data class Point(val x: Float, val y: Float)
    private data class TimedPoint(val x: Float, val y: Float, val t: Float)
}
