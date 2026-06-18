/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.swipe_futo

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.fcitx.fcitx5.android.common.ipc.ISwipeDecoderService

class SwipeDecoderService : Service() {

    private val binder = object : ISwipeDecoderService.Stub() {
        override fun getApiVersion(): Int = API_VERSION

        override fun isReady(pinyinMode: Boolean): Boolean = false

        override fun getStatus(): String = "FUTO Swipe decoder is not bundled in this build"

        override fun recognize(
            x: FloatArray?,
            y: FloatArray?,
            t: FloatArray?,
            letters: String?,
            centerX: FloatArray?,
            centerY: FloatArray?,
            pinyinMode: Boolean,
            topK: Int
        ): Array<String> = emptyArray()
    }

    override fun onBind(intent: Intent): IBinder = binder

    companion object {
        private const val API_VERSION = 1
    }
}
