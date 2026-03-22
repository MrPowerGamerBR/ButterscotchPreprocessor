package com.mrpowergamerbr.butterscotchpreprocessor.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mrpowergamerbr.butterscotchpreprocessor.DataWin
import com.mrpowergamerbr.butterscotchpreprocessor.DataWinParserOptions
import com.mrpowergamerbr.butterscotchpreprocessor.Iso9660Creator
import com.mrpowergamerbr.butterscotchpreprocessor.plausible
import js.date.Date
import js.objects.unsafeJso
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.FileReader
import web.workers.Worker
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun App() {
    var status by remember { mutableStateOf("Select the game's folder to begin!") }
    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var downloadUrl by remember { mutableStateOf<String?>(null) }
    var isoFileName by remember { mutableStateOf("output.iso") }
    var processing by remember { mutableStateOf(false) }
    var loadedFileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var loadedExternalAudio by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    var parsedGameName by remember { mutableStateOf<String?>(null) }
    var deferDrawToAfterAllSteps by remember { mutableStateOf(true) }
    // TODO: We need to support multiple "source folders"! (Butterscotch supports it, but we don't)
    val filesystemMappings = remember {
        mutableStateMapOf(
            "file0" to "mc0:UNDERTALE/file0",
            "file9" to "mc0:UNDERTALE/file9",
            "undertale.ini" to "mc0:UNDERTALE/undertale.ini",
            "credits.txt" to "\$BOOT:CREDITS.TXT"
        )
    }
    val scope = rememberCoroutineScope()

    // Create the worker once, loading the same script in a worker context
    val worker = remember {
        Worker("/assets/js/processor-web.js").also { w ->
            w.asDynamic().onmessage = { event: dynamic ->
                val msg: dynamic = event.data
                when (msg.type as String) {
                    "progress" -> {
                        val progressMsg = msg.message as String
                        println(progressMsg)
                        logMessages = logMessages + progressMsg
                    }
                    "result" -> {
                        scope.launch {
                            try {
                                status = "Creating ISO..."

                                val gameName = msg.gameName as String
                                val clut4Bin = jsInt8ArrayToByteArray(msg.clut4)
                                val clut8Bin = jsInt8ArrayToByteArray(msg.clut8)
                                val texturesBin = jsInt8ArrayToByteArray(msg.textures)
                                val atlasBin = jsInt8ArrayToByteArray(msg.atlas)
                                val soundBnkBin = jsInt8ArrayToByteArray(msg.soundBnk)
                                val soundsBin = jsInt8ArrayToByteArray(msg.sounds)

                                val dataWinBytes = loadedFileBytes!!

                                // Fetch the butterscotch ELF from resources
                                val elfBytes = fetchResourceBytes("/web/butterscotch.elf?v=${Date.now()}")

                                // Fetch icon from resources
                                val iconBytes = fetchResourceBytes("/web/ICON.ICO?v=${Date.now()}")

                                // Create SYSTEM.CNF content
                                val systemCnf = "BOOT2 = cdrom0:\\BUTR_000.00;1\nVER = 1.00\nVMODE = NTSC\n"

                                // Package into ISO 9660
                                val iso = Iso9660Creator(
                                    volumeId = gameName.uppercase().take(32),
                                    systemId = "PLAYSTATION"
                                )
                                val isoBytes = iso.create(listOf(
                                    Iso9660Creator.IsoFile("SYSTEM.CNF", systemCnf.encodeToByteArray()),
                                    Iso9660Creator.IsoFile("BUTR_000.00", elfBytes),
                                    Iso9660Creator.IsoFile("DATA.WIN", dataWinBytes),
                                    Iso9660Creator.IsoFile("CLUT4.BIN", clut4Bin),
                                    Iso9660Creator.IsoFile("CLUT8.BIN", clut8Bin),
                                    Iso9660Creator.IsoFile("TEXTURES.BIN", texturesBin),
                                    Iso9660Creator.IsoFile("ATLAS.BIN", atlasBin),
                                    Iso9660Creator.IsoFile("SOUNDBNK.BIN", soundBnkBin),
                                    Iso9660Creator.IsoFile("SOUNDS.BIN", soundsBin),
                                    Iso9660Creator.IsoFile("CONFIG.JSN", buildJsonObject {
                                        put("deferDrawToAfterAllSteps", deferDrawToAfterAllSteps)
                                        putJsonObject("fileSystem") {
                                            for (mapping in filesystemMappings) {
                                                putJsonArray(mapping.key) { add(mapping.value) }
                                            }
                                        }
                                        putJsonObject("saveIcon") {
                                            put("bgAlpha", 68)
                                            putJsonArray("bgColors") {
                                                addJsonArray { add(255); add(204); add(0) }
                                                addJsonArray { add(255); add(204); add(0) }
                                                addJsonArray { add(180); add(140); add(0) }
                                                addJsonArray { add(180); add(140); add(0) }
                                            }
                                            putJsonArray("lightDirs") {
                                                addJsonArray { add(0.5); add(0.5); add(0.5) }
                                                addJsonArray { add(0.0); add(-0.4); add(-0.1) }
                                                addJsonArray { add(-0.5); add(-0.5); add(0.5) }
                                            }
                                            putJsonArray("lightColors") {
                                                addJsonArray { add(0.7); add(0.7); add(0.7) }
                                                addJsonArray { add(0.5); add(0.5); add(0.5) }
                                                addJsonArray { add(0.3); add(0.3); add(0.3) }
                                            }
                                            putJsonArray("ambient") { add(1.0); add(0.8); add(0.0) }
                                        }
                                        putJsonArray("disabledObjects") {
                                            add("obj_snowfloor")
                                            add("obj_glowparticle")
                                            add("obj_true_lavawaver")
                                            add("obj_true_antiwaver")
                                            add("obj_orangeparticle")
                                        }
                                    }.toString().encodeToByteArray()),
                                    Iso9660Creator.IsoFile("ICON.ICO", iconBytes)
                                ))

                                isoFileName = "${gameName}.iso"
                                val blob = Blob(arrayOf(isoBytes), BlobPropertyBag(type = "application/octet-stream"))
                                downloadUrl = URL.createObjectURL(blob)
                                status = "Done!"
                                plausible("Generated PS2 ISO")
                            } catch (e: Exception) {
                                status = "Error creating ISO: ${e.message}"
                                logMessages = logMessages + "Error: ${e.stackTraceToString()}"
                            } finally {
                                processing = false
                            }
                        }
                    }
                    "error" -> {
                        status = "Error: ${msg.message}"
                        processing = false
                    }
                }
            }
        }
    }

    // The header, description, and ad are rendered server-side
    // File upload
    H2 { Text("Converter") }

    if (!processing) {
        Input(type = org.jetbrains.compose.web.attributes.InputType.File) {
            attr("webkitdirectory", "")
            onChange { event ->
                val input: dynamic = event.target
                val files: dynamic = input.files
                if (files == null || files.length == 0) return@onChange

                downloadUrl = null
                logMessages = listOf()
                parsedGameName = null
                loadedExternalAudio = emptyMap()
                status = "Reading folder..."

                scope.launch {
                    try {
                        // Find the data.win file and collect audio files
                        var dataWinFile: dynamic = null
                        val audioFiles = HashMap<String, dynamic>()
                        val fileCount = files.length as Int

                        for (i in 0 until fileCount) {
                            val file: dynamic = files[i]
                            val name = (file.name as String).lowercase()
                            if (name.endsWith(".win") || name.endsWith(".unx") || name.endsWith(".osx")) {
                                dataWinFile = file
                            } else if (name.endsWith(".ogg") || name.endsWith(".wav")) {
                                audioFiles[file.name as String] = file
                            }
                        }

                        if (dataWinFile == null) {
                            status = "No data.win file found in the selected folder!"
                            return@launch
                        }

                        status = "Reading data.win..."
                        val bytes = readFileAsBytes(dataWinFile)
                        loadedFileBytes = bytes

                        // Load external audio files
                        if (audioFiles.isNotEmpty()) {
                            status = "Reading ${audioFiles.size} audio files..."
                            val externalAudio = HashMap<String, ByteArray>()
                            for ((fileName, file) in audioFiles) {
                                externalAudio[fileName] = readFileAsBytes(file)
                            }
                            loadedExternalAudio = externalAudio
                        }

                        // Quick parse to get game name
                        val dw = DataWin.parse(bytes, DataWinParserOptions(
                            parseGen8 = true,
                            parseOptn = false,
                            parseLang = false,
                            parseExtn = false,
                            parseSond = false,
                            parseAgrp = false,
                            parseSprt = false,
                            parseBgnd = false,
                            parsePath = false,
                            parseScpt = false,
                            parseGlob = false,
                            parseShdr = false,
                            parseFont = false,
                            parseTmln = false,
                            parseObjt = false,
                            parseRoom = false,
                            parseTpag = false,
                            parseCode = false,
                            parseVari = false,
                            parseFunc = false,
                            parseStrg = true,
                            parseTxtr = false,
                            parseAudo = false,
                        ))

                        val gameName = dw.gen8.displayName ?: dw.gen8.name ?: "Unknown"
                        parsedGameName = gameName
                        val audioMsg = if (audioFiles.isNotEmpty()) " (${audioFiles.size} audio files)" else ""
                        status = "Game: $gameName$audioMsg"
                    } catch (e: Exception) {
                        status = "Error reading folder: ${e.message}"
                        loadedFileBytes = null
                        loadedExternalAudio = emptyMap()
                    }
                }
            }
        }
    }

    // Status
    P({ classes("status-text") }) { Text(status) }

    // Convert button
    if (parsedGameName != null && !processing && downloadUrl == null) {
        P {
            Text("The default settings are tailored for Undertale, you don't need to change them unless if you want to customize the output for a specific game or mod!")
        }

        FieldWrappers {
            DiscordToggle(
                "defer-draw-to-after-all-steps",
                "Defer Draw to After All Steps",
                "When enabled, Butterscotch will defer GameMaker draw events after all steps events have caught up. This improves performance when the game is lagging, but can cause glitches if the game depends on draw logic.",
                deferDrawToAfterAllSteps
            ) {
                deferDrawToAfterAllSteps = !deferDrawToAfterAllSteps
            }

            FieldWrapper {
                FieldInformation {
                    FieldLabel("Filesystem Mappings")
                }

                Table(attrs = {
                    classes("fancy-table")
                }) {
                    Thead {
                        Tr {
                            Th { Text("Source File") }
                            Th { Text("Target Path") }
                            Th {}
                        }
                    }
                    Tbody {
                        for (mapping in filesystemMappings.entries.sortedBy { it.key }) {
                            Tr {
                                Td { Text(mapping.key) }
                                Td { Text(mapping.value) }
                                Td(attrs = {
                                    classes("action-cell")
                                }) {
                                    DiscordButton(
                                        DiscordButtonType.NO_BACKGROUND_THEME_DEPENDENT_DARK_TEXT,
                                        attrs = {
                                            onClick {
                                                filesystemMappings.remove(mapping.key)
                                            }
                                        }
                                    ) {
                                        Text("Delete")
                                    }
                                }
                            }
                        }
                    }
                }

                var sourceFile by remember { mutableStateOf<String>("") }
                var targetFile by remember { mutableStateOf<String>("") }

                Div(attrs = {
                    classes("add-mapping-form")
                }) {
                    TextInput(sourceFile) {
                        attr("placeholder", "Source File")
                        onInput {
                            sourceFile = it.value
                        }
                    }

                    TextInput(targetFile) {
                        attr("placeholder", "Target Path")
                        onInput {
                            targetFile = it.value
                        }
                    }

                    DiscordButton(
                        DiscordButtonType.PRIMARY,
                        {
                            onClick {
                                if (sourceFile.isNotBlank() && targetFile.isNotBlank()) {
                                    filesystemMappings[sourceFile] = targetFile
                                    sourceFile = ""
                                    targetFile = ""
                                }
                            }
                        }
                    ) {
                        Text("Add")
                    }
                }
            }
        }

        Div({ classes("buttons-wrapper") }) {
            Button({
                classes("discord-button", "primary")
                onClick {
                    val bytes = loadedFileBytes ?: return@onClick
                    processing = true
                    logMessages = listOf()
                    status = "Processing..."

                    // Build external audio map as a JS object for the worker
                    val externalAudio = loadedExternalAudio
                    val audioObj: dynamic = js("{}")
                    for ((name, data) in externalAudio) {
                        audioObj[name] = data
                    }

                    // Send data to worker (no transfer - we need the bytes later for the ISO)
                    worker.asDynamic().postMessage(
                        unsafeJso {
                            this.type = "process"
                            this.data = bytes
                            this.externalAudio = audioObj
                        }
                    )
                }
            }) {
                Text("Generate PlayStation 2 ISO")
            }
        }
    }

    // Progress log
    if (logMessages.isNotEmpty()) {
        Div({ classes("progress-log") }) {
            for (msg in logMessages) {
                Div({ classes("log-entry") }) { Text(msg) }
            }
        }
    }

    // Download button
    downloadUrl?.let { url ->
        Div({ classes("buttons-wrapper") }) {
            A(href = url, attrs = {
                attr("download", isoFileName)
                classes("discord-button", "success")
            }) {
                Text("Download ISO")
            }
        }
    }
}

