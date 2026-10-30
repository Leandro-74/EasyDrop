package dev.leandro.easydrop.lab

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Modo mais simples: os dois já estão na mesma rede Wi-Fi.
 * Descoberta por mDNS, transporte TCP direto. Nenhum diálogo do sistema.
 */
class LanMode(
    private val context: Context,
    private val onStatus: (LabStatus) -> Unit,
) {

    companion object {
        const val SERVICE_TYPE = "_easydrop._tcp"
        const val PORT = 8991
        const val PAYLOAD = 16L * 1024 * 1024
        private const val CHUNK = 64 * 1024
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val pool = Executors.newCachedThreadPool()

    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var startedAt = 0L

    @Volatile
    private var finished = false

    fun receiver() {
        startedAt = System.currentTimeMillis()
        onStatus(LabStatus(Phase.SEARCHING, "anunciando na LAN"))

        pool.execute {
            try {
                val socketServer = ServerSocket(PORT)
                server = socketServer
                registerService()
                val client = socketServer.accept()
                onConnected(client, receive = true)
            } catch (e: Exception) {
                if (!finished) onStatus(LabStatus(Phase.ERROR, "servidor: ${e.message}"))
            }
        }
    }

    fun sender() {
        startedAt = System.currentTimeMillis()
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

                if (address == null) {
                    LabLog.add("mDNS: resolvido sem endereço")
                    return
                }

                LabLog.add("mDNS: ${serviceInfo.serviceName} em $address:${serviceInfo.port}")
                onStatus(LabStatus(Phase.CONNECTING, "conectando a $address"))
                pool.execute { connect(address.hostAddress ?: return@execute, serviceInfo.port) }
            }
        }

        nsd.resolveService(info, listener)
    }

    private fun connect(host: String, port: Int) {
        try {
            val socket = Socket(host, port)
            onConnected(socket, receive = false)
        } catch (e: Exception) {
            if (!finished) onStatus(LabStatus(Phase.ERROR, "conexão: ${e.message}"))
        }
    }

    private fun onConnected(socket: Socket, receive: Boolean) {
        if (finished) {
            runCatching { socket.close() }
            return
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val peer = socket.inetAddress?.hostAddress
        LabLog.add("LAN: conectado em ${elapsed}ms com $peer")
        onStatus(
            LabStatus(
                phase = Phase.CONNECTED,
                detail = "conectado em ${elapsed}ms",
                peer = peer,
                setupMs = elapsed,
            ),
        )

        // Pausa curta para o verde de "conectado" aparecer antes do verde de transferência.
        runCatching { Thread.sleep(500) }
        if (finished) return

        onStatus(
            LabStatus(
                phase = Phase.TRANSFERRING,
                detail = "transferindo 16 MB",
                peer = peer,
                setupMs = elapsed,
            ),
        )

        if (receive) receivePayload(socket) else sendPayload(socket)
    }

    private fun sendPayload(socket: Socket) {
        try {
            val out = DataOutputStream(socket.getOutputStream())
            val buffer = ByteArray(CHUNK)
            var sent = 0L
            val start = System.nanoTime()

            while (sent < PAYLOAD) {
                val size = minOf(CHUNK.toLong(), PAYLOAD - sent).toInt()
                out.write(buffer, 0, size)
                sent += size
            }
            out.flush()

            val seconds = max(1L, System.nanoTime() - start) / 1_000_000_000.0
            val mbps = (PAYLOAD / 1024.0 / 1024.0) / seconds
            LabLog.add("LAN: enviados 16 MB em %.2f s = %.1f MB/s".format(seconds, mbps))
            onStatus(
                LabStatus(
                    phase = Phase.CONNECTED,
                    detail = "enviado 16 MB",
                    mbps = mbps,
                    setupMs = System.currentTimeMillis() - startedAt,
                ),
            )
        } catch (e: Exception) {
            LabLog.add("LAN: envio falhou — ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun receivePayload(socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream())
            val buffer = ByteArray(CHUNK)
            var received = 0L
            val start = System.nanoTime()

            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                received += read
            }

            val seconds = max(1L, System.nanoTime() - start) / 1_000_000_000.0
            val mbps = (received / 1024.0 / 1024.0) / seconds
            LabLog.add("LAN: recebidos %.0f MB em %.2f s = %.1f MB/s".format(received / 1024.0 / 1024.0, seconds, mbps))
            onStatus(
                LabStatus(
                    phase = Phase.CONNECTED,
                    detail = "recebido ${received / 1024 / 1024} MB",
                    mbps = mbps,
                    setupMs = System.currentTimeMillis() - startedAt,
                ),
            )
        } catch (e: Exception) {
            LabLog.add("LAN: recebimento falhou — ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    fun stop() {
        finished = true

        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null

        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null

        runCatching { server?.close() }
        server = null

        pool.shutdownNow()
        LabLog.add("LAN: parado")
    }
}
