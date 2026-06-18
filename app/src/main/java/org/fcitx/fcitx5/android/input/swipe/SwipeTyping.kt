/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.swipe

import org.fcitx.fcitx5.android.core.InputMethodEntry
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

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

    private val centers = this.keys.associate { it.letter[0] to (it.centerX to it.centerY) }

    init {
        require(this.keys.isNotEmpty()) { "Swipe layout must contain at least one key" }
    }

    fun centerOf(letter: Char): Pair<Float, Float>? = centers[letter.lowercaseChar()]

    fun signature(): String = buildString {
        append(letters)
        for (i in keys.indices) {
            append(':')
            append((centerX[i] * 1000f).toInt())
            append(',')
            append((centerY[i] * 1000f).toInt())
        }
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
    fun recognize(request: SwipeRecognitionRequest, topK: Int = 4): List<SwipeCandidate>

    override fun close() {
        // do nothing by default
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

class TraceShapeSwipeDecoder(
    dictionary: Collection<String> = pinyinDictionary
) : SwipeTypingDecoder {
    private val dictionary = dictionary
        .asSequence()
        .map { it.trim().lowercase() }
        .filter { word -> word.length >= 2 && word.all { it in 'a'..'z' } }
        .distinct()
        .toList()

    override fun recognize(request: SwipeRecognitionRequest, topK: Int): List<SwipeCandidate> {
        if (request.points.size < 3) return emptyList()
        val normalizedTrace = request.tracedLetters.compressConsecutive()
        return dictionary.asSequence()
            .mapNotNull { word ->
                scoreWord(request, normalizedTrace, word)?.let { SwipeCandidate(word, it) }
            }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    private fun scoreWord(
        request: SwipeRecognitionRequest,
        normalizedTrace: String,
        word: String
    ): Float? {
        val wordPoints = word.map { request.layout.centerOf(it) ?: return null }
        if (wordPoints.isEmpty()) return null
        val pathCost = dtwDistance(request.points, wordPoints)
        val traceCost = levenshtein(normalizedTrace, word.compressConsecutive()).toFloat()
        val startCost = request.points.first().distanceTo(wordPoints.first())
        val endCost = request.points.last().distanceTo(wordPoints.last())
        val lengthCost = abs(normalizedTrace.length - word.compressConsecutive().length) * 0.05f
        val totalCost = pathCost + traceCost * 0.18f + startCost * 0.5f + endCost * 0.5f + lengthCost
        return 1f / (1f + totalCost)
    }

    private fun dtwDistance(points: List<SwipePoint>, wordPoints: List<Pair<Float, Float>>): Float {
        val rows = points.size
        val cols = wordPoints.size
        var previous = FloatArray(cols) { Float.POSITIVE_INFINITY }
        var current = FloatArray(cols) { Float.POSITIVE_INFINITY }
        previous[0] = points[0].distanceTo(wordPoints[0])
        for (j in 1 until cols) {
            previous[j] = previous[j - 1] + points[0].distanceTo(wordPoints[j])
        }
        for (i in 1 until rows) {
            current[0] = previous[0] + points[i].distanceTo(wordPoints[0])
            for (j in 1 until cols) {
                current[j] = points[i].distanceTo(wordPoints[j]) +
                        min(previous[j], min(current[j - 1], previous[j - 1]))
            }
            val tmp = previous
            previous = current
            current = tmp
        }
        return previous[cols - 1] / (rows + cols)
    }

    private fun SwipePoint.distanceTo(point: Pair<Float, Float>): Float {
        val dx = x - point.first
        val dy = y - point.second
        return sqrt(dx * dx + dy * dy)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val substitution = if (a[i] == b[j]) 0 else 1
                current[j + 1] = min(
                    min(current[j] + 1, previous[j + 1] + 1),
                    previous[j] + substitution
                )
            }
            val tmp = previous
            previous = current
            current = tmp
        }
        return previous[b.length]
    }

    private fun String.compressConsecutive(): String {
        if (isEmpty()) return this
        return buildString {
            var last = 0.toChar()
            for (char in this@compressConsecutive) {
                val normalized = char.lowercaseChar()
                if (normalized != last) {
                    append(normalized)
                    last = normalized
                }
            }
        }
    }

    companion object {
        val latinDictionary = listOf(
            "hello",
            "test",
            "keyboard",
            "swipe",
            "typing",
            "android",
            "fcitx",
            "input",
            "word",
            "text",
            "language",
            "space",
            "delete",
            "candidate",
            "pinyin"
        )

        val pinyinDictionary = listOf(
            "ni",
            "hao",
            "nihao",
            "zhongguo",
            "zhongwen",
            "wo",
            "ai",
            "woaini",
            "xiexie",
            "zaijian",
            "shijie",
            "women",
            "nimen",
            "tamen",
            "meiyou",
            "keyi",
            "buneng",
            "shurufa",
            "jintian",
            "mingtian",
            "zuotian",
            "xianzai",
            "dengyi",
            "qingwen",
            "bukeqi",
            "duibuqi",
            "meiguanxi",
            "zaoshang",
            "wanshang",
            "xiawu",
            "gongzuo",
            "xuexi",
            "diannao",
            "shouji",
            "dianhua",
            "pengyou",
            "jiaren",
            "laoshi",
            "xuesheng",
            "chengshi",
            "beijing",
            "shanghai",
            "guangzhou",
            "shenzhen",
            "taiwan",
            "xianggang",
            "meiguo",
            "riben",
            "hanguo",
            "yingguo",
            "faguo",
            "deguo"
        )
    }
}
