package com.mrpowergamerbr.butterscotchpreprocessor

import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt

// Butterscotch data.win port to C

// ===[ Parser Options ]===
data class DataWinParserOptions(
    val parseGen8: Boolean = true,
    val parseOptn: Boolean = true,
    val parseLang: Boolean = true,
    val parseExtn: Boolean = true,
    val parseSond: Boolean = true,
    val parseAgrp: Boolean = true,
    val parseSprt: Boolean = true,
    val parseBgnd: Boolean = true,
    val parsePath: Boolean = true,
    val parseScpt: Boolean = true,
    val parseGlob: Boolean = true,
    val parseShdr: Boolean = true,
    val parseFont: Boolean = true,
    val parseTmln: Boolean = true,
    val parseObjt: Boolean = true,
    val parseRoom: Boolean = true,
    val parseTpag: Boolean = true,
    val parseCode: Boolean = true,
    val parseVari: Boolean = true,
    val parseFunc: Boolean = true,
    val parseStrg: Boolean = true,
    val parseTxtr: Boolean = true,
    val parseAudo: Boolean = true,
    val skipLoadingPreciseMasksForNonPreciseSprites: Boolean = false
)

// ===[ GEN8 ]===
class Gen8 {
    var isDebuggerDisabled = 0
    var bytecodeVersion = 0
    var fileName: String? = null
    var config: String? = null
    var lastObj = 0
    var lastTile = 0
    var gameID = 0
    var directPlayGuid = ByteArray(16)
    var name: String? = null
    var major = 0
    var minor = 0
    var release = 0
    var build = 0
    var defaultWindowWidth = 0
    var defaultWindowHeight = 0
    var info = 0
    var licenseCRC32 = 0
    var licenseMD5 = ByteArray(16)
    var timestamp = 0L
    var displayName: String? = null
    var activeTargets = 0L
    var functionClassifications = 0L
    var steamAppID = 0
    var debuggerPort = 0
    var roomOrder = intArrayOf()
}

// ===[ OPTN ]===
class OptnConstant(val name: String?, val value: String?)

class Optn {
    var info = 0L
    var scale = 0
    var windowColor = 0
    var colorDepth = 0
    var resolution = 0
    var frequency = 0
    var vertexSync = 0
    var priority = 0
    var backImage = 0
    var frontImage = 0
    var loadImage = 0
    var loadAlpha = 0
    var constants = emptyList<OptnConstant>()
}

// ===[ LANG ]===
class Language(val name: String?, val region: String?, val entries: List<String?>)

class Lang {
    var unknown1 = 0
    var entryIds = emptyList<String?>()
    var languages = emptyList<Language>()
}

// ===[ EXTN ]===
class ExtensionFunction(
    val name: String?, val id: Int, val kind: Int, val retType: Int,
    val extName: String?, val arguments: IntArray
)

class ExtensionFile(
    val filename: String?, val cleanupScript: String?, val initScript: String?,
    val kind: Int, val functions: List<ExtensionFunction>
)

class Extension(
    val folderName: String?, val name: String?, val className: String?,
    val files: List<ExtensionFile>
)

class Extn {
    var extensions = emptyList<Extension>()
}

// ===[ SOND ]===
class Sound(
    val name: String?, val flags: Int, val type: String?, val file: String?,
    val effects: Int, val volume: Float, val pitch: Float,
    val audioGroup: Int, val audioFile: Int
)

class Sond {
    var sounds = emptyList<Sound>()
}

// ===[ AGRP ]===
class Agrp {
    var audioGroups = emptyList<String?>()
}

// ===[ SPRT ]===
class Sprite {
    var name: String? = null
    var width = 0
    var height = 0
    var marginLeft = 0
    var marginRight = 0
    var marginBottom = 0
    var marginTop = 0
    var transparent = false
    var smooth = false
    var preload = false
    var bboxMode = 0
    var sepMasks = 0
    var originX = 0
    var originY = 0
    var textureOffsets = intArrayOf()
    var masks: List<ByteArray>? = null
}

class Sprt {
    var sprites = emptyList<Sprite>()
}

// ===[ BGND ]===
class Background(val name: String?, val transparent: Boolean, val smooth: Boolean, val preload: Boolean, val textureOffset: Int)

class Bgnd {
    var backgrounds = emptyList<Background>()
}

// ===[ PATH ]===
class PathPoint(val x: Float, val y: Float, val speed: Float)

class InternalPathPoint(val x: Double, val y: Double, val speed: Double, var l: Double = 0.0)

data class PathPositionResult(val x: Double, val y: Double, val speed: Double)

class GamePath {
    var name: String? = null
    var isSmooth = false
    var isClosed = false
    var precision = 0
    var points = emptyList<PathPoint>()
    var internalPoints = emptyList<InternalPathPoint>()
    var length = 0.0

