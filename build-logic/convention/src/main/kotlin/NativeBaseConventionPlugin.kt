/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.Properties

open class NativeBaseConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val prebuiltDir = target.rootProject.projectDir.resolve("lib/fcitx5/src/main/cpp/prebuilt")
        val isBuildingBundle = target.rootProject.gradle.startParameter.taskNames.any {
            it.startsWith("${target.path}:bundle")
        }
        val localEcmDir = target.rootProject.layout.buildDirectory
            .dir("intermediates/extra-cmake-modules/install/share/ECM/cmake")
            .get()
            .asFile
        val shouldBootstrapEcm = isWindowsHost() && System.getenv("ECM_DIR").isNullOrBlank()
        val prepareExtraCmakeModules = if (shouldBootstrapEcm) {
            registerPrepareExtraCmakeModules(target, localEcmDir)
        } else {
            null
        }
        val localGettextBinDir = target.rootProject.layout.buildDirectory
            .dir("intermediates/gettext-tools/bin")
            .get()
            .asFile
        val localMsgfmt = localGettextBinDir.resolve("msgfmt.cmd")
        val localMsgmerge = localGettextBinDir.resolve("msgmerge.cmd")
        val shouldBootstrapGettext = isWindowsHost() && (
            System.getenv("GETTEXT_MSGFMT_EXECUTABLE").isNullOrBlank() ||
                System.getenv("GETTEXT_MSGMERGE_EXECUTABLE").isNullOrBlank()
            )
        val prepareGettextTools = if (shouldBootstrapGettext) {
            registerPrepareGettextTools(target, localGettextBinDir)
        } else {
            null
        }
        target.extensions.configure<CommonExtension> {
            ndkVersion = target.ndkVersion
            defaultConfig.apply {
                minSdk = Versions.minSdk
                @Suppress("UnstableApiUsage")
                externalNativeBuild {
                    cmake {
                        arguments(
                            "-DANDROID_STL=c++_shared",
                            "-DVERSION_NAME=${Versions.baseVersionName}",
                            "-DPREBUILT_DIR=${prebuiltDir.absolutePath}"
                        )
                        if (shouldBootstrapEcm) {
                            arguments("-DECM_DIR=${localEcmDir.absolutePath}")
                        }
                        if (shouldBootstrapGettext) {
                            arguments(
                                "-DGETTEXT_MSGFMT_EXECUTABLE=${localMsgfmt.absolutePath}",
                                "-DGETTEXT_MSGMERGE_EXECUTABLE=${localMsgmerge.absolutePath}"
                            )
                        }
                    }
                }
            }
            externalNativeBuild.apply {
                cmake {
                    version = target.cmakeVersion
                    path("src/main/cpp/CMakeLists.txt")
                }
            }
            // split apks should be disabled when building bundle
            // https://issuetracker.google.com/issues/402800800
            if (!isBuildingBundle) {
                splits.abi {
                    isEnable = true
                    isUniversalApk = false
                    reset()
                    (target.buildAbiOverride?.split(",") ?: Versions.supportedABIs).forEach {
                        include(it)
                    }
                }
            }
        }
        if (prepareExtraCmakeModules != null) {
            target.tasks.configureEach {
                if (name.startsWith("configureCMake")) {
                    dependsOn(prepareExtraCmakeModules)
                }
            }
        }
        if (prepareGettextTools != null) {
            target.tasks.configureEach {
                if (name.startsWith("configureCMake")) {
                    dependsOn(prepareGettextTools)
                }
            }
        }
        registerCleanCxxTask(target)
    }

    private fun registerPrepareExtraCmakeModules(
        project: Project,
        installDir: File
    ): TaskProvider<*> {
        val rootProject = project.rootProject
        val taskName = "prepareExtraCmakeModules"
        rootProject.tasks.findByName(taskName)?.let {
            return rootProject.tasks.named(taskName)
        }

        val workRoot = rootProject.layout.buildDirectory
            .dir("intermediates/extra-cmake-modules")
            .get()
            .asFile
        val downloadsDir = workRoot.resolve("downloads")
        val archiveFile = downloadsDir.resolve("extra-cmake-modules-v6.27.0.zip")
        val extractedRoot = workRoot.resolve("src")
        val extractedDir = extractedRoot.resolve("extra-cmake-modules-6.27.0")
        val cmakeBuildDir = workRoot.resolve("cmake-build")
        val cmakeConfigFile = installDir.resolve("ECMConfig.cmake")
        val sdkDir = androidSdkDir(rootProject)
        val cmakeExecutable = sdkDir.resolve("cmake/${project.cmakeVersion}/bin/cmake.exe")
        val ninjaExecutable = sdkDir.resolve("cmake/${project.cmakeVersion}/bin/ninja.exe")

        return rootProject.tasks.register(taskName) {
            outputs.file(cmakeConfigFile)

            doLast {
                if (cmakeConfigFile.isFile) return@doLast

                fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
                    .digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

                check(cmakeExecutable.isFile) {
                    "CMake executable not found: ${cmakeExecutable.absolutePath}"
                }
                check(ninjaExecutable.isFile) {
                    "Ninja executable not found: ${ninjaExecutable.absolutePath}"
                }

                downloadsDir.mkdirs()
                if (!archiveFile.isFile || sha256(archiveFile) != ECM_ARCHIVE_SHA256) {
                    archiveFile.parentFile.mkdirs()
                    val temporary = archiveFile.resolveSibling("${archiveFile.name}.part")
                    URL(ECM_ARCHIVE_URL).openConnection().apply {
                        connectTimeout = 30_000
                        readTimeout = 120_000
                    }.getInputStream().use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(sha256(temporary) == ECM_ARCHIVE_SHA256) {
                        "Checksum mismatch for ${archiveFile.name}"
                    }
                    temporary.copyTo(archiveFile, overwrite = true)
                    temporary.delete()
                }

                extractedRoot.deleteRecursively()
                extractedRoot.mkdirs()
                rootProject.copy {
                    from(rootProject.zipTree(archiveFile))
                    into(extractedRoot)
                }
                check(extractedDir.isDirectory) {
                    "Unexpected ECM archive layout: ${extractedDir.absolutePath}"
                }

                cmakeBuildDir.deleteRecursively()
                installDir.parentFile.parentFile.parentFile.deleteRecursively()

                rootProject.providers.exec {
                    commandLine(
                        cmakeExecutable.absolutePath,
                        "-S", extractedDir.absolutePath,
                        "-B", cmakeBuildDir.absolutePath,
                        "-G", "Ninja",
                        "-DCMAKE_MAKE_PROGRAM=${ninjaExecutable.absolutePath}",
                        "-DBUILD_DOC=OFF",
                        "-DBUILD_TESTING=OFF",
                        "-DCMAKE_INSTALL_PREFIX=${installDir.parentFile.parentFile.parentFile.absolutePath}"
                    )
                }.result.get()
                rootProject.providers.exec {
                    commandLine(
                        cmakeExecutable.absolutePath,
                        "--install", cmakeBuildDir.absolutePath
                    )
                }.result.get()
                check(cmakeConfigFile.isFile) {
                    "Failed to prepare ECM at ${cmakeConfigFile.absolutePath}"
                }
            }
        }
    }

    private fun androidSdkDir(project: Project): File {
        val localProperties = project.rootProject.file("local.properties")
        if (localProperties.isFile) {
            val properties = Properties()
            localProperties.inputStream().use(properties::load)
            properties.getProperty("sdk.dir")
                ?.takeIf { it.isNotBlank() }
                ?.let { return File(it) }
        }
        System.getenv("ANDROID_SDK_ROOT")
            ?.takeIf { it.isNotBlank() }
            ?.let { return File(it) }
        System.getenv("ANDROID_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let { return File(it) }
        error("Android SDK path not found in local.properties, ANDROID_SDK_ROOT, or ANDROID_HOME")
    }

    private fun isWindowsHost(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private fun registerPrepareGettextTools(
        project: Project,
        outputDir: File
    ): TaskProvider<*> {
        val rootProject = project.rootProject
        val taskName = "prepareGettextTools"
        rootProject.tasks.findByName(taskName)?.let {
            return rootProject.tasks.named(taskName)
        }

        val msgfmtCmd = outputDir.resolve("msgfmt.cmd")
        val msgmergeCmd = outputDir.resolve("msgmerge.cmd")
        val msgfmtPy = outputDir.resolve("msgfmt.py")
        val msgmergePy = outputDir.resolve("msgmerge.py")

        return rootProject.tasks.register(taskName) {
            outputs.files(msgfmtCmd, msgmergeCmd, msgfmtPy, msgmergePy)

            doLast {
                outputDir.mkdirs()

                msgfmtPy.writeText(
                    """
                    import ast
                    import os
                    import shutil
                    import struct
                    import sys

                    def _usage():
                        raise SystemExit("msgfmt shim usage: msgfmt [-o output] input.po")

                    def _unquote(text):
                        return ast.literal_eval(text)

                    def _parse_po(po_path):
                        messages = {}
                        msgctxt = None
                        msgid = None
                        msgid_plural = None
                        msgstr = {}
                        state = None
                        fuzzy = False

                        def finish():
                            nonlocal msgctxt, msgid, msgid_plural, msgstr, state, fuzzy
                            if msgid is None:
                                msgctxt = None
                                msgid_plural = None
                                msgstr = {}
                                state = None
                                fuzzy = False
                                return
                            if not fuzzy:
                                key = msgid
                                if msgctxt is not None:
                                    key = msgctxt + "\u0004" + key
                                if msgid_plural is not None:
                                    key = key + "\u0000" + msgid_plural
                                    translated = "\u0000".join(
                                        msgstr[index] for index in sorted(msgstr.keys())
                                    )
                                else:
                                    translated = msgstr.get(0, "")
                                messages[key] = translated
                            msgctxt = None
                            msgid = None
                            msgid_plural = None
                            msgstr = {}
                            state = None
                            fuzzy = False

                        with open(po_path, "r", encoding="utf-8") as handle:
                            for raw_line in handle:
                                line = raw_line.strip()
                                if not line:
                                    finish()
                                    continue
                                if line.startswith("#,") and "fuzzy" in line:
                                    fuzzy = True
                                    continue
                                if line.startswith("#"):
                                    continue
                                if line.startswith("msgctxt "):
                                    msgctxt = _unquote(line[8:].strip())
                                    state = ("msgctxt", None)
                                    continue
                                if line.startswith("msgid_plural "):
                                    msgid_plural = _unquote(line[13:].strip())
                                    state = ("msgid_plural", None)
                                    continue
                                if line.startswith("msgid "):
                                    if msgid is not None:
                                        finish()
                                    msgid = _unquote(line[6:].strip())
                                    state = ("msgid", None)
                                    continue
                                if line.startswith("msgstr["):
                                    index = int(line[7:line.index("]")])
                                    msgstr[index] = _unquote(line[line.index("]") + 1:].strip())
                                    state = ("msgstr", index)
                                    continue
                                if line.startswith("msgstr "):
                                    msgstr[0] = _unquote(line[7:].strip())
                                    state = ("msgstr", 0)
                                    continue
                                if line.startswith("\""):
                                    text = _unquote(line)
                                    if state is None:
                                        continue
                                    kind, index = state
                                    if kind == "msgctxt":
                                        msgctxt = (msgctxt or "") + text
                                    elif kind == "msgid":
                                        msgid = (msgid or "") + text
                                    elif kind == "msgid_plural":
                                        msgid_plural = (msgid_plural or "") + text
                                    elif kind == "msgstr":
                                        msgstr[index] = msgstr.get(index, "") + text
                            finish()
                        return messages

                    def _write_mo(messages, output_path):
                        keys = sorted(messages.keys())
                        ids = [key.encode("utf-8") for key in keys]
                        values = [messages[key].encode("utf-8") for key in keys]
                        n = len(keys)
                        keystart = 7 * 4 + n * 8 * 2
                        valuestart = keystart + sum(len(item) + 1 for item in ids)
                        offsets = []
                        current = keystart
                        for item in ids:
                            offsets.append((len(item), current))
                            current += len(item) + 1
                        value_offsets = []
                        current = valuestart
                        for item in values:
                            value_offsets.append((len(item), current))
                            current += len(item) + 1
                        output = bytearray()
                        output.extend(struct.pack("<7I", 0x950412de, 0, n, 28, 28 + n * 8, 0, 0))
                        for length, offset in offsets:
                            output.extend(struct.pack("<2I", length, offset))
                        for length, offset in value_offsets:
                            output.extend(struct.pack("<2I", length, offset))
                        for item in ids:
                            output.extend(item + b"\0")
                        for item in values:
                            output.extend(item + b"\0")
                        os.makedirs(os.path.dirname(output_path), exist_ok=True)
                        with open(output_path, "wb") as handle:
                            handle.write(output)

                    def _copy_template(arguments):
                        destination = None
                        template = None
                        index = 0
                        while index < len(arguments):
                            current = arguments[index]
                            if current == "-o" and index + 1 < len(arguments):
                                destination = arguments[index + 1]
                                index += 2
                            elif current == "--template" and index + 1 < len(arguments):
                                template = arguments[index + 1]
                                index += 2
                            else:
                                index += 1
                        if not template or not destination:
                            _usage()
                        os.makedirs(os.path.dirname(destination), exist_ok=True)
                        shutil.copyfile(template, destination)
                        return 0

                    def main(argv):
                        if "--version" in argv:
                            print("msgfmt shim 1.0")
                            return 0
                        if "--desktop" in argv or "--xml" in argv:
                            return _copy_template(argv[1:])

                        output = None
                        positional = []
                        index = 1
                        while index < len(argv):
                            current = argv[index]
                            if current in {"--no-hash", "--endianness=little"}:
                                index += 1
                            elif current == "-o" and index + 1 < len(argv):
                                output = argv[index + 1]
                                index += 2
                            elif current.startswith("-"):
                                index += 1
                            else:
                                positional.append(current)
                                index += 1
                        if not positional:
                            _usage()
                        po_file = positional[-1]
                        output = output or os.path.splitext(po_file)[0] + ".mo"
                        messages = _parse_po(po_file)
                        _write_mo(messages, output)
                        return 0

                    if __name__ == "__main__":
                        raise SystemExit(main(sys.argv))
                    """.trimIndent()
                )
                msgmergePy.writeText(
                    """
                    import sys

                    if "--version" in sys.argv:
                        print("msgmerge shim 1.0")
                    raise SystemExit(0)
                    """.trimIndent()
                )
                msgfmtCmd.writeText(
                    """
                    @echo off
                    python "%~dp0msgfmt.py" %*
                    """.trimIndent()
                )
                msgmergeCmd.writeText(
                    """
                    @echo off
                    python "%~dp0msgmerge.py" %*
                    """.trimIndent()
                )
            }
        }
    }

    private fun registerCleanCxxTask(project: Project) {
        project.tasks.register<Delete>("cleanCxxIntermediates") {
            delete(project.file(".cxx"))
        }.also {
            project.cleanTask.dependsOn(it)
        }
    }

    private companion object {
        const val ECM_ARCHIVE_URL =
            "https://github.com/KDE/extra-cmake-modules/archive/refs/tags/v6.27.0.zip"
        const val ECM_ARCHIVE_SHA256 =
            "d03b9ca9a3564feba363781ff05f252b8e18898d669e68d1eadd0e751998a883"
    }
}
