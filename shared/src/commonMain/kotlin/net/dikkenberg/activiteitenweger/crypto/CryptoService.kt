package net.dikkenberg.activiteitenweger.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import dev.whyoleg.cryptography.random.CryptographyRandom

class CryptoService(
    private val provider: CryptographyProvider = CryptographyProvider.Default,
) {
    data class RawKeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

    private val sha256 by lazy { provider.get(SHA256).hasher() }
    private val ed25519 by lazy { provider.get(EdDSA) }
    private val x25519 by lazy { provider.get(XDH) }
    private val chacha by lazy { provider.get(ChaCha20Poly1305) }

    fun randomBytes(size: Int): ByteArray = CryptographyRandom.nextBytes(size)

    suspend fun sha256(data: ByteArray): ByteArray = sha256.hash(data)

    suspend fun generateEd25519KeyPair(): RawKeyPair {
        val pair = ed25519.keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
        return RawKeyPair(
            publicKey = pair.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.RAW),
            privateKey = pair.privateKey.encodeToByteArray(EdDSA.PrivateKey.Format.RAW),
        )
    }

    suspend fun signEd25519(privateKeyRaw: ByteArray, data: ByteArray): ByteArray {
        val key = ed25519
            .privateKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArray(EdDSA.PrivateKey.Format.RAW, privateKeyRaw)
        return key.signatureGenerator().generateSignature(data)
    }

    suspend fun generateX25519KeyPair(): RawKeyPair {
        val pair = x25519.keyPairGenerator(XDH.Curve.X25519).generateKey()
        return RawKeyPair(
            publicKey = pair.publicKey.encodeToByteArray(XDH.PublicKey.Format.RAW),
            privateKey = pair.privateKey.encodeToByteArray(XDH.PrivateKey.Format.RAW),
        )
    }

    suspend fun createSelfKeyEnvelope(
        vaultId: String,
        vaultKey: ByteArray,
        signingPrivateKey: ByteArray,
        encryptionPrivateKey: ByteArray,
    ): ByteArray {
        val wrappingKey = sha256(
            "AW-SELF-ENVELOPE-V1".encodeToByteArray() +
                signingPrivateKey + encryptionPrivateKey + vaultId.encodeToByteArray()
        )
        val nonce = randomBytes(24)
        val ciphertext = xChaCha20Poly1305Encrypt(
            key = wrappingKey,
            nonce24 = nonce,
            plaintext = vaultKey,
            associatedData = vaultId.encodeToByteArray(),
        )
        return byteArrayOf(1) + nonce + ciphertext
    }

    suspend fun openSelfKeyEnvelope(
        vaultId: String,
        envelope: ByteArray,
        signingPrivateKey: ByteArray,
        encryptionPrivateKey: ByteArray,
    ): ByteArray {
        require(envelope.size >= 1 + 24 + 16 && envelope[0] == 1.toByte()) { "Unknown envelope" }
        val wrappingKey = sha256(
            "AW-SELF-ENVELOPE-V1".encodeToByteArray() +
                signingPrivateKey + encryptionPrivateKey + vaultId.encodeToByteArray()
        )
        return xChaCha20Poly1305Decrypt(
            key = wrappingKey,
            nonce24 = envelope.copyOfRange(1, 25),
            ciphertext = envelope.copyOfRange(25, envelope.size),
            associatedData = vaultId.encodeToByteArray(),
        )
    }

    @OptIn(DelicateCryptographyApi::class)
    suspend fun xChaCha20Poly1305Encrypt(
        key: ByteArray,
        nonce24: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(key.size == 32)
        require(nonce24.size == 24)
        val subKey = hChaCha20(key, nonce24.copyOfRange(0, 16))
        val nonce12 = ByteArray(12)
        nonce24.copyInto(nonce12, destinationOffset = 4, startIndex = 16, endIndex = 24)
        val keyMaterial = chacha.keyDecoder().decodeFromByteArray(
            ChaCha20Poly1305.Key.Format.RAW,
            subKey,
        )
        return keyMaterial.cipher().encryptWithIv(nonce12, plaintext, associatedData)
    }

    @OptIn(DelicateCryptographyApi::class)
    suspend fun xChaCha20Poly1305Decrypt(
        key: ByteArray,
        nonce24: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(key.size == 32)
        require(nonce24.size == 24)
        val subKey = hChaCha20(key, nonce24.copyOfRange(0, 16))
        val nonce12 = ByteArray(12)
        nonce24.copyInto(nonce12, destinationOffset = 4, startIndex = 16, endIndex = 24)
        val keyMaterial = chacha.keyDecoder().decodeFromByteArray(
            ChaCha20Poly1305.Key.Format.RAW,
            subKey,
        )
        return keyMaterial.cipher().decryptWithIv(nonce12, ciphertext, associatedData)
    }

    private fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32)
        require(nonce16.size == 16)
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = readLeInt(key, i * 4)
        for (i in 0 until 4) state[12 + i] = readLeInt(nonce16, i * 4)

        repeat(10) {
            quarter(state, 0, 4, 8, 12)
            quarter(state, 1, 5, 9, 13)
            quarter(state, 2, 6, 10, 14)
            quarter(state, 3, 7, 11, 15)
            quarter(state, 0, 5, 10, 15)
            quarter(state, 1, 6, 11, 12)
            quarter(state, 2, 7, 8, 13)
            quarter(state, 3, 4, 9, 14)
        }

        val out = ByteArray(32)
        val indices = intArrayOf(0, 1, 2, 3, 12, 13, 14, 15)
        indices.forEachIndexed { i, index -> writeLeInt(state[index], out, i * 4) }
        return out
    }

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun Int.rotateLeft(bits: Int): Int = (this shl bits) or (this ushr (32 - bits))

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun writeLeInt(value: Int, out: ByteArray, offset: Int) {
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
        out[offset + 2] = (value ushr 16).toByte()
        out[offset + 3] = (value ushr 24).toByte()
    }
}
