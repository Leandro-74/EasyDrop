package dev.leandro.easydrop.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NoiseHandshakeTest {

    private data class Session(
        val initiator: NoiseHandshake,
        val responder: NoiseHandshake,
        val initiatorTransport: NoiseTransport,
        val responderTransport: NoiseTransport,
    )

    private fun complete(
        prologue: ByteArray = NoiseHandshake.PROLOGUE.toByteArray(Charsets.US_ASCII),
    ): Session {
        val responder = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(responder.localPublicKey(), prologue)
        val m1 = initiator.writeMessage()
        responder.readMessage(m1)
        val m2 = responder.writeMessage()
        initiator.readMessage(m2)
        return Session(initiator, responder, initiator.split(), responder.split())
    }

    @Test
    fun `handshake completa e troca mensagens nos dois sentidos`() {
        val session = complete()
        val doEmissor = "emissor".toByteArray()
        val doReceptor = "receptor".toByteArray()

        assertArrayEquals(
            doEmissor,
            session.responderTransport.decrypt(session.initiatorTransport.encrypt(doEmissor)),
        )
        assertArrayEquals(
            doReceptor,
            session.initiatorTransport.decrypt(session.responderTransport.encrypt(doReceptor)),
        )
    }

    @Test
    fun `falha com chave publica errada`() {
        val real = NoiseHandshake.responder()
        val outro = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(real.localPublicKey())
        val m1 = initiator.writeMessage()

        assertThrows(ProtocolException::class.java) {
            outro.readMessage(m1)
        }
    }

    @Test
    fun `falha com mensagem adulterada`() {
        val responder = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(responder.localPublicKey())
        val m1 = initiator.writeMessage()
        m1[0] = (m1[0].toInt() xor 1).toByte()

        assertThrows(ProtocolException::class.java) {
            responder.readMessage(m1)
        }
    }

    @Test
    fun `rejeita replay do mesmo ciphertext`() {
        val session = complete()
        val ciphertext = session.initiatorTransport.encrypt("x".toByteArray())

        assertArrayEquals("x".toByteArray(), session.responderTransport.decrypt(ciphertext))
        assertThrows(ProtocolException::class.java) {
            session.responderTransport.decrypt(ciphertext)
        }
    }

    @Test
    fun `prologo diferente falha`() {
        val responder = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(
            responder.localPublicKey(),
            "easydrop/2".toByteArray(Charsets.US_ASCII),
        )
        val m1 = initiator.writeMessage()

        assertThrows(ProtocolException::class.java) {
            responder.readMessage(m1)
        }
    }

    @Test
    fun `mensagens do handshake tem 48 bytes`() {
        val responder = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(responder.localPublicKey())
        val m1 = initiator.writeMessage()
        responder.readMessage(m1)
        val m2 = responder.writeMessage()

        assertEquals(48, m1.size)
        assertEquals(48, m2.size)
    }

    @Test
    fun `chave publica do responder tem 32 bytes`() {
        assertEquals(32, NoiseHandshake.responder().localPublicKey().size)
    }

    @Test
    fun `iniciador nao tem chave estatica`() {
        val responder = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(responder.localPublicKey())

        assertThrows(ProtocolException::class.java) {
            initiator.localPublicKey()
        }
    }

    @Test
    fun `rejeita chave publica de tamanho errado`() {
        assertThrows(ProtocolException::class.java) {
            NoiseHandshake.initiator(ByteArray(31))
        }
    }

    @Test
    fun `transporte rejeita ciphertext curto`() {
        val session = complete()

        assertThrows(ProtocolException::class.java) {
            session.responderTransport.decrypt(ByteArray(4))
        }
    }
}
