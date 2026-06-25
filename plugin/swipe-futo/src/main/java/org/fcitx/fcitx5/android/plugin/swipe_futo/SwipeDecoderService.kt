/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.swipe_futo

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.fcitx.fcitx5.android.common.ipc.ISwipeDecoderService
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SwipeDecoderService : Service() {

    private lateinit var decoder: FutoSwipeDecoder
    private val warmUpExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val binder = object : ISwipeDecoderService.Stub() {
        override fun getApiVersion(): Int = API_VERSION

        override fun warmUp(pinyinMode: Boolean) {
            warmUpExecutor.execute {
                runCatching { decoder.warmUp(pinyinMode) }
                    .onFailure { Timber.w(it, "FUTO Swipe warm-up failed") }
            }
        }

        override fun isReady(pinyinMode: Boolean): Boolean = decoder.isReady(pinyinMode)

        override fun getStatus(): String = decoder.status

        override fun recognize(
            x: FloatArray?,
            y: FloatArray?,
            t: FloatArray?,
            letters: String?,
            centerX: FloatArray?,
            centerY: FloatArray?,
            pinyinMode: Boolean,
            topK: Int
        ): Array<String> = decoder.recognize(
            x = x,
            y = y,
            t = t,
            letters = letters,
            centerX = centerX,
            centerY = centerY,
            pinyinMode = pinyinMode,
            topK = topK
        ).toTypedArray()
    }

    override fun onCreate() {
        super.onCreate()
        decoder = FutoSwipeDecoder(applicationContext)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        warmUpExecutor.shutdownNow()
        decoder.close()
        super.onDestroy()
    }

    companion object {
        private const val API_VERSION = 1
    }
}
