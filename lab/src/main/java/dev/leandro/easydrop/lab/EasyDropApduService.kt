package dev.leandro.easydrop.lab

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Cartão emulado do EasyDrop. Não é NDEF: é um AID próprio com dois comandos — SELECT e
 * GET DATA — o suficiente para entregar SSID e senha do hotspot em um toque.
 */
class EasyDropApduService : HostApduService() {

    companion object {
        private val OK = byteArrayOf(0x90.toByte(), 0x00)
        private val NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val UNKNOWN = byteArrayOf(0x6D.toByte(), 0x00)

        /** Preenchido pelo modo NFC quando o hotspot está de pé. */
        @Volatile
        var payload: ByteArray? = null

        /** Avisa o app que alguém leu o cartão. */
        @Volatile
        var onRead: (() -> Unit)? = null
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 2) return UNKNOWN

        val ins = commandApdu[1]
        return when {
            commandApdu[0] == 0x00.toByte() && ins == 0xA4.toByte() -> OK

            commandApdu[0] == 0x80.toByte() && ins == 0xCA.toByte() -> {
                val data = payload ?: return NOT_FOUND
                onRead?.invoke()
                data + OK
            }

            else -> UNKNOWN
        }
    }

    override fun onDeactivated(reason: Int) = Unit
}
