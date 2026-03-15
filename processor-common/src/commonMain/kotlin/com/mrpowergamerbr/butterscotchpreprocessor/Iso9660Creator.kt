package com.mrpowergamerbr.butterscotchpreprocessor

/**
 * Creates simple ISO 9660 images with a flat file structure (no subdirectories).
 *
 * Equivalent to: mkisofs -o output.iso -V volumeId -sysid systemId -l -allow-lowercase files...
 */
class Iso9660Creator(
    private val volumeId: String = "UNDERTALE",
    private val systemId: String = "PLAYSTATION"
) {
    companion object {
        private const val SECTOR_SIZE = 2048
        private const val SYSTEM_AREA_SECTORS = 16
    }

    class IsoFile(val name: String, val data: ByteArray)

    fun create(files: List<IsoFile>): ByteArray {
        // Layout:
        // Sectors 0-15:  System Area (zeros)
        // Sector 16:     Primary Volume Descriptor
        // Sector 17:     Volume Descriptor Set Terminator
        // Sector 18:     L Path Table (Little Endian)
        // Sector 19:     M Path Table (Big Endian)
        // Sector 20:     Root Directory
        // Sector 21+:    File data

        val rootDirSector = 20
        val rootDirSectors = 1
        var nextDataSector = rootDirSector + rootDirSectors

        // Calculate file placements
        data class FilePlacement(val file: IsoFile, val sector: Int, val sectorCount: Int)

        val placements = mutableListOf<FilePlacement>()
        for (f in files) {
            val sectorCount = (f.data.size + SECTOR_SIZE - 1) / SECTOR_SIZE
            placements.add(FilePlacement(f, nextDataSector, if (f.data.size > 0) sectorCount.coerceAtLeast(1) else 1))
            nextDataSector += placements.last().sectorCount
        }

        val totalSectors = nextDataSector
        val pathTableSize = 10 // single root directory entry: 1 + 1 + 4 + 2 + 1 + 1(padding)
        val lPathTableSector = 18
        val mPathTableSector = 19

        val writer = ByteWriter(totalSectors * SECTOR_SIZE)

        // ---- System Area (sectors 0-15) ----
        writer.writeZeroPadding(SECTOR_SIZE * SYSTEM_AREA_SECTORS)

        // ---- Primary Volume Descriptor (sector 16) ----
        val pvdStart = writer.size
        writer.writeByte(0x01) // Type Code: Primary Volume Descriptor
        writeAscii(writer, "CD001") // Standard Identifier
        writer.writeByte(0x01) // Version
        writer.writeByte(0x00) // Unused Field
        writePaddedString(writer, systemId, 32) // System Identifier
        writePaddedString(writer, volumeId, 32) // Volume Identifier
        writer.writeZeroPadding(8) // Unused Field
        writeBothEndian32(writer, totalSectors) // Volume Space Size
        writer.writeZeroPadding(32) // Unused Field
        writeBothEndian16(writer, 1) // Volume Set Size
        writeBothEndian16(writer, 1) // Volume Sequence Number
        writeBothEndian16(writer, SECTOR_SIZE) // Logical Block Size
        writeBothEndian32(writer, pathTableSize) // Path Table Size
        writeInt32LE(writer, lPathTableSector) // Type L Path Table Location
        writeInt32LE(writer, 0) // Optional Type L Path Table Location
        writeInt32BE(writer, mPathTableSector) // Type M Path Table Location
        writeInt32BE(writer, 0) // Optional Type M Path Table Location

        // Root Directory Record (inline in PVD, 34 bytes)
        writeDirectoryRecord(writer, rootDirSector, SECTOR_SIZE, 0x02, byteArrayOf(0x00))

        writePaddedString(writer, "", 128) // Volume Set Identifier
        writePaddedString(writer, "", 128) // Publisher Identifier
        writePaddedString(writer, "", 128) // Data Preparer Identifier
        writePaddedString(writer, "BUTTERSCOTCH PREPROCESSOR", 128) // Application Identifier
        writePaddedString(writer, "", 37) // Copyright File Identifier
        writePaddedString(writer, "", 37) // Abstract File Identifier
        writePaddedString(writer, "", 37) // Bibliographic File Identifier
        writePvdDateTime(writer) // Volume Creation Date and Time
        writePvdDateTime(writer) // Volume Modification Date and Time
        writeEmptyPvdDateTime(writer) // Volume Expiration Date and Time
        writePvdDateTime(writer) // Volume Effective Date and Time
        writer.writeByte(0x01) // File Structure Version
        writer.writeByte(0x00) // Reserved

        // Pad remainder of PVD sector
        writer.writeZeroPadding(SECTOR_SIZE - (writer.size - pvdStart))

        // ---- Volume Descriptor Set Terminator (sector 17) ----
        val termStart = writer.size
        writer.writeByte(0xFF) // Type Code: Terminator
        writeAscii(writer, "CD001") // Standard Identifier
        writer.writeByte(0x01) // Version
        writer.writeZeroPadding(SECTOR_SIZE - (writer.size - termStart))

        // ---- L Path Table (sector 18, Little Endian) ----
        val lptStart = writer.size
        writer.writeByte(0x01) // Length of Directory Identifier
        writer.writeByte(0x00) // Extended Attribute Record Length
        writeInt32LE(writer, rootDirSector) // Location of Extent
        writeInt16LE(writer, 1) // Directory Number of Parent Directory
        writer.writeByte(0x00) // Directory Identifier (root)
        writer.writeByte(0x00) // Padding
        writer.writeZeroPadding(SECTOR_SIZE - (writer.size - lptStart))

        // ---- M Path Table (sector 19, Big Endian) ----
        val mptStart = writer.size
        writer.writeByte(0x01) // Length of Directory Identifier
        writer.writeByte(0x00) // Extended Attribute Record Length
        writeInt32BE(writer, rootDirSector) // Location of Extent
        writeInt16BE(writer, 1) // Directory Number of Parent Directory
        writer.writeByte(0x00) // Directory Identifier (root)
        writer.writeByte(0x00) // Padding
        writer.writeZeroPadding(SECTOR_SIZE - (writer.size - mptStart))

        // ---- Root Directory (sector 20) ----
        val dirStart = writer.size

        // "." entry (self)
        writeDirectoryRecord(writer, rootDirSector, SECTOR_SIZE, 0x02, byteArrayOf(0x00))

        // ".." entry (parent, same as root for top-level)
        writeDirectoryRecord(writer, rootDirSector, SECTOR_SIZE, 0x02, byteArrayOf(0x01))

        // File entries
        for (p in placements) {
            val identifier = "${p.file.name};1".encodeToByteArray()
            writeDirectoryRecord(writer, p.sector, p.file.data.size, 0x00, identifier)
        }

        // Pad directory to sector boundary
        writer.writeZeroPadding(SECTOR_SIZE - (writer.size - dirStart))

        // ---- File Data ----
        for (p in placements) {
            writer.writeByteArray(p.file.data)
            val remainder = p.file.data.size % SECTOR_SIZE
            if (remainder != 0) {
                writer.writeZeroPadding(SECTOR_SIZE - remainder)
            }
        }

        return writer.getAsByteArray()
    }

    private fun writeDirectoryRecord(
        writer: ByteWriter,
        extentSector: Int,
        dataLength: Int,
        flags: Int,
        identifier: ByteArray
    ) {
        // Record length must be even
        val baseLength = 33 + identifier.size
        val paddingByte = if (baseLength % 2 != 0) 1 else 0
        val recordLength = baseLength + paddingByte

        writer.writeByte(recordLength) // Length of Directory Record
        writer.writeByte(0x00) // Extended Attribute Record Length
        writeBothEndian32(writer, extentSector) // Location of Extent
        writeBothEndian32(writer, dataLength) // Data Length
        writeDirectoryDateTime(writer) // Recording Date and Time
        writer.writeByte(flags) // File Flags
        writer.writeByte(0x00) // File Unit Size
        writer.writeByte(0x00) // Interleave Gap Size
        writeBothEndian16(writer, 1) // Volume Sequence Number
        writer.writeByte(identifier.size) // Length of File Identifier
        writer.writeByteArray(identifier) // File Identifier
        if (paddingByte > 0) {
            writer.writeByte(0x00) // Padding
        }
    }

    // Both-endian (LE then BE) 32-bit integer
    private fun writeBothEndian32(writer: ByteWriter, value: Int) {
        writeInt32LE(writer, value)
        writeInt32BE(writer, value)
    }

    // Both-endian (LE then BE) 16-bit integer
    private fun writeBothEndian16(writer: ByteWriter, value: Int) {
        writeInt16LE(writer, value)
        writeInt16BE(writer, value)
    }

    private fun writeInt32LE(writer: ByteWriter, value: Int) {
        writer.writeByte(value and 0xFF)
        writer.writeByte((value shr 8) and 0xFF)
        writer.writeByte((value shr 16) and 0xFF)
        writer.writeByte((value shr 24) and 0xFF)
    }

    private fun writeInt32BE(writer: ByteWriter, value: Int) {
        writer.writeByte((value shr 24) and 0xFF)
        writer.writeByte((value shr 16) and 0xFF)
        writer.writeByte((value shr 8) and 0xFF)
        writer.writeByte(value and 0xFF)
    }

    private fun writeInt16LE(writer: ByteWriter, value: Int) {
        writer.writeByte(value and 0xFF)
        writer.writeByte((value shr 8) and 0xFF)
    }

    private fun writeInt16BE(writer: ByteWriter, value: Int) {
        writer.writeByte((value shr 8) and 0xFF)
        writer.writeByte(value and 0xFF)
    }

    private fun writeAscii(writer: ByteWriter, str: String) {
        writer.writeByteArray(str.encodeToByteArray())
    }

    // Write a string padded with spaces to the given length
    private fun writePaddedString(writer: ByteWriter, str: String, length: Int) {
        val bytes = str.encodeToByteArray()
        val toCopy = bytes.size.coerceAtMost(length)
        for (i in 0 until toCopy) {
            writer.writeByte(bytes[i].toInt())
        }
        for (i in toCopy until length) {
            writer.writeByte(0x20) // Space
        }
    }

    // PVD date/time format: 17 ASCII bytes "YYYYMMDDHHmmsscc" + 1 byte GMT offset
    private fun writePvdDateTime(writer: ByteWriter) {
        writeAscii(writer, "2026031500000000") // 2026-03-15 00:00:00.00
        writer.writeByte(0x00) // GMT offset
    }

    // Empty PVD date/time (all zeros means not specified)
    private fun writeEmptyPvdDateTime(writer: ByteWriter) {
        for (i in 0 until 17) {
            writer.writeByte(0x30) // '0'
        }
    }

    // Directory record date/time: 7 binary bytes
    private fun writeDirectoryDateTime(writer: ByteWriter) {
        writer.writeByte(126) // Years since 1900 (2026)
        writer.writeByte(3)   // Month
        writer.writeByte(15)  // Day
        writer.writeByte(0)   // Hour
        writer.writeByte(0)   // Minute
        writer.writeByte(0)   // Second
        writer.writeByte(0)   // GMT offset (15-min intervals)
    }
}
