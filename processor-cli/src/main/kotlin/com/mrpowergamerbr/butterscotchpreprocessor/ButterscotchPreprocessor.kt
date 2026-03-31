package com.mrpowergamerbr.butterscotchpreprocessor

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories

class ButterscotchPreprocessor : CliktCommand(name = "butterscotch-preprocessor") {
    val dataWinPath by argument(help = "Path to the data.win file")
        .path(mustExist = true, canBeDir = false)

    val outputDir by option("-o", "--output", help = "Output directory for processed files")
        .path()
        .default(Path.of("output"))

    val debugOutputDir by option("--debug-output", help = "Directory for debug output (sprites, backgrounds, fonts, tiles, atlas debug images)")
        .path()

    override fun run() {
        val dataWinFile = dataWinPath.toFile()
        echo("Parsing $dataWinPath...")

        val bytes = dataWinFile.readBytes()
        val dataWinDir = dataWinFile.parentFile ?: File(".")
        val outputDirFile = outputDir.toFile()
        outputDir.createDirectories()

        // Debug dumps (JVM-only, using ImageIO)
        if (debugOutputDir != null) {
            val debugDir = debugOutputDir!!.toFile()
            debugDir.mkdirs()
            dumpDebugImages(bytes, debugDir)
        }

        // Load external audio files from the same directory as data.win
        val externalAudioFiles = loadExternalAudioFiles(dataWinDir)

        // Load audiogroup files (audiogroup%d.dat) from the same directory as data.win
        val audioGroupFiles = loadAudioGroupFiles(dataWinDir)

        // Core processing via common pipeline
        val result = runBlocking {
            processDataWin(bytes, externalAudioFiles, audioGroupFiles) { echo(it) }
        }

        // Write output files
        File(outputDirFile, "CLUT4.BIN").writeBytes(result.clut4Bin)
        File(outputDirFile, "CLUT8.BIN").writeBytes(result.clut8Bin)
        File(outputDirFile, "TEXTURES.BIN").writeBytes(result.texturesBin)
        File(outputDirFile, "ATLAS.BIN").writeBytes(result.atlasBin)
        File(outputDirFile, "SOUNDBNK.BIN").writeBytes(result.soundBnkBin)
        File(outputDirFile, "SOUNDS.BIN").writeBytes(result.soundsBin)

        // Dump debug atlas images
        if (debugOutputDir != null) {
            val debugDir = debugOutputDir!!.toFile()
            val atlasDebugDir = File(debugDir, "atlas_debug")
            atlasDebugDir.mkdirs()
            for (atlas in result.atlases) {
                val debugImg = renderAtlasDebug(atlas)
                ImageIO.write(debugImg, "PNG", File(atlasDebugDir, "atlas_${atlas.id}_${atlas.bpp}bpp.png"))
            }
            echo("  Dumped ${result.atlases.size} debug atlas images to ${atlasDebugDir.path}")
        }

        echo("\nAll files written to ${outputDirFile.path}")
        echo("Done!")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = ButterscotchPreprocessor().main(args)
    }

    private fun dumpDebugImages(dataWinBytes: ByteArray, outputDir: File) {
        val dw = DataWin.parse(dataWinBytes, DataWinParserOptions(
            parseGen8 = true,
            parseOptn = false,
            parseLang = false,
            parseExtn = false,
            parseSond = true,
            parseAgrp = false,
            parseSprt = true,
            parseBgnd = true,
            parsePath = false,
            parseScpt = false,
            parseGlob = false,
            parseShdr = false,
            parseFont = true,
            parseTmln = false,
            parseObjt = false,
            parseRoom = true,
            parseTpag = true,
            parseCode = false,
            parseVari = false,
            parseFunc = false,
            parseStrg = true,
            parseTxtr = true,
            parseAudo = true,
            skipLoadingPreciseMasksForNonPreciseSprites = true
        ))

        val texturePages = dw.txtr.textures.map { tex ->
            if (tex.blobData != null) {
                ImageIO.read(ByteArrayInputStream(tex.blobData))
            } else {
                null
            }
        }

        dumpSprites(dw, texturePages, outputDir)
        dumpBackgrounds(dw, texturePages, outputDir)
        dumpFonts(dw, texturePages, outputDir)
        dumpTiles(dw, texturePages, outputDir)
    }

