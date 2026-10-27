package dev.atlas.coach.sync.relay

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ProtectedFrameKind(val wireValue: Int) { TEXT(0), BINARY(1) }

data class ProtectedFrame(val kind: ProtectedFrameKind, val data: ByteArray)

/** Paseo's authenticated record framing; a single record carries one application frame. */
object ProtectedRecordCodec {
    const val MAX_FRAME_BYTES = 65_536
    private const val HEADER_BYTES = 6
    const val MAX_CHUNK_DATA_BYTES =
        NoiseCipherState.NOISE_MAX_MESSAGE_BYTES - NoiseCipherState.NOISE_TAG_BYTES - HEADER_BYTES
    private const val LENGTH_PREFIX_BYTES = 2
    private const val FIRST = 0x01
    private const val FINAL = 0x02
    private const val CHUNK_WIRE_OVERHEAD = LENGTH_PREFIX_BYTES + HEADER_BYTES + NoiseCipherState.NOISE_TAG_BYTES
    private val EMPTY_AD = ByteArray(0)

    fun chunkCount(frameBytes: Int): Int {
        require(frameBytes in 0..MAX_FRAME_BYTES)
        return maxOf(1, (frameBytes + MAX_CHUNK_DATA_BYTES - 1) / MAX_CHUNK_DATA_BYTES)
    }

    fun wireByteLength(frameBytes: Int): Int {
        val chunks = chunkCount(frameBytes)
        return Math.addExact(frameBytes, Math.multiplyExact(chunks, CHUNK_WIRE_OVERHEAD))
    }

    @Throws(NoiseProtocolException::class)
    fun encode(kind: ProtectedFrameKind, frame: ByteArray, sender: NoiseCipherState): ByteArray {
        if (frame.size > MAX_FRAME_BYTES) throw NoiseProtocolException("frame too large")
        val chunks = chunkCount(frame.size)
        val output = ByteArray(wireByteLength(frame.size))
        var outputOffset = 0
        repeat(chunks) { index ->
            val start = index * MAX_CHUNK_DATA_BYTES
            val count = minOf(MAX_CHUNK_DATA_BYTES, frame.size - start)
            val plaintext = ByteArray(HEADER_BYTES + count)
            plaintext[0] = kind.wireValue.toByte()
            plaintext[1] = ((if (index == 0) FIRST else 0) or (if (index == chunks - 1) FINAL else 0)).toByte()
            ByteBuffer.wrap(plaintext, 2, 4).order(ByteOrder.BIG_ENDIAN).putInt(frame.size)
            if (count > 0) System.arraycopy(frame, start, plaintext, HEADER_BYTES, count)
            val ciphertext = sender.encryptWithAd(EMPTY_AD, plaintext)
            require(ciphertext.size <= 0xffff)
            output[outputOffset] = (ciphertext.size ushr 8).toByte()
            output[outputOffset + 1] = ciphertext.size.toByte()
            System.arraycopy(ciphertext, 0, output, outputOffset + LENGTH_PREFIX_BYTES, ciphertext.size)
            outputOffset += LENGTH_PREFIX_BYTES + ciphertext.size
        }
        return output
    }

