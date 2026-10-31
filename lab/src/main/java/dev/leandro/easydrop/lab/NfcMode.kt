package dev.leandro.easydrop.lab

import android.app.Activity

/**
 * Identificação por toque: quem recebe apresenta as credenciais do seu hotspot; quem envia
 * lê e entra na rede para empurrar os dados.
 *
 * Diferente do BLE, aqui a distância é zero por construção — o Android só entrega o tag
 * quando os dois estão encostados.
 */
class NfcMode(
    activity: Activity,
    private val role: Role,
    private val onStatus: (LabStatus) -> Unit,
) {

    private val beam = NfcBeam(activity)
    private val link = HotspotLink(activity, onStatus)

    fun start() {
        beam.problem()?.let {
            fail(it)
            return
        }
        if (role == Role.RECEIVER) startReceiver() else startSender()
    }

    // ------------------------------------------------------------- receptor

    private fun startReceiver() {
        link.host { ssid, pass ->
            beam.present("$ssid\n$pass") {
                onStatus(LabStatus(Phase.CONNECTING, "toque detectado, conectando"))
            }
            LabLog.add("NFC: credenciais de $ssid publicadas")
            onStatus(LabStatus(Phase.SEARCHING, "encoste o outro aparelho"))
        }
    }

    // ------------------------------------------------------------- emissor

    private fun startSender() {
        onStatus(LabStatus(Phase.SEARCHING, "encoste no receptor"))

        beam.read(
            onPayload = { payload ->
                val parts = payload.split("\n")
                if (parts.size < 2) {
                    fail("NFC: payload inválido")
                    return@read
                }
                onStatus(LabStatus(Phase.FOUND, "toque detectado"))
                link.join(parts[0], parts[1])
            },
            onError = { fail(it) },
        )
    }

    private fun fail(message: String) {
        LabLog.add(message)
        onStatus(LabStatus(Phase.ERROR, message))
    }

    fun stop() {
        beam.stop()
        link.stop()
        LabLog.add("NFC: parado")
    }
}
