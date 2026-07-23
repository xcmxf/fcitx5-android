/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.RawConfig
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import timber.log.Timber

class FcitxTest {

    private companion object {

        lateinit var fcitx: Fcitx
        val fcitxEventChannel = Channel<FcitxEvent<*>>(capacity = Channel.CONFLATED)
        val scope = MainScope()

        @BeforeClass
        @JvmStatic
        fun setup() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            fcitx = Fcitx(context)

            // forward to our channel for point to point consuming
            fcitx.eventFlow
                .onEach { fcitxEventChannel.send(it) }
                .launchIn(scope)
            fcitx.start()

            // wait fcitx started
            runBlocking {
                receiveFirst<FcitxEvent.ReadyEvent>()
                fcitx.setEnabledIme(arrayOf("pinyin"))
                fcitx.setGlobalConfig(
                    RawConfig(
                        arrayOf(
                            RawConfig(
                                "Behavior", arrayOf(
                                    RawConfig("ShowInputMethodInformation", false)
                                )
                            )
                        )
                    )
                )
            }
        }

        private suspend fun activateTestInputContext() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            fcitx.activate(context.applicationInfo.uid, context.packageName)
            fcitx.setCapFlags(CapabilityFlags.DefaultFlags)
            fcitx.focus(true)
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            fcitx.stop()
        }

        private suspend fun sendString(str: String) {
            str.forEach { c ->
                fcitx.sendKey(c)
                delay(50)
            }
        }

        private suspend fun waitForCandidates(
            timeoutMs: Long = 5000L,
            predicate: (Array<CandidateWord>) -> Boolean
        ): Array<CandidateWord> = withTimeout(timeoutMs) {
            while (true) {
                val candidates = fcitx.getCandidates(0, 10)
                if (predicate(candidates)) return@withTimeout candidates
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE")
            emptyArray()
        }

        private suspend inline fun <reified T : FcitxEvent<*>> receiveFirst(): T? =
            fcitxEventChannel.receiveAsFlow().mapNotNull { it as? T }.firstOrNull()

        private suspend fun receiveFirstCandidateList() =
            receiveFirst<FcitxEvent.CandidateListEvent>()

        private suspend fun receiveFirstCommitString() =
            receiveFirst<FcitxEvent.CommitStringEvent>()

        private suspend fun receiveFirstPreedit() = receiveFirst<FcitxEvent.ClientPreeditEvent>()

        private suspend fun receiveFirstInputPanelAux() =
            receiveFirst<FcitxEvent.InputPanelEvent>()

    }

    private var enabledIme: List<String> = listOf()

    @Before
    fun saveEnabledIME() = runBlocking {
        enabledIme = fcitx.enabledIme().map { it.uniqueName }
    }

    @After
    fun restoreEnabledIME() = runBlocking {
        fcitx.setEnabledIme(enabledIme.toTypedArray())
    }

    @Test
    fun testWbx(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("wbx"))
        sendString("wqvb")
        val expected = "你好"
        fcitx.select(0)
        val commitString = receiveFirstCommitString()?.data
        Timber.i("commitString is $commitString")
        Assert.assertEquals(expected, commitString)
        fcitx.reset()
    }

    @Test
    fun testPinyin(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin"))
        sendString("nihaoshijie")
        val expected = "你好世界"
        fcitx.select(0)
        val commitString = receiveFirstCommitString()?.data
        Timber.i("commitString is $commitString")
        Assert.assertEquals(expected, commitString)
        fcitx.reset()
    }

    @Test
    fun testSwipePinyinBridgeCandidates(): Unit = runBlocking {
        fcitx.reset()
        fcitx.setEnabledIme(arrayOf("pinyin"))
        activateTestInputContext()
        fcitx.activateIme("pinyin")
        sendString("shifoushi")

        val candidates = waitForCandidates { list ->
            list.any { it.text.contains("是") || it.text.contains("否") }
        }

        Timber.i(
            "swipe pinyin bridge candidates are ${candidates.joinToString()}, " +
                "currentIme=${fcitx.currentIme()}, preedit=${fcitx.inputPanelCached.preedit}"
        )
        Assert.assertTrue(candidates.none { it.text == "shifoushi" })
        Assert.assertTrue(candidates.any { it.text.contains("是") || it.text.contains("否") })
        fcitx.reset()
    }

    @Test
    fun testSwipePinyinBridgeAcceptsQwertyVAliases(): Unit = runBlocking {
        val aliases = mapOf(
            "jve" to "觉决绝",
            "qve" to "却确",
            "xve" to "学",
            "lue" to "略",
            "nue" to "虐"
        )
        fcitx.setEnabledIme(arrayOf("pinyin"))
        activateTestInputContext()
        fcitx.activateIme("pinyin")

        aliases.forEach { (alias, expectedChinese) ->
            fcitx.reset()
            sendString(alias)
            val candidates = waitForCandidates { list ->
                list.any { candidate -> candidate.text.any(expectedChinese::contains) }
            }

            Assert.assertTrue(
                "$alias did not produce an expected Chinese candidate: " +
                    candidates.joinToString(),
                candidates.any { candidate -> candidate.text.any(expectedChinese::contains) }
            )
        }
        fcitx.reset()
    }

    @Test
    fun testInputPanelStatus(): Unit = runBlocking {
        fcitx.reset()
        Timber.i("after first reset: ${fcitx.isEmpty()}")
        Assert.assertEquals(true, fcitx.isEmpty())
        fcitx.sendKey('a')
        do {
            val list = receiveFirstCandidateList()
        } while (list!!.data.candidates.isNotEmpty())
        Timber.i("after sending 'a': ${fcitx.isEmpty()}")
        Assert.assertEquals(false, fcitx.isEmpty())
        fcitx.reset()
        do {
            val list = receiveFirstCandidateList()
        } while (list!!.data.candidates.isNotEmpty())
        Timber.i("after second reset: ${fcitx.isEmpty()}")
        Assert.assertEquals(true, fcitx.isEmpty())
    }

}