    /** Any failure is fatal to the channel; callers must close and discard this cipher state. */
    @Throws(NoiseProtocolException::class)
    fun decode(record: ByteArray, receiver: NoiseCipherState): ProtectedFrame {
        if (record.isEmpty()) throw NoiseProtocolException("empty record")
        if (record.size > wireByteLength(MAX_FRAME_BYTES)) throw NoiseProtocolException("record too large")
        var offset = 0
        var assembly: Assembly? = null
        while (offset < record.size) {
            if (assembly != null && assembly.index >= assembly.expectedChunks) {
                throw NoiseProtocolException("unexpected chunk after final")
            }
            if (record.size - offset < LENGTH_PREFIX_BYTES) throw NoiseProtocolException("record truncated")
            val ciphertextLength = ((record[offset].toInt() and 0xff) shl 8) or
                (record[offset + 1].toInt() and 0xff)
            val chunkStart = offset + LENGTH_PREFIX_BYTES
            if (ciphertextLength !in (NoiseCipherState.NOISE_TAG_BYTES + HEADER_BYTES)..NoiseCipherState.NOISE_MAX_MESSAGE_BYTES) {
                throw NoiseProtocolException("invalid chunk length")
            }
            if (record.size - chunkStart < ciphertextLength) throw NoiseProtocolException("record truncated")
            val plaintext = receiver.decryptWithAd(EMPTY_AD, record.copyOfRange(chunkStart, chunkStart + ciphertextLength))
            val chunk = parseChunk(plaintext)
            assembly = if (assembly == null) startAssembly(chunk, record.size) else continueAssembly(assembly, chunk)
            offset = chunkStart + ciphertextLength
        }
        val complete = assembly ?: throw NoiseProtocolException("empty record")
        if (complete.index != complete.expectedChunks || complete.written != complete.totalLength) {
            throw NoiseProtocolException("record incomplete")
        }
        return ProtectedFrame(complete.kind, complete.frame)
    }

    private fun parseChunk(plaintext: ByteArray): Chunk {
        if (plaintext.size < HEADER_BYTES) throw NoiseProtocolException("chunk header truncated")
        val kind = when (plaintext[0].toInt() and 0xff) {
            0 -> ProtectedFrameKind.TEXT
            1 -> ProtectedFrameKind.BINARY
            else -> throw NoiseProtocolException("invalid frame kind")
        }
        val flags = plaintext[1].toInt() and 0xff
        if (flags and (FIRST or FINAL).inv() != 0) throw NoiseProtocolException("reserved flag set")
        val total = ByteBuffer.wrap(plaintext, 2, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
        if (total > MAX_FRAME_BYTES) throw NoiseProtocolException("frame too large")
        return Chunk(
            kind = kind,
            first = flags and FIRST != 0,
            final = flags and FINAL != 0,
            totalLength = total.toInt(),
            data = plaintext.copyOfRange(HEADER_BYTES, plaintext.size),
        )
    }

    private fun startAssembly(chunk: Chunk, recordLength: Int): Assembly {
        if (!chunk.first) throw NoiseProtocolException("record does not start with FIRST")
        if (wireByteLength(chunk.totalLength) != recordLength) throw NoiseProtocolException("record length mismatch")
        val assembly = Assembly(chunk.kind, chunk.totalLength, chunkCount(chunk.totalLength), ByteArray(chunk.totalLength))
        return append(assembly, chunk)
    }

    private fun continueAssembly(assembly: Assembly, chunk: Chunk): Assembly {
        if (chunk.first) throw NoiseProtocolException("FIRST repeated inside record")
        if (chunk.kind != assembly.kind) throw NoiseProtocolException("frame kind changed inside record")
        if (chunk.totalLength != assembly.totalLength) throw NoiseProtocolException("frame length changed inside record")
        return append(assembly, chunk)
    }

    private fun append(assembly: Assembly, chunk: Chunk): Assembly {
        val shouldBeFinal = assembly.index == assembly.expectedChunks - 1
        if (chunk.final != shouldBeFinal) throw NoiseProtocolException("FINAL flag mismatch")
        val expectedData = if (shouldBeFinal) {
            assembly.totalLength - assembly.index * MAX_CHUNK_DATA_BYTES
        } else MAX_CHUNK_DATA_BYTES
        if (chunk.data.size != expectedData) throw NoiseProtocolException("chunk size mismatch")
        System.arraycopy(chunk.data, 0, assembly.frame, assembly.written, chunk.data.size)
        assembly.written += chunk.data.size
        assembly.index += 1
        return assembly
    }

    private data class Chunk(
        val kind: ProtectedFrameKind,
        val first: Boolean,
        val final: Boolean,
        val totalLength: Int,
        val data: ByteArray,
    )

    private data class Assembly(
        val kind: ProtectedFrameKind,
        val totalLength: Int,
        val expectedChunks: Int,
        val frame: ByteArray,
        var written: Int = 0,
        var index: Int = 0,
    )
}
