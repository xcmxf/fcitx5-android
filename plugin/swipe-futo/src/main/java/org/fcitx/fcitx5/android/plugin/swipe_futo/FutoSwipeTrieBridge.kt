/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.swipe_futo

/**
 * Supplies the FUTO decoder with a dictionary through its public ITrie ABI.
 *
 * The FUTO Android AAR intentionally leaves dictionary ownership to its host.
 * This bridge owns a small, read-only trie built from an AOSP `.combined`
 * word list for as long as the decoder session needs it.
 */
object FutoSwipeTrieBridge {
    init {
        System.loadLibrary("futo_swipe_trie_bridge")
    }

    external fun load(path: String): Long

    external fun destroy(handle: Long)
}
