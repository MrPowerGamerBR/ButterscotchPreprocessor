package com.mrpowergamerbr.butterscotchpreprocessor

class ProcessingResult(
    val gameName: String,
    val clut4Bin: ByteArray,
    val clut8Bin: ByteArray,
    val texturesBin: ByteArray,
    val atlasBin: ByteArray,
    val atlases: List<TextureAtlas> = emptyList()
)

private data class TileKey(val bgDef: Int, val srcX: Int, val srcY: Int, val w: Int, val h: Int)
private data class CropInfo(val offsetX: Int, val offsetY: Int, val croppedWidth: Int, val croppedHeight: Int)

suspend fun processDataWin(
    dataWinBytes: ByteArray,
    progressCallback: ((String) -> Unit)? = null
): ProcessingResult {
    val log = progressCallback ?: {}

    log("Parsing data.win...")
    val dw = DataWin.parse(dataWinBytes, DataWinParserOptions(
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

    log("Parsed: ${dw.sprt.sprites.size} sprites, ${dw.bgnd.backgrounds.size} backgrounds, ${dw.font.fonts.size} fonts, ${dw.txtr.textures.size} textures, ${dw.tpag.items.size} TPAG items")

    // Decode texture pages as PixelImages
    log("Loading texture pages...")
    val texturePages = mutableListOf<PixelImage?>()
    for (tex in dw.txtr.textures) {
        if (tex.blobData != null) {
            texturePages.add(decodePngBytes(tex.blobData))
        } else {
            texturePages.add(null)
        }
    }
    log("Loaded ${texturePages.count { it != null }} texture pages")

    val allImages = mutableListOf<Pair<String, PixelImage>>()
    val atlasGroupKeys = HashMap<String, String>()
    val tpagIndexMap = HashMap<String, Int>()

    // Collect sprites
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
            if (0 > tile.backgroundDefinition || tile.backgroundDefinition >= dw.bgnd.backgrounds.size) continue
            val key = TileKey(tile.backgroundDefinition, tile.sourceX, tile.sourceY, tile.width, tile.height)
            if (key !in uniqueTiles) {
                uniqueTiles[key] = tile
            }
        }
    }
    val bgImages = HashMap<Int, PixelImage>()
    for ((key, _) in uniqueTiles) {
        if (bgImages.containsKey(key.bgDef)) continue
        val bg = dw.bgnd.backgrounds[key.bgDef]
        val tpagIdx = dw.resolveTPAG(bg.textureOffset)
        if (0 > tpagIdx) continue
        bgImages[key.bgDef] = extractFromTPAG(dw.tpag.items[tpagIdx], texturePages)
    }
    for ((key, _) in uniqueTiles) {
        val bgImg = bgImages[key.bgDef] ?: continue
        if (key.srcX + key.w > bgImg.width || key.srcY + key.h > bgImg.height) continue
        if (key.w == 0 || key.h == 0) continue
        val tileImg = extractSubImage(bgImg, key.srcX, key.srcY, key.w, key.h)
        val bgName = dw.bgnd.backgrounds[key.bgDef].name ?: "bg${key.bgDef}"
        val imgName = "tile/${bgName}_${key.srcX}_${key.srcY}_${key.w}x${key.h}"
        allImages.add(imgName to tileImg)
        atlasGroupKeys[imgName] = imgName
    }

    // Crop transparent borders before packing (sprites only)
    val cropInfoMap = HashMap<String, CropInfo>()
    var croppedCount = 0
    for (i in allImages.indices) {
        val (name, img) = allImages[i]
        if (name.startsWith("spr/")) {
            val crop = cropTransparentBorders(img)
            cropInfoMap[name] = CropInfo(crop.offsetX, crop.offsetY, crop.image.width, crop.image.height)
            if (crop.image.width != img.width || crop.image.height != img.height) {
                croppedCount++
            }
            allImages[i] = name to crop.image
        } else {
            // No crop: store original dimensions for correct cropW/cropH in ATLAS.BIN
            cropInfoMap[name] = CropInfo(0, 0, img.width, img.height)
        }
    }
    if (croppedCount > 0) {
        log("Cropped transparent borders from $croppedCount sprite images")
    }

    // Resize any images exceeding 512x512
    val maxDim = 512
    var resizedCount = 0
    for (i in allImages.indices) {
        val (name, img) = allImages[i]
        if (maxDim >= img.width && maxDim >= img.height) continue
        val scale = minOf(maxDim.toDouble() / img.width, maxDim.toDouble() / img.height)
        val newW = maxOf((img.width * scale).toInt(), 1)
        val newH = maxOf((img.height * scale).toInt(), 1)
        val resizedPixels = IntArray(newW * newH)
        for (y in 0 until newH) {
            val srcY = (y * img.height) / newH
            for (x in 0 until newW) {
                val srcX = (x * img.width) / newW
                resizedPixels[y * newW + x] = img.pixels[srcY * img.width + srcX]
            }
        }
        allImages[i] = name to PixelImage(newW, newH, resizedPixels)
        resizedCount++
    }
    if (resizedCount > 0) {
        log("Resized $resizedCount images to fit within ${maxDim}x${maxDim}")
    }

    log("Total images to process: ${allImages.size}")

    // Step 1: Create CLUTs
    log("Creating CLUTs...")
    val clutImages = mutableListOf<ClutImage>()
    for ((name, img) in allImages) {
        val clutImage = ClutProcessor.createClutImage(name, img)
        clutImages.add(clutImage)
    }

    val bpp4Count = clutImages.count { it.bpp == 4 }
    val bpp8Count = clutImages.count { it.bpp == 8 }
    log("  4bpp: $bpp4Count images, 8bpp: $bpp8Count images")

    // Step 2: Deduplicate CLUTs
    log("Deduplicating CLUTs...")
    val dedupGroups = ClutProcessor.deduplicateCluts(clutImages)
    log("  After dedup: ${dedupGroups.size} unique CLUTs (from ${clutImages.size} images)")

    // Step 3: Merge CLUTs
    log("Merging CLUTs...")
    val mergedGroups = ClutProcessor.mergeCluts(clutImages, dedupGroups)
    val merged4 = mergedGroups.count { it.bpp == 4 }
    val merged8 = mergedGroups.count { it.bpp == 8 }
    log("  After merge: $merged4 merged 4bpp CLUTs, $merged8 merged 8bpp CLUTs (${mergedGroups.size} total)")

    // Step 4: Pack into texture atlases
    log("Packing texture atlases...")
    val atlases = TextureAtlasPacker.packAtlases(clutImages, atlasGroupKeys)
    log("  ${atlases.count { it.bpp == 4 }} 4bpp atlases, ${atlases.count { it.bpp == 8 }} 8bpp atlases (${atlases.size} total)")

    // Step 5: Write CLUT binaries
    log("Writing CLUT binaries...")
    val clut4Bin = writeClutBinary(mergedGroups.filter { it.bpp == 4 }.sortedBy { it.id }, 16)
    val clut8Bin = writeClutBinary(mergedGroups.filter { it.bpp == 8 }.sortedBy { it.id }, 256)

    // Step 6: Write texture pages
    log("Writing texture pages...")
    val (texturesBin, atlasOffsets) = writeTexturePagesBytes(atlases, log)

    // Step 7: Build lookups and write ATLAS.BIN
    log("Writing ATLAS.BIN...")
    val clutIndexMap = HashMap<String, Int>()
    var clut4Idx = 0
    var clut8Idx = 0
    for (group in mergedGroups.sortedBy { it.id }) {
        val idx = if (group.bpp == 4) clut4Idx++ else clut8Idx++
        for (name in group.imageNames) {
            clutIndexMap[name] = idx
        }
    }

    val atlasEntryMap = HashMap<String, Pair<TextureAtlas, AtlasEntry>>()
    for (atlas in atlases) {
        for (entry in atlas.entries) {
            atlasEntryMap[entry.image.name] = atlas to entry
        }
    }

    val tpagIdxToImageName = HashMap<Int, String>()
    for ((imgName, tpagIdx) in tpagIndexMap) {
        tpagIdxToImageName[tpagIdx] = imgName
    }

    val atlasBin = writeAtlasMetadataBytes(dw, uniqueTiles, tpagIdxToImageName, atlasEntryMap, clutIndexMap, atlasOffsets, cropInfoMap)

    log("Done!")
    return ProcessingResult(dw.gen8.displayName ?: dw.gen8.name ?: "GAME", clut4Bin, clut8Bin, texturesBin, atlasBin, atlases)
}

