/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.swipe

import android.content.Context
import androidx.preference.PreferenceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.BuildConfig
import timber.log.Timber
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only, shareable representation of a swipe request. It deliberately excludes the target
 * app, the surrounding input text, account data, and Fcitx's committed text.
 */
@Serializable
internal data class SwipeTraceFixture(
    val schemaVersion: Int = SwipeTraceFixtures.SCHEMA_VERSION,
    val id: String,
    val profile: SwipeTraceFixtureProfile,
    val keyboard: SwipeTraceKeyboard,
    val points: List<SwipeTraceFixturePoint>,
    val tracedLetters: String,
    val pluginCandidates: List<String> = emptyList(),
    val expectedTopCandidate: String? = null
)

@Serializable
internal enum class SwipeTraceFixtureProfile {
    English,
    Pinyin
}

@Serializable
internal data class SwipeTraceKeyboard(
    val widthPx: Int,
    val heightPx: Int,
    val orientation: Int,
    val keys: List<SwipeTraceFixtureKey>
)

@Serializable
internal data class SwipeTraceFixtureKey(
    val letter: String,
    val centerX: Float,
    val centerY: Float
)

@Serializable
internal data class SwipeTraceFixturePoint(
    val x: Float,
    val y: Float,
    val t: Float
)

internal object SwipeTraceFixtures {

    const val SCHEMA_VERSION = 1

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
    }

    fun encode(fixture: SwipeTraceFixture): String = json.encodeToString(fixture)

    fun decode(serialized: String): SwipeTraceFixture? = runCatching {
        json.decodeFromString<SwipeTraceFixture>(serialized)
    }.getOrNull()

    fun create(
        id: String,
        profile: SwipeTypingProfile,
        request: SwipeRecognitionRequest,
        keyboardWidthPx: Int,
        keyboardHeightPx: Int,
        orientation: Int,
        candidates: List<SwipeCandidate>
    ): SwipeTraceFixture? {
        val fixtureProfile = profile.toFixtureProfile() ?: return null
        val boundedRequest = request.boundedForDecoder() ?: return null
        return SwipeTraceFixture(
            id = id,
            profile = fixtureProfile,
            keyboard = SwipeTraceKeyboard(
                widthPx = keyboardWidthPx.coerceAtLeast(1),
                heightPx = keyboardHeightPx.coerceAtLeast(1),
                orientation = orientation,
                keys = boundedRequest.layout.keys.map {
                    SwipeTraceFixtureKey(it.letter, it.centerX, it.centerY)
                }
            ),
            points = boundedRequest.points.map {
                SwipeTraceFixturePoint(it.x, it.y, it.t)
            },
            tracedLetters = normalizedLetters(boundedRequest.tracedLetters),
            pluginCandidates = candidates.asSequence()
                .map { it.word.trim().lowercase(Locale.ROOT).take(MAX_CANDIDATE_LENGTH) }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_RECORDED_CANDIDATES)
                .toList()
        )
    }

    fun toRecognitionRequest(fixture: SwipeTraceFixture): SwipeRecognitionRequest? {
        if (
            fixture.schemaVersion != SCHEMA_VERSION ||
            fixture.keyboard.widthPx <= 0 ||
            fixture.keyboard.heightPx <= 0 ||
            fixture.points.size < MIN_FIXTURE_POINT_COUNT
        ) {
            return null
        }
        val keys = fixture.keyboard.keys.mapNotNull { key ->
            key.letter
                .takeIf { it.length == 1 && it[0].lowercaseChar() in 'a'..'z' }
                ?.let { SwipeKey(it.lowercase(Locale.ROOT), key.centerX, key.centerY) }
        }
        if (keys.size != fixture.keyboard.keys.size || keys.isEmpty()) return null
        return runCatching {
            val layout = SwipeLayout(keys)
            if (layout.keys.size != keys.size) return null
            SwipeRecognitionRequest(
                points = fixture.points.map { SwipePoint(it.x, it.y, it.t) },
                layout = layout,
                tracedLetters = normalizedLetters(fixture.tracedLetters)
            ).boundedForDecoder()
        }.getOrNull()
    }

    private fun SwipeTypingProfile.toFixtureProfile(): SwipeTraceFixtureProfile? = when (this) {
        SwipeTypingProfile.English -> SwipeTraceFixtureProfile.English
        SwipeTypingProfile.Pinyin -> SwipeTraceFixtureProfile.Pinyin
        SwipeTypingProfile.Unsupported -> null
    }

    private fun normalizedLetters(value: String): String = buildString(value.length) {
        value.forEach { character ->
            val normalized = character.lowercaseChar()
            if (normalized in 'a'..'z') append(normalized)
        }
    }.take(MAX_TRACE_LENGTH)

    private const val MAX_TRACE_LENGTH = 128
    private const val MAX_CANDIDATE_LENGTH = 64
    private const val MAX_RECORDED_CANDIDATES = 8
    private const val MIN_FIXTURE_POINT_COUNT = 3
}

/**
 * Writes opt-in debug traces under the app-specific external-files directory. The recorder runs
 * only in debug builds and stores geometry, keyboard centers, the observed key trace, and plugin
 * output; it never receives the target package name or surrounding editor content.
 */
internal object SwipeTraceRecorder {

    const val PREFERENCE_KEY = "debug_swipe_trace_recording"
    const val DIRECTORY_NAME = "swipe-traces"

    private val nextId = AtomicInteger()

    fun record(
        context: Context,
        profile: SwipeTypingProfile,
        request: SwipeRecognitionRequest,
        keyboardWidthPx: Int,
        keyboardHeightPx: Int,
        orientation: Int,
        candidates: List<SwipeCandidate>
    ) {
        if (!BuildConfig.DEBUG || !isEnabled(context)) return
        val fixture = SwipeTraceFixtures.create(
            id = buildFixtureId(profile),
            profile = profile,
            request = request,
            keyboardWidthPx = keyboardWidthPx,
            keyboardHeightPx = keyboardHeightPx,
            orientation = orientation,
            candidates = candidates
        ) ?: return
        val directory = context.applicationContext.getExternalFilesDir(DIRECTORY_NAME) ?: return
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) return
            directory.resolve("${fixture.id}.json").writeText(SwipeTraceFixtures.encode(fixture))
        }.onFailure { Timber.w(it, "Unable to record swipe trace") }
    }

    fun isEnabled(context: Context): Boolean = BuildConfig.DEBUG &&
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .getBoolean(PREFERENCE_KEY, false)

    fun directory(context: Context): File? =
        context.applicationContext.getExternalFilesDir(DIRECTORY_NAME)

    private fun buildFixtureId(profile: SwipeTypingProfile): String =
        "swipe-${profile.name.lowercase(Locale.ROOT)}-${System.currentTimeMillis()}-${nextId.incrementAndGet()}"
}
