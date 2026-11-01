package dev.leandro.easydrop.lab

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

/**
 * Modo mais simples: os dois já estão na mesma rede Wi-Fi.
 * Descoberta por mDNS, transporte TCP direto. Nenhum diálogo do sistema.
 *
 * A transferência é o mesmo [TcpLink] do P2P e do hotspot: ACK, cancelamento e a mesma
 * medição. Assim os números dos três caminhos são comparáveis.
 */
class LanMode(
    private val context: Context,
    private val onStatus: (LabStatus) -> Unit,
) {

    companion object {
        const val SERVICE_TYPE = "_easydrop._tcp"
        const val PORT = 8991
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val tcp = TcpLink(onStatus)

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    @Volatile
    private var finished = false

    fun receiver() {
        onStatus(LabStatus(Phase.SEARCHING, "anunciando na LAN"))
        tcp.serve(PORT) { if (!finished) registerService() }
    }

    fun sender() {
        onStatus(LabStatus(Phase.SEARCHING, "procurando na LAN"))
        discover()
    }

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = "EasyDrop-${Build.MODEL}"
            serviceType = SERVICE_TYPE
            port = PORT
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                LabLog.add("mDNS: anunciado como ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                LabLog.add("mDNS: registro falhou ($errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }

        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun discover() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                LabLog.add("mDNS: busca iniciada")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("easydrop")) return
                LabLog.add("mDNS: achou ${info.serviceName}")
                onStatus(LabStatus(Phase.FOUND, "achou ${info.serviceName}"))
                resolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                LabLog.add("mDNS: busca falhou ($errorCode)")
                onStatus(LabStatus(Phase.ERROR, "mDNS: erro $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(info: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                LabLog.add("mDNS: resolve falhou ($errorCode)")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val address = if (Build.VERSION.SDK_INT >= 34) {
                    serviceInfo.hostAddresses.firstOrNull()
                } else {
                    @Suppress("DEPRECATION")
                    serviceInfo.host
                }

                val host = address?.hostAddress
                if (host == null) {
                    LabLog.add("mDNS: resolvido sem endereço")
                    return
                }

                LabLog.add("mDNS: ${serviceInfo.serviceName} em $host:${serviceInfo.port}")
                onStatus(LabStatus(Phase.CONNECTING, "conectando a $host"))
                tcp.send(host, serviceInfo.port)
            }
        }

        nsd.resolveService(info, listener)
    }

    fun stop() {
        finished = true

        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null

        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null

        tcp.stop()
        LabLog.add("LAN: parado")
    }
}
