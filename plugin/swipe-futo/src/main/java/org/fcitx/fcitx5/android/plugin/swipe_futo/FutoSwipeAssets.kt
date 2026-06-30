/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.swipe_futo

import android.content.Context
import java.io.File

internal data class FutoSwipeFiles(
    val encoder: File,
    val englishDictionary: File,
    val pinyinDictionary: File
)

/** Keeps FUTO's mmap-backed model files out of the APK asset container. */
internal object FutoSwipeAssets {
    private const val ASSET_ROOT = "futo-swipe"
    private const val VERSION = "futo-swipe-2026-06-27-r3"

    fun prepare(context: Context): FutoSwipeFiles {
        val root = context.createDeviceProtectedStorageContext().noBackupFilesDir.resolve(ASSET_ROOT)
        val marker = root.resolve(".version")
        if (!marker.isFile || marker.readText() != VERSION) {
            root.deleteRecursively()
            copyAssetTree(context, ASSET_ROOT, root)
            marker.parentFile?.mkdirs()
            marker.writeText(VERSION)
        }

        return FutoSwipeFiles(
            encoder = root.resolve("honorable_sturgeon/model_fp32.pte"),
            englishDictionary = root.resolve("vocabs/en_US_wordlist.combined"),
            pinyinDictionary = root.resolve("vocabs/pinyin.combined")
        )
    }

    private fun copyAssetTree(context: Context, source: String, target: File) {
        val children = context.assets.list(source).orEmpty()
        if (children.isEmpty()) {
            copyAssetFile(context, source, target)
            return
        }
        target.mkdirs()
        children.forEach { child ->
            copyAssetTree(context, "$source/$child", target.resolve(child))
        }
    }

    private fun copyAssetFile(context: Context, source: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(source).use { input ->
            val expectedLength = input.available().toLong()
            if (target.isFile && target.length() == expectedLength) return
            val temporary = File(target.parentFile, "${target.name}.part")
            temporary.outputStream().use { output -> input.copyTo(output) }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }
    }
}
