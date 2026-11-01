package dev.leandro.easydrop.lab

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Phase {
    IDLE,
    SEARCHING,
    FOUND,
    CONNECTING,
    CONNECTED,
    TRANSFERRING,
    ERROR,
}

enum class Mode(val label: String) {
    LAN("LAN + mDNS"),
    P2P("Wi-Fi Direct"),
    NFC("NFC + Hotspot"),
    BLE("BLE + Hotspot"),
}

enum class Role(val label: String) {
    SENDER("Emissor"),
    RECEIVER("Receptor"),
}

data class LabStatus(
    val phase: Phase = Phase.IDLE,
    val detail: String = "escolha o modo e o papel",
    val peer: String? = null,
    val mbps: Double? = null,
    val setupMs: Long? = null,
    val mode: Mode = Mode.LAN,
    val role: Role = Role.SENDER,
)

/**
 * Troca de modo/papel só vale parado. Depois de um erro, aceitar a troca e voltar para
 * [Phase.IDLE] evita o chip que parece clicável mas ignora o clique.
 */
fun LabStatus.choose(mode: Mode = this.mode, role: Role = this.role): LabStatus =
    if (phase == Phase.IDLE || phase == Phase.ERROR) LabStatus(mode = mode, role = role) else this

object LabLog {

    const val TAG = "EasyDropLab"
    private const val MAX_LINES = 400

    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(message: String) {
        Log.i(TAG, message)
        val line = "${LocalTime.now().format(time)}  $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