    private fun extractFromTPAG(tpag: TexturePageItem, texturePages: List<BufferedImage?>): BufferedImage {
        val w = maxOf(tpag.boundingWidth, 1)
        val h = maxOf(tpag.boundingHeight, 1)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

        if (tpag.texturePageId < 0) return img
        val texPage = texturePages.getOrNull(tpag.texturePageId.toInt()) ?: return img
        if (tpag.targetWidth == 0 || tpag.targetHeight == 0) return img

        val g = img.createGraphics()
        g.drawImage(
            texPage,
            tpag.targetX, tpag.targetY,
            tpag.targetX + tpag.targetWidth, tpag.targetY + tpag.targetHeight,
            tpag.sourceX, tpag.sourceY,
            tpag.sourceX + tpag.sourceWidth, tpag.sourceY + tpag.sourceHeight,
            null
        )
        g.dispose()
        return img
    }

    private fun dumpSprites(dw: DataWin, texturePages: List<BufferedImage?>, outputDir: File) {
        val spritesDir = File(outputDir, "sprites")
        spritesDir.mkdirs()

        var count = 0
        for (sprite in dw.sprt.sprites) {
            val name = sprite.name ?: "sprite_$count"
            for ((frameIdx, texOffset) in sprite.textureOffsets.withIndex()) {
                val tpagIdx = dw.resolveTPAG(texOffset)
                if (0 > tpagIdx) continue
                val tpag = dw.tpag.items[tpagIdx]
                val img = extractFromTPAG(tpag, texturePages)

                val filename = if (sprite.textureOffsets.size > 1) "${name}_$frameIdx.png" else "$name.png"
                ImageIO.write(img, "PNG", File(spritesDir, filename))
            }
            count++
        }
        println("  Dumped $count sprites to ${spritesDir.path}")
    }

    private fun dumpBackgrounds(dw: DataWin, texturePages: List<BufferedImage?>, outputDir: File) {
        val bgDir = File(outputDir, "backgrounds")
        bgDir.mkdirs()

        var count = 0
        for (bg in dw.bgnd.backgrounds) {
            val name = bg.name ?: "bg_$count"
            val tpagIdx = dw.resolveTPAG(bg.textureOffset)
            if (0 > tpagIdx) { count++; continue }
            val tpag = dw.tpag.items[tpagIdx]
            val img = extractFromTPAG(tpag, texturePages)
            ImageIO.write(img, "PNG", File(bgDir, "$name.png"))
            count++
        }
        println("  Dumped $count backgrounds to ${bgDir.path}")
    }

    private fun dumpFonts(dw: DataWin, texturePages: List<BufferedImage?>, outputDir: File) {
        val fontDir = File(outputDir, "fonts")
        fontDir.mkdirs()

        var count = 0
        for (font in dw.font.fonts) {
            val name = font.name ?: "font_$count"
            val tpagIdx = dw.resolveTPAG(font.textureOffset)
            if (0 > tpagIdx) { count++; continue }
            val tpag = dw.tpag.items[tpagIdx]
            val img = extractFromTPAG(tpag, texturePages)
            ImageIO.write(img, "PNG", File(fontDir, "$name.png"))
            count++
        }
        println("  Dumped $count fonts to ${fontDir.path}")
    }

