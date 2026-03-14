package com.mrpowergamerbr.butterscotchpreprocessor

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

data class AtlasEntry(
    val image: ClutImage,
    val x: Int,
    val y: Int
)

class TextureAtlas(
    val id: Int,
    val bpp: Int,
    val width: Int,
    val height: Int,
    val entries: MutableList<AtlasEntry> = mutableListOf()
)

object TextureAtlasPacker {
    const val MAX_SIZE = 512

    // Pack ClutImages into texture atlases, grouped by groupKey and separated by bpp.
    fun packAtlases(images: List<ClutImage>, groupKeys: Map<String, String>): List<TextureAtlas> {
        // Separate by bpp
        val by4bpp = images.filter { it.bpp == 4 }
        val by8bpp = images.filter { it.bpp == 8 }

        val atlases = mutableListOf<TextureAtlas>()
        packByBpp(by4bpp, 4, groupKeys, atlases)
        packByBpp(by8bpp, 8, groupKeys, atlases)
        return atlases
    }

    private fun packByBpp(
        images: List<ClutImage>,
        bpp: Int,
        groupKeys: Map<String, String>,
        atlases: MutableList<TextureAtlas>
    ) {
        if (images.isEmpty()) return

        // Group images by their group key
        val groups = LinkedHashMap<String, MutableList<ClutImage>>()
        for (img in images) {
            val key = groupKeys[img.name] ?: img.name
            groups.getOrPut(key) { mutableListOf() }.add(img)
        }

        // Sort groups by total area descending (pack large groups first for better utilization)
        val sortedGroups = groups.entries.sortedByDescending { entry ->
            entry.value.sumOf { it.width * it.height }
        }

        for ((_, groupImages) in sortedGroups) {
            // Sort images within group by height descending for better packing
            val sorted = groupImages.sortedByDescending { maxOf(it.width, it.height) }

            // Try to fit the entire group into one existing atlas (same bpp)
            var packed = false
            for (atlas in atlases) {
                if (atlas.bpp != bpp) continue
                if (tryPackAll(atlas, sorted)) {
                    packed = true
                    break
                }
            }

            if (!packed) {
                // Create new atlas(es) for this group
                val remaining = sorted.toMutableList()
                while (remaining.isNotEmpty()) {
                    val atlas = TextureAtlas(atlases.size, bpp, MAX_SIZE, MAX_SIZE)
                    val packer = MaxRectsPacker(MAX_SIZE, MAX_SIZE)

                    val iterator = remaining.iterator()
                    while (iterator.hasNext()) {
                        val img = iterator.next()
                        val pos = packer.insert(img.width, img.height)
                        if (pos != null) {
                            atlas.entries.add(AtlasEntry(img, pos.first, pos.second))
                            iterator.remove()
                        }
                    }

                    if (atlas.entries.isEmpty()) {
                        // Nothing could be packed (all remaining are too large or something went wrong)
                        break
                    }
                    atlases.add(atlas)
                }
            }
        }
    }

    private fun tryPackAll(atlas: TextureAtlas, images: List<ClutImage>): Boolean {
        // Build a packer that reflects the current atlas state
        val packer = MaxRectsPacker(atlas.width, atlas.height)
        for (entry in atlas.entries) {
            packer.reserve(entry.x, entry.y, entry.image.width, entry.image.height)
        }

        // Try to fit all new images
        val placements = mutableListOf<Pair<ClutImage, Pair<Int, Int>>>()
        for (img in images) {
            val pos = packer.insert(img.width, img.height) ?: return false
            placements.add(img to pos)
        }

        // All fit, commit the placements
        for ((img, pos) in placements) {
            atlas.entries.add(AtlasEntry(img, pos.first, pos.second))
        }
        return true
    }

    // Render an atlas to a BufferedImage for debugging/verification
    fun renderAtlas(atlas: TextureAtlas): BufferedImage {
        val img = BufferedImage(atlas.width, atlas.height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()

        for (entry in atlas.entries) {
            val rendered = ClutProcessor.renderClutImage(entry.image)
            g.drawImage(rendered, entry.x, entry.y, null)
        }

        g.dispose()
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

// MaxRects bin packing algorithm (Best Short Side Fit heuristic)
class MaxRectsPacker(private val binWidth: Int, private val binHeight: Int) {
    private val freeRects = mutableListOf(Rect(0, 0, binWidth, binHeight))

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    fun reserve(x: Int, y: Int, w: Int, h: Int) {
        splitFreeRects(Rect(x, y, w, h))
        pruneFreeRects()
    }

    fun insert(w: Int, h: Int): Pair<Int, Int>? {
        var bestX = -1
        var bestY = -1
        var bestShortSide = Int.MAX_VALUE
        var bestLongSide = Int.MAX_VALUE

        for (rect in freeRects) {
            if (rect.width >= w && rect.height >= h) {
                val leftoverHoriz = rect.width - w
                val leftoverVert = rect.height - h
                val shortSide = minOf(leftoverHoriz, leftoverVert)
                val longSide = maxOf(leftoverHoriz, leftoverVert)
                if (shortSide < bestShortSide || (shortSide == bestShortSide && bestLongSide > longSide)) {
                    bestX = rect.x
                    bestY = rect.y
                    bestShortSide = shortSide
                    bestLongSide = longSide
                }
            }
        }

        if (bestX == -1) return null

        val placed = Rect(bestX, bestY, w, h)
        splitFreeRects(placed)
        pruneFreeRects()
        return bestX to bestY
    }

    private fun splitFreeRects(used: Rect) {
        val newRects = mutableListOf<Rect>()
        val iterator = freeRects.iterator()
        while (iterator.hasNext()) {
            val free = iterator.next()
            if (!overlaps(free, used)) continue
            iterator.remove()

            // Left strip
            if (free.x < used.x) {
                newRects.add(Rect(free.x, free.y, used.x - free.x, free.height))
            }
            // Right strip
            if (free.x + free.width > used.x + used.width) {
                newRects.add(Rect(used.x + used.width, free.y, free.x + free.width - used.x - used.width, free.height))
            }
            // Top strip
            if (free.y < used.y) {
                newRects.add(Rect(free.x, free.y, free.width, used.y - free.y))
            }
            // Bottom strip
            if (free.y + free.height > used.y + used.height) {
                newRects.add(Rect(free.x, used.y + used.height, free.width, free.y + free.height - used.y - used.height))
            }
        }
        freeRects.addAll(newRects)
    }

    private fun overlaps(a: Rect, b: Rect): Boolean {
        return a.x < b.x + b.width && a.x + a.width > b.x &&
                a.y < b.y + b.height && a.y + a.height > b.y
    }

    private fun pruneFreeRects() {
        // Remove any free rect that is fully contained within another
        val size = freeRects.size
        var writeIdx = 0
        outer@ for (i in 0 until size) {
            val rectI = freeRects[i]
            for (j in 0 until size) {
                if (i != j && contains(freeRects[j], rectI)) {
                    continue@outer
                }
            }
            freeRects[writeIdx++] = rectI
        }
        // Trim the list in place
        while (freeRects.size > writeIdx) {
            freeRects.removeAt(freeRects.lastIndex)
        }
    }

    private fun contains(outer: Rect, inner: Rect): Boolean {
        return outer.x <= inner.x && outer.y <= inner.y &&
                outer.x + outer.width >= inner.x + inner.width &&
                outer.y + outer.height >= inner.y + inner.height
    }
}
