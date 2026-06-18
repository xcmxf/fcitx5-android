/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.swipe

import android.content.Context
import timber.log.Timber
import java.io.File
import java.lang.reflect.Method

class FutoSwipeDecoderAdapter private constructor(
    private val decoder: AutoCloseable,
    private val trieHandle: Long,
    private val recognizeMethod: Method,
    private val setModeMethod: Method,
    private val destroyTrieMethod: Method,
    private val resultWordMethod: Method,
    private val resultScoreMethod: Method
) : SwipeTypingDecoder {

    private var layoutSignature = ""

    override fun recognize(request: SwipeRecognitionRequest, topK: Int): List<SwipeCandidate> {
        updateLayout(request.layout)
        val x = FloatArray(request.points.size) { request.points[it].x }
        val y = FloatArray(request.points.size) { request.points[it].y }
        val t = FloatArray(request.points.size) { request.points[it].t }
        val raw = runCatching {
            recognizeMethod.invoke(decoder, x, y, t, topK, 100, null) as? List<*>
        }.onFailure {
            Timber.w(it, "FUTO swipe recognize failed")
        }.getOrNull() ?: return emptyList()
        return raw.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            val word = resultWordMethod.invoke(item) as? String ?: return@mapNotNull null
            val score = resultScoreMethod.invoke(item) as? Float ?: return@mapNotNull null
            SwipeCandidate(word, score)
        }
    }

    override fun close() {
        runCatching {
            destroyTrieMethod.invoke(null, trieHandle)
        }.onFailure {
            Timber.w(it, "FUTO swipe trie cleanup failed")
        }
        decoder.close()
    }

    private fun updateLayout(layout: SwipeLayout) {
        val signature = layout.signature()
        if (signature == layoutSignature) return
        val ok = runCatching {
            setModeMethod.invoke(
                decoder,
                layout.letters,
                layout.centerX,
                layout.centerY,
                longArrayOf(trieHandle),
                null,
                null,
                null
            ) as? Boolean
        }.onFailure {
            Timber.w(it, "FUTO swipe layout update failed")
        }.getOrNull() == true
        if (ok) {
            layoutSignature = signature
        }
    }

    companion object {
        private const val CLASS_NAME = "org.futo.ml.inference.SwipeDecoder"

        fun create(root: File, pinyinMode: Boolean): FutoSwipeDecoderAdapter? {
            val encoder = findFirstExisting(
                root.resolve("encoder/model_fp32.pte"),
                root.resolve("honorable_sturgeon/model_fp32.pte"),
                root.resolve("model_fp32.pte")
            ) ?: return null
            val dictionary = findDictionary(root, pinyinMode) ?: return null
            val decoderModel = findFirstExisting(
                root.resolve("decoder/model_fp32.pte"),
                root.resolve("magic_macaw/model_fp32.pte")
            )
            val lmModel = findFirstExisting(
                root.resolve("context_lm/model_fp32.pte"),
                root.resolve("hungry_jellyfish/context_lm.pte")
            )
            val lmVocab = findFirstExisting(
                root.resolve("context_lm/vocab.txt"),
                root.resolve("hungry_jellyfish/vocab.txt")
            )

            return runCatching {
                val clazz = Class.forName(CLASS_NAME)
                val constructor = clazz.getConstructor(
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    String::class.java
                )
                val instance = constructor.newInstance(
                    encoder.absolutePath,
                    decoderModel?.absolutePath,
                    1,
                    100,
                    4,
                    true,
                    "f",
                    lmModel?.absolutePath,
                    lmVocab?.absolutePath
                ) as AutoCloseable
                val recognize = clazz.getMethod(
                    "recognize",
                    FloatArray::class.java,
                    FloatArray::class.java,
                    FloatArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    FloatArray::class.java
                )
                val setMode = clazz.getMethod(
                    "setMode",
                    String::class.java,
                    FloatArray::class.java,
                    FloatArray::class.java,
                    LongArray::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java
                )
                val loadTrie = clazz.getMethod("loadTrieSimple", String::class.java)
                val destroyTrie = clazz.getMethod("destroyTrie", Long::class.javaPrimitiveType)
                val trieHandle = loadTrie.invoke(null, dictionary.absolutePath) as? Long
                    ?: error("FUTO swipe dictionary did not return a trie handle")
                if (trieHandle == 0L) error("FUTO swipe dictionary returned an empty trie handle")
                val resultClass = Class.forName("$CLASS_NAME\$Result")
                FutoSwipeDecoderAdapter(
                    instance,
                    trieHandle,
                    recognize,
                    setMode,
                    destroyTrie,
                    resultClass.getMethod("getWord"),
                    resultClass.getMethod("getScore")
                )
            }.onFailure {
                Timber.w(it, "FUTO swipe decoder is unavailable")
            }.getOrNull()
        }

        private fun findDictionary(root: File, pinyinMode: Boolean): File? =
            if (pinyinMode) {
                findFirstExisting(
                    root.resolve("vocabs/pinyin.combined"),
                    root.resolve("pinyin.combined"),
                    root.resolve("pinyin/pinyin.combined")
                )
            } else {
                findFirstExisting(
                    root.resolve("vocabs/en.combined"),
                    root.resolve("en.combined"),
                    root.resolve("vocabs/en_wordlist.combined"),
                    root.resolve("en_wordlist.combined"),
                    root.resolve("en_US_wordlist.combined")
                )
            }

        private fun findFirstExisting(vararg files: File): File? =
            files.firstOrNull { it.isFile }
    }
}

object UnavailableSwipeDecoder : SwipeTypingDecoder {
    override fun recognize(request: SwipeRecognitionRequest, topK: Int): List<SwipeCandidate> =
        emptyList()
}

object SwipeTypingDecoders {
    fun create(context: Context, pinyinMode: Boolean): SwipeTypingDecoder {
        val root = SwipeAssets.prepare(context)
        return FutoSwipeDecoderAdapter.create(root, pinyinMode) ?: UnavailableSwipeDecoder
    }
}
