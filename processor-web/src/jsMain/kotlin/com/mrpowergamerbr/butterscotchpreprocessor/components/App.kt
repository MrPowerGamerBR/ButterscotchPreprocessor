package com.mrpowergamerbr.butterscotchpreprocessor.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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

private val UNDERTALE_CONTROLLER_MAPPINGS = mapOf(
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

private val DEFAULT_LIGHT_SETTINGS = LightSettings(
    lightColor1 = Color(179, 179, 179), lightDir1 = Triple("0.5", "0.5", "0.5"),
    lightColor2 = Color(128, 128, 128), lightDir2 = Triple("0.0", "-0.4", "-0.1"),
    lightColor3 = Color(77, 77, 77), lightDir3 = Triple("-0.5", "-0.5", "0.5"),
)

data class LightSettings(
    val lightColor1: Color, val lightDir1: Triple<String, String, String>,
    val lightColor2: Color, val lightDir2: Triple<String, String, String>,
    val lightColor3: Color, val lightDir3: Triple<String, String, String>,
)

data class Preset(
    val displayName: String,
    val gen8MatchName: String,
    val controller1Mappings: Map<PS2PadKey, GMLKey>,
    val controller2Mappings: Map<PS2PadKey, GMLKey>,
    val filesystemMappings: Map<String, String>,
    val disabledObjects: List<String>,
    val bgAlpha: Int,
    val bgColorTopLeft: Color,
    val bgColorTopRight: Color,
    val bgColorBottomLeft: Color,
    val bgColorBottomRight: Color,
    val ambientColor: Color,
    val lights: LightSettings,
    val debugOverlayEnabled: Boolean,
)

private val UNDERTALE_PRESET = Preset(
    displayName = "Undertale",
    gen8MatchName = "UNDERTALE",
    controller1Mappings = UNDERTALE_CONTROLLER_MAPPINGS,
    controller2Mappings = emptyMap(),
    filesystemMappings = mapOf(
        "file0" to "mc0:UNDERTALE/file0",
        "file1" to "mc0:UNDERTALE/file1",
        "file2" to "mc0:UNDERTALE/file2",
        "file3" to "mc0:UNDERTALE/file3",
        "file4" to "mc0:UNDERTALE/file4",
        "file5" to "mc0:UNDERTALE/file5",
        "file6" to "mc0:UNDERTALE/file6",
        "file7" to "mc0:UNDERTALE/file7",
        "file8" to "mc0:UNDERTALE/file8",
        "file9" to "mc0:UNDERTALE/file9",
        "file9" to "mc0:UNDERTALE/file9",
        "system_information_962" to "mc0:UNDERTALE/system_information_962",
        "system_information_963" to "mc0:UNDERTALE/system_information_963",
        "undertale.ini" to "mc0:UNDERTALE/undertale.ini",
        "credits.txt" to "\$BOOT:CREDITS.TXT",
    ),
    disabledObjects = listOf(
        "obj_snowfloor",
        "obj_glowparticle",
        "obj_true_lavawaver",
        "obj_true_antiwaver",
        "obj_orangeparticle",
    ),
    bgAlpha = 68,
    bgColorTopLeft = Color(255, 204, 0),
    bgColorTopRight = Color(255, 204, 0),
    bgColorBottomLeft = Color(180, 140, 0),
    bgColorBottomRight = Color(180, 140, 0),
    ambientColor = Color(255, 204, 0),
    lights = DEFAULT_LIGHT_SETTINGS,
    debugOverlayEnabled = true,
)

private val SURVEY_PROGRAM_PRESET = Preset(
    displayName = "DELTARUNE (SURVEY_PROGRAM)",
    gen8MatchName = "SURVEY_PROGRAM",
    controller1Mappings = UNDERTALE_CONTROLLER_MAPPINGS,
    controller2Mappings = emptyMap(),
    filesystemMappings = mapOf(
        "lang/lang_en.json" to "\$BOOT:LANG/LANG_EN.JSON",
        "lang/lang_ja.json" to "\$BOOT:LANG/LANG_JA.JSON",
        "filech1_0" to "mc0:/SURVEY_PROGRAM/filech1_0",
        "filech1_1" to "mc0:/SURVEY_PROGRAM/filech1_1",
        "filech1_2" to "mc0:/SURVEY_PROGRAM/filech1_2",
        "filech1_3" to "mc0:/SURVEY_PROGRAM/filech1_3",
        "filech1_4" to "mc0:/SURVEY_PROGRAM/filech1_4",
        "filech1_5" to "mc0:/SURVEY_PROGRAM/filech1_5",
        "filech1_9" to "mc0:/SURVEY_PROGRAM/filech1_9",
        "dr.ini" to "mc0:/SURVEY_PROGRAM/dr.ini",
        "true_config.ini" to "mc0:/SURVEY_PROGRAM/true_config.ini"
    ),
    disabledObjects = emptyList(),
    bgAlpha = 68,
    bgColorTopLeft = Color(50, 20, 100),
    bgColorTopRight = Color(50, 20, 100),
    bgColorBottomLeft = Color(20, 5, 50),
    bgColorBottomRight = Color(20, 5, 50),
    ambientColor = Color(50, 20, 100),
    lights = DEFAULT_LIGHT_SETTINGS,
    debugOverlayEnabled = true,
)

private val PRESETS = listOf(UNDERTALE_PRESET, SURVEY_PROGRAM_PRESET)

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
    var loadedMusFiles by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    var loadedSourceFiles by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    var parsedGameName by remember { mutableStateOf<String?>(null) }
    val controller1Mappings = remember {
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
    val controller2Mappings = remember { mutableStateMapOf<PS2PadKey, GMLKey>() }
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
    var selectedPreset by remember { mutableStateOf<Preset?>(UNDERTALE_PRESET) }

    val applyPreset = { preset: Preset ->
        selectedPreset = preset
        controller1Mappings.clear()
        controller1Mappings.putAll(preset.controller1Mappings)
        controller2Mappings.clear()
        controller2Mappings.putAll(preset.controller2Mappings)
        filesystemMappings.clear()
        filesystemMappings.putAll(preset.filesystemMappings)
        disabledObjects.clear()
        disabledObjects.addAll(preset.disabledObjects)
        bgAlpha = preset.bgAlpha
        bgColorTopLeft = preset.bgColorTopLeft
        bgColorTopRight = preset.bgColorTopRight
        bgColorBottomLeft = preset.bgColorBottomLeft
        bgColorBottomRight = preset.bgColorBottomRight
        ambientColor = preset.ambientColor
        lightColor1 = preset.lights.lightColor1
        lightDir1X = preset.lights.lightDir1.first
        lightDir1Y = preset.lights.lightDir1.second
        lightDir1Z = preset.lights.lightDir1.third
        lightColor2 = preset.lights.lightColor2
        lightDir2X = preset.lights.lightDir2.first
        lightDir2Y = preset.lights.lightDir2.second
        lightDir2Z = preset.lights.lightDir2.third
        lightColor3 = preset.lights.lightColor3
        lightDir3X = preset.lights.lightDir3.first
        lightDir3Y = preset.lights.lightDir3.second
        lightDir3Z = preset.lights.lightDir3.third
        debugOverlayEnabled = preset.debugOverlayEnabled
    }

    val scope = rememberCoroutineScope()

    // Create the worker once, loading the same script in a worker context
    val worker = remember {
        Worker("/assets/js/processor-web.js?v=${js("window.jsBundleHash") as String}").also { w ->
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
                                        put("debugOverlayEnabled", debugOverlayEnabled)
                                        putJsonObject("fileSystem") {
                                            for (mapping in filesystemMappings) {
                                                putJsonArray(mapping.key) { add(mapping.value) }
                                            }
                                        }
                                        putJsonObject("controller1Mappings") {
                                            for ((key, value) in controller1Mappings) {
                                                put(key.value.toString(), value.value)
                                            }
                                        }
                                        putJsonObject("controller2Mappings") {
                                            for ((key, value) in controller2Mappings) {
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
                loadedMusFiles = emptyMap()
                loadedSourceFiles = emptyMap()
                status = "Reading folder..."

                scope.launch {
                    try {
                        // Find the data.win file, collect audio files, audiogroup files, and other source files
                        var dataWinFile: dynamic = null
                        val audioFiles = HashMap<String, dynamic>()
                        val audioGroupDatFiles = HashMap<Int, dynamic>() // groupId -> file
                        val musFileEntries = HashMap<String, dynamic>() // relativePath -> file
                        val otherFiles = HashMap<String, dynamic>()
                        val fileCount = files.length as Int
                        val audioGroupPattern = Regex("""audiogroup(\d+)\.dat""", RegexOption.IGNORE_CASE)

                        // Get the root folder name from the first file's webkitRelativePath
                        val firstFile: dynamic = files[0]
                        val rootFolderPrefix = (firstFile.webkitRelativePath as String).substringBefore("/") + "/"

                        for (i in 0 until fileCount) {
                            val file: dynamic = files[i]
                            val name = (file.name as String).lowercase()
                            val relativePath = (file.webkitRelativePath as String).removePrefix(rootFolderPrefix)
                            if (name.endsWith(".win") || name.endsWith(".unx") || name.endsWith(".osx")) {
                                dataWinFile = file
                            } else if ((name.endsWith(".ogg") || name.endsWith(".wav")) && !relativePath.contains("/")) {
                                // Audio files in the root directory are external audio (SOND-referenced)
                                audioFiles[file.name as String] = file
                            } else if (name.endsWith(".ogg") && relativePath.contains("/")) {
                                // OGG files in subdirectories (e.g. mus/) are streamed music files
                                musFileEntries[relativePath] = file
                            } else {
                                val audioGroupMatch = audioGroupPattern.matchEntire(file.name as String)
                                if (audioGroupMatch != null) {
                                    val groupId = audioGroupMatch.groupValues[1].toInt()
                                    if (groupId != 0) {
                                        audioGroupDatFiles[groupId] = file
                                    }
                                } else {
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

                        // Load streamed music files (from subdirectories like mus/)
                        if (musFileEntries.isNotEmpty()) {
                            status = "Reading ${musFileEntries.size} music files..."
                            val musData = HashMap<String, ByteArray>()
                            for ((path, file) in musFileEntries) {
                                musData[path] = readFileAsBytes(file)
                            }
                            loadedMusFiles = musData
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
                        val matchedPreset = PRESETS.find { it.gen8MatchName.equals(gameName, ignoreCase = true) }
                        if (matchedPreset != null) {
                            applyPreset(matchedPreset)
                        }
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
            Text("Select a preset to quickly configure the settings for a specific game, or customize the options below.")
        }

        Div({ classes("preset-buttons"); style { property("display", "flex"); property("gap", "8px") } }) {
            for (preset in PRESETS) {
                DiscordButton(
                    DiscordButtonType.PRIMARY,
                    attrs = {
                        onClick { applyPreset(preset) }
                    }
                ) {
                    if (selectedPreset == preset) {
                        Text("${preset.displayName} (Selected)")
                    } else {
                        Text(preset.displayName)
                    }
                }
            }
        }

        FieldWrappers {
            ControllerMappings("Controller 1 Mappings", controller1Mappings)
            ControllerMappings("Controller 2 Mappings", controller2Mappings)

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

                    // Build mus files map as a JS object for the worker
                    val musObj = unsafeJso<dynamic> {
                        for ((path, data) in loadedMusFiles) {
                            this[path] = data
                        }
                    }

                    // Send data to worker (no transfer - we need the bytes later for the ISO)
                    worker.asDynamic().postMessage(
                        unsafeJso {
                            this.type = "process"
                            this.data = bytes
                            this.externalAudio = audioObj
                            this.audioGroups = audioGroupObj
                            this.musFiles = musObj
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