// Pure pixel copy from a TPAG item (replaces Graphics2D.drawImage)
private fun extractFromTPAG(tpag: TexturePageItem, texturePages: List<PixelImage?>): PixelImage {
    val w = maxOf(tpag.boundingWidth, 1)
    val h = maxOf(tpag.boundingHeight, 1)
    val pixels = IntArray(w * h)

    if (tpag.texturePageId < 0) return PixelImage(w, h, pixels)
    val texPage = texturePages.getOrNull(tpag.texturePageId.toInt()) ?: return PixelImage(w, h, pixels)
    if (tpag.targetWidth == 0 || tpag.targetHeight == 0) return PixelImage(w, h, pixels)

    // Copy the rectangle from source texture page to destination
    for (dy in 0 until tpag.targetHeight) {
        val srcY = tpag.sourceY + dy
        val dstY = tpag.targetY + dy
        if (srcY >= texPage.height || dstY >= h) continue
        for (dx in 0 until tpag.targetWidth) {
            val srcX = tpag.sourceX + dx
            val dstX = tpag.targetX + dx
            if (srcX >= texPage.width || dstX >= w) continue
            pixels[dstY * w + dstX] = texPage.pixels[srcY * texPage.width + srcX]
        }
    }

    return PixelImage(w, h, pixels)
}

