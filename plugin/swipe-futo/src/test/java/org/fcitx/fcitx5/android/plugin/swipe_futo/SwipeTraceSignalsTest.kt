/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.swipe_futo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeTraceSignalsTest {

    @Test
    fun normalizeKeepsOnlyDistinctLatinRunes() {
        assertEquals("shifuosi", SwipeTraceSignals.normalize("sShhii--ffuoo__ssii"))
    }

    @Test
    fun blendedTraceSimilarityPrefersDirectKeyboardTrace() {
        val directTrace = "shifoushi"
        val inferredTrace = "shifuosi"

        val intended = SwipeTraceSignals.blendedTraceSimilarity(
            candidate = "shifoushi",
            directTrace = directTrace,
            inferredTrace = inferredTrace
        )
        val typo = SwipeTraceSignals.blendedTraceSimilarity(
            candidate = "shifuosi",
            directTrace = directTrace,
            inferredTrace = inferredTrace
        )

        assertTrue(intended > typo)
    }

    @Test
    fun blendedSubsequenceCoverageAlsoFavorsDirectTrace() {
        val directTrace = "shifoushi"
        val inferredTrace = "shifuosi"

        val intended = SwipeTraceSignals.blendedSubsequenceCoverage(
            candidate = "shifoushi",
            directTrace = directTrace,
            inferredTrace = inferredTrace
        )
        val typo = SwipeTraceSignals.blendedSubsequenceCoverage(
            candidate = "shifuosi",
            directTrace = directTrace,
            inferredTrace = inferredTrace
        )

        assertTrue(intended > typo)
    }

    @Test
    fun pinyinSyllableScorePenalizesAwkwardImplicitSplits() {
        assertTrue(PinyinSyllableScorer.score("fou") > PinyinSyllableScorer.score("fuo"))
        assertTrue(
            PinyinSyllableScorer.score("shifoushi") >
                PinyinSyllableScorer.score("shifuosi")
        )
    }

    @Test
    fun pinyinSyllableScoreKeepsCommonPinyinStrong() {
        assertTrue(PinyinSyllableScorer.score("nihao") > 0.95f)
        assertTrue(PinyinSyllableScorer.score("zhongguo") > 0.95f)
    }

    @Test
    fun pinyinSyllableScoreIdentifiesStrongTraceFallbacks() {
        assertTrue(PinyinSyllableScorer.isStrongTraceCandidate("shifoushi"))
        assertTrue(!PinyinSyllableScorer.isStrongTraceCandidate("shifuosi"))
        assertTrue(!PinyinSyllableScorer.isStrongTraceCandidate("fuo"))
    }
}
