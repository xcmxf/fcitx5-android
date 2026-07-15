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
import android.os.Looper
import android.os.SystemClock
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.common.ipc.ISwipeDecoderService
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.data.PluginDescriptor
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val SWIPE_DECODER_PLUGIN_NAME = "swipe_futo"

object SwipePluginContract {
    const val API_VERSION = 2
    const val SERVICE_ACTION = "${BuildConfig.APPLICATION_ID}.plugin.SWIPE_DECODER"
}

class SwipePluginDecoder(
    context: Context,
    private val pinyinMode: Boolean
) : SwipeTypingDecoder {

    private val connection = SwipeDecoderPluginConnection(context.applicationContext)
    @Volatile
    private var lastStatus = SwipeDecoderStatus(SwipeDecoderState.NotReady)

    override fun warmUp() {
        val service = connection.getOrConnect() ?: run {
            lastStatus = SwipeDecoderStatus(SwipeDecoderState.MissingPlugin)
            return
        }
        runCatching {
            service.warmUp(pinyinMode)
        }.onSuccess {
            lastStatus = SwipeDecoderStatus.Ready
        }.onFailure {
            Timber.w(it, "Swipe decoder plugin warm-up failed")
            lastStatus = SwipeDecoderStatus(SwipeDecoderState.Error, it.message)
            connection.disconnect()
        }
    }

    override fun status(): SwipeDecoderStatus = lastStatus

    override fun recognize(request: SwipeRecognitionRequest, topK: Int): List<SwipeCandidate> {
        val requestedTopK = topK.coerceIn(MIN_TOP_K, MAX_TOP_K)
        val service = connection.getOrConnect(RECOGNITION_BIND_TIMEOUT_MS) ?: run {
            lastStatus = SwipeDecoderStatus(SwipeDecoderState.MissingPlugin)
            return emptyList()
        }
        val apiVersion = runCatching {
            service.getApiVersion()
        }.onFailure {
            Timber.w(it, "Swipe decoder plugin API check failed")
            lastStatus = SwipeDecoderStatus(SwipeDecoderState.Error, it.message)
            connection.disconnect()
        }.getOrNull() ?: return emptyList()
        if (apiVersion != SwipePluginContract.API_VERSION) {
            lastStatus = SwipeDecoderStatus(
                SwipeDecoderState.ApiMismatch,
                "plugin=$apiVersion, app=${SwipePluginContract.API_VERSION}"
            )
            connection.disconnect()
            return emptyList()
        }

        val readyResult = runCatching {
            service.isReady(pinyinMode)
        }.onFailure {
            Timber.w(it, "Swipe decoder plugin readiness check failed")
            lastStatus = SwipeDecoderStatus(SwipeDecoderState.Error, it.message)
            connection.disconnect()
        }
        if (readyResult.isFailure) return emptyList()
        if (!readyResult.getOrThrow()) {
            val status = runCatching { service.getStatus() }.getOrNull()
            lastStatus = SwipeDecoderStatus(SwipeDecoderState.NotReady, status)
            return emptyList()
        }

        val points = request.points
        val words = runCatching {
            service.recognize(
                FloatArray(points.size) { points[it].x },
                FloatArray(points.size) { points[it].y },
                FloatArray(points.size) { points[it].t },
                request.layout.letters,
                request.tracedLetters,
                request.layout.centerX,
                request.layout.centerY,
                pinyinMode,
                requestedTopK
            )
        }.onFailure {
            Timber.w(it, "Swipe decoder plugin recognition failed")
            lastStatus = SwipeDecoderStatus(SwipeDecoderState.Error, it.message)
            connection.disconnect()
        }.getOrNull() ?: return emptyList()

        lastStatus = SwipeDecoderStatus.Ready
        return words.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(requestedTopK)
            .mapIndexed { index, word -> SwipeCandidate(word, 1f - index * 0.01f) }
            .toList()
    }

    override fun close() {
        connection.close()
    }
}

