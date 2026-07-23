/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.core.data.PluginDescriptor
import org.fcitx.fcitx5.android.input.swipe.SwipeCandidate
import org.fcitx.fcitx5.android.input.swipe.SwipeKey
import org.fcitx.fcitx5.android.input.swipe.SwipeLayout
import org.fcitx.fcitx5.android.input.swipe.SwipePluginDecoder
import org.fcitx.fcitx5.android.input.swipe.SwipePoint
import org.fcitx.fcitx5.android.input.swipe.SwipeRecognitionRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Requires the separately built Swipe plugin APK to be installed beside the host test APK.
 * It intentionally skips in a generic app-only connected-test invocation.
 */
class SwipePluginDecoderInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val decoder = SwipePluginDecoder(context, pinyinMode = true)

    @After
    fun tearDown() {
        decoder.close()
    }

    @Test
    fun coldStartsAndRecoversAfterPluginForceStop() {
        assumeTrue("FUTO Swipe plugin must be installed for this integration test", pluginInstalled())

        val request = pinyinSwipeRequest("shifou")
        assertEquals("shifou", awaitTopCandidate(request, "cold start").word)

        instrumentation.uiAutomation.executeShellCommand("am force-stop $pluginPackage").use { }

        assertEquals("shifou", awaitTopCandidate(request, "plugin force-stop recovery").word)
    }

    private fun awaitTopCandidate(
        request: SwipeRecognitionRequest,
        phase: String
    ): SwipeCandidate {
        repeat(MAX_ATTEMPTS) {
            decoder.warmUp()
            decoder.recognize(request, topK = 4).firstOrNull()?.let { candidate ->
                if (candidate.word == "shifou") return candidate
            }
            Thread.sleep(RETRY_DELAY_MS)
        }
        fail("FUTO Swipe plugin did not produce shifou during $phase; status=${decoder.status()}")
        error("unreachable")
    }

    private fun pluginInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(pluginPackage, 0)
    }.isSuccess

    private fun pinyinSwipeRequest(word: String): SwipeRecognitionRequest {
        val positions = qwertyPositions()
        val layout = SwipeLayout(
            positions.entries.map { (letter, point) -> SwipeKey(letter.toString(), point.x, point.y) }
        )
        val anchors = word.map { positions.getValue(it) }
        val points = buildList {
            var timeMs = 0f
            anchors.forEachIndexed { index, point ->
                if (index == 0) {
                    add(SwipePoint(point.x, point.y, timeMs))
                    return@forEachIndexed
                }
                val previous = anchors[index - 1]
                for (step in 1..4) {
                    val ratio = step / 4f
                    timeMs += 22f
                    add(
                        SwipePoint(
                            previous.x + (point.x - previous.x) * ratio,
                            previous.y + (point.y - previous.y) * ratio,
                            timeMs
                        )
                    )
                }
            }
        }
        return SwipeRecognitionRequest(points, layout, word)
    }

    private fun qwertyPositions(): Map<Char, Point> = buildMap {
        "qwertyuiop".forEachIndexed { index, letter ->
            put(letter, Point(0.05f + index * 0.10f, 0.24f))
        }
        "asdfghjkl".forEachIndexed { index, letter ->
            put(letter, Point(0.09f + index * 0.10f, 0.50f))
        }
        "zxcvbnm".forEachIndexed { index, letter ->
            put(letter, Point(0.22f + index * 0.11f, 0.76f))
        }
    }

    private data class Point(val x: Float, val y: Float)

    private val pluginPackage: String =
        PluginDescriptor.pluginPackagePrefix + "swipe_futo" + PluginDescriptor.pluginPackageSuffix

    private companion object {
        const val MAX_ATTEMPTS = 80
        const val RETRY_DELAY_MS = 125L
    }
}
