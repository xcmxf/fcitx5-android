/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.swipe.SwipeCandidate
import java.util.Locale

private fun normalizeSwipeCandidates(candidates: List<SwipeCandidate>): List<SwipeCandidate> =
    candidates.asSequence()
        .map { candidate ->
            candidate.copy(word = candidate.word.trim().lowercase(Locale.ROOT))
        }
        .filter { it.word.isNotBlank() }
        .distinctBy { it.word }
        .toList()

internal fun swipeCandidateToKeyAction(
    candidate: SwipeCandidate?,
    bridgeToFcitx: Boolean
): KeyAction? {
    val word = candidate?.word
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return if (bridgeToFcitx) {
        KeyAction.FcitxKeySequenceAction(word, deleteAsUnit = true)
    } else {
        KeyAction.CommitAction(word)
    }
}

internal fun swipeCandidatesToKeyAction(
    candidates: List<SwipeCandidate>,
    bridgeToFcitx: Boolean
): KeyAction? {
    val normalized = normalizeSwipeCandidates(candidates)
    if (normalized.isEmpty()) return null

    if (!bridgeToFcitx && normalized.size > 1) {
        return KeyAction.SwipeCandidatesAction(normalized, bridgeToFcitx = false)
    }

    return swipeCandidateToKeyAction(normalized.first(), bridgeToFcitx)
}
