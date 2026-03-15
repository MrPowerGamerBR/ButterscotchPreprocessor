package com.mrpowergamerbr.butterscotchpreprocessor

class PixelImage(val width: Int, val height: Int, val pixels: IntArray) {
    fun getPixel(x: Int, y: Int): Int = pixels[y * width + x]

    fun setPixel(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }
}
