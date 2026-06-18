/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.swipe

import android.content.Context
import timber.log.Timber
import java.io.File

object SwipeAssets {
    const val ASSET_ROOT = "swipe"
    private const val LATIN_DICTIONARY_FILE = "latin.txt"
    private const val PINYIN_DICTIONARY_FILE = "pinyin.txt"

    fun prepare(context: Context): File {
        val target = context.noBackupFilesDir.resolve(ASSET_ROOT)
        copyAssetDirectory(context, ASSET_ROOT, target)
        return target
    }

    fun readDictionary(root: File, pinyinMode: Boolean): List<String> {
        val defaultWords = if (pinyinMode) {
            TraceShapeSwipeDecoder.pinyinDictionary
        } else {
            TraceShapeSwipeDecoder.latinDictionary
        }
        val file = root.resolve(
            if (pinyinMode) PINYIN_DICTIONARY_FILE else LATIN_DICTIONARY_FILE
        )
        if (!file.isFile) return defaultWords
        val words = runCatching {
            file.useLines { lines ->
                lines.map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            }
        }.onFailure {
            Timber.w(it, "Failed to read swipe dictionary")
        }.getOrNull().orEmpty()
        return defaultWords + words
    }

    private fun copyAssetDirectory(context: Context, assetPath: String, target: File) {
        val assets = context.assets
        val children = runCatching { assets.list(assetPath).orEmpty() }.getOrDefault(emptyArray())
        if (children.isEmpty()) {
            copyAssetFile(context, assetPath, target)
            return
        }
        target.mkdirs()
        children.forEach { child ->
            copyAssetDirectory(context, "$assetPath/$child", target.resolve(child))
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, target: File) {
        runCatching {
            context.assets.open(assetPath).use { input ->
                val assetSize = input.available().toLong()
                target.parentFile?.mkdirs()
                if (target.isFile && target.length() == assetSize) return
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
        }.onFailure {
            Timber.w(it, "Failed to copy swipe asset $assetPath")
        }
    }
}
