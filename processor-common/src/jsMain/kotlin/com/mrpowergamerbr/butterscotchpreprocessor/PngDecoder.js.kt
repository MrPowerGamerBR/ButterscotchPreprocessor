package com.mrpowergamerbr.butterscotchpreprocessor

import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.browser.document

actual suspend fun decodePngBytes(bytes: ByteArray): PixelImage {
    val blob = Blob(arrayOf(bytes), BlobPropertyBag(type = "image/png"))
    val url = URL.createObjectURL(blob)

    try {
        val img = suspendCancellableCoroutine<HTMLImageElement> { cont ->
            val image = document.createElement("img") as HTMLImageElement
            image.onload = {
                cont.resume(image)
            }
            image.onerror = { _, _, _, _, _ ->
                cont.resumeWithException(RuntimeException("Failed to decode PNG"))
            }
            image.src = url
        }

        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = img.width
        canvas.height = img.height
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.drawImage(img, 0.0, 0.0)
        val imageData = ctx.getImageData(0.0, 0.0, img.width.toDouble(), img.height.toDouble())

        val rgbaData: dynamic = imageData.data
        val w = img.width
        val h = img.height
        val pixelCount = w * h
        val pixels = IntArray(pixelCount)

        // Use a Uint32Array view over the ImageData buffer for fast bulk read
        val uint32View = js("new Uint32Array(rgbaData.buffer, rgbaData.byteOffset, rgbaData.length / 4)")
        for (i in 0 until pixelCount) {
            // Canvas gives us RGBA in native byte order (little-endian: ABGR as uint32)
            val abgr = uint32View[i] as Int
            val r = abgr and 0xFF
            val g = (abgr ushr 8) and 0xFF
            val b = (abgr ushr 16) and 0xFF
            val a = (abgr ushr 24) and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return PixelImage(w, h, pixels)
    } finally {
        URL.revokeObjectURL(url)
    }
}
