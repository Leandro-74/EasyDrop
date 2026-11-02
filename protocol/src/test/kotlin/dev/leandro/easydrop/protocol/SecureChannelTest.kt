package dev.leandro.easydrop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class SecureChannelTest {

    private data class Pair(
        val initiatorTransport: NoiseTransport,
        val responderTransport: NoiseTransport,
    )

    private fun handshake(): Pair {
        val responder = NoiseHandshake.responder()
        val initiator = NoiseHandshake.initiator(responder.localPublicKey())
        val m1 = initiator.writeMessage()
        responder.readMessage(m1)
        val m2 = responder.writeMessage()
        initiator.readMessage(m2)
        return Pair(initiator.split(), responder.split())
    }

    private fun sender(wire: ByteArrayOutputStream, transport: NoiseTransport) =
        SecureChannel(ByteArrayInputStream(ByteArray(0)), wire, transport)

    private fun receiver(wire: ByteArray, transport: NoiseTransport) =
        SecureChannel(ByteArrayInputStream(wire), ByteArrayOutputStream(), transport)

    @Test
    fun `envia e recebe offer`() {
        val pair = handshake()
        val wire = ByteArrayOutputStream()
        val frame = Frame.Offer(listOf(FileMeta("a.txt", 10, "text/plain")))

        sender(wire, pair.initiatorTransport).send(frame)

        assertEquals(frame, receiver(wire.toByteArray(), pair.responderTransport).receive())
    }

    @Test
    fun `envia e recebe data`() {
        val pair = handshake()
        val wire = ByteArrayOutputStream()
        val frame = Frame.Data(0, 0, ByteArray(1024) { it.toByte() })

        sender(wire, pair.initiatorTransport).send(frame)

        assertEquals(frame, receiver(wire.toByteArray(), pair.responderTransport).receive())
    }

    @Test
    fun `envia varios frames em sequencia`() {
        val pair = handshake()
        val wire = ByteArrayOutputStream()
        val frames = listOf(
            Frame.Offer(listOf(FileMeta("a", 1), FileMeta("b", 2))),
            Frame.Accept(0),
            Frame.Data(0, 0, byteArrayOf(1, 2, 3)),
            Frame.End(0, ByteArray(32)),
            Frame.Ack(0, AckStatus.OK),
        )

        val sender = sender(wire, pair.initiatorTransport)
        frames.forEach { sender.send(it) }

        val receiver = receiver(wire.toByteArray(), pair.responderTransport)
        frames.forEach { assertEquals(it, receiver.receive()) }
        assertNull(receiver.receive())
    }

    @Test
    fun `rejeita ciphertext adulterado`() {
        val pair = handshake()
        val wire = ByteArrayOutputStream()
        sender(wire, pair.initiatorTransport).send(Frame.Cancel(CancelReason.USER))

        val bytes = wire.toByteArray()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()

        assertThrows(ProtocolException::class.java) {
            receiver(bytes, pair.responderTransport).receive()
        }
    }

    @Test
    fun `rejeita prefixo de tamanho invalido`() {
        val pair = handshake()
        val bytes = ByteBuffer.allocate(4).putInt(0).array()

        assertThrows(ProtocolException::class.java) {
            receiver(bytes, pair.responderTransport).receive()
        }
    }

    @Test
    fun `rejeita ciphertext acima do limite`() {
        val pair = handshake()
        val bytes = ByteBuffer.allocate(4).putInt(SecureChannel.MAX_CIPHERTEXT + 1).array()

        assertThrows(ProtocolException::class.java) {
            receiver(bytes, pair.responderTransport).receive()
        }
    }

    @Test
    fun `retorna null no fim do stream`() {
        val pair = handshake()

        assertNull(receiver(ByteArray(0), pair.responderTransport).receive())
    }

    @Test
    fun `rejeita stream truncado`() {
        val pair = handshake()
        val bytes = ByteBuffer.allocate(4).putInt(10).array() + ByteArray(5)

        assertThrows(ProtocolException::class.java) {
            receiver(bytes, pair.responderTransport).receive()
        }
    }
}
