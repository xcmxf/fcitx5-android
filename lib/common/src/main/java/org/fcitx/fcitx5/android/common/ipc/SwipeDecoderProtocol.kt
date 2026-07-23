/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.ipc

/**
 * Version contract for the separately installed Swipe decoder service.
 *
 * Keep compatibility decisions here so the host and each plugin APK cannot silently drift.
 * A future API v3 rollout should widen [MIN_SUPPORTED_API_VERSION] / [MAX_SUPPORTED_API_VERSION]
 * in the host before moving [CURRENT_API_VERSION] in a plugin.
 */
object SwipeDecoderProtocol {
    const val CURRENT_API_VERSION = 2
    const val MIN_SUPPORTED_API_VERSION = CURRENT_API_VERSION
    const val MAX_SUPPORTED_API_VERSION = CURRENT_API_VERSION

    fun isCompatible(apiVersion: Int): Boolean =
        apiVersion in MIN_SUPPORTED_API_VERSION..MAX_SUPPORTED_API_VERSION
}
