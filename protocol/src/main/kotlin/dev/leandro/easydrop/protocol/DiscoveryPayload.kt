package dev.leandro.easydrop.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Transporte indicado no payload de descoberta. */
enum class Transport(val code: Int) {
    P2P(1),
    HOTSPOT(2),
    LAN(3);

    companion object {
        fun fromCode(code: Int): Transport =
            entries.firstOrNull { it.code == code }
                ?: throw ProtocolException("transporte desconhecido: $code")
    }
}

/**
 * Payload entregue pelo NFC (HCE).
 *
 * Formato binário com tamanho por campo: newline em nome, SSID ou senha não quebra o
 * parsing. O tamanho máximo é 199 bytes, dentro do limite de APDU curta.
 */
class DiscoveryPayload(
    val name: String,
    val ssid: String,
    val pass: String,
    val frequencyMhz: Int,
    noisePublicKey: ByteArray,
    val transport: Transport = Transport.P2P,
) {

    private val key: ByteArray = noisePublicKey.copyOf()

    /** Cópia defensiva da chave pública X25519 do receptor. */
    val noisePublicKey: ByteArray get() = key.copyOf()

    init {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val ssidBytes = ssid.toByteArray(StandardCharsets.UTF_8)
        val passBytes = pass.toByteArray(StandardCharsets.UTF_8)

        require(nameBytes.isNotEmpty()) { "nome vazio" }
        require(nameBytes.size <= MAX_NAME) { "nome maior que $MAX_NAME bytes" }
        require(ssidBytes.size <= MAX_SSID) { "SSID maior que $MAX_SSID bytes" }
        require(passBytes.size <= MAX_PASS) { "senha maior que $MAX_PASS bytes" }
        require(ssid.isNotEmpty() || pass.isEmpty()) { "senha sem SSID" }
        require(frequencyMhz in 0..0xFFFF) { "frequência fora de u16" }
        require(key.size == KEY_SIZE) { "chave Noise deve ter $KEY_SIZE bytes" }
    }

    private val hasCredentials: Boolean get() = ssid.isNotEmpty()

    val flags: Int
        get() = transport.code or
            (if (hasCredentials) FLAG_CREDENTIALS else 0) or
            FLAG_NOISE_KEY

    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(MAX_SIZE)
        buffer.put(VERSION.toByte())
        buffer.put(flags.toByte())
        putString(buffer, name)
        putString(buffer, ssid)
        putString(buffer, pass)
        buffer.putShort(frequencyMhz.toShort())
        buffer.put(key)
        return buffer.array().copyOf(buffer.position())
    }

    override fun equals(other: Any?): Boolean =
        other is DiscoveryPayload &&
            name == other.name &&
            ssid == other.ssid &&
            pass == other.pass &&
            frequencyMhz == other.frequencyMhz &&
            transport == other.transport &&
            key.contentEquals(other.key)

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + ssid.hashCode()
        result = 31 * result + pass.hashCode()
        result = 31 * result + frequencyMhz
        result = 31 * result + transport.hashCode()
        result = 31 * result + key.contentHashCode()
        return result
    }

    /** Não inclui a senha. */
    override fun toString(): String =
        "DiscoveryPayload(name=$name, ssid=$ssid, pass=***, frequencyMhz=$frequencyMhz, " +
            "transport=$transport, key=${key.size}B)"

    companion object {
        const val VERSION = 1
        const val MAX_NAME = 64
        const val MAX_SSID = 32
        const val MAX_PASS = 64
        const val KEY_SIZE = 32
        const val MAX_SIZE = 199

        private const val FLAG_CREDENTIALS = 0x04
        private const val FLAG_NOISE_KEY = 0x08
        private const val FLAG_RESERVED = 0xF0

        fun decode(bytes: ByteArray): DiscoveryPayload {
            if (bytes.size < 2) throw ProtocolException("payload curto")

            val buffer = ByteBuffer.wrap(bytes)
            val version = buffer.get().toInt() and 0xFF
            if (version != VERSION) throw ProtocolException("versão $version não suportada")

            val flags = buffer.get().toInt() and 0xFF
            if (flags and FLAG_RESERVED != 0) throw ProtocolException("flags reservadas em uso")

            val transport = Transport.fromCode(flags and 0x03)
            val hasCredentials = flags and FLAG_CREDENTIALS != 0
            val hasKey = flags and FLAG_NOISE_KEY != 0
            if (!hasKey) throw ProtocolException("chave Noise ausente")

            val name = readString(buffer, MAX_NAME)
            val ssid = readString(buffer, MAX_SSID)
            val pass = readString(buffer, MAX_PASS)

            if (buffer.remaining() < 2 + KEY_SIZE) throw ProtocolException("payload truncado")
            val frequency = buffer.getShort().toInt() and 0xFFFF
            val key = ByteArray(KEY_SIZE)
            buffer.get(key)
            if (buffer.hasRemaining()) throw ProtocolException("bytes extras no payload")

            if (name.isEmpty()) throw ProtocolException("nome vazio")
            if (hasCredentials && ssid.isEmpty()) throw ProtocolException("credenciais ausentes")
            if (!hasCredentials && (ssid.isNotEmpty() || pass.isNotEmpty())) {
                throw ProtocolException("credenciais inesperadas")
            }

            return DiscoveryPayload(name, ssid, pass, frequency, key, transport)
        }

        private fun putString(buffer: ByteBuffer, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > 0xFF) throw ProtocolException("string longa demais")
            buffer.put(bytes.size.toByte())
            buffer.put(bytes)
        }

        private fun readString(buffer: ByteBuffer, max: Int): String {
            if (buffer.remaining() < 1) throw ProtocolException("payload truncado")
            val length = buffer.get().toInt() and 0xFF
            if (length > max) throw ProtocolException("string de $length bytes acima do limite $max")
            if (buffer.remaining() < length) throw ProtocolException("payload truncado")
            val bytes = ByteArray(length)
            buffer.get(bytes)
            return decodeUtf8(bytes)
        }

        private fun decodeUtf8(bytes: ByteArray): String {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                throw ProtocolException("UTF-8 inválido", e)
            }
        }
    }
}