    fun computeInternal() {
        val temp = mutableListOf<InternalPathPoint>()

        fun addPoint(x: Double, y: Double, speed: Double) {
            temp.add(InternalPathPoint(x, y, speed))
        }

        fun handlePiece(depth: Int, x1: Double, y1: Double, s1: Double, x2: Double, y2: Double, s2: Double, x3: Double, y3: Double, s3: Double) {
            if (depth == 0) return
            val mx = (x1 + x2 + x2 + x3) / 4.0
            val my = (y1 + y2 + y2 + y3) / 4.0
            val ms = (s1 + s2 + s2 + s3) / 4.0
            if ((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) > 16.0) {
                handlePiece(depth - 1, x1, y1, s1, (x2 + x1) / 2.0, (y2 + y1) / 2.0, (s2 + s1) / 2.0, mx, my, ms)
            }
            addPoint(mx, my, ms)
            if ((x2 - x3) * (x2 - x3) + (y2 - y3) * (y2 - y3) > 16.0) {
                handlePiece(depth - 1, mx, my, ms, (x3 + x2) / 2.0, (y3 + y2) / 2.0, (s3 + s2) / 2.0, x3, y3, s3)
            }
        }

        if (points.isEmpty()) {
            internalPoints = emptyList()
            length = 0.0
            return
        }

        if (isSmooth) {
            if (!isClosed) {
                addPoint(points[0].x.toDouble(), points[0].y.toDouble(), points[0].speed.toDouble())
            }
            val n = if (isClosed) points.size - 1 else points.size - 3
            for (i in 0..n) {
                val p1 = points[i % points.size]
                val p2 = points[(i + 1) % points.size]
                val p3 = points[(i + 2) % points.size]
                handlePiece(
                    precision,
                    (p1.x + p2.x) / 2.0, (p1.y + p2.y) / 2.0, (p1.speed + p2.speed) / 2.0,
                    p2.x.toDouble(), p2.y.toDouble(), p2.speed.toDouble(),
                    (p2.x + p3.x) / 2.0, (p2.y + p3.y) / 2.0, (p2.speed + p3.speed) / 2.0
                )
            }
            if (!isClosed) {
                val last = points.last()
                addPoint(last.x.toDouble(), last.y.toDouble(), last.speed.toDouble())
            } else if (temp.isNotEmpty()) {
                addPoint(temp[0].x, temp[0].y, temp[0].speed)
            }
        } else {
            for (p in points) {
                addPoint(p.x.toDouble(), p.y.toDouble(), p.speed.toDouble())
            }
            if (isClosed) {
                addPoint(points[0].x.toDouble(), points[0].y.toDouble(), points[0].speed.toDouble())
            }
        }

        internalPoints = temp
        length = 0.0
        if (internalPoints.isNotEmpty()) {
            internalPoints[0].l = 0.0
            for (i in 1 until internalPoints.size) {
                val dx = internalPoints[i].x - internalPoints[i - 1].x
                val dy = internalPoints[i].y - internalPoints[i - 1].y
                length += sqrt(dx * dx + dy * dy)
                internalPoints[i].l = length
            }
        }
    }

    fun getPosition(t: Double): PathPositionResult {
        if (internalPoints.isEmpty()) return PathPositionResult(0.0, 0.0, 0.0)
        if (internalPoints.size == 1 || length == 0.0 || 0.0 >= t) {
            val p = internalPoints[0]
            return PathPositionResult(p.x, p.y, p.speed)
        }
        if (t >= 1.0) {
            val p = internalPoints.last()
            return PathPositionResult(p.x, p.y, p.speed)
        }
        val l = length * t
        var pos = 0
        while (internalPoints.size - 2 > pos && l >= internalPoints[pos + 1].l) {
            pos++
        }
        val node = internalPoints[pos]
        val lRem = l - node.l
        val w = internalPoints[pos + 1].l - node.l
        if (w != 0.0) {
            val next = internalPoints[pos + 1]
            return PathPositionResult(
                node.x + lRem * (next.x - node.x) / w,
                node.y + lRem * (next.y - node.y) / w,
                node.speed + lRem * (next.speed - node.speed) / w
            )
        }
        return PathPositionResult(node.x, node.y, node.speed)
    }
}

class PathChunk {
    var paths = emptyList<GamePath>()
}

// ===[ SCPT ]===
class Script(val name: String?, val codeId: Int)

class Scpt {
    var scripts = emptyList<Script>()
}

// ===[ GLOB ]===
class Glob {
    var codeIds = intArrayOf()
}

// ===[ SHDR ]===
class Shader {
    var name: String? = null
    var type = 0
    var glslES_Vertex: String? = null
    var glslES_Fragment: String? = null
    var glsl_Vertex: String? = null
    var glsl_Fragment: String? = null
    var hlsl9_Vertex: String? = null
    var hlsl9_Fragment: String? = null
    var hlsl11_VertexOffset = 0
    var hlsl11_PixelOffset = 0
    var vertexAttributes = emptyList<String?>()
    var version = 0
    var pssl_VertexOffset = 0
    var pssl_VertexLen = 0
    var pssl_PixelOffset = 0
    var pssl_PixelLen = 0
    var cgVita_VertexOffset = 0
    var cgVita_VertexLen = 0
    var cgVita_PixelOffset = 0
    var cgVita_PixelLen = 0
    var cgPS3_VertexOffset = 0
    var cgPS3_VertexLen = 0
    var cgPS3_PixelOffset = 0
    var cgPS3_PixelLen = 0
}

class Shdr {
    var shaders = emptyList<Shader>()
}

// ===[ FONT ]===
class KerningPair(val character: Short, val shiftModifier: Short)

class FontGlyph {
    var character = 0
    var sourceX = 0
    var sourceY = 0
    var sourceWidth = 0
    var sourceHeight = 0
    var shift: Short = 0
    var offset: Short = 0
    var kerning = emptyList<KerningPair>()
}

class Font {
    var name: String? = null
    var displayName: String? = null
    var emSize = 0
    var bold = false
    var italic = false
    var rangeStart = 0
    var charset = 0
    var antiAliasing = 0
    var rangeEnd = 0
    var textureOffset = 0
    var scaleX = 0f
    var scaleY = 0f
    var glyphs = emptyList<FontGlyph>()
}