// Extract a sub-rectangle from an image (replaces BufferedImage.getSubimage + Graphics2D copy)
private fun extractSubImage(src: PixelImage, srcX: Int, srcY: Int, w: Int, h: Int): PixelImage {
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            pixels[y * w + x] = src.pixels[(srcY + y) * src.width + (srcX + x)]
        }
    }
    return PixelImage(w, h, pixels)
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

// PS2 CSM1 CLUT swizzle for 8bpp
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

private fun writeClutBinary(groups: List<ClutGroup>, paletteSize: Int): ByteArray {
    if (groups.isEmpty()) return ByteArray(0)

    val writer = ByteWriter(groups.size * paletteSize * 4)
    for (group in groups) {
        val palette = IntArray(paletteSize)
        group.palette.copyInto(palette, 0, 0, minOf(group.palette.size, paletteSize))

        // Convert ARGB to PS2 RGBA with alpha remap
        for (i in palette.indices) {
            palette[i] = convertARGBtoPS2RGBA(palette[i])
        }

        // Swizzle 8bpp palettes for CSM1
        val finalPalette = if (paletteSize == 256) swizzlePalette8bpp(palette) else palette

        for (color in finalPalette) {
            writer.writeIntLE(color)
        }
    }
    return writer.getAsByteArray()
}

private fun rleCompress(data: ByteArray): ByteArray {
    if (data.isEmpty()) return ByteArray(0)

    val writer = ByteWriter(data.size)
    var i = 0
    while (data.size > i) {
        val current = data[i]
        var runLength = 1
        while (data.size > i + runLength && runLength < 255 && data[i + runLength] == current) {
            runLength++
        }
        writer.writeByte(runLength)
        writer.writeByte(current.toInt() and 0xFF)
        i += runLength
    }
    return writer.getAsByteArray()
}

private fun writeTexturePagesBytes(atlases: List<TextureAtlas>, log: (String) -> Unit): Pair<ByteArray, Map<Int, Long>> {
    val headerSize = 128
    val atlasOffsets = HashMap<Int, Long>()
    var currentOffset = 0L

    val sortedAtlases = atlases.sortedBy { it.id }
    val writer = ByteWriter()

    for (atlas in sortedAtlases) {
        atlasOffsets[atlas.id] = currentOffset

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
        val uncompressedPixelData: ByteArray
        if (atlas.bpp == 4) {
            val packedSize = (atlas.width * atlas.height + 1) / 2
            uncompressedPixelData = ByteArray(packedSize)
            for (i in canvas.indices step 2) {
                val lo = canvas[i].toInt() and 0x0F
                val hi = if (i + 1 < canvas.size) (canvas[i + 1].toInt() and 0x0F) shl 4 else 0
                uncompressedPixelData[i / 2] = (lo or hi).toByte()
            }
        } else {
            uncompressedPixelData = canvas
        }

        // Try RLE compression
        val rleData = rleCompress(uncompressedPixelData)
        val useRle = uncompressedPixelData.size > rleData.size
        val compressionType: Int
        val pixelData: ByteArray

        if (useRle) {
            compressionType = 1
            pixelData = rleData
            log("  Atlas ${atlas.id} (${atlas.bpp}bpp): RLE compressed ${uncompressedPixelData.size} -> ${rleData.size} bytes (saved ${uncompressedPixelData.size - rleData.size} bytes, ${(100.0 * (uncompressedPixelData.size - rleData.size) / uncompressedPixelData.size).toInt()}%)")
        } else {
            compressionType = 0
            pixelData = uncompressedPixelData
            log("  Atlas ${atlas.id} (${atlas.bpp}bpp): RLE not beneficial (${uncompressedPixelData.size} -> ${rleData.size} bytes), keeping uncompressed")
        }

        // Header (128 bytes)
        writer.writeByte(0)                          // version
        writer.writeShortLE(atlas.width)             // width
        writer.writeShortLE(atlas.height)            // height
        writer.writeByte(atlas.bpp)                  // bpp
        writer.writeIntLE(pixelData.size)            // pixelDataSize
        writer.writeByte(compressionType)            // compressionType (0 = uncompressed, 1 = RLE)
        // Pad to 128 bytes (we wrote 1+2+2+1+4+1 = 11 bytes so far)
        writer.writeZeroPadding(headerSize - 11)

        // Pixel data
        writer.writeByteArray(pixelData)

        currentOffset += headerSize + pixelData.size
    }

    return writer.getAsByteArray() to atlasOffsets
}

