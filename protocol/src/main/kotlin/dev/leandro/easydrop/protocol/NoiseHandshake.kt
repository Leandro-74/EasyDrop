package dev.leandro.easydrop.protocol

import com.southernstorm.noise.protocol.HandshakeState
import java.security.NoSuchAlgorithmException
import javax.crypto.BadPaddingException
import javax.crypto.ShortBufferException

/**
 * Handshake Noise NK.
 *
 * O emissor é o iniciador e usa a chave pública recebida por NFC. O receptor é o
 * respondente e gera uma chave estática efêmera por janela de recebimento.
 */
class NoiseHandshake private constructor(
    private val state: HandshakeState,
    val role: Role,
) {

    enum class Role { INITIATOR, RESPONDER }

    companion object {
        const val PROTOCOL_NAME = "Noise_NK_25519_ChaChaPoly_SHA256"
        const val PROLOGUE = "easydrop/1"

        private const val MAX_MESSAGE = 1024
        private val DEFAULT_PROLOGUE = PROLOGUE.toByteArray(Charsets.US_ASCII)

        fun initiator(
            responderPublicKey: ByteArray,
            prologue: ByteArray = DEFAULT_PROLOGUE,
        ): NoiseHandshake {
            if (responderPublicKey.size != 32) {
                throw ProtocolException("chave pública do receptor deve ter 32 bytes")
            }
            val state = newState(HandshakeState.INITIATOR)
            val remote = state.remotePublicKey
                ?: throw ProtocolException("padrão NK sem chave remota")
            remote.setPublicKey(responderPublicKey, 0)
            state.setPrologue(prologue, 0, prologue.size)
            state.start()
            return NoiseHandshake(state, Role.INITIATOR)
        }

        fun responder(prologue: ByteArray = DEFAULT_PROLOGUE): NoiseHandshake {
            val state = newState(HandshakeState.RESPONDER)
            val local = state.localKeyPair
                ?: throw ProtocolException("padrão NK sem chave local")
            local.generateKeyPair()
            state.setPrologue(prologue, 0, prologue.size)
            state.start()
            return NoiseHandshake(state, Role.RESPONDER)
        }

        private fun newState(role: Int): HandshakeState =
            try {
                HandshakeState(PROTOCOL_NAME, role)
            } catch (e: NoSuchAlgorithmException) {
                throw ProtocolException("Noise indisponível", e)
            }
    }

    /** Chave pública estática da sessão. Só existe no respondente. */
    fun localPublicKey(): ByteArray {
        val local = state.localKeyPair
            ?: throw ProtocolException("papel sem chave estática")
        val key = ByteArray(local.publicKeyLength)
        local.getPublicKey(key, 0)
        return key
    }

    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(MAX_MESSAGE)
        val length = try {
            state.writeMessage(out, 0, payload, 0, payload.size)
        } catch (e: ShortBufferException) {
            throw ProtocolException("mensagem Noise grande demais", e)
        }
        return out.copyOf(length)
    }

    fun readMessage(message: ByteArray): ByteArray {
        val out = ByteArray(MAX_MESSAGE)
        val length = try {
            state.readMessage(message, 0, message.size, out, 0)
        } catch (e: ShortBufferException) {
            throw ProtocolException("mensagem Noise grande demais", e)
        } catch (e: BadPaddingException) {
            throw ProtocolException("handshake Noise inválido", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("mensagem Noise inválida", e)
        }
        return out.copyOf(length)
    }

    fun split(): NoiseTransport {
        val pair = try {
            state.split()
        } catch (e: IllegalStateException) {
            throw ProtocolException("handshake incompleto", e)
        }
        return NoiseTransport(pair.sender, pair.receiver)
    }

    fun destroy() {
        state.destroy()
    }
}