class FontChunk {
    var fonts = emptyList<Font>()
}

// ===[ EventAction (shared by TMLN and OBJT) ]===
class EventAction {
    var libID = 0
    var id = 0
    var kind = 0
    var useRelative = false
    var isQuestion = false
    var useApplyTo = false
    var exeType = 0
    var actionName: String? = null
    var codeId = 0
    var argumentCount = 0
    var who = 0
    var relative = false
    var isNot = false
    var unknownAlwaysZero = 0
}

// ===[ TMLN ]===
class TimelineMoment(val step: Int, val actions: List<EventAction>)

class Timeline(val name: String?, val moments: List<TimelineMoment>)

class Tmln {
    var timelines = emptyList<Timeline>()
}

// ===[ OBJT ]===
const val OBJT_EVENT_TYPE_COUNT = 12

class ObjectEvent(val eventSubtype: Int, val actions: List<EventAction>)

class ObjectEventList(val events: List<ObjectEvent> = emptyList())

class PhysicsVertex(val x: Float, val y: Float)

class GameObject {
    var name: String? = null
    var spriteId = 0
    var visible = false
    var solid = false
    var depth = 0
    var persistent = false
    var parentId = 0
    var textureMaskId = 0
    var usesPhysics = false
    var isSensor = false
    var collisionShape = 0
    var density = 0f
    var restitution = 0f
    var group = 0
    var linearDamping = 0f
    var angularDamping = 0f
    var physicsVertexCount = 0
    var friction = 0f
    var awake = false
    var kinematic = false
    var physicsVertices = emptyList<PhysicsVertex>()
    var eventLists = Array(OBJT_EVENT_TYPE_COUNT) { ObjectEventList() }
}

class Objt {
    var objects = emptyList<GameObject>()
}

// ===[ ROOM ]===
class RoomBackground {
    var enabled = false
    var foreground = false
    var backgroundDefinition = 0
    var x = 0
    var y = 0
    var tileX = 0
    var tileY = 0
    var speedX = 0
    var speedY = 0
    var stretch = false
}

class RoomView {
    var enabled = false
    var viewX = 0
    var viewY = 0
    var viewWidth = 0
    var viewHeight = 0
    var portX = 0
    var portY = 0
    var portWidth = 0
    var portHeight = 0
    var borderX = 0
    var borderY = 0
    var speedX = 0
    var speedY = 0
    var objectId = 0
}

class RoomGameObject {
    var x = 0
    var y = 0
    var objectDefinition = 0
    var instanceID = 0
    var creationCode = 0
    var scaleX = 0f
    var scaleY = 0f
    var color = 0
    var rotation = 0f
    var preCreateCode = 0
}

class RoomTile {
    var x = 0
    var y = 0
    var backgroundDefinition = 0
    var sourceX = 0
    var sourceY = 0
    var width = 0
    var height = 0
    var tileDepth = 0
    var instanceID = 0
    var scaleX = 0f
    var scaleY = 0f
    var color = 0
}

class Room {
    var name: String? = null
    var caption: String? = null
    var width = 0
    var height = 0
    var speed = 0
    var persistent = false
    var backgroundColor = 0
    var drawBackgroundColor = false
    var creationCodeId = 0
    var flags = 0
    var world = false
    var top = 0
    var left = 0
    var right = 0
    var bottom = 0
    var gravityX = 0f
    var gravityY = 0f
    var metersPerPixel = 0f
    var backgrounds = Array(8) { RoomBackground() }
    var views = Array(8) { RoomView() }
    var gameObjects = emptyList<RoomGameObject>()
    var tiles = emptyList<RoomTile>()
}

class RoomChunk {
    var rooms = emptyList<Room>()
}

// ===[ TPAG ]===
class TexturePageItem {
    var sourceX = 0
    var sourceY = 0
    var sourceWidth = 0
    var sourceHeight = 0
    var targetX = 0
    var targetY = 0
    var targetWidth = 0
    var targetHeight = 0
    var boundingWidth = 0
    var boundingHeight = 0
    var texturePageId: Short = 0
}

class Tpag {
    var items = emptyList<TexturePageItem>()
}

// ===[ CODE ]===
class CodeEntry(
    val name: String?, val length: Int, val localsCount: Int, val argumentsCount: Int,
    val bytecodeAbsoluteOffset: Int, val offset: Int
)

class Code {
    var entries = emptyList<CodeEntry>()
}

// ===[ VARI ]===
class Variable(
    val name: String?, val instanceType: Int, val varID: Int,
    val occurrences: Int, val firstAddress: Int
)

class Vari {
    var varCount1 = 0
    var varCount2 = 0
    var maxLocalVarCount = 0
    var variables = emptyList<Variable>()
}

// ===[ FUNC ]===
class Function(val name: String?, val occurrences: Int, val firstAddress: Int)

class LocalVar(val index: Int, val name: String?)

class CodeLocals(val name: String?, val locals: List<LocalVar>)

class Func {
    var functions = emptyList<Function>()
    var codeLocals = emptyList<CodeLocals>()
}

// ===[ STRG ]===
class Strg {
    var strings = emptyList<String>()
}

// ===[ TXTR ]===
class Texture(val scaled: Int, val blobOffset: Int, val blobSize: Int, val blobData: ByteArray?)

class Txtr {
    var textures = emptyList<Texture>()
}

// ===[ AUDO ]===
class AudioEntry(val dataSize: Int, val dataOffset: Int, val data: ByteArray?)