private fun writeAtlasMetadataBytes(
    dw: DataWin,
    uniqueTiles: LinkedHashMap<TileKey, RoomTile>,
    tpagIdxToImageName: Map<Int, String>,
    atlasEntryMap: Map<String, Pair<TextureAtlas, AtlasEntry>>,
    clutIndexMap: Map<String, Int>,
    atlasOffsets: Map<Int, Long>,
    cropInfoMap: Map<String, CropInfo>
): ByteArray {
    val tpagCount = dw.tpag.items.size
    val tileCount = uniqueTiles.size
    val atlasCount = atlasOffsets.size

    val writer = ByteWriter()

    // Header
    writer.writeByte(0)                            // version
    writer.writeShortLE(tpagCount)                 // tpagEntryCount
    writer.writeShortLE(tileCount)                 // tileEntryCount
    writer.writeShortLE(atlasCount)                // atlasCount

    // Atlas offset table
    for (id in atlasOffsets.keys.sorted()) {
        writer.writeIntLE(atlasOffsets[id]!!.toInt())
    }

    // TPAG entries
    for (tpagIdx in 0 until tpagCount) {
        val imgName = tpagIdxToImageName[tpagIdx]
        if (imgName != null) {
            val pair = atlasEntryMap[imgName]
            if (pair != null) {
                val (atlas, entry) = pair
                writer.writeShortLE(atlas.id)               // atlasId
                writer.writeShortLE(entry.x)                // atlasX
                writer.writeShortLE(entry.y)                // atlasY
                writer.writeShortLE(entry.image.width)      // width
                writer.writeShortLE(entry.image.height)     // height
                val crop = cropInfoMap[imgName]
                writer.writeShortLE(crop?.offsetX ?: 0)     // cropX
                writer.writeShortLE(crop?.offsetY ?: 0)     // cropY
                writer.writeShortLE(crop?.croppedWidth ?: entry.image.width)  // cropW
                writer.writeShortLE(crop?.croppedHeight ?: entry.image.height) // cropH
                writer.writeShortLE(clutIndexMap[imgName] ?: 0) // clutIndex
                writer.writeByte(atlas.bpp)                 // bpp
                continue
            }
        }
        // Unmapped TPAG entry
        writer.writeShortLE(0xFFFF)  // atlasId = unmapped
        writer.writeShortLE(0)       // atlasX
        writer.writeShortLE(0)       // atlasY
        writer.writeShortLE(0)       // width
        writer.writeShortLE(0)       // height
        writer.writeShortLE(0)       // cropX
        writer.writeShortLE(0)       // cropY
        writer.writeShortLE(0)       // cropW
        writer.writeShortLE(0)       // cropH
        writer.writeShortLE(0)       // clutIndex
        writer.writeByte(0)          // bpp
    }

    // Tile entries
    for ((key, _) in uniqueTiles) {
        val bgName = dw.bgnd.backgrounds[key.bgDef].name ?: "bg${key.bgDef}"
        val imgName = "tile/${bgName}_${key.srcX}_${key.srcY}_${key.w}x${key.h}"
        val pair = atlasEntryMap[imgName]
        val atlas = pair?.first
        val entry = pair?.second

        writer.writeShortLE(key.bgDef)                              // bgDef
        writer.writeShortLE(key.srcX)                               // srcX
        writer.writeShortLE(key.srcY)                               // srcY
        writer.writeShortLE(key.w)                                  // srcW
        writer.writeShortLE(key.h)                                  // srcH
        writer.writeShortLE(atlas?.id ?: 0xFFFF)                    // atlasId
        writer.writeShortLE(entry?.x ?: 0)                          // atlasX
        writer.writeShortLE(entry?.y ?: 0)                          // atlasY
        writer.writeShortLE(entry?.image?.width ?: 0)               // width
        writer.writeShortLE(entry?.image?.height ?: 0)              // height
        val crop = cropInfoMap[imgName]
        writer.writeShortLE(crop?.offsetX ?: 0)                    // cropX
        writer.writeShortLE(crop?.offsetY ?: 0)                    // cropY
        writer.writeShortLE(crop?.croppedWidth ?: (entry?.image?.width ?: 0))  // cropW
        writer.writeShortLE(crop?.croppedHeight ?: (entry?.image?.height ?: 0)) // cropH
        writer.writeShortLE(clutIndexMap[imgName] ?: 0)             // clutIndex
        writer.writeByte(atlas?.bpp ?: 0)                           // bpp
    }

    return writer.getAsByteArray()
}
