package dev.leandro.easydrop.lab

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Liga a UI aos modos de teste. Cada `start` cria instâncias novas, para que nenhum
 * estado sujo de um teste anterior vaze para o seguinte — no laboratório, repetir o
 * mesmo teste limpo vale mais do que reaproveitar objeto.
 */
class LabController(private val activity: Activity) {

    private val context: Context = activity

    private val _status = MutableStateFlow(LabStatus())
    val status: StateFlow<LabStatus> = _status.asStateFlow()

    private var lan: LanMode? = null
    private var ble: BleMode? = null
    private var nfc: NfcMode? = null
    private var direct: WifiDirectMode? = null

    fun start(mode: Mode, role: Role) {
        stop(quiet = true)
        LabLog.add("--- ${mode.label} / ${role.label} ---")
        _status.value = LabStatus(
            phase = Phase.SEARCHING,
            detail = "iniciando ${mode.label}",
            mode = mode,
            role = role,
        )

        when (mode) {
            Mode.LAN -> LanMode(context) { report(it) }.also {
                lan = it
                if (role == Role.RECEIVER) it.receiver() else it.sender()
            }
            Mode.BLE -> BleMode(context, role) { report(it) }.also {
                ble = it
                it.start()
            }
            Mode.NFC -> NfcMode(activity, role) { report(it) }.also {
                nfc = it
                it.start()
            }
            Mode.P2P -> WifiDirectMode(activity, role) { report(it) }.also {
                direct = it
                it.start()
            }
            else -> fail("${mode.label}: ainda não implementado")
        }
    }

    fun stop(quiet: Boolean = false) {
        lan?.stop()
        lan = null
        ble?.stop()
        ble = null
        nfc?.stop()
        nfc = null
        val hadDirect = direct != null
        direct?.stop()
        direct = null
        // Se o app foi reiniciado, o modo P2P não está mais em memória, mas o grupo
        // pode ter ficado no framework. Nesse caso, limpa na hora de parar.
        if (!quiet && !hadDirect) P2pCleanup.remove(activity)
        _status.value = LabStatus(
            mode = _status.value.mode,
            role = _status.value.role,
        )
        if (!quiet) LabLog.add("parado")
    }

    fun selectMode(mode: Mode) {
        if (_status.value.phase == Phase.IDLE) _status.value = _status.value.copy(mode = mode)
    }

    fun selectRole(role: Role) {
        if (_status.value.phase == Phase.IDLE) _status.value = _status.value.copy(role = role)
    }

    /** Os modos reportam só fase/detalhe; modo e papel são do controlador. */
    private fun report(status: LabStatus) {
        _status.value = status.copy(
            mode = _status.value.mode,
            role = _status.value.role,
        )
    }

    private fun fail(message: String) {
        LabLog.add(message)
        _status.value = _status.value.copy(phase = Phase.ERROR, detail = message)
    }
}
