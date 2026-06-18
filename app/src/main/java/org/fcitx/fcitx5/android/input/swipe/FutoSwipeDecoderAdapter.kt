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
    private val recognizeMethod: Method,
    private val setModeMethod: Method,
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
                null,
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

        fun create(root: File): FutoSwipeDecoderAdapter? {
            val encoder = findFirstExisting(
                root.resolve("encoder/model_fp32.pte"),
                root.resolve("model_fp32.pte")
            ) ?: return null
            val decoderModel = findFirstExisting(
                root.resolve("decoder/model_fp32.pte"),
                root.resolve("magic_macaw/model_fp32.pte")
            )
            val lmModel = findFirstExisting(root.resolve("context_lm/model_fp32.pte"))
            val lmVocab = findFirstExisting(root.resolve("context_lm/vocab.txt"))

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
                val resultClass = Class.forName("$CLASS_NAME\$Result")
                FutoSwipeDecoderAdapter(
                    instance,
                    recognize,
                    setMode,
                    resultClass.getMethod("getWord"),
                    resultClass.getMethod("getScore")
                )
            }.onFailure {
                Timber.w(it, "FUTO swipe decoder is unavailable")
            }.getOrNull()
        }

        private fun findFirstExisting(vararg files: File): File? =
            files.firstOrNull { it.isFile }
    }
}

object SwipeTypingDecoders {
    fun create(context: Context, pinyinMode: Boolean): SwipeTypingDecoder {
        val root = SwipeAssets.prepare(context)
        val dictionary = SwipeAssets.readDictionary(root, pinyinMode)
        return if (pinyinMode) {
            TraceShapeSwipeDecoder(dictionary)
        } else {
            FutoSwipeDecoderAdapter.create(root) ?: TraceShapeSwipeDecoder(dictionary)
        }
    }
}
