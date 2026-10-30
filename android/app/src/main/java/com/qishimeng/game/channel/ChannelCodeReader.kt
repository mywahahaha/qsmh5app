package com.qishimeng.game.channel

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object ChannelCodeReader {
    private val signingBlockMagic = "APK Sig Block 42".toByteArray(StandardCharsets.US_ASCII)
    private val eocdSignature = byteArrayOf(0x50, 0x4b, 0x05, 0x06)

    fun read(apkPath: String): String = runCatching {
        RandomAccessFile(apkPath, "r").use { file ->
            val eocdOffset = findEocd(file) ?: return ""
            file.seek(eocdOffset + 16)
            val centralDirectoryOffset = file.readUnsignedIntLittleEndian()
            if (centralDirectoryOffset < 24) return ""

            file.seek(centralDirectoryOffset - 24)
            val footer = ByteArray(24)
            file.readFully(footer)
            val blockSize = ByteBuffer.wrap(footer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (blockSize < 24 || blockSize > MAX_SIGNING_BLOCK_SIZE || !footer.copyOfRange(8, 24).contentEquals(signingBlockMagic)) return ""
            val blockStart = centralDirectoryOffset - blockSize - 8
            if (blockStart < 0) return ""
            file.seek(blockStart)
            if (file.readLongLittleEndian() != blockSize) return ""

            val pairsEnd = centralDirectoryOffset - 24
            var cursor = blockStart + 8
            while (cursor < pairsEnd) {
                if (pairsEnd - cursor < 8) return ""
                file.seek(cursor)
                val pairSize = file.readLongLittleEndian()
                if (pairSize < 4 || pairSize > pairsEnd - cursor - 8) return ""
                val pairId = file.readUnsignedIntLittleEndian()
                val valueSize = pairSize - 4
                if (pairId == CHANNEL_BLOCK_ID) {
                    if (valueSize > MAX_CHANNEL_BYTES) return ""
                    val value = ByteArray(valueSize.toInt())
                    file.readFully(value)
                    return decodeUtf8(value).orEmpty()
                }
                cursor += 8 + pairSize
            }
            ""
        }
    }.getOrNull().orEmpty()

    private fun findEocd(file: RandomAccessFile): Long? {
        val searchSize = minOf(file.length(), (EOCD_MIN_SIZE + MAX_ZIP_COMMENT_SIZE).toLong())
        if (searchSize < EOCD_MIN_SIZE) return null
        val searchStart = file.length() - searchSize
        val tail = ByteArray(searchSize.toInt())
        file.seek(searchStart)
        file.readFully(tail)
        for (offset in tail.size - EOCD_MIN_SIZE downTo 0) {
            if (!tail.copyOfRange(offset, offset + 4).contentEquals(eocdSignature)) continue
            val commentLength = ByteBuffer.wrap(tail, offset + 20, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
            if (offset + EOCD_MIN_SIZE + commentLength == tail.size) return searchStart + offset.toLong()
        }
        return null
    }

    private fun decodeUtf8(value: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString()
    }.getOrNull()

    private fun RandomAccessFile.readUnsignedIntLittleEndian(): Long {
        val bytes = ByteArray(4)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    }

    private fun RandomAccessFile.readLongLittleEndian(): Long {
        val bytes = ByteArray(8)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private const val CHANNEL_BLOCK_ID = 0x454D4348L
    private const val MAX_CHANNEL_BYTES = 4096L
    private const val MAX_SIGNING_BLOCK_SIZE = 64L * 1024 * 1024
    private const val EOCD_MIN_SIZE = 22
    private const val MAX_ZIP_COMMENT_SIZE = 0xffff
}
