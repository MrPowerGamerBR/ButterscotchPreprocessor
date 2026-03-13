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

        // Dump raw PNGs
        dumpSprites(dw, texturePages, outputDir)
        dumpBackgrounds(dw, texturePages, outputDir)
        dumpFonts(dw, texturePages, outputDir)
        dumpTiles(dw, texturePages, outputDir)

        // CLUT processing for PS2
        println("\n=== CLUT Processing ===")
        processCluts(dw, texturePages, outputDir)

        println("\nDone!")
    }

    private fun processCluts(dw: DataWin, texturePages: List<BufferedImage?>, outputDir: File) {
        val allImages = mutableListOf<Pair<String, BufferedImage>>()

        // Collect sprites
        for ((sprIdx, sprite) in dw.sprt.sprites.withIndex()) {
            val name = sprite.name ?: "sprite_$sprIdx"
            for ((frameIdx, texOffset) in sprite.textureOffsets.withIndex()) {
                val tpagIdx = dw.resolveTPAG(texOffset)
                if (0 > tpagIdx) continue
                val img = extractFromTPAG(dw.tpag.items[tpagIdx], texturePages)
                val imgName = if (sprite.textureOffsets.size > 1) "spr/${name}_$frameIdx" else "spr/$name"
                allImages.add(imgName to img)
            }
        }

        // Collect backgrounds
        for ((bgIdx, bg) in dw.bgnd.backgrounds.withIndex()) {
            val name = bg.name ?: "bg_$bgIdx"
            val tpagIdx = dw.resolveTPAG(bg.textureOffset)
            if (0 > tpagIdx) continue
            allImages.add("bg/$name" to extractFromTPAG(dw.tpag.items[tpagIdx], texturePages))
        }

        // Collect unique tiles
        data class TileKey(val bgDef: Int, val srcX: Int, val srcY: Int, val w: Int, val h: Int)
        val uniqueTiles = LinkedHashMap<TileKey, RoomTile>()
        for (room in dw.room.rooms) {
            for (tile in room.tiles) {
                uniqueTiles.putIfAbsent(
                    TileKey(tile.backgroundDefinition, tile.sourceX, tile.sourceY, tile.width, tile.height),
                    tile
                )
            }
        }
        val bgImages = HashMap<Int, BufferedImage>()
        for ((key, _) in uniqueTiles) {
            if (bgImages.containsKey(key.bgDef)) continue
            if (0 > key.bgDef || key.bgDef >= dw.bgnd.backgrounds.size) continue
            val bg = dw.bgnd.backgrounds[key.bgDef]
            val tpagIdx = dw.resolveTPAG(bg.textureOffset)
            if (0 > tpagIdx) continue
            bgImages[key.bgDef] = extractFromTPAG(dw.tpag.items[tpagIdx], texturePages)
        }
        for ((key, _) in uniqueTiles) {
            val bgImg = bgImages[key.bgDef] ?: continue
            if (key.srcX + key.w > bgImg.width || key.srcY + key.h > bgImg.height) continue
            if (key.w == 0 || key.h == 0) continue
            val tileImg = BufferedImage(key.w, key.h, BufferedImage.TYPE_INT_ARGB)
            val g = tileImg.createGraphics()
            g.drawImage(bgImg.getSubimage(key.srcX, key.srcY, key.w, key.h), 0, 0, null)
            g.dispose()
            val bgName = if (dw.bgnd.backgrounds.size > key.bgDef) dw.bgnd.backgrounds[key.bgDef].name ?: "bg${key.bgDef}" else "bg${key.bgDef}"
            allImages.add("tile/${bgName}_${key.srcX}_${key.srcY}_${key.w}x${key.h}" to tileImg)
        }

        println("Total images to process: ${allImages.size}")

        // Step 1: Create CLUTs for each image
        println("Creating CLUTs...")
        val clutImages = mutableListOf<ClutImage>()
        var quantizedCount = 0
        for ((name, img) in allImages) {
            val pixels = IntArray(img.width * img.height)
            img.getRGB(0, 0, img.width, img.height, pixels, 0, img.width)
            // Normalize transparent
            for (i in pixels.indices) {
                if ((pixels[i] ushr 24) == 0) pixels[i] = 0
            }
            val uniqueCount = pixels.toHashSet().size
            if (uniqueCount > 256) quantizedCount++

            val clutImage = ClutProcessor.createClutImage(name, img)
            clutImages.add(clutImage)
        }

        val bpp4Count = clutImages.count { it.bpp == 4 }
        val bpp8Count = clutImages.count { it.bpp == 8 }
        println("  4bpp: $bpp4Count images, 8bpp: $bpp8Count images ($quantizedCount required NeuQuant)")

        // Step 2: Deduplicate CLUTs
        println("Deduplicating CLUTs...")
        val dedupGroups = ClutProcessor.deduplicateCluts(clutImages)
        val dedup4 = dedupGroups.count { it.bpp == 4 }
        val dedup8 = dedupGroups.count { it.bpp == 8 }
        println("  After dedup: $dedup4 unique 4bpp CLUTs, $dedup8 unique 8bpp CLUTs (${dedupGroups.size} total, from ${clutImages.size} images)")

        // Step 3: Merge CLUTs
        println("Merging CLUTs...")
        val mergedGroups = ClutProcessor.mergeCluts(clutImages, dedupGroups)
        val merged4 = mergedGroups.count { it.bpp == 4 }
        val merged8 = mergedGroups.count { it.bpp == 8 }
        println("  After merge: $merged4 merged 4bpp CLUTs, $merged8 merged 8bpp CLUTs (${mergedGroups.size} total)")

        // Print merge stats
        for (group in mergedGroups) {
            val maxSlots = if (group.bpp == 4) 16 else 256
            val freeSlots = maxSlots - group.colors.size
            if (group.imageNames.size > 1) {
                println("    CLUT #${group.id} (${group.bpp}bpp): ${group.colors.size}/$maxSlots colors, ${group.imageNames.size} images, $freeSlots free slots")
            }
        }

        // Verify: render a few CLUT images back to PNG for visual comparison
        val verifyDir = File(outputDir, "clut_verify")
        verifyDir.mkdirs()
        // Verify: pick images that were quantized (NeuQuant) by checking if they
        // originally had > 256 colors (we can detect this by checking the name is a bg/)
        // For simplicity, just verify some 4bpp and 8bpp
        val samplesToVerify = clutImages.filter { it.bpp == 4 }.take(5) +
            clutImages.filter { it.bpp == 8 && it.name.startsWith("bg/") }.take(5) +
            clutImages.filter { it.bpp == 8 && it.name.startsWith("spr/") }.take(5)
        for (clutImage in samplesToVerify) {
            val rendered = ClutProcessor.renderClutImage(clutImage)
            val safeName = clutImage.name.replace("/", "_")
            ImageIO.write(rendered, "PNG", File(verifyDir, "${safeName}_${clutImage.bpp}bpp.png"))
        }
        println("  Wrote ${samplesToVerify.size} verification images to ${verifyDir.path}")

        // Print summary
        println("\n=== CLUT Summary ===")
        println("  Images: ${clutImages.size}")
        println("  Unique CLUTs after dedup: ${dedupGroups.size}")
        println("  CLUTs after merge: ${mergedGroups.size}")
        val total4Colors = mergedGroups.filter { it.bpp == 4 }.sumOf { it.colors.size }
        val total8Colors = mergedGroups.filter { it.bpp == 8 }.sumOf { it.colors.size }
        println("  Total 4bpp palette entries: $total4Colors (across $merged4 CLUTs)")
        println("  Total 8bpp palette entries: $total8Colors (across $merged8 CLUTs)")
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

        data class TileKey(val bgDef: Int, val srcX: Int, val srcY: Int, val w: Int, val h: Int)

        val uniqueTiles = LinkedHashMap<TileKey, RoomTile>()
        for (room in dw.room.rooms) {
            for (tile in room.tiles) {
                val key = TileKey(tile.backgroundDefinition, tile.sourceX, tile.sourceY, tile.width, tile.height)
                uniqueTiles.putIfAbsent(key, tile)
            }
        }

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
