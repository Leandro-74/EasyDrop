package dev.leandro.easydrop.protocol

import com.southernstorm.noise.protocol.CipherState
import javax.crypto.BadPaddingException
import javax.crypto.ShortBufferException

/**
 * Canal cifrado depois do handshake.
 *
 * Cada direção tem seu próprio [CipherState] e nonce. O nonce nunca é reutilizado:
 * a biblioteca incrementa a cada mensagem.
 */
class NoiseTransport internal constructor(
    private val sender: CipherState,
    private val receiver: CipherState,
) {

    val macLength: Int get() = sender.macLength

    fun encrypt(plaintext: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + sender.macLength)
        val length = try {
            sender.encryptWithAd(null, plaintext, 0, out, 0, plaintext.size)
        } catch (e: ShortBufferException) {
            throw ProtocolException("buffer de cifra insuficiente", e)
        }
        if (length != out.size) throw ProtocolException("tamanho de cifra inesperado: $length")
        return out
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val mac = receiver.macLength
        if (ciphertext.size < mac) throw ProtocolException("ciphertext curto")
        val out = ByteArray(ciphertext.size - mac)
        val length = try {
            receiver.decryptWithAd(null, ciphertext, 0, out, 0, ciphertext.size)
        } catch (e: ShortBufferException) {
            throw ProtocolException("buffer de decifra insuficiente", e)
        } catch (e: BadPaddingException) {
            throw ProtocolException("ciphertext inválido", e)
        }
        if (length != out.size) throw ProtocolException("tamanho de plaintext inesperado: $length")
        return out
    }

    fun destroy() {
        sender.destroy()
        receiver.destroy()
    }
}
