package com.qishimeng.game.channel

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelCodeReaderTest {
    @Test
    fun readsFullUtf8PackageCodeFromSigningBlock() {
        val apk = createApk("1-25-6#10028")
        try {
            assertEquals("1-25-6#10028", ChannelCodeReader.read(apk.absolutePath))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun missingOrMalformedApkReturnsEmptyCode() {
        val apk = File.createTempFile("unsigned", ".apk")
        try {
            apk.writeBytes("not-an-apk".toByteArray())
            assertEquals("", ChannelCodeReader.read(apk.absolutePath))
        } finally {
            apk.delete()
        }
    }

    private fun createApk(channelCode: String): File {
        fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        fun long(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
        val code = channelCode.toByteArray(Charsets.UTF_8)
        val pair = long((code.size + 4).toLong()) + int(CHANNEL_ID) + code
        val size = (pair.size + 24).toLong()
        val signingBlock = long(size) + pair + long(size) + "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        val prefix = "apk-prefix".toByteArray()
        val centralDirectory = "PK\u0001\u0002fake-central-directory".toByteArray(Charsets.ISO_8859_1)
        val centralOffset = prefix.size + signingBlock.size
        val eocd = ByteArray(22)
        eocd[0] = 'P'.code.toByte()
        eocd[1] = 'K'.code.toByte()
        eocd[2] = 5
        eocd[3] = 6
        ByteBuffer.wrap(eocd).order(ByteOrder.LITTLE_ENDIAN).putInt(12, centralDirectory.size).putInt(16, centralOffset)
        return File.createTempFile("signed", ".apk").apply {
            writeBytes(prefix + signingBlock + centralDirectory + eocd)
        }
    }

    private companion object {
        const val CHANNEL_ID = 0x454D4348
    }
}
