package com.mrpowergamerbr.butterscotchpreprocessor

import androidx.compose.runtime.*
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.renderComposable
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.files.FileReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun main() {
    renderComposable("root") {
        App()
    }
}

@Composable
fun App() {
    var status by remember { mutableStateOf("Ready. Upload a data.win file to begin.") }
    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var downloadUrl by remember { mutableStateOf<String?>(null) }
    var isoFileName by remember { mutableStateOf("output.iso") }
    var processing by remember { mutableStateOf(false) }
    var loadedFileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var parsedGameName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Div({ style { padding(24.px); fontFamily("monospace"); maxWidth(800.px); margin(0.px, "auto".unsafeCast<CSSNumeric>()) } }) {
        H1 { Text("Butterscotch Preprocessor") }
        P { Text("Convert GameMaker data.win to PS2 texture atlases") }

        // File upload
        if (!processing) {
            Input(type = org.jetbrains.compose.web.attributes.InputType.File) {
                attr("accept", ".win")
                onChange { event ->
                    val input: dynamic = event.target
                    val files: dynamic = input.files
                    if (files == null || files.length == 0) return@onChange
                    val file: dynamic = files[0]

                    downloadUrl = null
                    logMessages = listOf()
                    parsedGameName = null
                    status = "Reading file..."

                    scope.launch {
                        try {
                            val bytes = readFileAsBytes(file)
                            loadedFileBytes = bytes

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
                                parseStrg = true, // needed for string pointers
                                parseTxtr = false,
                                parseAudo = false,
                            ))

                            val gameName = dw.gen8.displayName ?: dw.gen8.name ?: "Unknown"
                            parsedGameName = gameName
                            status = "Game: $gameName - Ready to convert."
                        } catch (e: Exception) {
                            status = "Error reading file: ${e.message}"
                            loadedFileBytes = null
                        }
                    }
                }
            }
        }

        // Convert button
        if (parsedGameName != null && !processing && downloadUrl == null) {
            Br()
            Button({
                style {
                    padding(12.px, 24.px)
                    backgroundColor(Color("#2196F3"))
                    color(Color.white)
                    property("border", "none")
                    property("border-radius", "4px")
                    fontSize(16.px)
                    property("cursor", "pointer")
                }
                onClick {
                    val bytes = loadedFileBytes ?: return@onClick
                    processing = true
                    logMessages = listOf()

                    scope.launch {
                        try {
                            status = "Processing..."

                            val result = processDataWin(bytes) { msg ->
                                println(msg)
                                logMessages = logMessages + msg
                            }

                            status = "Creating ISO..."

                            val gameName = result.gameName

                            // Fetch the butterscotch ELF from resources
                            val elfBytes = fetchResourceBytes("butterscotch.elf")

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
                                Iso9660Creator.IsoFile("DATA.WIN", bytes),
                                Iso9660Creator.IsoFile("CLUT4.BIN", result.clut4Bin),
                                Iso9660Creator.IsoFile("CLUT8.BIN", result.clut8Bin),
                                Iso9660Creator.IsoFile("TEXTURES.BIN", result.texturesBin),
                                Iso9660Creator.IsoFile("ATLAS.BIN", result.atlasBin),
                            ))

                            isoFileName = "${gameName}.iso"
                            val isoUint8Array = toUint8Array(isoBytes)
                            val blob = js("new Blob([isoUint8Array], {type: 'application/octet-stream'})")
                            val url = js("URL.createObjectURL(blob)") as String
                            downloadUrl = url

                            status = "Done! Click the download button below."
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"
                            logMessages = logMessages + "Error: ${e.stackTraceToString()}"
                        } finally {
                            processing = false
                        }
                    }
                }
            }) {
                Text("Convert")
            }
        }

        // Status
        P({ style { fontWeight("bold") } }) { Text(status) }

        // Progress log
        if (logMessages.isNotEmpty()) {
            Div({
                style {
                    backgroundColor(Color("#1a1a1a"))
                    color(Color("#00ff00"))
                    padding(12.px)
                    overflow("auto")
                    property("max-height", "400px")
                    fontSize(12.px)
                    property("white-space", "pre-wrap")
                    property("border-radius", "4px")
                }
            }) {
                for (msg in logMessages) {
                    Div { Text(msg) }
                }
            }
        }

        // Download button
        downloadUrl?.let { url ->
            Br()
            A(href = url, attrs = {
                attr("download", isoFileName)
                style {
                    display(DisplayStyle.InlineBlock)
                    padding(12.px, 24.px)
                    backgroundColor(Color("#4CAF50"))
                    color(Color.white)
                    property("text-decoration", "none")
                    property("border-radius", "4px")
                    fontSize(16.px)
                    property("cursor", "pointer")
                }
            }) {
                Text("Download ISO")
            }
        }

        // Processing indicator
        if (processing) {
            P { Text("Processing... please wait.") }
        }
    }
}

private fun toUint8Array(bytes: ByteArray): dynamic {
    val jsArray = js("new Uint8Array(bytes.length)")
    for (i in bytes.indices) {
        jsArray[i] = bytes[i]
    }
    return jsArray
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
