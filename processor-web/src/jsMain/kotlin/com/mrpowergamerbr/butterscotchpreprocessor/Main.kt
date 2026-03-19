package com.mrpowergamerbr.butterscotchpreprocessor

import androidx.compose.runtime.*
import js.date.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.FileReader
import web.workers.Worker
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private external fun plausible(eventName: String)

// Detect if we are running inside a Web Worker (no document available)
private val IS_WORKER = js("typeof document === 'undefined'") as Boolean

fun main() {
    if (IS_WORKER) {
        workerMain()
    } else {
        renderComposable("root") {
            App()
        }
    }
}

// ======== Worker entry point ========

private fun workerMain() {
    val self: dynamic = js("self")
    val scope = CoroutineScope(Dispatchers.Default)

    self.onmessage = { event: dynamic ->
        val msg: dynamic = event.data
        val type = msg.type as String

        when (type) {
            "process" -> {
                // Receive the data.win ArrayBuffer
                val arrayBuffer: dynamic = msg.data
                val bytes = js("new Int8Array(arrayBuffer)").unsafeCast<ByteArray>()

                // Receive external audio files
                val externalAudioObj: dynamic = msg.externalAudio
                val externalAudioFiles = HashMap<String, ByteArray>()
                val keys = js("Object.keys(externalAudioObj)").unsafeCast<Array<String>>()
                for (key in keys) {
                    val audioData = externalAudioObj[key]
                    externalAudioFiles[key] = js("new Int8Array(audioData)").unsafeCast<ByteArray>()
                }

                scope.launch {
                    try {
                        val result = processDataWin(bytes, externalAudioFiles) { progressMsg ->
                            self.postMessage(jsObject("type" to "progress", "message" to progressMsg))
                        }

                        // Send result back with all the byte arrays
                        val resultMsg: dynamic = jsObject()
                        resultMsg.type = "result"
                        resultMsg.gameName = result.gameName
                        resultMsg.clut4 = result.clut4Bin
                        resultMsg.clut8 = result.clut8Bin
                        resultMsg.textures = result.texturesBin
                        resultMsg.atlas = result.atlasBin
                        resultMsg.soundBnk = result.soundBnkBin
                        resultMsg.sounds = result.soundsBin
                        self.postMessage(resultMsg)
                    } catch (e: Exception) {
                        self.postMessage(jsObject("type" to "error", "message" to (e.message ?: "Unknown error")))
                    }
                }
            }
        }
    }
}

private fun jsObject(vararg pairs: Pair<String, Any?>): dynamic {
    val obj: dynamic = js("{}")
    for ((k, v) in pairs) {
        obj[k] = v
    }
    return obj
}

// ======== Main thread UI ========

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
                                    Iso9660Creator.IsoFile("CONFIG.JSN", """
                                        {
                                            "fileSystem": {
                                                "file0": ["mc0:UNDERTALE/file0"],
                                                "file9": ["mc0:UNDERTALE/file9"],
                                                "undertale.ini": ["mc0:UNDERTALE/undertale.ini"],
                                                "credits.txt": ["${'$'}BOOT:CREDITS.TXT"]
                                            },
                                            "saveIcon": {
                                                "bgAlpha": 68,
                                                "bgColors": [[255, 204, 0], [255, 204, 0], [180, 140, 0], [180, 140, 0]],
                                                "lightDirs": [[0.5, 0.5, 0.5], [0.0, -0.4, -0.1], [-0.5, -0.5, 0.5]],
                                                "lightColors": [[0.7, 0.7, 0.7], [0.5, 0.5, 0.5], [0.3, 0.3, 0.3]],
                                                "ambient": [1.0, 0.8, 0.0]
                                            },
                                            "disabledObjects": ["obj_snowfloor"]
                                        }
                                    """.trimIndent().encodeToByteArray()),
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
                    worker.asDynamic().postMessage(jsObject("type" to "process", "data" to bytes, "externalAudio" to audioObj))
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
