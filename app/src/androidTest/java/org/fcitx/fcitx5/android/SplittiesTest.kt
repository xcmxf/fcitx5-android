/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import org.junit.Assert.assertEquals
import org.junit.Test

class SplittiesTest {

    private class EmptyThemeContext(base: Context) : ContextWrapper(base) {
        private val emptyTheme = resources.newTheme()

        override fun getTheme(): Resources.Theme = emptyTheme
    }

    @Test
    fun missingThemeColorUsesDefault() {
        val context = EmptyThemeContext(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        val defaultColor = 0x00123456

        assertEquals(
            defaultColor,
            context.styledColorOrDefault(android.R.attr.colorAccent, defaultColor)
        )
    }
}
