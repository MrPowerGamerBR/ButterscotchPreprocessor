package com.mrpowergamerbr.butterscotchpreprocessor

import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryReader(buffer: ByteBuffer) {
    val buffer: ByteBuffer = buffer.apply { order(ByteOrder.LITTLE_ENDIAN) }

    var position: Int
        get() = buffer.position()
        set(value) { buffer.position(value) }

    val size: Int get() = buffer.limit()

    fun readUint8(): Int = buffer.get().toInt() and 0xFF
    fun readInt16(): Short = buffer.getShort()
    fun readUint16(): Int = buffer.getShort().toInt() and 0xFFFF
    fun readInt32(): Int = buffer.getInt()
    fun readFloat32(): Float = buffer.getFloat()
    fun readLong(): Long = buffer.getLong()
    fun readBool32(): Boolean = buffer.getInt() != 0

    fun readBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        buffer.get(bytes)
        return bytes
    }

    fun readBytesAt(offset: Int, count: Int): ByteArray {
        val saved = position
        position = offset
        val bytes = readBytes(count)
        position = saved
        return bytes
    }

    fun skip(count: Int) {
        position += count
    }

    // Reads a uint32 absolute file offset to a string.
    // The offset points to the string content; the 4-byte length prefix is at offset - 4.
    fun readStringPtr(): String? {
        val offset = readInt32()
        if (offset == 0) return null
        val saved = position
        position = offset - 4
        val length = buffer.getInt()
        val bytes = ByteArray(length)
        buffer.get(bytes)
        position = saved
        return String(bytes, Charsets.UTF_8)
    }

    // Reads a pointer table: uint32 count + count * uint32 absolute offsets
    fun readPointerTable(): IntArray {
        val count = readInt32()
        if (count == 0) return intArrayOf()
        return IntArray(count) { readInt32() }
    }

    fun readChunkName(): String = String(readBytes(4), Charsets.US_ASCII)
}
