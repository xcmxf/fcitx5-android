/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.swipe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.common.ipc.ISwipeDecoderService
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.data.PluginDescriptor
import timber.log.Timber

private const val SWIPE_DECODER_PLUGIN_NAME = "swipe_futo"

object SwipePluginContract {
    const val API_VERSION = 1
    const val SERVICE_ACTION = "${BuildConfig.APPLICATION_ID}.plugin.SWIPE_DECODER"
}

class SwipePluginDecoder(
    context: Context,
    private val pinyinMode: Boolean
) : SwipeTypingDecoder {

    private val connection = SwipeDecoderPluginConnection(context.applicationContext)

    override fun warmUp() {
        connection.connect()
    }

    override fun recognize(request: SwipeRecognitionRequest, topK: Int): List<SwipeCandidate> {
        val service = connection.getOrConnect() ?: return emptyList()
        val ready = runCatching {
            service.getApiVersion() == SwipePluginContract.API_VERSION && service.isReady(pinyinMode)
        }.onFailure {
            Timber.w(it, "Swipe decoder plugin readiness check failed")
            connection.disconnect()
        }.getOrDefault(false)
        if (!ready) return emptyList()

        val points = request.points
        val words = runCatching {
            service.recognize(
                FloatArray(points.size) { points[it].x },
                FloatArray(points.size) { points[it].y },
                FloatArray(points.size) { points[it].t },
                request.layout.letters,
                request.layout.centerX,
                request.layout.centerY,
                pinyinMode,
                topK
            )
        }.onFailure {
            Timber.w(it, "Swipe decoder plugin recognition failed")
            connection.disconnect()
        }.getOrNull() ?: return emptyList()

        return words.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(topK)
            .mapIndexed { index, word -> SwipeCandidate(word, 1f - index * 0.01f) }
            .toList()
    }

    override fun close() {
        connection.disconnect()
    }
}

private class SwipeDecoderPluginConnection(
    private val context: Context
) : ServiceConnection {

    private var service: ISwipeDecoderService? = null
    private var bound = false
    private var binding = false
    private var missingPackageCheckedAt = 0L

    fun getOrConnect(): ISwipeDecoderService? {
        service?.let { return it }
        connect()
        return null
    }

    fun connect() {
        if (bound || binding) return
        val pluginPackage = findPluginPackage() ?: run {
            missingPackageCheckedAt = SystemClock.uptimeMillis()
            return
        }
        binding = true
        val ok = runCatching {
            context.bindService(
                Intent(SwipePluginContract.SERVICE_ACTION).setPackage(pluginPackage),
                this,
                Context.BIND_AUTO_CREATE
            )
        }.onFailure {
            Timber.w(it, "Cannot bind swipe decoder plugin")
        }.getOrDefault(false)
        if (!ok) {
            binding = false
            Timber.w("Swipe decoder plugin service is unavailable: $pluginPackage")
        }
    }

    private fun findPluginPackage(): String? {
        DataManager.getLoadedPlugins()
            .firstOrNull { it.name == SWIPE_DECODER_PLUGIN_NAME }
            ?.packageName
            ?.let { return it }

        if (SystemClock.uptimeMillis() - missingPackageCheckedAt < MISSING_PLUGIN_CHECK_INTERVAL_MS) {
            return null
        }

        val intent = Intent(SwipePluginContract.SERVICE_ACTION)
        val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
        }
        return services.asSequence()
            .map { it.serviceInfo.packageName }
            .firstOrNull { packageName ->
                packageName.removePrefix(PluginDescriptor.pluginPackagePrefix)
                    .removeSuffix(PluginDescriptor.pluginPackageSuffix) == SWIPE_DECODER_PLUGIN_NAME
            }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = ISwipeDecoderService.Stub.asInterface(binder)
        bound = true
        binding = false
        Timber.d("Swipe decoder plugin connected: ${name.packageName}")
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
        bound = false
        binding = false
        Timber.d("Swipe decoder plugin disconnected: ${name.packageName}")
    }

    override fun onBindingDied(name: ComponentName?) {
        disconnect()
        Timber.d("Swipe decoder plugin binding died: ${name?.packageName}")
    }

    fun disconnect() {
        if (bound || binding) {
            runCatching {
                context.unbindService(this)
            }.onFailure {
                Timber.w(it, "Cannot unbind swipe decoder plugin")
            }
        }
        service = null
        bound = false
        binding = false
    }
}

private const val MISSING_PLUGIN_CHECK_INTERVAL_MS = 2_000L

object UnavailableSwipeDecoder : SwipeTypingDecoder {
    override fun recognize(request: SwipeRecognitionRequest, topK: Int): List<SwipeCandidate> =
        emptyList()
}

object SwipeTypingDecoders {
    fun create(context: Context, pinyinMode: Boolean): SwipeTypingDecoder =
        SwipePluginDecoder(context, pinyinMode)
}
