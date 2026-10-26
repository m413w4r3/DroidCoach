package dev.atlas.coach.sync.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtectedRecordCodecTest {
    @Test
    fun roundTripsEmptyTextAndMultiChunkFrames() {
        listOf(ByteArray(0), "atlas relay".toByteArray(), ByteArray(ProtectedRecordCodec.MAX_CHUNK_DATA_BYTES + 23) { it.toByte() }).forEach { frame ->
            val (sender, receiver) = cipherPair()
            val encoded = ProtectedRecordCodec.encode(ProtectedFrameKind.TEXT, frame, sender)
            assertEquals(ProtectedRecordCodec.wireByteLength(frame.size), encoded.size)
            val decoded = ProtectedRecordCodec.decode(encoded, receiver)
            assertEquals(ProtectedFrameKind.TEXT, decoded.kind)
            assertArrayEquals(frame, decoded.data)
        }
    }

    @Test
    fun rejectsTamperingReplayAndReorderedRecords() {
        val (sender, receiver) = cipherPair()
        val first = ProtectedRecordCodec.encode(ProtectedFrameKind.TEXT, byteArrayOf(1), sender)
        val second = ProtectedRecordCodec.encode(ProtectedFrameKind.TEXT, byteArrayOf(2), sender)
        assertThrows(NoiseProtocolException::class.java) { ProtectedRecordCodec.decode(second, receiver) }
        assertArrayEquals(byteArrayOf(1), ProtectedRecordCodec.decode(first, receiver).data)

        val (sender2, receiver2) = cipherPair()
        val valid = ProtectedRecordCodec.encode(ProtectedFrameKind.TEXT, byteArrayOf(3), sender2)
        assertArrayEquals(byteArrayOf(3), ProtectedRecordCodec.decode(valid, receiver2).data)
        assertThrows(NoiseProtocolException::class.java) { ProtectedRecordCodec.decode(valid, receiver2) }

        val (sender3, receiver3) = cipherPair()
        val tampered = ProtectedRecordCodec.encode(ProtectedFrameKind.TEXT, byteArrayOf(4), sender3).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(NoiseProtocolException::class.java) { ProtectedRecordCodec.decode(tampered, receiver3) }
        assertEquals(java.math.BigInteger.ZERO, receiver3.nonce())
    }

    @Test
    fun rejectsMalformedChunkLengthsTruncationAndTrailingBytes() {
        val (_, receiver) = cipherPair()
        assertThrows(NoiseProtocolException::class.java) { ProtectedRecordCodec.decode(ByteArray(0), receiver) }
        assertThrows(NoiseProtocolException::class.java) { ProtectedRecordCodec.decode(byteArrayOf(0, 0), receiver) }
        val (sender, receiver2) = cipherPair()
        val record = ProtectedRecordCodec.encode(ProtectedFrameKind.TEXT, byteArrayOf(1, 2, 3), sender)
        assertThrows(NoiseProtocolException::class.java) { ProtectedRecordCodec.decode(record.copyOf(record.size - 1), receiver2) }
        val (sender3, receiver3) = cipherPair()
        val exact = ProtectedRecordCodec.encode(ProtectedFrameKind.TEXT, byteArrayOf(9), sender3)
        assertThrows(NoiseProtocolException::class.java) { ProtectedRecordCodec.decode(exact + byteArrayOf(0), receiver3) }
    }

    @Test
    fun rejectsOversizedFrameBeforeAllocatingAndInvalidAuthenticatedHeaders() {
        val (sender, _) = cipherPair()
        assertThrows(NoiseProtocolException::class.java) {
            ProtectedRecordCodec.encode(ProtectedFrameKind.TEXT, ByteArray(ProtectedRecordCodec.MAX_FRAME_BYTES + 1), sender)
        }

        val (forger, receiver) = cipherPair()
        val plaintext = ByteArray(7)
        plaintext[0] = 0
        plaintext[1] = 1
        ByteBuffer.wrap(plaintext, 2, 4).order(ByteOrder.BIG_ENDIAN).putInt(ProtectedRecordCodec.MAX_FRAME_BYTES + 1)
        plaintext[6] = 42
        val encrypted = forger.encryptWithAd(ByteArray(0), plaintext)
        val record = byteArrayOf((encrypted.size ushr 8).toByte(), encrypted.size.toByte()) + encrypted
        assertThrows(NoiseProtocolException::class.java) { ProtectedRecordCodec.decode(record, receiver) }

        val (flagSender, flagReceiver) = cipherPair()
        val invalidFlags = byteArrayOf(0, 0x05, 0, 0, 0, 1, 7)
        val forgedFlags = flagSender.encryptWithAd(ByteArray(0), invalidFlags)
        val wrapped = byteArrayOf((forgedFlags.size ushr 8).toByte(), forgedFlags.size.toByte()) + forgedFlags
        assertThrows(NoiseProtocolException::class.java) { ProtectedRecordCodec.decode(wrapped, flagReceiver) }
    }

    private fun cipherPair(): Pair<NoiseCipherState, NoiseCipherState> {
        val key = ByteArray(32) { 0x35 }
        return NoiseCipherState().also { it.initializeKey(key) } to NoiseCipherState().also { it.initializeKey(key) }
    }
}
