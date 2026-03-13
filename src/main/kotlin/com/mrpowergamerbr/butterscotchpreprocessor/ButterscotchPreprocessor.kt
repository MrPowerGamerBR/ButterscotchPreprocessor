package com.mrpowergamerbr.butterscotchpreprocessor

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

object ButterscotchPreprocessor {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: ButterscotchPreprocessor <path/to/data.win>")
            return
        }

        val dataWinPath = args[0]
        println("Parsing $dataWinPath...")

        val dw = DataWin.parse(dataWinPath, DataWinParserOptions(
            // Only parse what we need for sprite/background/font/tile dumping
            parseGen8 = true,
            parseOptn = false,
            parseLang = false,
            parseExtn = false,
            parseSond = false,
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
            parseAudo = false,
            skipLoadingPreciseMasksForNonPreciseSprites = true
        ))

        println("Parsed: ${dw.sprt.sprites.size} sprites, ${dw.bgnd.backgrounds.size} backgrounds, ${dw.font.fonts.size} fonts, ${dw.txtr.textures.size} textures, ${dw.tpag.items.size} TPAG items")

        // Load texture pages as BufferedImages
        println("Loading texture pages...")
        val texturePages = dw.txtr.textures.map { tex ->
            if (tex.blobData != null) {
                ImageIO.read(ByteArrayInputStream(tex.blobData))
            } else {
                null
            }
        }

        val outputDir = File("sprites_output")

        // Dump sprites
        dumpSprites(dw, texturePages, outputDir)

        // Dump backgrounds
        dumpBackgrounds(dw, texturePages, outputDir)

        // Dump fonts
        dumpFonts(dw, texturePages, outputDir)

        // Dump tiles
        dumpTiles(dw, texturePages, outputDir)

        println("Done!")
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
            // Destination: where in the output sprite (target = sprite-space offset)
            tpag.targetX, tpag.targetY,
            tpag.targetX + tpag.targetWidth, tpag.targetY + tpag.targetHeight,
            // Source: where on the texture page (source = texture page coords)
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

        // Collect unique tiles: (backgroundDefinition, sourceX, sourceY, width, height) -> first instance
        data class TileKey(val bgDef: Int, val srcX: Int, val srcY: Int, val w: Int, val h: Int)

        val uniqueTiles = LinkedHashMap<TileKey, RoomTile>()
        for (room in dw.room.rooms) {
            for (tile in room.tiles) {
                val key = TileKey(tile.backgroundDefinition, tile.sourceX, tile.sourceY, tile.width, tile.height)
                uniqueTiles.putIfAbsent(key, tile)
            }
        }

        // Pre-extract background images for tile cropping
        val bgImages = HashMap<Int, BufferedImage>()
        for ((key, _) in uniqueTiles) {
            if (bgImages.containsKey(key.bgDef)) continue
            if (0 > key.bgDef || key.bgDef >= dw.bgnd.backgrounds.size) continue
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

            val bgName = if (dw.bgnd.backgrounds.size > key.bgDef) dw.bgnd.backgrounds[key.bgDef].name ?: "bg${key.bgDef}" else "bg${key.bgDef}"
            ImageIO.write(outImg, "PNG", File(tilesDir, "${bgName}_${key.srcX}_${key.srcY}_${key.w}x${key.h}.png"))
            count++
        }
        println("  Dumped $count unique tiles to ${tilesDir.path}")
    }
}
