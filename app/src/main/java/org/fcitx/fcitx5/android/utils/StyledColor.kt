/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import splitties.resources.styledColor

@ColorInt
fun Context.styledColorOr(@AttrRes attr: Int, @ColorInt fallback: Int): Int {
    return try {
        styledColor(attr)
    } catch (_: Exception) {
        fallback
    }
}
