package com.mrpowergamerbr.butterscotchpreprocessor

import java.awt.image.BufferedImage

// A palette-indexed image for PS2 (PSMT4 = 4bpp/16 colors, PSMT8 = 8bpp/256 colors)
class ClutImage(
    val name: String,
    val width: Int,
    val height: Int,
    var bpp: Int,
    var palette: IntArray,  // ARGB colors, sorted by unsigned value, padded to 16 or 256
    var usedColors: Int,    // actual number of used palette entries
    var indices: ByteArray  // one byte per pixel (palette index)
)

// A shared CLUT group (multiple images can reference the same CLUT)
class ClutGroup(
    val id: Int,
    val bpp: Int,
    val colors: Set<Int>,
    val palette: IntArray,
    val imageNames: MutableList<String>
)

object ClutProcessor {
    fun createClutImage(name: String, img: BufferedImage): ClutImage {
        val w = img.width
        val h = img.height
        val pixels = IntArray(w * h)
        img.getRGB(0, 0, w, h, pixels, 0, w)

        // Normalize transparent pixels to 0x00000000
        for (i in pixels.indices) {
            if ((pixels[i] ushr 24) == 0) pixels[i] = 0
        }

        val uniqueColors = mutableSetOf<Int>()
        for (p in pixels) uniqueColors.add(p)

        return when {
            16 >= uniqueColors.size -> buildDirectClut(name, w, h, pixels, uniqueColors, 4)
            256 >= uniqueColors.size -> buildDirectClut(name, w, h, pixels, uniqueColors, 8)
            else -> buildQuantizedClut(name, w, h, pixels, uniqueColors)
        }
    }

    private fun buildDirectClut(
        name: String, w: Int, h: Int, pixels: IntArray,
        uniqueColors: Set<Int>, bpp: Int
    ): ClutImage {
        val maxSlots = if (bpp == 4) 16 else 256
        val sorted = uniqueColors.sortedBy { it.toUInt() }.toIntArray()
        val palette = IntArray(maxSlots)
        sorted.copyInto(palette)

        val colorToIndex = HashMap<Int, Int>(sorted.size * 2)
        for ((i, c) in sorted.withIndex()) colorToIndex[c] = i

        val indices = ByteArray(pixels.size) { colorToIndex[pixels[it]]!!.toByte() }
        return ClutImage(name, w, h, bpp, palette, sorted.size, indices)
    }