private class SwipeDecoderPluginConnection(
    private val context: Context
) : ServiceConnection {

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private var service: ISwipeDecoderService? = null
    private var bound = false
    private var binding = false
    private var closed = false
    private var missingPackageCheckedAt = 0L

    fun getOrConnect(waitMs: Long = 0L): ISwipeDecoderService? {
        synchronized(lock) {
            service?.let { return it }
        }
        connect()
        if (waitMs <= 0L) return synchronized(lock) { service }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            Looper.myLooper() == Looper.getMainLooper()
        ) {
            return synchronized(lock) { service }
        }
        val deadline = SystemClock.uptimeMillis() + waitMs
        synchronized(lock) {
            while (service == null && binding && !closed) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0L) break
                runCatching {
                    lock.wait(remaining)
                }.onFailure {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return service
        }
    }

    fun connect() {
        synchronized(lock) {
            if (bound || binding || closed) return
        }
        val pluginService = findPluginService() ?: run {
            missingPackageCheckedAt = SystemClock.uptimeMillis()
            return
        }
        synchronized(lock) {
            if (bound || binding || closed) return
            binding = true
        }
        val ok = runCatching {
            val intent = Intent(SwipePluginContract.SERVICE_ACTION).setComponent(pluginService)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.bindService(intent, Context.BIND_AUTO_CREATE, serviceExecutor, this)
            } else {
                @Suppress("DEPRECATION")
                context.bindService(intent, this, Context.BIND_AUTO_CREATE)
            }
        }.onFailure {
            Timber.w(it, "Cannot bind swipe decoder plugin")
        }.getOrDefault(false)
        if (!ok) {
            synchronized(lock) {
                binding = false
                lock.notifyAll()
            }
            Timber.w(
                "Swipe decoder plugin service is unavailable: " +
                    pluginService.flattenToShortString()
            )
        }
    }

    private fun findPluginService(): ComponentName? {
        val loadedPluginPackage = DataManager.getLoadedPlugins()
            .firstOrNull { it.name == SWIPE_DECODER_PLUGIN_NAME }
            ?.packageName

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
            .map { it.serviceInfo }
            .filter { serviceInfo ->
                serviceInfo.packageName.removePrefix(PluginDescriptor.pluginPackagePrefix)
                    .removeSuffix(PluginDescriptor.pluginPackageSuffix) == SWIPE_DECODER_PLUGIN_NAME
            }
            .sortedByDescending { serviceInfo ->
                if (serviceInfo.packageName == loadedPluginPackage) 1 else 0
            }
            .firstOrNull()
            ?.let { serviceInfo -> ComponentName(serviceInfo.packageName, serviceInfo.name) }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val shouldUnbind = synchronized(lock) {
            if (closed) {
                binding = false
                lock.notifyAll()
                true
            } else {
                service = ISwipeDecoderService.Stub.asInterface(binder)
                bound = true
                binding = false
                lock.notifyAll()
                false
            }
        }
        if (shouldUnbind) {
            runCatching {
                context.unbindService(this)
            }.onFailure {
                Timber.w(it, "Cannot unbind closed swipe decoder plugin")
            }
            Timber.d("Ignored closed swipe decoder plugin connection: ${name.packageName}")
            return
        }
        Timber.d("Swipe decoder plugin connected: ${name.packageName}")
    }

    override fun onServiceDisconnected(name: ComponentName) {
        synchronized(lock) {
            service = null
            bound = false
            binding = false
            lock.notifyAll()
        }
        Timber.d("Swipe decoder plugin disconnected: ${name.packageName}")
    }

    override fun onBindingDied(name: ComponentName?) {
        disconnect()
        Timber.d("Swipe decoder plugin binding died: ${name?.packageName}")
    }

    fun disconnect() {
        val shouldUnbind = synchronized(lock) {
            bound || binding
        }
        if (shouldUnbind) {
            runCatching {
                context.unbindService(this)
            }.onFailure {
                Timber.w(it, "Cannot unbind swipe decoder plugin")
            }
        }
        synchronized(lock) {
            service = null
            bound = false
            binding = false
            lock.notifyAll()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
        }
        disconnect()
    }

    companion object {
        private val serviceExecutor: ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "SwipeDecoderPlugin").apply {
                    isDaemon = true
                }
            }
    }
}

private const val MISSING_PLUGIN_CHECK_INTERVAL_MS = 2_000L
private const val RECOGNITION_BIND_TIMEOUT_MS = 250L
private const val MIN_TOP_K = 1
private const val MAX_TOP_K = 8

object SwipeTypingDecoders {
    fun create(context: Context, pinyinMode: Boolean): SwipeTypingDecoder =
        SwipePluginDecoder(context, pinyinMode)
}
