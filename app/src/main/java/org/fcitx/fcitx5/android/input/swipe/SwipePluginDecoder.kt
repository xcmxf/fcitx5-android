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
import org.fcitx.fcitx5.android.common.ipc.SwipeDecoderProtocol
import org.fcitx.fcitx5.android.core.data.PluginDescriptor
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val SWIPE_DECODER_PLUGIN_NAME = "swipe_futo"

object SwipePluginContract {
    const val SERVICE_ACTION = "${BuildConfig.APPLICATION_ID}.plugin.SWIPE_DECODER"
    const val REQUIRED_SERVICE_PERMISSION = "${BuildConfig.APPLICATION_ID}.permission.PLUGIN"
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
            lastStatus = connection.status()
            return
        }
        runCatching {
            service.warmUp(pinyinMode)
        }.onSuccess {
            lastStatus = SwipeDecoderStatus(SwipeDecoderState.Warming)
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
            lastStatus = connection.status()
            return emptyList()
        }
        val apiVersion = runCatching {
            service.getApiVersion()
        }.onFailure {
            Timber.w(it, "Swipe decoder plugin API check failed")
            lastStatus = SwipeDecoderStatus(SwipeDecoderState.Error, it.message)
            connection.disconnect()
        }.getOrNull() ?: return emptyList()
        if (!SwipeDecoderProtocol.isCompatible(apiVersion)) {
            lastStatus = SwipeDecoderStatus(
                SwipeDecoderState.ApiMismatch,
                "plugin=$apiVersion, host=${SwipeDecoderProtocol.MIN_SUPPORTED_API_VERSION}" +
                    "..${SwipeDecoderProtocol.MAX_SUPPORTED_API_VERSION}"
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
            lastStatus = SwipeDecoderStatus(
                if (status?.startsWith("FUTO Swipe unavailable:") == true) {
                    SwipeDecoderState.Error
                } else {
                    SwipeDecoderState.Warming
                },
                status
            )
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
    private var lastPluginService: ComponentName? = null
    private var lastBindError: String? = null

    fun status(): SwipeDecoderStatus = synchronized(lock) {
        when {
            service != null -> SwipeDecoderStatus.Ready
            binding -> SwipeDecoderStatus(SwipeDecoderState.Binding)
            lastBindError != null -> SwipeDecoderStatus(SwipeDecoderState.Error, lastBindError)
            lastPluginService == null -> SwipeDecoderStatus(SwipeDecoderState.MissingPlugin)
            else -> SwipeDecoderStatus(SwipeDecoderState.NotReady)
        }
    }

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
        val pluginService = findPluginService() ?: return
        synchronized(lock) {
            if (bound || binding || closed) return
            binding = true
            lastBindError = null
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
                lastBindError = "Plugin service is unavailable"
                lock.notifyAll()
            }
            Timber.w(
                "Swipe decoder plugin service is unavailable: " +
                    pluginService.flattenToShortString()
            )
        }
    }

    private fun findPluginService(): ComponentName? {
        if (SystemClock.uptimeMillis() - missingPackageCheckedAt < MISSING_PLUGIN_CHECK_INTERVAL_MS) {
            return synchronized(lock) { lastPluginService }
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
        var rejectionReason: String? = null
        val pluginService = services.asSequence()
            .map { it.serviceInfo }
            .filter { serviceInfo ->
                serviceInfo.packageName == expectedPluginPackage()
            }
            .firstOrNull { serviceInfo ->
                trustedServiceFailure(serviceInfo)?.also { rejectionReason = it } == null
            }
            ?.let { serviceInfo -> ComponentName(serviceInfo.packageName, serviceInfo.name) }
        synchronized(lock) {
            missingPackageCheckedAt = SystemClock.uptimeMillis()
            lastPluginService = pluginService
            lastBindError = rejectionReason
        }
        return pluginService
    }

    private fun expectedPluginPackage(): String =
        PluginDescriptor.pluginPackagePrefix + SWIPE_DECODER_PLUGIN_NAME +
            PluginDescriptor.pluginPackageSuffix

    private fun trustedServiceFailure(serviceInfo: android.content.pm.ServiceInfo): String? = when {
        !serviceInfo.exported -> "Swipe decoder service is not exported"
        serviceInfo.permission != SwipePluginContract.REQUIRED_SERVICE_PERMISSION ->
            "Swipe decoder service has an unexpected permission"
        context.packageManager.checkSignatures(context.packageName, serviceInfo.packageName) !=
            PackageManager.SIGNATURE_MATCH ->
            "Swipe decoder plugin signing certificate does not match the host"
        else -> null
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
                lastBindError = null
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
            lastBindError = "Plugin service disconnected"
            lock.notifyAll()
        }
        Timber.d("Swipe decoder plugin disconnected: ${name.packageName}")
    }

    override fun onBindingDied(name: ComponentName?) {
        synchronized(lock) {
            lastBindError = "Plugin service binding died"
        }
        disconnect()
        Timber.d("Swipe decoder plugin binding died: ${name?.packageName}")
    }

    override fun onNullBinding(name: ComponentName) {
        synchronized(lock) {
            service = null
            bound = false
            binding = false
            lastBindError = "Plugin service returned no Binder"
            lock.notifyAll()
        }
        Timber.w("Swipe decoder plugin returned no Binder: ${name.packageName}")
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