    private fun buildQuantizedClut(
        name: String, w: Int, h: Int, pixels: IntArray,
        uniqueColors: Set<Int>
    ): ClutImage {
        val hasTransparent = uniqueColors.contains(0)
        val opaquePixels = if (hasTransparent) pixels.filter { (it ushr 24) != 0 } else pixels.toList()

        if (opaquePixels.isEmpty()) {
            // Entirely transparent image
            val palette = IntArray(16)
            return ClutImage(name, w, h, 4, palette, 1, ByteArray(pixels.size))
        }

        // Build BGR byte array for NeuQuant
        val bgrBytes = ByteArray(opaquePixels.size * 3)
        for ((i, argb) in opaquePixels.withIndex()) {
            bgrBytes[i * 3] = (argb and 0xFF).toByte()
            bgrBytes[i * 3 + 1] = ((argb shr 8) and 0xFF).toByte()
            bgrBytes[i * 3 + 2] = ((argb shr 16) and 0xFF).toByte()
        }

        // NeuQuant requires at least 3*503 = 1509 bytes
        val inputBytes = if (bgrBytes.size < 1509) {
            // Duplicate pixel data to meet the minimum
            val expanded = ByteArray(1509)
            var pos = 0
            while (1509 > pos) {
                val toCopy = minOf(bgrBytes.size, 1509 - pos)
                bgrBytes.copyInto(expanded, pos, 0, toCopy)
                pos += toCopy
            }
            expanded
        } else {
            bgrBytes
        }

        val sampleFac = if (opaquePixels.size > 10000) 10 else 1
        val nq = NeuQuant(inputBytes, inputBytes.size, sampleFac)
        val colorMap = nq.process() // 768 bytes BGR, 256 colors

        // Build ARGB palette from NeuQuant result
        val paletteOffset = if (hasTransparent) 1 else 0
        val nqColorCount = 256 - paletteOffset // how many NeuQuant colors we can fit

        // Convert NeuQuant colormap to ARGB and collect as a sorted palette
        val nqColors = mutableListOf<Int>()
        if (hasTransparent) nqColors.add(0x00000000)
        for (i in 0 until nqColorCount) {
            val b = colorMap[i * 3].toInt() and 0xFF
            val g = colorMap[i * 3 + 1].toInt() and 0xFF
            val r = colorMap[i * 3 + 2].toInt() and 0xFF
            nqColors.add((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        }

        // Sort palette canonically by unsigned ARGB
        val sortedColors = nqColors.sortedBy { it.toUInt() }.toIntArray()
        val palette = IntArray(256)
        sortedColors.copyInto(palette)

        // Build a fast lookup: NeuQuant index -> ARGB color
        val nqIdxToArgb = IntArray(256)
        for (i in 0 until 256) {
            val b = colorMap[i * 3].toInt() and 0xFF
            val g = colorMap[i * 3 + 1].toInt() and 0xFF
            val r = colorMap[i * 3 + 2].toInt() and 0xFF
            nqIdxToArgb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // Build a reverse lookup: ARGB color -> sorted palette index
        val colorToSortedIdx = HashMap<Int, Int>(sortedColors.size * 2)
        for ((i, c) in sortedColors.withIndex()) colorToSortedIdx[c] = i

        // Map pixels
        val indices = ByteArray(pixels.size) { idx ->
            val argb = pixels[idx]
            if ((argb ushr 24) == 0) {
                // Transparent pixel
                colorToSortedIdx[0x00000000]?.toByte() ?: 0
            } else {
                val b = argb and 0xFF
                val g = (argb shr 8) and 0xFF
                val r = (argb shr 16) and 0xFF
                val nqIdx = nq.map(b, g, r)
                // Clamp to the range we actually use
                val clampedNqIdx = minOf(nqIdx, nqColorCount - 1)
                val nqArgb = nqIdxToArgb[clampedNqIdx]
                (colorToSortedIdx[nqArgb] ?: 0).toByte()
            }
        }

        return ClutImage(name, w, h, 8, palette, sortedColors.size, indices)
    }

    // Get the set of actually-used colors from a ClutImage's palette
    fun getUsedColorSet(img: ClutImage): Set<Int> {
        val colors = mutableSetOf<Int>()
        for (i in 0 until img.usedColors) {
            colors.add(img.palette[i])
        }
        return colors
    }

    // Deduplicate CLUTs: group images with identical palette color sets
    fun deduplicateCluts(images: List<ClutImage>): List<ClutGroup> {
        // Key: sorted color list, Value: list of images
        val groups = LinkedHashMap<List<Int>, MutableList<ClutImage>>()

        for (img in images) {
            val key = img.palette.take(img.usedColors).toList()
            groups.getOrPut(key) { mutableListOf() }.add(img)
        }

        val clutGroups = mutableListOf<ClutGroup>()
        for ((_, groupImages) in groups) {
            val representative = groupImages[0]
            val group = ClutGroup(
                id = clutGroups.size,
                bpp = representative.bpp,
                colors = getUsedColorSet(representative),
                palette = representative.palette.clone(),
                imageNames = groupImages.map { it.name }.toMutableList()
            )
            for (img in groupImages) {
                img.palette = group.palette
            }
            clutGroups.add(group)
        }

        return clutGroups
    }

    // Merge CLUTs that have available slots with compatible CLUTs
    fun mergeCluts(images: List<ClutImage>, initialGroups: List<ClutGroup>): List<ClutGroup> {
        // Build a mutable working list
        data class MergeableClut(
            var id: Int,
            val bpp: Int,
            var colors: MutableSet<Int>,
            var imageNames: MutableList<String>,
            var alive: Boolean = true
        )

        val cluts = initialGroups.map {
            MergeableClut(it.id, it.bpp, it.colors.toMutableSet(), it.imageNames.toMutableList())
        }.toMutableList()

        // Build name -> ClutImage lookup
        val imageByName = images.associateBy { it.name }

        // Greedy merging: repeatedly try to merge the smallest CLUT into another
        var changed = true
        while (changed) {
            changed = false
            // Sort alive cluts by color count ascending (merge small into large)
            val aliveCluts = cluts.filter { it.alive }.sortedBy { it.colors.size }

            for (i in aliveCluts.indices) {
                val clutA = aliveCluts[i]
                if (!clutA.alive) continue
                val maxSlots = if (clutA.bpp == 4) 16 else 256

                // Find the best merge partner: same bpp, union fits, and maximizes shared colors
                var bestPartner: MergeableClut? = null
                var bestUnionSize = Int.MAX_VALUE

                for (j in aliveCluts.indices) {
                    if (i == j) continue
                    val clutB = aliveCluts[j]
                    if (!clutB.alive) continue
                    if (clutA.bpp != clutB.bpp) continue

                    val unionSize = (clutA.colors union clutB.colors).size
                    if (maxSlots >= unionSize && unionSize < bestUnionSize) {
                        bestUnionSize = unionSize
                        bestPartner = clutB
                    }
                }

                if (bestPartner != null) {
                    // Merge clutA into bestPartner
                    val mergedColors = bestPartner.colors union clutA.colors
                    val sortedMerged = mergedColors.sortedBy { it.toUInt() }.toIntArray()
                    val newPalette = IntArray(if (bestPartner.bpp == 4) 16 else 256)
                    sortedMerged.copyInto(newPalette)

                    // Remap all images from both CLUTs
                    for (imgName in bestPartner.imageNames + clutA.imageNames) {
                        val img = imageByName[imgName] ?: continue
                        remapIndices(img, newPalette, sortedMerged.size)
                    }

                    bestPartner.colors = mergedColors.toMutableSet()
                    bestPartner.imageNames.addAll(clutA.imageNames)
                    clutA.alive = false
                    changed = true
                    break // restart outer loop
                }
            }
        }

        // Build final ClutGroups
        val finalGroups = mutableListOf<ClutGroup>()
        for (clut in cluts) {
            if (!clut.alive) continue
            val sortedColors = clut.colors.sortedBy { it.toUInt() }.toIntArray()
            val palette = IntArray(if (clut.bpp == 4) 16 else 256)
            sortedColors.copyInto(palette)
            finalGroups.add(ClutGroup(
                id = finalGroups.size,
                bpp = clut.bpp,
                colors = clut.colors,
                palette = palette,
                imageNames = clut.imageNames
            ))
        }

        return finalGroups
    }

    private fun remapIndices(img: ClutImage, newPalette: IntArray, newUsedColors: Int) {
        val oldPalette = img.palette

        // Build color -> new index mapping
        val colorToNewIdx = HashMap<Int, Int>(newUsedColors * 2)
        for (i in 0 until newUsedColors) {
            colorToNewIdx[newPalette[i]] = i
        }

        for (i in img.indices.indices) {
            val oldIdx = img.indices[i].toInt() and 0xFF
            val color = oldPalette[oldIdx]
            val newIdx = colorToNewIdx[color]
            if (newIdx != null) {
                img.indices[i] = newIdx.toByte()
            }
            // If color not found (shouldn't happen), leave index as-is
        }

        img.palette = newPalette
        img.usedColors = newUsedColors
    }

    // Render a ClutImage back to BufferedImage for verification
    fun renderClutImage(img: ClutImage): BufferedImage {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        val pixels = IntArray(img.width * img.height)
        for (i in pixels.indices) {
            val idx = img.indices[i].toInt() and 0xFF
            pixels[i] = img.palette[idx]
        }
        out.setRGB(0, 0, img.width, img.height, pixels, 0, img.width)
        return out
    }
}
