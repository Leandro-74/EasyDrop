package dev.leandro.easydrop.lab

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Handler
import android.os.Looper

/**
 * Identificação por toque: quem recebe emula um cartão (HCE) que responde com as
 * credenciais do seu hotspot; quem envia lê por reader mode e entra na rede para
 * empurrar os dados.
 *
 * Diferente do BLE, aqui a distância é zero por construção — o Android só entrega o tag
 * quando os dois estão encostados.
 */
class NfcMode(
    private val activity: Activity,
    private val role: Role,
    private val onStatus: (LabStatus) -> Unit,
) {

    companion object {
        /** AID próprio: F0 + "EDROP". */
        private val SELECT = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x06,
            0xF0.toByte(), 0x45, 0x44, 0x52, 0x4F, 0x50, 0x00,
        )
        private val GET_DATA = byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00)

        private const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }

    private val nfc = NfcAdapter.getDefaultAdapter(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val link = HotspotLink(activity, onStatus)

    @Volatile
    private var finished = false

    private var readerCallback: NfcAdapter.ReaderCallback? = null

    fun start() {
        if (nfc == null) {
            fail("este aparelho não tem NFC")
            return
        }
        if (!nfc.isEnabled) {
            fail("NFC desligado")
            return
        }
        if (role == Role.RECEIVER) startReceiver() else startSender()
    }

    // ------------------------------------------------------------- receptor

    private fun startReceiver() {
        link.host { ssid, pass ->
            EasyDropApduService.payload = "$ssid\n$pass".toByteArray()
            EasyDropApduService.onRead = {
                handler.post {
                    if (!finished) onStatus(LabStatus(Phase.CONNECTING, "toque detectado, conectando"))
                }
            }
            LabLog.add("NFC: cartão pronto ($ssid)")
            onStatus(LabStatus(Phase.SEARCHING, "encoste o outro aparelho"))
        }
    }

    // ------------------------------------------------------------- emissor

    private fun startSender() {
        onStatus(LabStatus(Phase.SEARCHING, "encoste no receptor"))

        val callback = NfcAdapter.ReaderCallback { tag -> onTag(tag) }
        readerCallback = callback

        handler.post {
            runCatching {
                nfc.enableReaderMode(activity, callback, READER_FLAGS, null)
            }.onFailure { fail("reader mode falhou: ${it.message}") }
            LabLog.add("NFC: lendo, encoste no receptor")
        }
    }

    private fun onTag(tag: Tag) {
        if (finished) return

        val iso = IsoDep.get(tag)
        if (iso == null) {
            LabLog.add("NFC: tag sem IsoDep, ignorando")
            return
        }

        onStatus(LabStatus(Phase.FOUND, "toque detectado"))

        try {
            iso.connect()
            iso.timeout = 5_000

            val selected = iso.transceive(SELECT)
            if (selected.size < 2 || selected.last() != 0x00.toByte()) {
                fail("NFC: SELECT recusado")
                return
            }

            val response = iso.transceive(GET_DATA)
            if (response.size < 3) {
                fail("NFC: resposta curta")
                return
            }

            val payload = response.copyOfRange(0, response.size - 2)
            val parts = String(payload).split("\n")
            if (parts.size < 2) {
                fail("NFC: payload inválido")
                return
            }

            LabLog.add("NFC: credenciais recebidas (${parts[0]})")
            stopReader()
            link.join(parts[0], parts[1])
        } catch (e: Exception) {
            if (!finished) fail("NFC: ${e.message}")
        } finally {
            runCatching { iso.close() }
        }
    }

    private fun stopReader() {
        val callback = readerCallback ?: return
        readerCallback = null
        runCatching { nfc?.disableReaderMode(activity) }
        LabLog.add("NFC: leitura encerrada")
    }

    private fun fail(message: String) {
        LabLog.add(message)
        onStatus(LabStatus(Phase.ERROR, message))
    }

    fun stop() {
        finished = true
        stopReader()
        EasyDropApduService.payload = null
        EasyDropApduService.onRead = null
        link.stop()
        LabLog.add("NFC: parado")
    }
}
