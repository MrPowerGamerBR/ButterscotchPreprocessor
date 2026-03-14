package com.mrpowergamerbr.butterscotchpreprocessor

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

private data class TileKey(val bgDef: Int, val srcX: Int, val srcY: Int, val w: Int, val h: Int)

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

        val outputDir = File("/home/mrpowergamerbr/Projects/Butterscotch/build-ps2/")

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
        // Maps image name to atlas group key (images with the same key should be in the same atlas)
        val atlasGroupKeys = HashMap<String, String>()
        // Maps image name to TPAG index (for ATLAS.BIN generation)
        val tpagIndexMap = HashMap<String, Int>()

        // Collect sprites - group all frames of a sprite under the same group key
        for ((sprIdx, sprite) in dw.sprt.sprites.withIndex()) {
            val name = sprite.name ?: "sprite_$sprIdx"
            val groupKey = "spr/$name"
            for ((frameIdx, texOffset) in sprite.textureOffsets.withIndex()) {
                val tpagIdx = dw.resolveTPAG(texOffset)
                if (0 > tpagIdx) continue
                val img = extractFromTPAG(dw.tpag.items[tpagIdx], texturePages)
                val imgName = if (sprite.textureOffsets.size > 1) "spr/${name}_$frameIdx" else "spr/$name"
                allImages.add(imgName to img)
                atlasGroupKeys[imgName] = groupKey
                tpagIndexMap[imgName] = tpagIdx
            }
        }

        // Collect backgrounds
        for ((bgIdx, bg) in dw.bgnd.backgrounds.withIndex()) {
            val name = bg.name ?: "bg_$bgIdx"
            val tpagIdx = dw.resolveTPAG(bg.textureOffset)
            if (0 > tpagIdx) continue
            val imgName = "bg/$name"
            allImages.add(imgName to extractFromTPAG(dw.tpag.items[tpagIdx], texturePages))
            atlasGroupKeys[imgName] = imgName
            tpagIndexMap[imgName] = tpagIdx
        }

        // Collect fonts
        for ((fontIdx, font) in dw.font.fonts.withIndex()) {
            val name = font.name ?: "font_$fontIdx"
            val tpagIdx = dw.resolveTPAG(font.textureOffset)
            if (0 > tpagIdx) continue
            val imgName = "font/$name"
            allImages.add(imgName to extractFromTPAG(dw.tpag.items[tpagIdx], texturePages))
            atlasGroupKeys[imgName] = imgName
            tpagIndexMap[imgName] = tpagIdx
        }

        // Collect unique tiles
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
            val imgName = "tile/${bgName}_${key.srcX}_${key.srcY}_${key.w}x${key.h}"
            allImages.add(imgName to tileImg)
            atlasGroupKeys[imgName] = imgName
        }

        // Resize any images exceeding 512x512 (nearest neighbor, preserving aspect ratio)
        val maxDim = 512
        var resizedCount = 0
        for (i in allImages.indices) {
            val (name, img) = allImages[i]
            if (maxDim >= img.width && maxDim >= img.height) continue
            val scale = minOf(maxDim.toDouble() / img.width, maxDim.toDouble() / img.height)
            val newW = maxOf((img.width * scale).toInt(), 1)
            val newH = maxOf((img.height * scale).toInt(), 1)
            val resized = BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB)
            // Nearest neighbor: sample from source directly
            for (y in 0 until newH) {
                val srcY = (y * img.height) / newH
                for (x in 0 until newW) {
                    val srcX = (x * img.width) / newW
                    resized.setRGB(x, y, img.getRGB(srcX, srcY))
                }
            }
            allImages[i] = name to resized
            resizedCount++
        }
        if (resizedCount > 0) {
            println("Resized $resizedCount images to fit within ${maxDim}x${maxDim}")
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

        // Step 5: Pack into texture atlases
        println("\n=== Texture Atlas Packing ===")
        val atlases = TextureAtlasPacker.packAtlases(clutImages, atlasGroupKeys)

        val atlas4 = atlases.filter { it.bpp == 4 }
        val atlas8 = atlases.filter { it.bpp == 8 }
        println("  4bpp atlases: ${atlas4.size}, 8bpp atlases: ${atlas8.size} (${atlases.size} total)")
        for (atlas in atlases) {
            val usedArea = atlas.entries.sumOf { it.image.width * it.image.height }
            val totalArea = atlas.width * atlas.height
            val utilization = (usedArea * 100.0 / totalArea)
            println("    Atlas #${atlas.id} (${atlas.bpp}bpp): ${atlas.entries.size} images [${atlas.entries.joinToString(", ") { it.image.name }}], %.1f%% utilization".format(utilization))
        }

        // Write debug atlas images
        val atlasDir = File(outputDir, "atlases")
        atlasDir.mkdirs()
        for (atlas in atlases) {
            val debugImg = TextureAtlasPacker.renderAtlasDebug(atlas)
            ImageIO.write(debugImg, "PNG", File(atlasDir, "atlas_${atlas.id}_${atlas.bpp}bpp.png"))
        }
        println("  Wrote ${atlases.size} atlas debug images to ${atlasDir.path}")

        // Step 6: Write CLUT binary files (BGRA32, swizzled, alpha remapped to 0-128)
        println("\n=== Writing CLUT Binaries ===")
        writeClutBinaries(mergedGroups, outputDir)

        // Step 7: Write texture atlas binaries
        println("\n=== Writing Texture Binaries ===")
        writeTexturePages(atlases, outputDir)

        // Step 8: Build reverse lookups and write ATLAS.BIN
        println("\n=== Writing ATLAS.BIN ===")

        // imageName -> per-bpp CLUT index (index within CLUT4.BIN or CLUT8.BIN)
        val clutIndexMap = HashMap<String, Int>()
        // Assign sequential indices separately for 4bpp and 8bpp groups
        var clut4Idx = 0
        var clut8Idx = 0
        for (group in mergedGroups.sortedBy { it.id }) {
            val idx = if (group.bpp == 4) clut4Idx++ else clut8Idx++
            for (name in group.imageNames) {
                clutIndexMap[name] = idx
            }
        }

        // imageName -> (atlas, entry)
        val atlasEntryMap = HashMap<String, Pair<TextureAtlas, AtlasEntry>>()
        for (atlas in atlases) {
            for (entry in atlas.entries) {
                atlasEntryMap[entry.image.name] = atlas to entry
            }
        }

        // Reverse lookup: TPAG index -> image name
        val tpagIdxToImageName = HashMap<Int, String>()
        for ((imgName, tpagIdx) in tpagIndexMap) {
            tpagIdxToImageName[tpagIdx] = imgName
        }

        writeAtlasMetadata(dw, uniqueTiles, tpagIdxToImageName, atlasEntryMap, clutIndexMap, outputDir)
    }

    // Converts ARGB palette to PS2 RGBA format with alpha remapped to 0-128 range
    private fun convertARGBtoPS2RGBA(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        // PS2 alpha: 0-255 -> 0-128 (>> 1), fully opaque = 0x80
        val ps2Alpha = a shr 1
        return (ps2Alpha shl 24) or (b shl 16) or (g shl 8) or r
    }

    // PS2 CSM1 CLUT swizzle for 8bpp: swap entries where (i & 0x18) == 8 with i+8
    private fun swizzlePalette8bpp(palette: IntArray): IntArray {
        val swizzled = palette.copyOf()
        for (i in swizzled.indices) {
            if ((i and 0x18) == 8) {
                val tmp = swizzled[i]
                swizzled[i] = swizzled[i + 8]
                swizzled[i + 8] = tmp
            }
        }
        return swizzled
    }

    private fun writeClutBinaries(mergedGroups: List<ClutGroup>, outputDir: File) {
        val groups4 = mergedGroups.filter { it.bpp == 4 }.sortedBy { it.id }
        val groups8 = mergedGroups.filter { it.bpp == 8 }.sortedBy { it.id }

        for ((filename, groups, paletteSize) in listOf(
            Triple("CLUT4.BIN", groups4, 16),
            Triple("CLUT8.BIN", groups8, 256)
        )) {
            if (groups.isEmpty()) {
                println("  $filename: skipped (no CLUTs)")
                continue
            }

            // Each CLUT is paletteSize entries of 4 bytes (PS2 RGBA32)
            val buf = ByteBuffer.allocate(groups.size * paletteSize * 4)
            buf.order(ByteOrder.LITTLE_ENDIAN)

            for (group in groups) {
                var palette = IntArray(paletteSize)
                // Copy the group's palette (may be shorter than paletteSize, rest stays 0)
                System.arraycopy(group.palette, 0, palette, 0, minOf(group.palette.size, paletteSize))

                // Convert ARGB to PS2 RGBA with alpha remap
                for (i in palette.indices) {
                    palette[i] = convertARGBtoPS2RGBA(palette[i])
                }

                // Swizzle 8bpp palettes for CSM1
                if (paletteSize == 256) {
                    palette = swizzlePalette8bpp(palette)
                }

                for (color in palette) {
                    buf.putInt(color)
                }
            }

            val file = File(outputDir, filename)
            file.writeBytes(buf.array())
            println("  $filename: ${groups.size} CLUTs, ${buf.capacity()} bytes")
        }
    }

    private fun writeTexturePages(atlases: List<TextureAtlas>, outputDir: File) {
        val headerSize = 128

        for (atlas in atlases) {
            // Composite all entries into a single pixel index buffer
            val canvas = ByteArray(atlas.width * atlas.height)
            for (entry in atlas.entries) {
                val img = entry.image
                for (y in 0 until img.height) {
                    for (x in 0 until img.width) {
                        canvas[(entry.y + y) * atlas.width + (entry.x + x)] =
                            img.indices[y * img.width + x]
                    }
                }
            }

            // Pack pixel data according to bpp
            val pixelData: ByteArray
            if (atlas.bpp == 4) {
                // 4bpp: two pixels per byte (low nibble = even pixel, high nibble = odd pixel)
                val packedSize = (atlas.width * atlas.height + 1) / 2
                pixelData = ByteArray(packedSize)
                for (i in canvas.indices step 2) {
                    val lo = canvas[i].toInt() and 0x0F
                    val hi = if (i + 1 < canvas.size) (canvas[i + 1].toInt() and 0x0F) shl 4 else 0
                    pixelData[i / 2] = (lo or hi).toByte()
                }
            } else {
                // 8bpp: one byte per pixel, already in the right format
                pixelData = canvas
            }

            // Build the file: 128-byte header + pixel data
            val buf = ByteBuffer.allocate(headerSize + pixelData.size)
            buf.order(ByteOrder.LITTLE_ENDIAN)

            // Header
            buf.put(0.toByte())                  // version
            buf.putShort(atlas.width.toShort())   // width
            buf.putShort(atlas.height.toShort())  // height
            buf.put(atlas.bpp.toByte())           // bpp
            buf.putInt(pixelData.size)            // pixelDataSize

            // Pad the rest of the header to 128 bytes (already zeroed by allocate)
            buf.position(headerSize)

            // Pixel data
            buf.put(pixelData)

            val filename = "TEX${atlas.id}.BIN"
            val file = File(outputDir, filename)
            file.writeBytes(buf.array())
            println("  $filename: ${atlas.width}x${atlas.height} ${atlas.bpp}bpp, ${pixelData.size} bytes pixel data")
        }
    }

    private fun writeAtlasMetadata(
        dw: DataWin,
        uniqueTiles: LinkedHashMap<TileKey, RoomTile>,
        tpagIdxToImageName: Map<Int, String>,
        atlasEntryMap: Map<String, Pair<TextureAtlas, AtlasEntry>>,
        clutIndexMap: Map<String, Int>,
        outputDir: File
    ) {
        val tpagCount = dw.tpag.items.size
        val tileCount = uniqueTiles.size
        val tpagEntrySize = 13  // 6*uint16 + 1*uint8
        val tileEntrySize = 23  // 11*int16/uint16 + 1*uint8
        val headerSize = 5
        val totalSize = headerSize + tpagCount * tpagEntrySize + tileCount * tileEntrySize

        val buf = ByteBuffer.allocate(totalSize)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        // Header
        buf.put(0.toByte())                       // version
        buf.putShort(tpagCount.toShort())          // tpagEntryCount
        buf.putShort(tileCount.toShort())          // tileEntryCount

        // TPAG entries
        var mappedTpagCount = 0
        for (tpagIdx in 0 until tpagCount) {
            val imgName = tpagIdxToImageName[tpagIdx]
            if (imgName != null) {
                val pair = atlasEntryMap[imgName]
                if (pair != null) {
                    val (atlas, entry) = pair
                    buf.putShort(atlas.id.toShort())             // atlasId
                    buf.putShort(entry.x.toShort())              // atlasX
                    buf.putShort(entry.y.toShort())              // atlasY
                    buf.putShort(entry.image.width.toShort())    // width
                    buf.putShort(entry.image.height.toShort())   // height
                    buf.putShort((clutIndexMap[imgName] ?: 0).toShort()) // clutIndex
                    buf.put(atlas.bpp.toByte())                  // bpp
                    mappedTpagCount++
                    continue
                }
            }
            // Unmapped TPAG entry
            buf.putShort(0xFFFF.toShort())  // atlasId = unmapped
            buf.putShort(0)                 // atlasX
            buf.putShort(0)                 // atlasY
            buf.putShort(0)                 // width
            buf.putShort(0)                 // height
            buf.putShort(0)                 // clutIndex
            buf.put(0.toByte())             // bpp
        }

        // Tile entries
        for ((key, _) in uniqueTiles) {
            val bgName = if (dw.bgnd.backgrounds.size > key.bgDef) dw.bgnd.backgrounds[key.bgDef].name ?: "bg${key.bgDef}" else "bg${key.bgDef}"
            val imgName = "tile/${bgName}_${key.srcX}_${key.srcY}_${key.w}x${key.h}"
            val pair = atlasEntryMap[imgName]
            val atlas = pair?.first
            val entry = pair?.second

            buf.putShort(key.bgDef.toShort())                          // bgDef
            buf.putShort(key.srcX.toShort())                           // srcX
            buf.putShort(key.srcY.toShort())                           // srcY
            buf.putShort(key.w.toShort())                              // srcW
            buf.putShort(key.h.toShort())                              // srcH
            buf.putShort((atlas?.id ?: 0xFFFF).toShort())              // atlasId
            buf.putShort((entry?.x ?: 0).toShort())                    // atlasX
            buf.putShort((entry?.y ?: 0).toShort())                    // atlasY
            buf.putShort((entry?.image?.width ?: 0).toShort())         // width
            buf.putShort((entry?.image?.height ?: 0).toShort())        // height
            buf.putShort((clutIndexMap[imgName] ?: 0).toShort())       // clutIndex
            buf.put((atlas?.bpp ?: 0).toByte())                        // bpp
        }

        val file = File(outputDir, "ATLAS.BIN")
        file.writeBytes(buf.array())
        println("  ATLAS.BIN: $tpagCount TPAG entries ($mappedTpagCount mapped), $tileCount tile entries, $totalSize bytes")
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
