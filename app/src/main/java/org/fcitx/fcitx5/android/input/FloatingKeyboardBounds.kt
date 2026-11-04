/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import kotlin.math.max
import kotlin.math.min

internal class FloatingKeyboardBounds(
    availableWidth: Int,
    parentHeight: Int,
    bottomInset: Int,
    keyboardWidth: Int,
    keyboardHeight: Int,
) {
    private val maxX = max(0, availableWidth - keyboardWidth).toFloat()
    private val maxY = max(0, parentHeight - bottomInset - keyboardHeight).toFloat()

    fun clampX(x: Float) = min(max(x, 0f), maxX)
    fun clampY(y: Float) = min(max(y, 0f), maxY)
    fun xFromRatio(ratio: Float) = maxX * ratio.coerceIn(0f, 1f)
    fun yFromRatio(ratio: Float) = maxY * ratio.coerceIn(0f, 1f)
    fun xRatio(x: Float) = if (maxX > 0f) x / maxX else 0.5f
    fun yRatio(y: Float) = if (maxY > 0f) y / maxY else 1f
}