class Audo {
    var entries = emptyList<AudioEntry>()
}

// ===[ Top-level DataWin container ]===
class DataWin {
    var gen8 = Gen8()
    var optn = Optn()
    var lang = Lang()
    var extn = Extn()
    var sond = Sond()
    var agrp = Agrp()
    var sprt = Sprt()
    var bgnd = Bgnd()
    var path = PathChunk()
    var scpt = Scpt()
    var glob = Glob()
    var shdr = Shdr()
    var font = FontChunk()
    var tmln = Tmln()
    var objt = Objt()
    var room = RoomChunk()
    var tpag = Tpag()
    var code = Code()
    var vari = Vari()
    var func = Func()
    var strg = Strg()
    var txtr = Txtr()
    var audo = Audo()

    var bytecodeBuffer: ByteArray? = null
    var bytecodeBufferBase = 0

    val tpagOffsetMap = HashMap<Int, Int>()

    fun resolveTPAG(offset: Int): Int = tpagOffsetMap.getOrDefault(offset, -1)

    companion object {
        fun parse(filePath: String, options: DataWinParserOptions = DataWinParserOptions()): DataWin {
            val bytes = File(filePath).readBytes()
            val reader = BinaryReader(ByteBuffer.wrap(bytes))
            val dw = DataWin()

            // Validate FORM header
            val magic = reader.readChunkName()
            require(magic == "FORM") { "Invalid file: expected FORM magic, got '$magic'" }
            reader.readInt32() // form length

            // Single pass: since the entire file is in memory, readStringPtr can seek
            // to any offset at any time. No need for a separate STRG pre-load pass.
            while (reader.size > reader.position) {
                if (reader.position + 8 > reader.size) break
                val chunkName = reader.readChunkName()
                val chunkLength = reader.readInt32()
                val chunkDataStart = reader.position
                val chunkEnd = chunkDataStart + chunkLength

                when {
                    options.parseGen8 && chunkName == "GEN8" -> parseGEN8(reader, dw)
                    options.parseOptn && chunkName == "OPTN" -> parseOPTN(reader, dw)
                    options.parseLang && chunkName == "LANG" -> parseLANG(reader, dw)
                    options.parseExtn && chunkName == "EXTN" -> parseEXTN(reader, dw)
                    options.parseSond && chunkName == "SOND" -> parseSOND(reader, dw)
                    options.parseAgrp && chunkName == "AGRP" -> parseAGRP(reader, dw)
                    options.parseSprt && chunkName == "SPRT" -> parseSPRT(reader, dw, options.skipLoadingPreciseMasksForNonPreciseSprites)
                    options.parseBgnd && chunkName == "BGND" -> parseBGND(reader, dw)
                    options.parsePath && chunkName == "PATH" -> parsePATH(reader, dw)
                    options.parseScpt && chunkName == "SCPT" -> parseSCPT(reader, dw)
                    options.parseGlob && chunkName == "GLOB" -> parseGLOB(reader, dw)
                    options.parseShdr && chunkName == "SHDR" -> parseSHDR(reader, dw)
                    options.parseFont && chunkName == "FONT" -> parseFONT(reader, dw)
                    options.parseTmln && chunkName == "TMLN" -> parseTMLN(reader, dw)
                    options.parseObjt && chunkName == "OBJT" -> parseOBJT(reader, dw)
                    options.parseRoom && chunkName == "ROOM" -> parseROOM(reader, dw)
                    chunkName == "DAFL" -> { /* empty chunk */ }
                    options.parseTpag && chunkName == "TPAG" -> parseTPAG(reader, dw)
                    options.parseCode && chunkName == "CODE" -> parseCODE(reader, dw, chunkLength, chunkDataStart)
                    options.parseVari && chunkName == "VARI" -> parseVARI(reader, dw, chunkLength)
                    options.parseFunc && chunkName == "FUNC" -> parseFUNC(reader, dw)
                    options.parseStrg && chunkName == "STRG" -> parseSTRG(reader, dw)
                    options.parseTxtr && chunkName == "TXTR" -> parseTXTR(reader, dw, chunkEnd)
                    options.parseAudo && chunkName == "AUDO" -> parseAUDO(reader, dw)
                    else -> println("Unknown chunk: $chunkName (length $chunkLength at offset 0x${(chunkDataStart - 8).toString(16)})")
                }

                reader.position = chunkEnd
            }

            return dw
        }

        // ===[ Helpers ]===

        private fun readEventActions(reader: BinaryReader): List<EventAction> {
            val ptrs = reader.readPointerTable()
            if (ptrs.isEmpty()) return emptyList()
            return ptrs.map { ptr ->
                reader.position = ptr
                EventAction().apply {
                    libID = reader.readInt32()
                    id = reader.readInt32()
                    kind = reader.readInt32()
                    useRelative = reader.readBool32()
                    isQuestion = reader.readBool32()
                    useApplyTo = reader.readBool32()
                    exeType = reader.readInt32()
                    actionName = reader.readStringPtr()
                    codeId = reader.readInt32()
                    argumentCount = reader.readInt32()
                    who = reader.readInt32()
                    relative = reader.readBool32()
                    isNot = reader.readBool32()
                    unknownAlwaysZero = reader.readInt32()
                }
            }
        }

        // ===[ Chunk Parsers ]===

        private fun parseGEN8(reader: BinaryReader, dw: DataWin) {
            val g = dw.gen8
            g.isDebuggerDisabled = reader.readUint8()
            g.bytecodeVersion = reader.readUint8()
            reader.skip(2) // padding
            g.fileName = reader.readStringPtr()
            g.config = reader.readStringPtr()
            g.lastObj = reader.readInt32()
            g.lastTile = reader.readInt32()
            g.gameID = reader.readInt32()
            g.directPlayGuid = reader.readBytes(16)
            g.name = reader.readStringPtr()
            g.major = reader.readInt32()
            g.minor = reader.readInt32()
            g.release = reader.readInt32()
            g.build = reader.readInt32()
            g.defaultWindowWidth = reader.readInt32()
            g.defaultWindowHeight = reader.readInt32()
            g.info = reader.readInt32()
            g.licenseCRC32 = reader.readInt32()
            g.licenseMD5 = reader.readBytes(16)
            g.timestamp = reader.readLong()
            g.displayName = reader.readStringPtr()
            g.activeTargets = reader.readLong()
            g.functionClassifications = reader.readLong()
            g.steamAppID = reader.readInt32()
            g.debuggerPort = reader.readInt32()
            val roomOrderCount = reader.readInt32()
            g.roomOrder = IntArray(roomOrderCount) { reader.readInt32() }
        }

        private fun parseOPTN(reader: BinaryReader, dw: DataWin) {
            val o = dw.optn
            val marker = reader.readInt32()
            require(marker == Int.MIN_VALUE) { "OPTN: expected new format marker 0x80000000, got 0x${marker.toUInt().toString(16)}" }
            reader.readInt32() // shaderExtVersion (always 2)
            o.info = reader.readLong()
            o.scale = reader.readInt32()
            o.windowColor = reader.readInt32()
            o.colorDepth = reader.readInt32()
            o.resolution = reader.readInt32()
            o.frequency = reader.readInt32()
            o.vertexSync = reader.readInt32()
            o.priority = reader.readInt32()
            o.backImage = reader.readInt32()
            o.frontImage = reader.readInt32()
            o.loadImage = reader.readInt32()
            o.loadAlpha = reader.readInt32()
            val constantCount = reader.readInt32()
            o.constants = List(constantCount) { OptnConstant(reader.readStringPtr(), reader.readStringPtr()) }
        }

        private fun parseLANG(reader: BinaryReader, dw: DataWin) {
            val l = dw.lang
            l.unknown1 = reader.readInt32()
            val languageCount = reader.readInt32()
            val entryCount = reader.readInt32()
            l.entryIds = List(entryCount) { reader.readStringPtr() }
            l.languages = List(languageCount) {
                val name = reader.readStringPtr()
                val region = reader.readStringPtr()
                Language(name, region, List(entryCount) { reader.readStringPtr() })
            }
        }

        private fun parseEXTN(reader: BinaryReader, dw: DataWin) {
            val extPtrs = reader.readPointerTable()
            dw.extn.extensions = extPtrs.map { extPtr ->
                reader.position = extPtr
                val folderName = reader.readStringPtr()
                val name = reader.readStringPtr()
                val className = reader.readStringPtr()
                val filePtrs = reader.readPointerTable()
                val files = filePtrs.map { filePtr ->
                    reader.position = filePtr
                    val filename = reader.readStringPtr()
                    val cleanupScript = reader.readStringPtr()
                    val initScript = reader.readStringPtr()
                    val kind = reader.readInt32()
                    val funcPtrs = reader.readPointerTable()
                    val functions = funcPtrs.map { funcPtr ->
                        reader.position = funcPtr
                        val fName = reader.readStringPtr()
                        val id = reader.readInt32()
                        val fKind = reader.readInt32()
                        val retType = reader.readInt32()
                        val extName = reader.readStringPtr()
                        val argCount = reader.readInt32()
                        val arguments = IntArray(argCount) { reader.readInt32() }
                        ExtensionFunction(fName, id, fKind, retType, extName, arguments)
                    }
                    ExtensionFile(filename, cleanupScript, initScript, kind, functions)
                }
                Extension(folderName, name, className, files)
            }
        }

        private fun parseSOND(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.sond.sounds = ptrs.map { ptr ->
                reader.position = ptr
                val name = reader.readStringPtr()
                val flags = reader.readInt32()
                val type = reader.readStringPtr()
                val file = reader.readStringPtr()
                val effects = reader.readInt32()
                val volume = reader.readFloat32()
                val pitch = reader.readFloat32()
                val audioGroup = if ((flags and 0x64) == 0x64) {
                    reader.readInt32()
                } else {
                    reader.readInt32() // preload, discard
                    0
                }
                val audioFile = reader.readInt32()
                Sound(name, flags, type, file, effects, volume, pitch, audioGroup, audioFile)
            }
        }

        private fun parseAGRP(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.agrp.audioGroups = ptrs.map { ptr ->
                reader.position = ptr
                reader.readStringPtr()
            }
        }

        private fun parseSPRT(reader: BinaryReader, dw: DataWin, skipNonPrecise: Boolean) {
            val ptrs = reader.readPointerTable()
            dw.sprt.sprites = ptrs.map { ptr ->
                reader.position = ptr
                Sprite().apply {
                    name = reader.readStringPtr()
                    width = reader.readInt32()
                    height = reader.readInt32()
                    marginLeft = reader.readInt32()
                    marginRight = reader.readInt32()
                    marginBottom = reader.readInt32()
                    marginTop = reader.readInt32()
                    transparent = reader.readBool32()
                    smooth = reader.readBool32()
                    preload = reader.readBool32()
                    bboxMode = reader.readInt32()
                    sepMasks = reader.readInt32()
                    originX = reader.readInt32()
                    originY = reader.readInt32()

                    // Detect special type vs normal
                    val check = reader.readInt32()
                    require(check != -1) { "SPRT: unexpected special type sprite '$name' (GMS2 format not supported)" }

                    textureOffsets = IntArray(check) { reader.readInt32() }

                    // Collision masks
                    val maskDataCount = reader.readInt32()
                    if (maskDataCount > 0 && width > 0 && height > 0) {
                        val bytesPerRow = (width + 7) / 8
                        val bytesPerMask = bytesPerRow * height
                        if (sepMasks == 1 || !skipNonPrecise) {
                            masks = List(maskDataCount) {
                                val mask = reader.readBytes(bytesPerMask)
                                val remainder = bytesPerMask % 4
                                if (remainder != 0) reader.skip(4 - remainder)
                                mask
                            }
                        } else {
                            repeat(maskDataCount) {
                                reader.skip(bytesPerMask)
                                val remainder = bytesPerMask % 4
                                if (remainder != 0) reader.skip(4 - remainder)
                            }
                        }
                    }
                }
            }
        }

        private fun parseBGND(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.bgnd.backgrounds = ptrs.map { ptr ->
                reader.position = ptr
                Background(
                    reader.readStringPtr(),
                    reader.readBool32(),
                    reader.readBool32(),
                    reader.readBool32(),
                    reader.readInt32()
                )
            }
        }

        private fun parsePATH(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.path.paths = ptrs.map { ptr ->
                reader.position = ptr
                GamePath().apply {
                    name = reader.readStringPtr()
                    isSmooth = reader.readBool32()
                    isClosed = reader.readBool32()
                    precision = reader.readInt32()
                    val pointCount = reader.readInt32()
                    points = List(pointCount) {
                        PathPoint(reader.readFloat32(), reader.readFloat32(), reader.readFloat32())
                    }
                    computeInternal()
                }
            }
        }

        private fun parseSCPT(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.scpt.scripts = ptrs.map { ptr ->
                reader.position = ptr
                Script(reader.readStringPtr(), reader.readInt32())
            }
        }

        private fun parseGLOB(reader: BinaryReader, dw: DataWin) {
            val count = reader.readInt32()
            dw.glob.codeIds = IntArray(count) { reader.readInt32() }
        }

        private fun parseSHDR(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.shdr.shaders = ptrs.map { ptr ->
                reader.position = ptr
                Shader().apply {
                    name = reader.readStringPtr()
                    type = reader.readInt32() and 0x7FFFFFFF
                    glslES_Vertex = reader.readStringPtr()
                    glslES_Fragment = reader.readStringPtr()
                    glsl_Vertex = reader.readStringPtr()
                    glsl_Fragment = reader.readStringPtr()
                    hlsl9_Vertex = reader.readStringPtr()
                    hlsl9_Fragment = reader.readStringPtr()
                    hlsl11_VertexOffset = reader.readInt32()
                    hlsl11_PixelOffset = reader.readInt32()
                    val attrCount = reader.readInt32()
                    vertexAttributes = List(attrCount) { reader.readStringPtr() }
                    version = reader.readInt32()
                    pssl_VertexOffset = reader.readInt32()
                    pssl_VertexLen = reader.readInt32()
                    pssl_PixelOffset = reader.readInt32()
                    pssl_PixelLen = reader.readInt32()
                    cgVita_VertexOffset = reader.readInt32()
                    cgVita_VertexLen = reader.readInt32()
                    cgVita_PixelOffset = reader.readInt32()
                    cgVita_PixelLen = reader.readInt32()
                    if (version >= 2) {
                        cgPS3_VertexOffset = reader.readInt32()
                        cgPS3_VertexLen = reader.readInt32()
                        cgPS3_PixelOffset = reader.readInt32()
                        cgPS3_PixelLen = reader.readInt32()
                    }
                }
            }
        }

        private fun parseFONT(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.font.fonts = ptrs.map { ptr ->
                reader.position = ptr
                Font().apply {
                    name = reader.readStringPtr()
                    displayName = reader.readStringPtr()
                    emSize = reader.readInt32()
                    bold = reader.readBool32()
                    italic = reader.readBool32()
                    rangeStart = reader.readUint16()
                    charset = reader.readUint8()
                    antiAliasing = reader.readUint8()
                    rangeEnd = reader.readInt32()
                    textureOffset = reader.readInt32()
                    scaleX = reader.readFloat32()
                    scaleY = reader.readFloat32()
                    val glyphPtrs = reader.readPointerTable()
                    glyphs = glyphPtrs.map { glyphPtr ->
                        reader.position = glyphPtr
                        FontGlyph().apply {
                            character = reader.readUint16()
                            sourceX = reader.readUint16()
                            sourceY = reader.readUint16()
                            sourceWidth = reader.readUint16()
                            sourceHeight = reader.readUint16()
                            shift = reader.readInt16()
                            offset = reader.readInt16()
                            val kerningCount = reader.readUint16()
                            kerning = List(kerningCount) {
                                KerningPair(reader.readInt16(), reader.readInt16())
                            }
                        }
                    }
                }
            }
        }

        private fun parseTMLN(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.tmln.timelines = ptrs.map { ptr ->
                reader.position = ptr
                val name = reader.readStringPtr()
                val momentCount = reader.readInt32()
                if (momentCount > 0) {
                    // Pass 1: read step + event pointer pairs
                    val moments = Array(momentCount) {
                        Pair(reader.readInt32(), reader.readInt32()) // step, eventPtr
                    }
                    // Pass 2: parse event actions
                    Timeline(name, moments.map { (step, eventPtr) ->
                        reader.position = eventPtr
                        TimelineMoment(step, readEventActions(reader))
                    })
                } else {
                    Timeline(name, emptyList())
                }
            }
        }

        private fun parseOBJT(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.objt.objects = ptrs.map { ptr ->
                reader.position = ptr
                GameObject().apply {
                    name = reader.readStringPtr()
                    spriteId = reader.readInt32()
                    visible = reader.readBool32()
                    solid = reader.readBool32()
                    depth = reader.readInt32()
                    persistent = reader.readBool32()
                    parentId = reader.readInt32()
                    textureMaskId = reader.readInt32()
                    usesPhysics = reader.readBool32()
                    isSensor = reader.readBool32()
                    collisionShape = reader.readInt32()
                    density = reader.readFloat32()
                    restitution = reader.readFloat32()
                    group = reader.readInt32()
                    linearDamping = reader.readFloat32()
                    angularDamping = reader.readFloat32()
                    physicsVertexCount = reader.readInt32()
                    friction = reader.readFloat32()
                    awake = reader.readBool32()
                    kinematic = reader.readBool32()

                    if (physicsVertexCount > 0) {
                        physicsVertices = List(physicsVertexCount) {
                            PhysicsVertex(reader.readFloat32(), reader.readFloat32())
                        }
                    }

                    // Events: PointerList<PointerList<Event>>
                    val eventTypePtrs = reader.readPointerTable()
                    for (eventType in eventTypePtrs.indices) {
                        if (OBJT_EVENT_TYPE_COUNT > eventType) {
                            reader.position = eventTypePtrs[eventType]
                            val eventPtrs = reader.readPointerTable()
                            eventLists[eventType] = ObjectEventList(eventPtrs.map { eventPtr ->
                                reader.position = eventPtr
                                ObjectEvent(reader.readInt32(), readEventActions(reader))
                            })
                        }
                    }
                }
            }
        }

        private fun parseROOM(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.room.rooms = ptrs.map { ptr ->
                reader.position = ptr
                Room().apply {
                    name = reader.readStringPtr()
                    caption = reader.readStringPtr()
                    width = reader.readInt32()
                    height = reader.readInt32()
                    speed = reader.readInt32()
                    persistent = reader.readBool32()
                    backgroundColor = reader.readInt32()
                    drawBackgroundColor = reader.readBool32()
                    creationCodeId = reader.readInt32()
                    flags = reader.readInt32()
                    val backgroundsPtr = reader.readInt32()
                    val viewsPtr = reader.readInt32()
                    val gameObjectsPtr = reader.readInt32()
                    val tilesPtr = reader.readInt32()
                    world = reader.readBool32()
                    top = reader.readInt32()
                    left = reader.readInt32()
                    right = reader.readInt32()
                    bottom = reader.readInt32()
                    gravityX = reader.readFloat32()
                    gravityY = reader.readFloat32()
                    metersPerPixel = reader.readFloat32()

                    // Backgrounds (always 8)
                    reader.position = backgroundsPtr
                    val bgPtrs = reader.readPointerTable()
                    for (j in bgPtrs.indices) {
                        if (8 > j) {
                            reader.position = bgPtrs[j]
                            backgrounds[j] = RoomBackground().apply {
                                enabled = reader.readBool32()
                                foreground = reader.readBool32()
                                backgroundDefinition = reader.readInt32()
                                x = reader.readInt32()
                                y = reader.readInt32()
                                tileX = reader.readInt32()
                                tileY = reader.readInt32()
                                speedX = reader.readInt32()
                                speedY = reader.readInt32()
                                stretch = reader.readBool32()
                            }
                        }
                    }

                    // Views (always 8)
                    reader.position = viewsPtr
                    val viewPtrs = reader.readPointerTable()
                    for (j in viewPtrs.indices) {
                        if (8 > j) {
                            reader.position = viewPtrs[j]
                            views[j] = RoomView().apply {
                                enabled = reader.readBool32()
                                viewX = reader.readInt32()
                                viewY = reader.readInt32()
                                viewWidth = reader.readInt32()
                                viewHeight = reader.readInt32()
                                portX = reader.readInt32()
                                portY = reader.readInt32()
                                portWidth = reader.readInt32()
                                portHeight = reader.readInt32()
                                borderX = reader.readInt32()
                                borderY = reader.readInt32()
                                speedX = reader.readInt32()
                                speedY = reader.readInt32()
                                objectId = reader.readInt32()
                            }
                        }
                    }

                    // Game Objects
                    reader.position = gameObjectsPtr
                    val objPtrs = reader.readPointerTable()
                    gameObjects = objPtrs.map { objPtr ->
                        reader.position = objPtr
                        RoomGameObject().apply {
                            x = reader.readInt32()
                            y = reader.readInt32()
                            objectDefinition = reader.readInt32()
                            instanceID = reader.readInt32()
                            creationCode = reader.readInt32()
                            scaleX = reader.readFloat32()
                            scaleY = reader.readFloat32()
                            color = reader.readInt32()
                            rotation = reader.readFloat32()
                            preCreateCode = reader.readInt32()
                        }
                    }

                    // Tiles
                    reader.position = tilesPtr
                    val tilePtrs = reader.readPointerTable()
                    tiles = tilePtrs.map { tilePtr ->
                        reader.position = tilePtr
                        RoomTile().apply {
                            x = reader.readInt32()
                            y = reader.readInt32()
                            backgroundDefinition = reader.readInt32()
                            sourceX = reader.readInt32()
                            sourceY = reader.readInt32()
                            width = reader.readInt32()
                            height = reader.readInt32()
                            tileDepth = reader.readInt32()
                            instanceID = reader.readInt32()
                            scaleX = reader.readFloat32()
                            scaleY = reader.readFloat32()
                            color = reader.readInt32()
                        }
                    }
                }
            }
        }

        private fun parseTPAG(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.tpag.items = ptrs.mapIndexed { i, ptr ->
                reader.position = ptr
                TexturePageItem().apply {
                    sourceX = reader.readUint16()
                    sourceY = reader.readUint16()
                    sourceWidth = reader.readUint16()
                    sourceHeight = reader.readUint16()
                    targetX = reader.readUint16()
                    targetY = reader.readUint16()
                    targetWidth = reader.readUint16()
                    targetHeight = reader.readUint16()
                    boundingWidth = reader.readUint16()
                    boundingHeight = reader.readUint16()
                    texturePageId = reader.readInt16()
                }
            }
            // Build offset -> index map
            for (i in ptrs.indices) {
                dw.tpagOffsetMap[ptrs[i]] = i
            }
        }

        private fun parseCODE(reader: BinaryReader, dw: DataWin, chunkLength: Int, chunkDataStart: Int) {
            if (chunkLength == 0) return // YYC-compiled, no bytecode

            val codePtrs = reader.readPointerTable()
            val entries = codePtrs.map { ptr ->
                reader.position = ptr
                val name = reader.readStringPtr()
                val length = reader.readInt32()
                val localsCount = reader.readUint16()
                val argumentsCount = reader.readUint16()
                val relAddrFieldPos = reader.position
                val bytecodeRelAddr = reader.readInt32()
                val bytecodeAbsoluteOffset = relAddrFieldPos + bytecodeRelAddr
                val offset = reader.readInt32()
                CodeEntry(name, length, localsCount, argumentsCount, bytecodeAbsoluteOffset, offset)
            }
            dw.code.entries = entries

            // Load bytecode blob
            if (entries.isNotEmpty()) {
                val blobStart = entries.minOf { it.bytecodeAbsoluteOffset }
                val chunkEnd = chunkDataStart + chunkLength
                val blobSize = chunkEnd - blobStart
                dw.bytecodeBufferBase = blobStart
                dw.bytecodeBuffer = reader.readBytesAt(blobStart, blobSize)
            }
        }

        private fun parseVARI(reader: BinaryReader, dw: DataWin, chunkLength: Int) {
            val v = dw.vari
            v.varCount1 = reader.readInt32()
            v.varCount2 = reader.readInt32()
            v.maxLocalVarCount = reader.readInt32()
            val variableCount = (chunkLength - 12) / 20
            v.variables = List(variableCount) {
                Variable(reader.readStringPtr(), reader.readInt32(), reader.readInt32(), reader.readInt32(), reader.readInt32())
            }
        }

        private fun parseFUNC(reader: BinaryReader, dw: DataWin) {
            val f = dw.func
            val funcCount = reader.readInt32()
            f.functions = List(funcCount) {
                Function(reader.readStringPtr(), reader.readInt32(), reader.readInt32())
            }
            val codeLocalsCount = reader.readInt32()
            f.codeLocals = List(codeLocalsCount) {
                val localVarCount = reader.readInt32()
                val name = reader.readStringPtr()
                CodeLocals(name, List(localVarCount) { LocalVar(reader.readInt32(), reader.readStringPtr()) })
            }
        }

        private fun parseSTRG(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.strg.strings = ptrs.map { ptr ->
                val saved = reader.position
                reader.position = ptr // points to length prefix
                val length = reader.readInt32()
                val bytes = reader.readBytes(length)
                reader.position = saved
                String(bytes, Charsets.UTF_8)
            }
        }

        private fun parseTXTR(reader: BinaryReader, dw: DataWin, chunkEnd: Int) {
            val ptrs = reader.readPointerTable()
            if (ptrs.isEmpty()) return

            // Read metadata
            data class TexMeta(val scaled: Int, val blobOffset: Int)
            val metas = ptrs.map { ptr ->
                reader.position = ptr
                TexMeta(reader.readInt32(), reader.readInt32())
            }

            // Compute blob sizes from successive offsets
            dw.txtr.textures = metas.mapIndexed { i, meta ->
                if (meta.blobOffset == 0) {
                    Texture(meta.scaled, 0, 0, null)
                } else {
                    val blobSize = if (metas.size > i + 1 && metas[i + 1].blobOffset != 0) {
                        metas[i + 1].blobOffset - meta.blobOffset
                    } else {
                        chunkEnd - meta.blobOffset
                    }
                    val blobData = if (blobSize > 0) reader.readBytesAt(meta.blobOffset, blobSize) else null
                    Texture(meta.scaled, meta.blobOffset, blobSize, blobData)
                }
            }
        }

        private fun parseAUDO(reader: BinaryReader, dw: DataWin) {
            val ptrs = reader.readPointerTable()
            dw.audo.entries = ptrs.map { ptr ->
                reader.position = ptr
                val dataSize = reader.readInt32()
                val dataOffset = reader.position
                val data = if (dataSize > 0) reader.readBytes(dataSize) else null
                AudioEntry(dataSize, dataOffset, data)
            }
        }
    }
}
