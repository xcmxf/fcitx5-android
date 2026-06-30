/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.swipe_futo

internal object SwipeTraceSignals {

    fun normalize(trace: String?): String {
        if (trace.isNullOrBlank()) return ""

        return buildString(trace.length) {
            trace.forEach { character ->
                val normalized = character.lowercaseChar()
                if (normalized in 'a'..'z' && (isEmpty() || last() != normalized)) {
                    append(normalized)
                }
            }
        }
    }

    fun blendedTraceSimilarity(
        candidate: String,
        directTrace: String,
        inferredTrace: String
    ): Float {
        val normalizedDirect = normalize(directTrace)
        val normalizedInferred = normalize(inferredTrace)
        val directScore = traceSimilarity(candidate, normalizedDirect)
        val inferredScore = traceSimilarity(candidate, normalizedInferred)

        return when {
            normalizedDirect.isEmpty() -> inferredScore
            normalizedInferred.isEmpty() -> directScore
            else -> maxOf(
                directScore,
                directScore * 0.72f + inferredScore * 0.28f,
                inferredScore * 0.96f
            )
        }
    }

    fun blendedSubsequenceCoverage(
        candidate: String,
        directTrace: String,
        inferredTrace: String
    ): Float {
        val normalizedDirect = normalize(directTrace)
        val normalizedInferred = normalize(inferredTrace)
        val directScore = subsequenceCoverage(candidate, normalizedDirect)
        val inferredScore = subsequenceCoverage(candidate, normalizedInferred)

        return when {
            normalizedDirect.isEmpty() -> inferredScore
            normalizedInferred.isEmpty() -> directScore
            else -> maxOf(directScore, (directScore + inferredScore) / 2f, inferredScore * 0.96f)
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
}
