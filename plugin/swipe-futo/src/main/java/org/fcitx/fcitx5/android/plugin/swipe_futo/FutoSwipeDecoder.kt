/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.swipe_futo

import android.content.Context
import org.futo.ml.inference.SwipeDecoder
import timber.log.Timber
import java.io.Closeable
import kotlin.math.hypot

/** Owns the GPL FUTO decoder and its model/dictionary state inside the plugin process. */
internal class FutoSwipeDecoder(context: Context) : Closeable {

    private val files = FutoSwipeAssets.prepare(context)
    private val decoders = mutableMapOf<Boolean, FutoSwipeSession>()
    private var lastError: String? = null

    @Synchronized
    fun warmUp(pinyinMode: Boolean) {
        sessionFor(pinyinMode)
    }

    @Synchronized
    fun isReady(pinyinMode: Boolean): Boolean = runCatching {
        sessionFor(pinyinMode)
        true
    }.onFailure {
        lastError = it.message ?: it.javaClass.simpleName
        Timber.w(it, "FUTO Swipe decoder is unavailable")
    }.isSuccess

    @Synchronized
    fun recognize(
        x: FloatArray?,
        y: FloatArray?,
        t: FloatArray?,
        letters: String?,
        centerX: FloatArray?,
        centerY: FloatArray?,
        pinyinMode: Boolean,
        topK: Int
    ): List<String> {
        if (x == null || y == null || t == null || letters == null || centerX == null || centerY == null) {
            return emptyList()
        }
        if (x.size < 3 || x.size != y.size || x.size != t.size || centerX.size != centerY.size) {
            return emptyList()
        }

        return runCatching {
            sessionFor(pinyinMode).recognize(x, y, t, letters, centerX, centerY, topK)
        }.onFailure {
            lastError = it.message ?: it.javaClass.simpleName
            Timber.w(it, "FUTO Swipe recognition failed")
        }.getOrDefault(emptyList())
    }

    @get:Synchronized
    val status: String
        get() = lastError?.let { "FUTO Swipe unavailable: $it" } ?: "FUTO Swipe decoder ready"

    @Synchronized
    override fun close() {
        decoders.values.forEach(FutoSwipeSession::close)
        decoders.clear()
    }

    private fun sessionFor(pinyinMode: Boolean): FutoSwipeSession = decoders.getOrPut(pinyinMode) {
        FutoSwipeSession(
            encoder = files.encoder,
            dictionary = if (pinyinMode) files.pinyinDictionary else files.englishDictionary,
            traceRescoring = pinyinMode
        )
    }
}

