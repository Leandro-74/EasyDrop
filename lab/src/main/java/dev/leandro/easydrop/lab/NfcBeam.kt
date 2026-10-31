package dev.leandro.easydrop.lab

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Handler
import android.os.Looper

/**
 * O toque em si: um lado apresenta um cartão (HCE) com um texto, o outro lê por reader mode.
 *
 * Não sabe nada sobre hotspot ou Wi-Fi Direct — só entrega a string. Assim o mesmo toque
 * serve para qualquer transporte.
 */
class NfcBeam(private val activity: Activity) {

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

    @Volatile
    private var finished = false

    private var readerCallback: NfcAdapter.ReaderCallback? = null

    /** Devolve o motivo de não dar para usar NFC, ou null se está tudo certo. */
    fun problem(): String? = when {
        nfc == null -> "este aparelho não tem NFC"
        !nfc.isEnabled -> "NFC desligado"
        else -> null
    }

    /** Fica pronto para ser lido. [onRead] avisa que alguém encostou. */
    fun present(payload: String, onRead: () -> Unit) {
        EasyDropApduService.payload = payload.toByteArray()
        EasyDropApduService.onRead = { handler.post { if (!finished) onRead() } }
        LabLog.add("NFC: cartão pronto")
    }

    /** Liga o reader mode e entrega o texto lido. */
    fun read(onPayload: (String) -> Unit, onError: (String) -> Unit) {
        val callback = NfcAdapter.ReaderCallback { tag -> onTag(tag, onPayload, onError) }
        readerCallback = callback

        handler.post {
            runCatching {
                nfc.enableReaderMode(activity, callback, READER_FLAGS, null)
            }.onFailure { onError("reader mode falhou: ${it.message}") }
            LabLog.add("NFC: lendo, encoste no outro aparelho")
        }
    }

    private fun onTag(tag: Tag, onPayload: (String) -> Unit, onError: (String) -> Unit) {
        if (finished) return

        val iso = IsoDep.get(tag)
        if (iso == null) {
            LabLog.add("NFC: tag sem IsoDep, ignorando")
            return
        }

        try {
            iso.connect()
            iso.timeout = 5_000

            val selected = iso.transceive(SELECT)
            if (selected.size < 2 || selected.last() != 0x00.toByte()) {
                onError("NFC: SELECT recusado")
                return
            }

            val response = iso.transceive(GET_DATA)
            if (response.size < 3) {
                onError("NFC: resposta curta")
                return
            }

            val payload = String(response.copyOfRange(0, response.size - 2))
            LabLog.add("NFC: recebido (${payload.lineSequence().firstOrNull()})")
            stopReader()
            onPayload(payload)
        } catch (e: Exception) {
            if (!finished) onError("NFC: ${e.message}")
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

    fun stop() {
        finished = true
        stopReader()
        EasyDropApduService.payload = null
        EasyDropApduService.onRead = null
    }
}