    private fun dumpTiles(dw: DataWin, texturePages: List<BufferedImage?>, outputDir: File) {
        val tilesDir = File(outputDir, "tiles")
        tilesDir.mkdirs()

        data class TileKey(val bgDef: Int, val srcX: Int, val srcY: Int, val w: Int, val h: Int)
        val uniqueTiles = LinkedHashMap<TileKey, RoomTile>()
        for (room in dw.room.rooms) {
            for (tile in room.tiles) {
                if (0 > tile.backgroundDefinition || tile.backgroundDefinition >= dw.bgnd.backgrounds.size) continue
                val key = TileKey(tile.backgroundDefinition, tile.sourceX, tile.sourceY, tile.width, tile.height)
                uniqueTiles.putIfAbsent(key, tile)
            }
        }

        val bgImages = HashMap<Int, BufferedImage>()
        for ((key, _) in uniqueTiles) {
            if (bgImages.containsKey(key.bgDef)) continue
            val bg = dw.bgnd.backgrounds[key.bgDef]
            val tpagIdx = dw.resolveTPAG(bg.textureOffset)
            if (0 > tpagIdx) continue
            bgImages[key.bgDef] = extractFromTPAG(dw.tpag.items[tpagIdx], texturePages)
        }

        var count = 0
        for ((key, _) in uniqueTiles) {
            val bgImg = bgImages[key.bgDef] ?: continue
            if (key.srcX + key.w > bgImg.width || key.srcY + key.h > bgImg.height) continue
            if (key.w == 0 || key.h == 0) continue

            val tileImg = bgImg.getSubimage(key.srcX, key.srcY, key.w, key.h)
            val outImg = BufferedImage(key.w, key.h, BufferedImage.TYPE_INT_ARGB)
            val g = outImg.createGraphics()
            g.drawImage(tileImg, 0, 0, null)
            g.dispose()

            val bgName = dw.bgnd.backgrounds[key.bgDef].name ?: "bg${key.bgDef}"
            ImageIO.write(outImg, "PNG", File(tilesDir, "${bgName}_${key.srcX}_${key.srcY}_${key.w}x${key.h}.png"))
            count++
        }
        println("  Dumped $count unique tiles to ${tilesDir.path}")
    }

    // Scan a directory for audiogroup%d.dat files and load them into a map keyed by group ID
    // Audiogroup 0 is embedded in data.win, so we skip it
    private fun loadAudioGroupFiles(dir: File): Map<Int, ByteArray> {
        val result = HashMap<Int, ByteArray>()
        val files = dir.listFiles() ?: return result
        val pattern = Regex("""audiogroup(\d+)\.dat""", RegexOption.IGNORE_CASE)
        for (file in files) {
            val match = pattern.matchEntire(file.name) ?: continue
            val groupId = match.groupValues[1].toInt()
            if (groupId == 0) continue // audiogroup 0 is in data.win
            result[groupId] = file.readBytes()
        }
        if (result.isNotEmpty()) {
            println("Found ${result.size} audiogroup files in ${dir.path}: ${result.keys.sorted().joinToString { "audiogroup$it.dat" }}")
        }
        return result
    }

    // Scan a directory for audio files (OGG, WAV) and load them into a map keyed by filename
    private fun loadExternalAudioFiles(dir: File): Map<String, ByteArray> {
        val result = HashMap<String, ByteArray>()
        val files = dir.listFiles() ?: return result
        for (file in files) {
            val name = file.name.lowercase()
            if (name.endsWith(".ogg") || name.endsWith(".wav")) {
                result[file.name] = file.readBytes()
            }
        }
        if (result.isNotEmpty()) {
            println("Found ${result.size} external audio files in ${dir.path}")
        }
        return result
    }

    // Render an atlas to a BufferedImage for debugging/verification
    fun renderAtlas(atlas: TextureAtlas): BufferedImage {
        val img = BufferedImage(atlas.width, atlas.height, BufferedImage.TYPE_INT_ARGB)
        for (entry in atlas.entries) {
            val rendered = ClutProcessor.renderClutImage(entry.image)
            val pixels = rendered.pixels
            img.setRGB(entry.x, entry.y, rendered.width, rendered.height, pixels, 0, rendered.width)
        }
        return img
    }

    // Render an atlas with colored outlines around each entry for debugging
    fun renderAtlasDebug(atlas: TextureAtlas): BufferedImage {
        val img = renderAtlas(atlas)
        val g = img.createGraphics()

        val colors = arrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.ORANGE)
        for ((i, entry) in atlas.entries.withIndex()) {
            g.color = colors[i % colors.size]
            g.drawRect(entry.x, entry.y, entry.image.width - 1, entry.image.height - 1)
        }

        g.dispose()
        return img
    }
}
