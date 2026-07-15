/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.swipeCandidateToKeyAction
import org.fcitx.fcitx5.android.input.keyboard.swipeCandidatesToKeyAction
import org.fcitx.fcitx5.android.input.swipe.SwipeCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeTypingActionTest {

    @Test
    fun pinyinBridgeSendsSwipeResultThroughFcitx() {
        val action = swipeCandidateToKeyAction(
            SwipeCandidate("shifoushi", 1f),
            bridgeToFcitx = true
        )

        assertTrue(action is KeyAction.FcitxKeySequenceAction)
        action as KeyAction.FcitxKeySequenceAction
        assertEquals("shifoushi", action.text)
        assertTrue(action.deleteAsUnit)
    }

    @Test
    fun latinSwipeCommitsLowercaseWordDirectly() {
        val action = swipeCandidateToKeyAction(
            SwipeCandidate("Hello", 1f),
            bridgeToFcitx = false
        )

        assertTrue(action is KeyAction.CommitAction)
        assertEquals("hello", (action as KeyAction.CommitAction).text)
    }

    @Test
    fun blankSwipeCandidateDoesNothing() {
        assertNull(swipeCandidateToKeyAction(SwipeCandidate(" ", 1f), bridgeToFcitx = true))
        assertNull(swipeCandidateToKeyAction(null, bridgeToFcitx = false))
    }

    @Test
    fun latinSwipeCandidatesShowSelectionWhenThereAreAlternatives() {
        val candidates = listOf(
            SwipeCandidate(" Hello ", 1f),
            SwipeCandidate("HELLO", 0.95f),
            SwipeCandidate("Help", 0.9f)
        )

        val action = swipeCandidatesToKeyAction(candidates, bridgeToFcitx = false)

        assertTrue(action is KeyAction.SwipeCandidatesAction)
        action as KeyAction.SwipeCandidatesAction
        assertEquals(false, action.bridgeToFcitx)
        assertEquals(listOf("hello", "help"), action.candidates.map { it.word })
    }

    @Test
    fun pinyinBridgeKeepsImmediateFcitxCandidatePipeline() {
        val candidates = listOf(
            SwipeCandidate("shifoushi", 1f),
            SwipeCandidate("shifou", 0.9f)
        )

        val action = swipeCandidatesToKeyAction(candidates, bridgeToFcitx = true)

        assertTrue(action is KeyAction.FcitxKeySequenceAction)
        action as KeyAction.FcitxKeySequenceAction
        assertEquals("shifoushi", action.text)
        assertTrue(action.deleteAsUnit)
    }

    @Test
    fun blankSwipeCandidateListDoesNothing() {
        assertNull(
            swipeCandidatesToKeyAction(
                listOf(SwipeCandidate(" ", 1f)),
                bridgeToFcitx = false
            )
        )
    }
}
