import java.io.BufferedInputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.swipe_futo"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.swipe_futo"
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets("futo_swipe_trie_bridge")
            }
        }
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        release {
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

    packaging {
        jniLibs {
            // The FUTO AAR and our dictionary bridge both use the shared C++ runtime.
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

val futoSwipeAssets = layout.buildDirectory.dir("generated/futo-swipe-assets")
val futoSwipeWorkspace = layout.buildDirectory.dir("intermediates/futo-swipe-workspace")

data class DownloadedAsset(
    val relativePath: String,
    val url: String,
    val sha256: String
)

data class PinyinEntryStats(
    var entryCount: Int = 0,
    var weightedUsage: Double = 0.0,
    var maxSyllableCount: Int = 1
)

val downloadFutoSwipeAssets by tasks.registering {
    description = "Downloads the pinned FUTO Swipe model and vocabularies."
    inputs.property("generatedAssetLayoutVersion", 4)
    outputs.dir(futoSwipeAssets)
    outputs.dir(futoSwipeWorkspace)

    doLast {
        val assetRoot = futoSwipeAssets.get().asFile
        val workspaceRoot = futoSwipeWorkspace.get().asFile
        assetRoot.deleteRecursively()
        assetRoot.mkdirs()
        workspaceRoot.mkdirs()

        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun download(asset: DownloadedAsset, rootDir: File = assetRoot): File {
            val target = rootDir.resolve(asset.relativePath)
            if (target.isFile && sha256(target) == asset.sha256) return target

            target.parentFile.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.part")
            URL(asset.url).openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 120_000
            }.getInputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(sha256(temporary) == asset.sha256) {
                "Checksum mismatch for ${asset.relativePath}"
            }
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
            return target
        }

        fun extractTar(archive: File, outputDir: File, expectedFiles: List<String>) {
            if (expectedFiles.all { outputDir.resolve(it).isFile }) return

            outputDir.deleteRecursively()
            outputDir.mkdirs()
            val process = ProcessBuilder(
                "tar",
                "-xf",
                archive.absolutePath,
                "-C",
                outputDir.absolutePath
            )
                .directory(project.rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) {
                "Failed to extract ${archive.name}: $output"
            }
            check(expectedFiles.all { outputDir.resolve(it).isFile }) {
                "Failed to extract ${archive.name}"
            }
        }

        fun buildPinyinFrequency(stats: PinyinEntryStats): Int {
            val ambiguityBonus = minOf(
                32,
                (log2((stats.entryCount + 1).toDouble()) * 7.0).roundToInt()
            )
            val usageBonus = minOf(
                12,
                (log2(stats.weightedUsage + 1.0) * 4.0).roundToInt()
            )
            val syllableBonus = minOf(
                16,
                (stats.maxSyllableCount - 1).coerceAtLeast(0) * 8
            )
            return (132 + ambiguityBonus + usageBonus + syllableBonus).coerceAtMost(212)
        }

        fun generatePinyinDictionary(target: File, sources: List<File>) {
            val entryStats = linkedMapOf<String, PinyinEntryStats>()
            sources.forEach { source ->
                source.useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split('\t')
                        if (parts.size < 2) return@forEach
                        val rawPinyin = parts[1].lowercase()
                        val word = buildString(rawPinyin.length) {
                            rawPinyin.forEach { character ->
                                if (character in 'a'..'z') append(character)
                            }
                        }
                        if (word.length !in 2..24) return@forEach
                        val sourceScore = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                        val usageWeight = if (sourceScore == 0.0) 1.0 else 10.0.pow(sourceScore)
                        val syllableCount = rawPinyin.count { it == '\'' } + 1
                        val stats = entryStats.getOrPut(word, ::PinyinEntryStats)
                        stats.entryCount += 1
                        stats.weightedUsage += usageWeight
                        stats.maxSyllableCount = maxOf(stats.maxSyllableCount, syllableCount)
                    }
                }
            }

            target.parentFile.mkdirs()
            target.bufferedWriter().use { writer ->
                writer.appendLine(
                    "dictionary=main:zh-pinyin,locale=zh,description=Fcitx5 Pinyin Swipe,version=2"
                )
                entryStats.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, PinyinEntryStats>> {
                            buildPinyinFrequency(it.value)
                        }
                            .thenByDescending { it.value.weightedUsage }
                            .thenByDescending { it.value.entryCount }
                            .thenByDescending { it.value.maxSyllableCount }
                            .thenByDescending { it.key.length }
                            .thenBy { it.key }
                    )
                    .forEach { (word, stats) ->
                        writer.appendLine("word=$word,f=${buildPinyinFrequency(stats)}")
                    }
            }
        }

        val modelRevision = "07ddb48ca68eee2be29b071c71d654f0e7bb126a"
        val modelBase = "https://huggingface.co/futo-org/futo-swipe/resolve/$modelRevision"
        download(
            DownloadedAsset(
                "futo-swipe/honorable_sturgeon/model_fp32.pte",
                "$modelBase/honorable_sturgeon/model_fp32.pte?download=true",
                "725242bab5d14345e96ff214e8de2bfbc1f962c232d320df9c24cb82ffd1fbaf"
            )
        )
        download(
            DownloadedAsset(
                "futo-swipe/honorable_sturgeon/metadata.json",
                "$modelBase/honorable_sturgeon/metadata.json?download=true",
                "d2c5aecd89d97e21125046eb1f311b5aed1bdb5805e97316bba70b13f1c7be2c"
            )
        )
        download(
            DownloadedAsset(
                "futo-swipe/licenses/FUTO_SWIPE_MODELS_LICENSE.md",
                "$modelBase/LICENSE.md?download=true",
                "ef6b4f6437efa0a2929de351b10c16a8c870b32e1c23054ac76af61531f5db21"
            )
        )

        val keyboardRevision = "007394af28bb72ad70420143b08aba2d74e0e790"
        val keyboardBase = "https://raw.githubusercontent.com/futo-org/android-keyboard/$keyboardRevision"
        val dictionaryArchive = download(
            DownloadedAsset(
                "downloads/en_US_wordlist.combined.gz",
                "$keyboardBase/dictionaries/en_US_wordlist.combined.gz",
                "0f78dd455b532be169a23f233227b811fabced4b5bd7fc9c40cc05839793bcbd"
            ),
            workspaceRoot
        )
        download(
            DownloadedAsset(
                "futo-swipe/licenses/FUTO_KEYBOARD_LICENSE.md",
                "$keyboardBase/LICENSE.md",
                "1cb7bb3c9ff502c32aed996b3465d30aa7a0cc7e50f65a22d2512d51dc6ef6d7"
            )
        )

        val dictionary = futoSwipeAssets.get().file(
            "futo-swipe/vocabs/en_US_wordlist.combined"
        ).asFile
        if (!dictionary.isFile || dictionary.length() == 0L) {
            dictionary.parentFile.mkdirs()
            GZIPInputStream(BufferedInputStream(dictionaryArchive.inputStream())).use { input ->
                dictionary.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val fcitxDictArchive = download(
            DownloadedAsset(
                "downloads/dict-20260430.tar.zst",
                "https://download.fcitx-im.org/data/dict-20260430.tar.zst",
                "3edc008d90fcd61b9967b9e590f396189d7a00fa74c45ecde0d9850a0fdd6241"
            ),
            workspaceRoot
        )
        val extractedPinyinDir = workspaceRoot.resolve("pinyin-dict")
        extractTar(
            fcitxDictArchive,
            extractedPinyinDir,
            listOf("dict_sc.txt", "dict_extb.txt")
        )

        generatePinyinDictionary(
            futoSwipeAssets.get().file("futo-swipe/vocabs/pinyin.combined").asFile,
            listOf(
                extractedPinyinDir.resolve("dict_sc.txt"),
                extractedPinyinDir.resolve("dict_extb.txt")
            )
        )
    }
}

android.sourceSets.getByName("main").assets.srcDir(futoSwipeAssets.get().asFile)
tasks.configureEach {
    if (
        (name.startsWith("merge") && name.endsWith("Assets")) ||
        name == "lintVitalAnalyzeRelease" ||
        name == "generateReleaseLintVitalReportModel"
    ) {
        dependsOn(downloadFutoSwipeAssets)
    }
}

dependencies {
    implementation(project(":lib:plugin-base"))
    implementation(files("third_party/futo-android-libs/futo-swipe-release.aar"))
    implementation(libs.timber)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