private class FutoSwipeSession(
    encoder: java.io.File,
    dictionary: java.io.File,
    private val traceRescoring: Boolean
) : Closeable {

    private val trie = FutoSwipeTrieBridge.load(dictionary.absolutePath).also {
        require(it != 0L) { "Unable to load FUTO Swipe dictionary: ${dictionary.name}" }
    }
    private val beamWidth = if (traceRescoring) PINYIN_BEAM_WIDTH else ENGLISH_BEAM_WIDTH
    private val decoder = SwipeDecoder(
        encoder.absolutePath,
        null,
        1,
        beamWidth,
        DEFAULT_TOP_K,
        true,
        "f",
        null,
        null
    )
    private var layoutSignature = ""

    fun recognize(
        x: FloatArray,
        y: FloatArray,
        t: FloatArray,
        letters: String,
        centerX: FloatArray,
        centerY: FloatArray,
        topK: Int
    ): List<String> {
        require(isLatinAlphabet(letters) && letters.length == ALPHABET_SIZE) {
            "FUTO Swipe plugin currently supports complete Latin alphabet layouts only"
        }
        val signature = buildString {
            append(letters)
            centerX.zip(centerY).forEach { (keyX, keyY) -> append(":$keyX,$keyY") }
        }
        if (signature != layoutSignature) {
            check(
                decoder.setMode(
                    letters,
                    centerX,
                    centerY,
                    longArrayOf(trie),
                    null,
                    null,
                    null
                )
            ) { "FUTO Swipe rejected the keyboard layout" }
            layoutSignature = signature
        }
        val requestedTopK = topK.coerceIn(1, MAX_TOP_K)
        val rawTopK = if (traceRescoring) {
            (requestedTopK * 3).coerceIn(DEFAULT_TOP_K, MAX_RAW_TOP_K)
        } else {
            requestedTopK
        }
        val rawCandidates = decoder.recognize(x, y, t, rawTopK, beamWidth, null)
            .mapIndexedNotNull { index, result ->
                result.word.trim().lowercase().takeIf { it.isNotEmpty() }?.let {
                    RankedCandidate(it, index)
                }
            }
            .distinctBy { it.word }
        val layoutCenters = buildLayoutCenters(letters, centerX, centerY)
        val observedTrace = buildObservedTrace(x, y, letters, centerX, centerY)
        return reorderCandidates(rawCandidates, observedTrace, x, y, layoutCenters)
            .take(requestedTopK)
            .map { it.word }
    }

    override fun close() {
        decoder.close()
        FutoSwipeTrieBridge.destroy(trie)
    }

    private fun reorderCandidates(
        candidates: List<RankedCandidate>,
        observedTrace: String,
        x: FloatArray,
        y: FloatArray,
        layoutCenters: Map<Char, NormalizedPoint>
    ): List<RankedCandidate> {
        if (!traceRescoring || candidates.isEmpty()) return candidates
        val observedPoints = x.indices.map { NormalizedPoint(x[it], y[it]) }
        if (observedPoints.size < 2) return candidates

        return candidates
            .map { candidate ->
                val geometryScore = pathSimilarity(candidate.word, observedPoints, layoutCenters)
                val traceScore = traceSimilarity(candidate.word, observedTrace)
                val subsequenceScore = subsequenceCoverage(candidate.word, observedTrace)
                val totalScore = geometryScore * 0.68f + traceScore * 0.22f + subsequenceScore * 0.10f
                CandidateScore(candidate, totalScore, geometryScore, traceScore, subsequenceScore)
            }
            .sortedWith(
                compareByDescending<CandidateScore> { it.totalScore }
                    .thenByDescending { it.geometryScore }
                    .thenByDescending { it.traceScore }
                    .thenByDescending { it.subsequenceScore }
                    .thenBy { it.candidate.rank }
            )
            .map { it.candidate }
    }

    private fun buildLayoutCenters(
        letters: String,
        centerX: FloatArray,
        centerY: FloatArray
    ): Map<Char, NormalizedPoint> {
        if (letters.length != centerX.size || centerX.size != centerY.size) return emptyMap()

        return buildMap(letters.length) {
            letters.indices.forEach { keyIndex ->
                put(
                    letters[keyIndex].lowercaseChar(),
                    NormalizedPoint(centerX[keyIndex], centerY[keyIndex])
                )
            }
        }
    }

    private fun buildObservedTrace(
        x: FloatArray,
        y: FloatArray,
        letters: String,
        centerX: FloatArray,
        centerY: FloatArray
    ): String {
        if (letters.length != centerX.size || centerX.size != centerY.size) return ""

        return buildString {
            x.indices.forEach { pointIndex ->
                var nearestIndex = 0
                var nearestDistance = Float.POSITIVE_INFINITY
                centerX.indices.forEach { keyIndex ->
                    val dx = x[pointIndex] - centerX[keyIndex]
                    val dy = y[pointIndex] - centerY[keyIndex]
                    val distance = dx * dx + dy * dy
                    if (distance < nearestDistance) {
                        nearestDistance = distance
                        nearestIndex = keyIndex
                    }
                }
                val letter = letters[nearestIndex].lowercaseChar()
                if (isEmpty() || last() != letter) append(letter)
            }
        }
    }

    private fun traceSimilarity(candidate: String, observedTrace: String): Float {
        if (observedTrace.isEmpty()) return 0f
        val maxLength = maxOf(candidate.length, observedTrace.length)
        if (maxLength == 0) return 0f

        val normalizedEditScore =
            1f - levenshteinDistance(candidate, observedTrace).toFloat() / maxLength.toFloat()
        val lcsScore = longestCommonSubsequenceLength(candidate, observedTrace).toFloat() /
            maxOf(candidate.length, observedTrace.length).toFloat()
        var bonus = 0f
        if (candidate.firstOrNull() == observedTrace.firstOrNull()) bonus += 0.12f
        if (candidate.lastOrNull() == observedTrace.lastOrNull()) bonus += 0.12f
        if (candidate.contains(observedTrace) || observedTrace.contains(candidate)) bonus += 0.08f
        return normalizedEditScore * 0.55f + lcsScore * 0.45f + bonus
    }

    private fun subsequenceCoverage(candidate: String, observedTrace: String): Float {
        if (candidate.isEmpty() || observedTrace.isEmpty()) return 0f
        return longestCommonSubsequenceLength(candidate, observedTrace).toFloat() /
            candidate.length.toFloat()
    }

    private fun pathSimilarity(
        candidate: String,
        observedPoints: List<NormalizedPoint>,
        layoutCenters: Map<Char, NormalizedPoint>
    ): Float {
        val candidatePath = buildCandidatePath(candidate, layoutCenters)
        if (candidatePath.size < 2 || observedPoints.size < 2) return 0f

        val sampleCount = observedPoints.size.coerceIn(16, 32)
        val sampledObserved = resamplePath(observedPoints, sampleCount)
        val sampledCandidate = resamplePath(candidatePath, sampleCount)
        val averageDistance = sampledObserved.zip(sampledCandidate)
            .sumOf { (left, right) ->
                hypot(
                    (left.x - right.x).toDouble(),
                    (left.y - right.y).toDouble()
                )
            }
            .toFloat() / sampleCount.toFloat()
        val startDistance = distance(sampledObserved.first(), sampledCandidate.first())
        val endDistance = distance(sampledObserved.last(), sampledCandidate.last())
        val geometryScore = 1f - (averageDistance / 0.28f).coerceIn(0f, 1f)
        val endpointScore = (
            1f - (startDistance / 0.18f).coerceIn(0f, 1f) +
                1f - (endDistance / 0.18f).coerceIn(0f, 1f)
            ) / 2f
        return geometryScore * 0.78f + endpointScore * 0.22f
    }

    private fun buildCandidatePath(
        candidate: String,
        layoutCenters: Map<Char, NormalizedPoint>
    ): List<NormalizedPoint> {
        val points = ArrayList<NormalizedPoint>(candidate.length)
        var previous: Char? = null
        candidate.forEach { char ->
            val normalized = char.lowercaseChar()
            if (normalized == previous) return@forEach
            layoutCenters[normalized]?.let(points::add)
            previous = normalized
        }
        return points
    }

    private fun resamplePath(
        points: List<NormalizedPoint>,
        sampleCount: Int
    ): List<NormalizedPoint> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return List(sampleCount) { points.first() }

        val cumulativeLengths = FloatArray(points.size)
        for (index in 1 until points.size) {
            cumulativeLengths[index] = cumulativeLengths[index - 1] +
                distance(points[index - 1], points[index])
        }
        val totalLength = cumulativeLengths.last()
        if (totalLength <= 0f) return List(sampleCount) { points.first() }

        val resampled = ArrayList<NormalizedPoint>(sampleCount)
        var segmentIndex = 1
        repeat(sampleCount) { sampleIndex ->
            val targetDistance = totalLength * sampleIndex.toFloat() /
                (sampleCount - 1).coerceAtLeast(1).toFloat()
            while (
                segmentIndex < cumulativeLengths.lastIndex &&
                cumulativeLengths[segmentIndex] < targetDistance
            ) {
                segmentIndex++
            }
            val previousDistance = cumulativeLengths[segmentIndex - 1]
            val nextDistance = cumulativeLengths[segmentIndex]
            val previousPoint = points[segmentIndex - 1]
            val nextPoint = points[segmentIndex]
            val ratio = if (nextDistance <= previousDistance) {
                0f
            } else {
                (targetDistance - previousDistance) / (nextDistance - previousDistance)
            }
            resampled += NormalizedPoint(
                x = previousPoint.x + (nextPoint.x - previousPoint.x) * ratio,
                y = previousPoint.y + (nextPoint.y - previousPoint.y) * ratio
            )
        }
        return resampled
    }

    private fun distance(left: NormalizedPoint, right: NormalizedPoint): Float =
        hypot(
            (left.x - right.x).toDouble(),
            (left.y - right.y).toDouble()
        ).toFloat()

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        left.forEachIndexed { leftIndex, leftChar ->
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                val substitutionCost = if (leftChar == rightChar) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost
                )
            }
            previous.indices.forEach { previous[it] = current[it] }
        }
        return previous[right.length]
    }

    private fun longestCommonSubsequenceLength(left: String, right: String): Int {
        if (left.isEmpty() || right.isEmpty()) return 0

        val previous = IntArray(right.length + 1)
        val current = IntArray(right.length + 1)
        left.forEach { leftChar ->
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = if (leftChar == rightChar) {
                    previous[rightIndex] + 1
                } else {
                    maxOf(previous[rightIndex + 1], current[rightIndex])
                }
            }
            previous.indices.forEach { previous[it] = current[it] }
        }
        return previous[right.length]
    }

    private data class RankedCandidate(val word: String, val rank: Int)
    private data class NormalizedPoint(val x: Float, val y: Float)
    private data class CandidateScore(
        val candidate: RankedCandidate,
        val totalScore: Float,
        val geometryScore: Float,
        val traceScore: Float,
        val subsequenceScore: Float
    )

    private fun isLatinAlphabet(letters: String): Boolean =
        letters.length == ALPHABET_SIZE && letters.toSet() == LATIN_ALPHABET.toSet()

    private companion object {
        const val ALPHABET_SIZE = 26
        const val DEFAULT_TOP_K = 4
        const val MAX_TOP_K = 8
        const val MAX_RAW_TOP_K = 24
        const val ENGLISH_BEAM_WIDTH = 100
        const val PINYIN_BEAM_WIDTH = 224
        const val LATIN_ALPHABET = "abcdefghijklmnopqrstuvwxyz"
    }
}
