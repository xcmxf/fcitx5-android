/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingKeyboardBoundsTest {
    @Test
    fun clampsPositionAndRestoresSavedRatios() {
        val bounds = FloatingKeyboardBounds(1000, 2000, 100, 600, 500)

        assertEquals(0f, bounds.clampX(-10f), 0f)
        assertEquals(400f, bounds.clampX(500f), 0f)
        assertEquals(0f, bounds.clampY(-10f), 0f)
        assertEquals(1400f, bounds.clampY(1500f), 0f)
        assertEquals(100f, bounds.xFromRatio(bounds.xRatio(100f)), 0f)
        assertEquals(700f, bounds.yFromRatio(bounds.yRatio(700f)), 0f)
        assertEquals(0f, bounds.xFromRatio(-1f), 0f)
        assertEquals(1400f, bounds.yFromRatio(2f), 0f)
    }

    @Test
    fun zeroAvailableSpaceUsesExistingFallbackRatios() {
        val bounds = FloatingKeyboardBounds(300, 400, 50, 400, 500)

        assertEquals(0f, bounds.clampX(100f), 0f)
        assertEquals(0f, bounds.clampY(100f), 0f)
        assertEquals(0f, bounds.xFromRatio(0.5f), 0f)
        assertEquals(0f, bounds.yFromRatio(1f), 0f)
        assertEquals(0.5f, bounds.xRatio(0f), 0f)
        assertEquals(1f, bounds.yRatio(0f), 0f)
    }
}