// Convert a JS Int8Array (received via postMessage) back to a Kotlin ByteArray
private fun jsInt8ArrayToByteArray(int8Array: dynamic): ByteArray {
    val length = int8Array.length as Int
    val bytes = ByteArray(length)
    for (i in 0 until length) {
        bytes[i] = (int8Array[i] as Int).toByte()
    }
    return bytes
}

private suspend fun readFileAsBytes(file: dynamic): ByteArray {
    return suspendCancellableCoroutine { cont ->
        val reader = FileReader()
        reader.onload = {
            val arrayBuffer = reader.result
            val uint8Array = js("new Uint8Array(arrayBuffer)")
            val length = uint8Array.length as Int
            val bytes = ByteArray(length)
            for (i in 0 until length) {
                bytes[i] = (uint8Array[i] as Int).toByte()
            }
            cont.resume(bytes)
        }
        reader.onerror = {
            cont.resumeWithException(RuntimeException("Failed to read file"))
        }
        reader.readAsArrayBuffer(file)
    }
}

private suspend fun fetchResourceBytes(path: String): ByteArray {
    return suspendCancellableCoroutine { cont ->
        val xhr = js("new XMLHttpRequest()")
        xhr.open("GET", path, true)
        xhr.responseType = "arraybuffer"
        xhr.onload = {
            val status = xhr.status as Int
            if (200 > status || status >= 300) {
                cont.resumeWithException(RuntimeException("Failed to fetch $path: HTTP $status"))
            } else {
                val arrayBuffer = xhr.response
                val uint8Array = js("new Uint8Array(arrayBuffer)")
                val length = uint8Array.length as Int
                val bytes = ByteArray(length)
                for (i in 0 until length) {
                    bytes[i] = (uint8Array[i] as Int).toByte()
                }
                cont.resume(bytes)
            }
        }
        xhr.onerror = {
            cont.resumeWithException(RuntimeException("Failed to fetch $path"))
        }
        xhr.send()
    }
}