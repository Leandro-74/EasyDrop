package dev.leandro.easydrop.protocol

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Canal de frames cifrados sobre um par de streams.
 *
 * Cada frame é enviado como `u32 ciphertextLength` + ciphertext Noise. O prefixo não é
 * cifrado; o conteúdo e o tipo são.
 */
class SecureChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val transport: NoiseTransport,
) {

    companion object {
        const val MAX_CIPHERTEXT = 65535
    }

    fun send(frame: Frame) {
        val plaintext = FrameCodec.encode(frame)
        val ciphertext = transport.encrypt(plaintext)
        if (ciphertext.size > MAX_CIPHERTEXT) {
            throw ProtocolException("ciphertext de ${ciphertext.size} bytes acima do limite")
        }
        output.write(ByteBuffer.allocate(4).putInt(ciphertext.size).array())
        output.write(ciphertext)
        output.flush()
    }

    fun receive(): Frame? {
        val header = readExactly(4, allowEof = true) ?: return null
        val length = ByteBuffer.wrap(header).int
        if (length !in 1..MAX_CIPHERTEXT) {
            throw ProtocolException("tamanho de ciphertext inválido: $length")
        }
        val ciphertext = readExactly(length, allowEof = false)!!
        return FrameCodec.decode(transport.decrypt(ciphertext))
    }

    private fun readExactly(length: Int, allowEof: Boolean): ByteArray? {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) {
                if (read == 0 && allowEof) return null
                throw ProtocolException("stream terminou no meio do frame")
            }
            read += count
        }
        return bytes
    }
}
