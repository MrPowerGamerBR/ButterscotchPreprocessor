package com.mrpowergamerbr.butterscotchpreprocessor.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mrpowergamerbr.butterscotchpreprocessor.ButterscotchPreprocessorWeb
import com.mrpowergamerbr.butterscotchpreprocessor.DataWin
import com.mrpowergamerbr.butterscotchpreprocessor.DataWinParserOptions
import com.mrpowergamerbr.butterscotchpreprocessor.GMLKey
import com.mrpowergamerbr.butterscotchpreprocessor.Iso9660Creator
import com.mrpowergamerbr.butterscotchpreprocessor.PS2PadKey
import com.mrpowergamerbr.butterscotchpreprocessor.components.colorpicker.Color
import com.mrpowergamerbr.butterscotchpreprocessor.components.colorpicker.ColorPicker
import com.mrpowergamerbr.butterscotchpreprocessor.plausible
import com.mrpowergamerbr.butterscotchpreprocessor.utils.SVGIconManager
import js.buffer.ArrayBuffer
import js.buffer.ArrayBufferLike
import js.core.JsUByte
import js.date.Date
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr
import web.blob.Blob
import web.blob.BlobPropertyBag
import web.dom.ElementId
import web.dom.document
import web.events.EventHandler
import web.file.FileReader
import web.http.arrayBuffer
import web.http.fetch
import web.url.URL
import web.workers.Worker
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun App(m: ButterscotchPreprocessorWeb) {
    var status by remember { mutableStateOf("Select the game's folder to begin!") }
    var logMessages = remember { mutableStateListOf<String>() }
    var downloadUrl by remember { mutableStateOf<String?>(null) }
    var isoFileName by remember { mutableStateOf("output.iso") }
    var processing by remember { mutableStateOf(false) }
    var loadedFileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var loadedExternalAudio by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    var loadedAudioGroupFiles by remember { mutableStateOf<Map<Int, ByteArray>>(emptyMap()) }
    var loadedSourceFiles by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    var parsedGameName by remember { mutableStateOf<String?>(null) }
    var deferDrawToAfterAllSteps by remember { mutableStateOf(true) }
    val controllerMappings = remember {
        mutableStateMapOf(
            PS2PadKey.PAD_UP to GMLKey.VK_UP,
            PS2PadKey.PAD_DOWN to GMLKey.VK_DOWN,
            PS2PadKey.PAD_LEFT to GMLKey.VK_LEFT,
            PS2PadKey.PAD_RIGHT to GMLKey.VK_RIGHT,
            PS2PadKey.PAD_CROSS to GMLKey.KEY_Z,
            PS2PadKey.PAD_SQUARE to GMLKey.KEY_X,
            PS2PadKey.PAD_START to GMLKey.KEY_C,
            PS2PadKey.PAD_TRIANGLE to GMLKey.VK_ESCAPE,
            PS2PadKey.PAD_L1 to GMLKey.VK_PAGEDOWN,
            PS2PadKey.PAD_R1 to GMLKey.VK_PAGEUP,
            PS2PadKey.PAD_L2 to GMLKey.VK_F10,
            PS2PadKey.PAD_SELECT to GMLKey.VK_F12,
        )
    }
    // TODO: We need to support multiple "source folders"! (Butterscotch supports it, but we don't)
    val filesystemMappings = remember {
        mutableStateMapOf(
            "file0" to "mc0:UNDERTALE/file0",
            "file9" to "mc0:UNDERTALE/file9",
            "undertale.ini" to "mc0:UNDERTALE/undertale.ini",
            "credits.txt" to "\$BOOT:CREDITS.TXT"
        )
    }
    var customIconBytes by remember { mutableStateOf<ByteArray?>(null) }
    var customElfBytes by remember { mutableStateOf<ByteArray?>(null) }
    // Memory Card Save Icon settings
    var bgAlpha by remember { mutableStateOf(68) }
    var bgColorTopLeft by remember { mutableStateOf(Color(255, 204, 0)) }
    var bgColorTopRight by remember { mutableStateOf(Color(255, 204, 0)) }
    var bgColorBottomLeft by remember { mutableStateOf(Color(180, 140, 0)) }
    var bgColorBottomRight by remember { mutableStateOf(Color(180, 140, 0)) }
    var lightColor1 by remember { mutableStateOf(Color(179, 179, 179)) }
    var lightDir1X by remember { mutableStateOf("0.5") }
    var lightDir1Y by remember { mutableStateOf("0.5") }
    var lightDir1Z by remember { mutableStateOf("0.5") }
    var lightColor2 by remember { mutableStateOf(Color(128, 128, 128)) }
    var lightDir2X by remember { mutableStateOf("0.0") }
    var lightDir2Y by remember { mutableStateOf("-0.4") }
    var lightDir2Z by remember { mutableStateOf("-0.1") }
    var lightColor3 by remember { mutableStateOf(Color(77, 77, 77)) }
    var lightDir3X by remember { mutableStateOf("-0.5") }
    var lightDir3Y by remember { mutableStateOf("-0.5") }
    var lightDir3Z by remember { mutableStateOf("0.5") }
    var ambientColor by remember { mutableStateOf(Color(255, 204, 0)) }
    val disabledObjects = remember {
        mutableStateListOf(
            "obj_snowfloor",
            "obj_glowparticle",
            "obj_true_lavawaver",
            "obj_true_antiwaver",
            "obj_orangeparticle"
        )
    }
    var debugOverlayEnabled by remember { mutableStateOf(true) }

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
                        logMessages.add(progressMsg)
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

                                // Use custom ELF if provided, otherwise fetch default from resources
                                val elfBytes = customElfBytes ?: fetchResourceBytes("/web/butterscotch.elf?v=${Date.now()}")

                                // Use custom icon if provided, otherwise fetch default from resources
                                val iconBytes = customIconBytes ?: fetchResourceBytes("/web/ICON.ICO?v=${Date.now()}")

                                // Create SYSTEM.CNF content
                                val systemCnf = "BOOT2 = cdrom0:\\BUTR_000.00;1\nVER = 1.00\nVMODE = NTSC\n"

                                // Package into ISO 9660
                                val iso = Iso9660Creator(
                                    volumeId = gameName.uppercase().take(32),
                                    systemId = "PLAYSTATION"
                                )
                                // Collect files mapped to $BOOT that should be included in the ISO
                                val bootFiles = filesystemMappings
                                    .filter { it.value.startsWith("\$BOOT:") }
                                    .mapNotNull { (sourceFile, targetPath) ->
                                        val isoFileName = targetPath.removePrefix("\$BOOT:")
                                        val fileBytes = loadedSourceFiles[sourceFile]
                                        if (fileBytes != null) {
                                            Iso9660Creator.IsoFile(isoFileName, fileBytes)
                                        } else {
                                            logMessages.add("Warning: Source file \"$sourceFile\" not found in folder, skipping \$BOOT mapping")
                                            null
                                        }
                                    }

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
                                        put("debugOverlayEnabled", debugOverlayEnabled)
                                        putJsonObject("fileSystem") {
                                            for (mapping in filesystemMappings) {
                                                putJsonArray(mapping.key) { add(mapping.value) }
                                            }
                                        }
                                        putJsonObject("controllerMappings") {
                                            for ((key, value) in controllerMappings) {
                                                put(key.value.toString(), value.value)
                                            }
                                        }

                                        putJsonObject("saveIcon") {
                                            put("bgAlpha", bgAlpha)
                                            putJsonArray("bgColors") {
                                                addJsonArray { add(bgColorTopLeft.red); add(bgColorTopLeft.green); add(bgColorTopLeft.blue) }
                                                addJsonArray { add(bgColorTopRight.red); add(bgColorTopRight.green); add(bgColorTopRight.blue) }
                                                addJsonArray { add(bgColorBottomLeft.red); add(bgColorBottomLeft.green); add(bgColorBottomLeft.blue) }
                                                addJsonArray { add(bgColorBottomRight.red); add(bgColorBottomRight.green); add(bgColorBottomRight.blue) }
                                            }
                                            putJsonArray("lightDirs") {
                                                addJsonArray { add(lightDir1X.toDoubleOrNull() ?: 0.0); add(lightDir1Y.toDoubleOrNull() ?: 0.0); add(lightDir1Z.toDoubleOrNull() ?: 0.0) }
                                                addJsonArray { add(lightDir2X.toDoubleOrNull() ?: 0.0); add(lightDir2Y.toDoubleOrNull() ?: 0.0); add(lightDir2Z.toDoubleOrNull() ?: 0.0) }
                                                addJsonArray { add(lightDir3X.toDoubleOrNull() ?: 0.0); add(lightDir3Y.toDoubleOrNull() ?: 0.0); add(lightDir3Z.toDoubleOrNull() ?: 0.0) }
                                            }
                                            putJsonArray("lightColors") {
                                                addJsonArray { add(lightColor1.red / 255.0); add(lightColor1.green / 255.0); add(lightColor1.blue / 255.0) }
                                                addJsonArray { add(lightColor2.red / 255.0); add(lightColor2.green / 255.0); add(lightColor2.blue / 255.0) }
                                                addJsonArray { add(lightColor3.red / 255.0); add(lightColor3.green / 255.0); add(lightColor3.blue / 255.0) }
                                            }
                                            putJsonArray("ambient") { add(ambientColor.red / 255.0); add(ambientColor.green / 255.0); add(ambientColor.blue / 255.0) }
                                        }
                                        putJsonArray("disabledObjects") {
                                            for (disabledObject in disabledObjects) {
                                                add(disabledObject)
                                            }
                                        }
                                    }.toString().encodeToByteArray()),
                                    Iso9660Creator.IsoFile("ICON.ICO", iconBytes)
                                ) + bootFiles)

                                isoFileName = "${gameName}.iso"
                                val blob = Blob(arrayOf(isoBytes), BlobPropertyBag(type = "application/octet-stream"))
                                downloadUrl = URL.createObjectURL(blob)
                                status = "Done!"
                                plausible("Generated PS2 ISO")
                            } catch (e: Exception) {
                                status = "Error creating ISO: ${e.message}"
                                logMessages.add("Error: ${e.stackTraceToString()}")
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
        Input(type = InputType.File) {
            attr("webkitdirectory", "")
            onChange { event ->
                val input: dynamic = event.target
                val files: dynamic = input.files
                if (files == null || files.length == 0) return@onChange

                downloadUrl = null
                logMessages.clear()
                parsedGameName = null
                loadedExternalAudio = emptyMap()
                loadedAudioGroupFiles = emptyMap()
                loadedSourceFiles = emptyMap()
                status = "Reading folder..."

                scope.launch {
                    try {
                        // Find the data.win file, collect audio files, audiogroup files, and other source files
                        var dataWinFile: dynamic = null
                        val audioFiles = HashMap<String, dynamic>()
                        val audioGroupDatFiles = HashMap<Int, dynamic>() // groupId -> file
                        val otherFiles = HashMap<String, dynamic>()
                        val fileCount = files.length as Int
                        val audioGroupPattern = Regex("""audiogroup(\d+)\.dat""", RegexOption.IGNORE_CASE)

                        // Get the root folder name from the first file's webkitRelativePath
                        val firstFile: dynamic = files[0]
                        val rootFolderPrefix = (firstFile.webkitRelativePath as String).substringBefore("/") + "/"

                        for (i in 0 until fileCount) {
                            val file: dynamic = files[i]
                            val name = (file.name as String).lowercase()
                            if (name.endsWith(".win") || name.endsWith(".unx") || name.endsWith(".osx")) {
                                dataWinFile = file
                            } else if (name.endsWith(".ogg") || name.endsWith(".wav")) {
                                audioFiles[file.name as String] = file
                            } else {
                                val audioGroupMatch = audioGroupPattern.matchEntire(file.name as String)
                                if (audioGroupMatch != null) {
                                    val groupId = audioGroupMatch.groupValues[1].toInt()
                                    if (groupId != 0) {
                                        audioGroupDatFiles[groupId] = file
                                    }
                                } else {
                                    // Use relative path (minus root folder) to preserve directory structure
                                    val relativePath = (file.webkitRelativePath as String).removePrefix(rootFolderPrefix)
                                    otherFiles[relativePath] = file
                                }
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

                        // Load audiogroup files
                        if (audioGroupDatFiles.isNotEmpty()) {
                            status = "Reading ${audioGroupDatFiles.size} audiogroup files..."
                            val audioGroups = HashMap<Int, ByteArray>()
                            for ((groupId, file) in audioGroupDatFiles) {
                                audioGroups[groupId] = readFileAsBytes(file)
                            }
                            loadedAudioGroupFiles = audioGroups
                        }

                        // Load other source files (for $BOOT filesystem mappings)
                        if (otherFiles.isNotEmpty()) {
                            status = "Reading ${otherFiles.size} other files..."
                            val sourceFiles = HashMap<String, ByteArray>()
                            for ((fileName, file) in otherFiles) {
                                sourceFiles[fileName] = readFileAsBytes(file)
                            }
                            loadedSourceFiles = sourceFiles
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
                        val audioGroupMsg = if (audioGroupDatFiles.isNotEmpty()) " (${audioGroupDatFiles.size} audiogroup files)" else ""
                        status = "Game: $gameName$audioMsg$audioGroupMsg"
                    } catch (e: Exception) {
                        status = "Error reading folder: ${e.message}"
                        loadedFileBytes = null
                        loadedExternalAudio = emptyMap()
                        loadedAudioGroupFiles = emptyMap()
                        loadedSourceFiles = emptyMap()
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
            FieldWrapper {
                FieldInformation {
                    FieldLabel("Controller Mappings")
                }

                Table(attrs = {
                    classes("fancy-table")
                }) {
                    Thead {
                        Tr {
                            Th { Text("PlayStation 2 Key") }
                            Th { Text("GameMaker Key") }
                        }
                    }
                    Tbody {
                        for (ps2Key in PS2PadKey.entries) {
                            Tr {
                                Td {
                                    Text(
                                        when (ps2Key) {
                                            PS2PadKey.PAD_LEFT -> "D-Pad Left"
                                            PS2PadKey.PAD_DOWN -> "D-Pad Down"
                                            PS2PadKey.PAD_RIGHT -> "D-Pad Right"
                                            PS2PadKey.PAD_UP -> "D-Pad Up"
                                            PS2PadKey.PAD_START -> "Start"
                                            PS2PadKey.PAD_R3 -> "R3"
                                            PS2PadKey.PAD_L3 -> "L3"
                                            PS2PadKey.PAD_SELECT -> "Select"
                                            PS2PadKey.PAD_SQUARE -> "Square (□)"
                                            PS2PadKey.PAD_CROSS -> "Cross (X)"
                                            PS2PadKey.PAD_CIRCLE -> "Circle (O)"
                                            PS2PadKey.PAD_TRIANGLE -> "Triangle (△)"
                                            PS2PadKey.PAD_R1 -> "R1"
                                            PS2PadKey.PAD_L1 -> "L1"
                                            PS2PadKey.PAD_R2 -> "R2"
                                            PS2PadKey.PAD_L2 -> "L2"
                                        }
                                    )
                                }

                                Td {
                                    val currentMapping = controllerMappings[ps2Key]

                                    Select(attrs = {
                                        onChange { event ->
                                            val selectedValue = event.value
                                            if (selectedValue == "") {
                                                controllerMappings.remove(ps2Key)
                                            } else {
                                                val gmlKey = GMLKey.fromValue(selectedValue!!.toInt())
                                                if (gmlKey != null) {
                                                    controllerMappings[ps2Key] = gmlKey
                                                }
                                            }
                                        }
                                    }) {
                                        Option("", attrs = {
                                            if (currentMapping == null) selected()
                                        }) {
                                            Text("<not mapped>")
                                        }

                                        for (gmlKey in GMLKey.entries) {
                                            if (gmlKey == GMLKey.VK_NOKEY)
                                                continue

                                            Option(gmlKey.value.toString(), attrs = {
                                                if (currentMapping == gmlKey) selected()
                                            }) {
                                                Text(
                                                    when (gmlKey) {
                                                        GMLKey.VK_NOKEY -> "No Key"
                                                        GMLKey.VK_BACKSPACE -> "Backspace"
                                                        GMLKey.VK_TAB -> "Tab"
                                                        GMLKey.VK_ENTER -> "Enter"
                                                        GMLKey.VK_SHIFT -> "Shift"
                                                        GMLKey.VK_CONTROL -> "Control"
                                                        GMLKey.VK_ALT -> "Alt"
                                                        GMLKey.VK_ESCAPE -> "Escape"
                                                        GMLKey.VK_SPACE -> "Space"
                                                        GMLKey.VK_PAGEUP -> "Page Up"
                                                        GMLKey.VK_PAGEDOWN -> "Page Down"
                                                        GMLKey.VK_END -> "End"
                                                        GMLKey.VK_HOME -> "Home"
                                                        GMLKey.VK_LEFT -> "Left Arrow"
                                                        GMLKey.VK_UP -> "Up Arrow"
                                                        GMLKey.VK_RIGHT -> "Right Arrow"
                                                        GMLKey.VK_DOWN -> "Down Arrow"
                                                        GMLKey.VK_INSERT -> "Insert"
                                                        GMLKey.VK_DELETE -> "Delete"
                                                        GMLKey.KEY_0 -> "0"
                                                        GMLKey.KEY_1 -> "1"
                                                        GMLKey.KEY_2 -> "2"
                                                        GMLKey.KEY_3 -> "3"
                                                        GMLKey.KEY_4 -> "4"
                                                        GMLKey.KEY_5 -> "5"
                                                        GMLKey.KEY_6 -> "6"
                                                        GMLKey.KEY_7 -> "7"
                                                        GMLKey.KEY_8 -> "8"
                                                        GMLKey.KEY_9 -> "9"
                                                        GMLKey.KEY_A -> "A"
                                                        GMLKey.KEY_B -> "B"
                                                        GMLKey.KEY_C -> "C"
                                                        GMLKey.KEY_D -> "D"
                                                        GMLKey.KEY_E -> "E"
                                                        GMLKey.KEY_F -> "F"
                                                        GMLKey.KEY_G -> "G"
                                                        GMLKey.KEY_H -> "H"
                                                        GMLKey.KEY_I -> "I"
                                                        GMLKey.KEY_J -> "J"
                                                        GMLKey.KEY_K -> "K"
                                                        GMLKey.KEY_L -> "L"
                                                        GMLKey.KEY_M -> "M"
                                                        GMLKey.KEY_N -> "N"
                                                        GMLKey.KEY_O -> "O"
                                                        GMLKey.KEY_P -> "P"
                                                        GMLKey.KEY_Q -> "Q"
                                                        GMLKey.KEY_R -> "R"
                                                        GMLKey.KEY_S -> "S"
                                                        GMLKey.KEY_T -> "T"
                                                        GMLKey.KEY_U -> "U"
                                                        GMLKey.KEY_V -> "V"
                                                        GMLKey.KEY_W -> "W"
                                                        GMLKey.KEY_X -> "X"
                                                        GMLKey.KEY_Y -> "Y"
                                                        GMLKey.KEY_Z -> "Z"
                                                        GMLKey.VK_F1 -> "F1"
                                                        GMLKey.VK_F2 -> "F2"
                                                        GMLKey.VK_F3 -> "F3"
                                                        GMLKey.VK_F4 -> "F4"
                                                        GMLKey.VK_F5 -> "F5"
                                                        GMLKey.VK_F6 -> "F6"
                                                        GMLKey.VK_F7 -> "F7"
                                                        GMLKey.VK_F8 -> "F8"
                                                        GMLKey.VK_F9 -> "F9"
                                                        GMLKey.VK_F10 -> "F10"
                                                        GMLKey.VK_F11 -> "F11"
                                                        GMLKey.VK_F12 -> "F12"
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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

            FieldWrapper {
                FieldInformation {
                    FieldLabel("Disabled Objects")
                }

                Table(attrs = {
                    classes("fancy-table")
                }) {
                    Thead {
                        Tr {
                            Th { Text("Object Name") }
                            Th {}
                        }
                    }
                    Tbody {
                        for (disabledObject in disabledObjects) {
                            Tr {
                                Td { Text(disabledObject) }
                                Td(attrs = {
                                    classes("action-cell")
                                }) {
                                    DiscordButton(
                                        DiscordButtonType.NO_BACKGROUND_THEME_DEPENDENT_DARK_TEXT,
                                        attrs = {
                                            onClick {
                                                disabledObjects.remove(disabledObject)
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

                var objectName by remember { mutableStateOf<String>("") }

                Div(attrs = {
                    classes("add-mapping-form")
                }) {
                    TextInput(objectName) {
                        attr("placeholder", "Object Name")
                        onInput {
                            objectName = it.value
                        }
                    }

                    DiscordButton(
                        DiscordButtonType.PRIMARY,
                        {
                            onClick {
                                if (objectName.isNotBlank()) {
                                    disabledObjects.add(objectName)
                                    objectName = ""
                                }
                            }
                        }
                    ) {
                        Text("Add")
                    }
                }
            }

            val checkmarkIcon = SVGIconManager.fromRawHtml(ButterscotchPreprocessorWeb.CHECKMARK_SVG)
            val eyeDropperIcon = SVGIconManager.fromRawHtml(ButterscotchPreprocessorWeb.EYE_DROPPER_SVG)

            FieldWrapper {
                FieldInformation {
                    FieldLabel("Memory Card Save Ambient Light")
                }
                ColorPicker(m, checkmarkIcon, eyeDropperIcon, ambientColor) {
                    if (it != null) ambientColor = it
                }
            }

            FieldWrapper {
                FieldInformation {
                    FieldLabel("Memory Card Save Background Alpha")
                }

                Div(attrs = { classes("range-input-with-value") }) {
                    Input(InputType.Range) {
                        attr("min", "0")
                        attr("max", "128")
                        value(bgAlpha.toString())
                        onInput { bgAlpha = it.value!!.toInt() }
                    }

                    Div(attrs = { classes("range-input-value") }) {
                        Text(bgAlpha.toString())
                    }
                }
            }

            FieldWrapper {
                FieldInformation {
                    FieldLabel("Memory Card Save Background (Top Left)")
                }
                ColorPicker(m, checkmarkIcon, eyeDropperIcon, bgColorTopLeft) {
                    if (it != null) bgColorTopLeft = it
                }
            }

            FieldWrapper {
                FieldInformation {
                    FieldLabel("Memory Card Save Background (Top Right)")
                }
                ColorPicker(m, checkmarkIcon, eyeDropperIcon, bgColorTopRight) {
                    if (it != null) bgColorTopRight = it
                }
            }

            FieldWrapper {
                FieldInformation {
                    FieldLabel("Memory Card Save Background (Bottom Left)")
                }
                ColorPicker(m, checkmarkIcon, eyeDropperIcon, bgColorBottomLeft) {
                    if (it != null) bgColorBottomLeft = it
                }
            }

            FieldWrapper {
                FieldInformation {
                    FieldLabel("Memory Card Save Background (Bottom Right)")
                }
                ColorPicker(m, checkmarkIcon, eyeDropperIcon, bgColorBottomRight) {
                    if (it != null) bgColorBottomRight = it
                }
            }

            FieldWrapper {
                FieldInformation {
                    FieldLabel("Memory Card Save Light 1")
                }
                ColorPicker(m, checkmarkIcon, eyeDropperIcon, lightColor1) {
                    if (it != null) lightColor1 = it
                }
                Div(attrs = { classes("light-direction-inputs") }) {
                    Text("Direction:")
                    TextInput(lightDir1X) {
                        attr("placeholder", "X")
                        onInput { lightDir1X = it.value }
                    }
                    TextInput(lightDir1Y) {
                        attr("placeholder", "Y")
                        onInput { lightDir1Y = it.value }
                    }
                    TextInput(lightDir1Z) {
                        attr("placeholder", "Z")
                        onInput { lightDir1Z = it.value }
                    }
                }
            }

            FieldWrapper {
                FieldInformation {
                    FieldLabel("Memory Card Save Light 2")
                }
                ColorPicker(m, checkmarkIcon, eyeDropperIcon, lightColor2) {
                    if (it != null) lightColor2 = it
                }
                Div(attrs = { classes("light-direction-inputs") }) {
                    Text("Direction:")
                    TextInput(lightDir2X) {
                        attr("placeholder", "X")
                        onInput { lightDir2X = it.value }
                    }
                    TextInput(lightDir2Y) {
                        attr("placeholder", "Y")
                        onInput { lightDir2Y = it.value }
                    }
                    TextInput(lightDir2Z) {
                        attr("placeholder", "Z")
                        onInput { lightDir2Z = it.value }
                    }
                }
            }

            FieldWrapper {
                FieldInformation {
                    FieldLabel("Memory Card Save Light 3")
                }
                ColorPicker(m, checkmarkIcon, eyeDropperIcon, lightColor3) {
                    if (it != null) lightColor3 = it
                }
                Div(attrs = { classes("light-direction-inputs") }) {
                    Text("Direction:")
                    TextInput(lightDir3X) {
                        attr("placeholder", "X")
                        onInput { lightDir3X = it.value }
                    }
                    TextInput(lightDir3Y) {
                        attr("placeholder", "Y")
                        onInput { lightDir3Y = it.value }
                    }
                    TextInput(lightDir3Z) {
                        attr("placeholder", "Z")
                        onInput { lightDir3Z = it.value }
                    }
                }
            }

            FieldWrapper {
                Div(attrs = {
                    classes("field-information-with-control")
                }) {
                    Div(attrs = {
                        classes("field-information")
                    }) {
                        Div(attrs = {
                            classes("field-title")
                        }) {
                            Text("Memory Card Save Icon")
                        }

                        Div(attrs = {
                            classes("field-description")
                        }) {
                            Text("When selected, Butterscotch will use a custom memory card save icon for your game. The icon must be in the PlayStation 2 save icon format. If not set, then the default annoying dog save icon will be used.")
                        }
                    }

                    Div {
                        Input(type = InputType.File) {
                            id("custom-icon-input")
                            style { property("display", "none") }
                            onChange { event ->
                                val input: dynamic = event.target
                                val files: dynamic = input.files
                                if (files == null || files.length == 0)
                                    return@onChange

                                val file: dynamic = files[0]

                                scope.launch {
                                    try {
                                        customIconBytes = readFileAsBytes(file)
                                    } catch (e: Exception) {
                                        // Reset on error
                                        customIconBytes = null
                                    }
                                }
                            }
                        }

                        if (customIconBytes != null) {
                            DiscordButton(
                                DiscordButtonType.DANGER,
                                {
                                    onClick {
                                        customIconBytes = null
                                    }
                                }
                            ) {
                                Text("Remove")
                            }
                        } else {
                            DiscordButton(
                                DiscordButtonType.PRIMARY,
                                {
                                    onClick {
                                        document.getElementById(ElementId("custom-icon-input"))!!.click()
                                    }
                                }
                            ) {
                                Text("Select")
                            }
                        }
                    }
                }
            }

            DiscordToggle(
                "defer-draw-to-after-all-steps",
                "Defer Draw to After All Steps",
                "When enabled, Butterscotch will defer GameMaker draw events after all steps events have caught up. This improves performance when the game is lagging, but can cause glitches if the game depends on draw logic.",
                deferDrawToAfterAllSteps
            ) {
                deferDrawToAfterAllSteps = !deferDrawToAfterAllSteps
            }

            DiscordToggle(
                "debug-overlay-enabled",
                "Debug Overlay Enabled by Default",
                "When enabled, Butterscotch will render the debug overlay by default. You can toggle the debug overlay by pressing the key that's bound to F12.",
                debugOverlayEnabled
            ) {
                debugOverlayEnabled = !debugOverlayEnabled
            }

            FieldWrapper {
                Div(attrs = {
                    classes("field-information-with-control")
                }) {
                    Div(attrs = {
                        classes("field-information")
                    }) {
                        Div(attrs = {
                            classes("field-title")
                        }) {
                            Text("Butterscotch ELF (Advanced)")
                        }

                        Div(attrs = {
                            classes("field-description")
                        }) {
                            Text("When selected, Butterscotch will use a custom ELF binary instead of the built-in one. This is intended for advanced users who have built their own Butterscotch runtime. If not set, the default ELF will be used.")
                        }
                    }

                    Div {
                        Input(type = InputType.File) {
                            id("custom-elf-input")
                            style { property("display", "none") }
                            onChange { event ->
                                val input: dynamic = event.target
                                val files: dynamic = input.files
                                if (files == null || files.length == 0)
                                    return@onChange

                                val file: dynamic = files[0]

                                scope.launch {
                                    try {
                                        customElfBytes = readFileAsBytes(file)
                                    } catch (e: Exception) {
                                        customElfBytes = null
                                    }
                                }
                            }
                        }

                        if (customElfBytes != null) {
                            DiscordButton(
                                DiscordButtonType.DANGER,
                                {
                                    onClick {
                                        customElfBytes = null
                                    }
                                }
                            ) {
                                Text("Remove")
                            }
                        } else {
                            DiscordButton(
                                DiscordButtonType.PRIMARY,
                                {
                                    onClick {
                                        document.getElementById(ElementId("custom-elf-input"))!!.click()
                                    }
                                }
                            ) {
                                Text("Select")
                            }
                        }
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
                    logMessages.clear()
                    status = "Processing..."

                    // Build external audio map as a JS object for the worker
                    val externalAudio = loadedExternalAudio

                    val audioObj = unsafeJso<dynamic> {
                        for ((name, data) in externalAudio) {
                            this[name] = data
                        }
                    }

                    // Build audiogroup map as a JS object for the worker
                    val audioGroupObj = unsafeJso<dynamic> {
                        for ((groupId, data) in loadedAudioGroupFiles) {
                            this[groupId.toString()] = data
                        }
                    }

                    // Send data to worker (no transfer - we need the bytes later for the ISO)
                    worker.asDynamic().postMessage(
                        unsafeJso {
                            this.type = "process"
                            this.data = bytes
                            this.externalAudio = audioObj
                            this.audioGroups = audioGroupObj
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
    if (downloadUrl != null) {
        Div({ classes("buttons-wrapper") }) {
            A(href = downloadUrl, attrs = {
                attr("download", isoFileName)
                classes("discord-button", "success")
            }) {
                Text("Download ISO")
            }

            DiscordButton(
                DiscordButtonType.NO_BACKGROUND_THEME_DEPENDENT_DARK_TEXT,
                attrs = {
                    onClick {
                        downloadUrl = null
                        logMessages.clear()
                    }
                }
            ) {
                Text("Go Back")
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
        reader.onload = EventHandler {
            val arrayBuffer = reader.result as ArrayBuffer
            val uint8Array = Uint8Array(arrayBuffer)
            val length = uint8Array.length as Int
            val bytes = ByteArray(length)
            for (i in 0 until length) {
                bytes[i] = uint8Array[i].toByte()
            }
            cont.resume(bytes)
        }
        reader.onerror = EventHandler {
            cont.resumeWithException(RuntimeException("Failed to read file"))
        }
        reader.readAsArrayBuffer(file)
    }
}

private suspend fun fetchResourceBytes(path: String): ByteArray {
    val httpResult = fetch(path)
    val status = httpResult.status
    if (200 > status || status >= 300)
        error("Failed to fetch $path: HTTP $status")

    val arrayBuffer = httpResult.arrayBuffer()
    val uint8Array = Uint8Array(arrayBuffer)
    val length = uint8Array.length
    val bytes = ByteArray(length)
    for (i in 0 until length) {
        bytes[i] = uint8Array[i].toByte()
    }
    return bytes
}