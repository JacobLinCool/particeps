package cool.jacoblin.particeps.core.export

import java.io.FilterOutputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM encryption (NIST SP 800-38D, 96-bit nonce, 128-bit tag) that streams.
 *
 * Android's Conscrypt AEAD cipher buffers every plaintext byte until `doFinal`, so a
 * `CipherOutputStream` over AES/GCM holds the whole export in memory and a five-day study's export
 * exhausts the app heap. This writer produces byte-for-byte the same `ciphertext || tag` as
 * `Cipher("AES/GCM/NoPadding")` from AES-CTR for the keystream and its own GHASH, so memory stays
 * constant and the wire format, and every reader, are unchanged. It encrypts only; readers keep
 * the platform cipher, which must authenticate before releasing plaintext anyway.
 */
internal class StreamingAesGcmOutputStream(
    output: OutputStream,
    key: SecretKey,
    nonce: ByteArray,
    aad: ByteArray,
) : FilterOutputStream(output) {
    private val ctr: Cipher
    private val tagMask: ByteArray
    private val ghash: Ghash
    private var aadBytes = aad.size.toLong()
    private var textBytes = 0L
    private var closed = false

    init {
        require(nonce.size == NONCE_BYTES) { "AES-GCM streaming requires a 96-bit nonce" }
        val raw = SecretKeySpec(key.encoded, "AES")
        val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, raw) }
        ghash = Ghash(ecb.doFinal(ByteArray(BLOCK)))
        val j0 = nonce.copyOf(BLOCK).also { it[BLOCK - 1] = 1 }
        tagMask = ecb.doFinal(j0)
        val firstCounter = nonce.copyOf(BLOCK).also { it[BLOCK - 1] = 2 }
        ctr = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, raw, IvParameterSpec(firstCounter))
        }
        ghash.update(aad, 0, aad.size)
        ghash.padToBlock()
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!closed) { "Stream is closed" }
        if (len == 0) return
        // GCM's 32-bit block counter must not wrap: at most 2^32 - 2 blocks of plaintext.
        require(textBytes + len <= MAXIMUM_PLAINTEXT_BYTES) { "AES-GCM plaintext limit exceeded" }
        val encrypted = ctr.update(b, off, len) ?: return
        textBytes += len
        ghash.update(encrypted, 0, encrypted.size)
        out.write(encrypted)
    }

    override fun flush() = out.flush()

    override fun close() {
        if (closed) return
        closed = true
        try {
            ctr.doFinal().takeIf { it.isNotEmpty() }?.let { tail ->
                ghash.update(tail, 0, tail.size)
                out.write(tail)
            }
            ghash.padToBlock()
            val lengths = ByteArray(BLOCK)
            putLong(lengths, 0, aadBytes * 8)
            putLong(lengths, 8, textBytes * 8)
            ghash.update(lengths, 0, BLOCK)
            val tag = ghash.digest()
            for (i in 0 until BLOCK) tag[i] = (tag[i].toInt() xor tagMask[i].toInt()).toByte()
            out.write(tag)
            out.flush()
        } finally {
            out.close()
        }
    }

    private fun putLong(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) target[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }

    private companion object {
        const val BLOCK = 16
        const val NONCE_BYTES = 12
        const val MAXIMUM_PLAINTEXT_BYTES = (0xFFFFFFFFL - 1) * BLOCK
    }
}

/**
 * GHASH over GF(2^128) with Shoup's 4-bit tables. It keeps a partial block, so callers may feed
 * arbitrary slices; [padToBlock] zero-pads at the AAD/ciphertext boundary as GCM requires.
 */
internal class Ghash(h: ByteArray) {
    private val tableHigh = LongArray(16)
    private val tableLow = LongArray(16)
    private var zHigh = 0L
    private var zLow = 0L
    private val pending = ByteArray(16)
    private var pendingSize = 0

    init {
        require(h.size == 16)
        var vHigh = readLong(h, 0)
        var vLow = readLong(h, 8)
        // tables[i] = i * H in GCM bit order, with index bit 3 the most significant (x^0).
        tableHigh[8] = vHigh; tableLow[8] = vLow
        var i = 4
        while (i > 0) {
            val carry = vLow and 1L
            vLow = (vLow ushr 1) or (vHigh shl 63)
            vHigh = (vHigh ushr 1) xor (if (carry != 0L) R else 0L)
            tableHigh[i] = vHigh; tableLow[i] = vLow
            i = i shr 1
        }
        i = 2
        while (i < 16) {
            for (j in 1 until i) {
                tableHigh[i + j] = tableHigh[i] xor tableHigh[j]
                tableLow[i + j] = tableLow[i] xor tableLow[j]
            }
            i = i shl 1
        }
    }

    fun update(data: ByteArray, offset: Int, length: Int) {
        var position = offset
        var remaining = length
        if (pendingSize > 0) {
            val take = minOf(16 - pendingSize, remaining)
            System.arraycopy(data, position, pending, pendingSize, take)
            pendingSize += take
            position += take
            remaining -= take
            if (pendingSize == 16) {
                block(pending, 0)
                pendingSize = 0
            }
        }
        while (remaining >= 16) {
            block(data, position)
            position += 16
            remaining -= 16
        }
        if (remaining > 0) {
            System.arraycopy(data, position, pending, 0, remaining)
            pendingSize = remaining
        }
    }

    fun padToBlock() {
        if (pendingSize == 0) return
        pending.fill(0, pendingSize, 16)
        block(pending, 0)
        pendingSize = 0
    }

    fun digest(): ByteArray {
        check(pendingSize == 0) { "GHASH input is not block aligned" }
        return ByteArray(16).also {
            writeLong(it, 0, zHigh)
            writeLong(it, 8, zLow)
        }
    }

    private fun block(data: ByteArray, offset: Int) {
        zHigh = zHigh xor readLong(data, offset)
        zLow = zLow xor readLong(data, offset + 8)
        multiplyByH()
    }

    /** Z = Z * H, processing Z four bits at a time from its last nibble to its first. */
    private fun multiplyByH() {
        var resultHigh = 0L
        var resultLow = 0L
        for (nibbleIndex in 31 downTo 0) {
            val nibble = if (nibbleIndex >= 16) {
                ((zLow ushr ((31 - nibbleIndex) * 4)) and 0xF).toInt()
            } else {
                ((zHigh ushr ((15 - nibbleIndex) * 4)) and 0xF).toInt()
            }
            if (nibbleIndex != 31) {
                // Multiply the accumulator by x^4 (shift right by four in GCM bit order).
                val dropped = (resultLow and 0xF).toInt()
                resultLow = (resultLow ushr 4) or (resultHigh shl 60)
                resultHigh = (resultHigh ushr 4) xor REDUCTION[dropped]
            }
            resultHigh = resultHigh xor tableHigh[nibble]
            resultLow = resultLow xor tableLow[nibble]
        }
        zHigh = resultHigh
        zLow = resultLow
    }

    private companion object {
        const val R = -0x1f00000000000000L // 0xE1 << 56
        /** Reduction of the four bits shifted out by a 4-bit right shift, folded into the high word. */
        val REDUCTION = LongArray(16) { dropped ->
            var value = 0L
            for (bit in 0 until 4) {
                if ((dropped shr bit) and 1 == 1) value = value xor (R ushr (3 - bit))
            }
            value
        }

        fun readLong(data: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
            return value
        }

        fun writeLong(target: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) target[offset + i] = (value ushr (56 - 8 * i)).toByte()
        }
    }
}
