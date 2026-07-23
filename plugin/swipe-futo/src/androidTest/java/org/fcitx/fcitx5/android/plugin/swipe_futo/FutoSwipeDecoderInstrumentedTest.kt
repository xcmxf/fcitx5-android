/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.swipe_futo

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FutoSwipeDecoderInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var decoder: FutoSwipeDecoder

    @Before
    fun setUp() {
        decoder = FutoSwipeDecoder(instrumentation.targetContext)
        decoder.warmUp(pinyinMode = true)
    }

    @After
    fun tearDown() {
        decoder.close()
    }

    @Test
    fun recognizesCommonPinyinFromSyntheticQwertySwipe() {
        val samples = listOf(
            PinyinSwipeSample(expected = "nihao", topN = 1),
            PinyinSwipeSample(expected = "xiexie", topN = 1),
            PinyinSwipeSample(expected = "zaijian", topN = 1),
            PinyinSwipeSample(expected = "zhongguo", topN = 1),
            PinyinSwipeSample(expected = "chongxin", topN = 1),
            PinyinSwipeSample(expected = "shuoshi", topN = 1),
            PinyinSwipeSample(expected = "shifou", topN = 1),
            PinyinSwipeSample(expected = "shifoushi", topN = 1),
            PinyinSwipeSample(expected = "zhongguoren", topN = 1),
            PinyinSwipeSample(expected = "meiyou", topN = 1),
            PinyinSwipeSample(expected = "keyi", topN = 1)
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
        val fixture = loadPinyinFixture("swipe-fixtures/user_reference_shi_fuo_si.json")
        val result = decodePinyinFixture(fixture)

        assertTrue(
            "Fixture ${fixture.id} expected ${fixture.expectedTopCandidate}, got $result",
            result.firstOrNull() == fixture.expectedTopCandidate
        )
    }

    @Test
    fun ranksConstrainedPinyinInitialRepairsFirst() {
        val samples = listOf(
            PinyinSwipeSample(
                expected = "zhongguo",
                geometricWord = "zhongguo",
                tracedLetters = "zongguo"
            ),
            PinyinSwipeSample(
                expected = "chongxin",
                geometricWord = "chongxin",
                tracedLetters = "congxin"
            ),
            PinyinSwipeSample(
                expected = "shuoshi",
                geometricWord = "shuoshi",
                tracedLetters = "suoshi"
            )
        )

        samples.forEach { sample ->
            val result = decodePinyin(
                geometricWord = sample.geometricWord,
                tracedLetters = sample.tracedLetters
            )

            assertTrue(
                "Expected ${sample.expected} as first result for ${sample.tracedLetters}, got $result",
                result.firstOrNull() == sample.expected
            )
        }
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

    private fun decodePinyinFixture(fixture: PinyinSwipeFixture): List<String> = decoder.recognize(
        x = fixture.points.map { it.x }.toFloatArray(),
        y = fixture.points.map { it.y }.toFloatArray(),
        t = fixture.points.map { it.t }.toFloatArray(),
        letters = fixture.letters,
        tracedLetters = fixture.tracedLetters,
        centerX = fixture.centerX,
        centerY = fixture.centerY,
        pinyinMode = true,
        topK = 4
    )

    private fun loadPinyinFixture(assetPath: String): PinyinSwipeFixture {
        val root = instrumentation.context.assets.open(assetPath).bufferedReader().use { reader ->
            JSONObject(reader.readText())
        }
        check(root.getInt("schemaVersion") == 1) { "Unsupported fixture schema: $assetPath" }
        check(root.getString("profile") == "Pinyin") { "Fixture is not Pinyin: $assetPath" }

        val keyPositions = linkedMapOf<Char, Point>()
        val keys = root.getJSONObject("keyboard").getJSONArray("keys")
        repeat(keys.length()) { index ->
            val key = keys.getJSONObject(index)
            val letter = key.getString("letter").single().lowercaseChar()
            check(letter in 'a'..'z' && letter !in keyPositions) {
                "Invalid keyboard key in fixture $assetPath: $letter"
            }
            keyPositions[letter] = Point(
                x = key.getDouble("centerX").toFloat(),
                y = key.getDouble("centerY").toFloat()
            )
        }
        val letters = keyPositions.keys.sorted().joinToString("")
        val points = root.getJSONArray("points").let { samples ->
            List(samples.length()) { index ->
                samples.getJSONObject(index).let { point ->
                    TimedPoint(
                        x = point.getDouble("x").toFloat(),
                        y = point.getDouble("y").toFloat(),
                        t = point.getDouble("t").toFloat()
                    )
                }
            }
        }
        return PinyinSwipeFixture(
            id = root.getString("id"),
            expectedTopCandidate = root.getString("expectedTopCandidate"),
            letters = letters,
            centerX = FloatArray(letters.length) { index -> keyPositions.getValue(letters[index]).x },
            centerY = FloatArray(letters.length) { index -> keyPositions.getValue(letters[index]).y },
            points = points,
            tracedLetters = root.getString("tracedLetters")
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

    private data class PinyinSwipeFixture(
        val id: String,
        val expectedTopCandidate: String,
        val letters: String,
        val centerX: FloatArray,
        val centerY: FloatArray,
        val points: List<TimedPoint>,
        val tracedLetters: String
    )

    private data class Point(val x: Float, val y: Float)
    private data class TimedPoint(val x: Float, val y: Float, val t: Float)
}
