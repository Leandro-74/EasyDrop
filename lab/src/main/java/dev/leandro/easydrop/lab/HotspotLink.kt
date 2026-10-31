package dev.leandro.easydrop.lab

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * O transporte em si, independente de como os aparelhos se acharam (BLE, NFC, mDNS).
 *
 * **Quem recebe hospeda o hotspot.** Parece contraintuitivo, mas é o que dá velocidade:
 * o cliente de `WifiNetworkSpecifier` fica em economia de energia e recebe devagar
 * (medido 2,7 MB/s com Rx a 17 Mbps), enquanto transmite bem (229 Mbps). Deixando o
 * hotspot no lado que recebe, o cliente só envia, e o mesmo par de aparelhos saltou
 * de 2,7 para 12,7 MB/s.
 */
class HotspotLink(
    private val context: Context,
    private val onStatus: (LabStatus) -> Unit,
) {

    companion object {
        const val PORT = 8992
        const val PAYLOAD = 16L * 1024 * 1024
        private const val CHUNK = 64 * 1024
        private const val ACCEPT_MS = 300_000

        /** Janela grande: com a padrão (64 KB) o RTT do specifier limita a ~3,7 MB/s. */
        private const val SOCKET_BUFFER = 4 * 1024 * 1024
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private var startedAt = System.currentTimeMillis()

    @Volatile
    private var finished = false

    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var server: ServerSocket? = null
    private var network: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ------------------------------------------------------------- receptor

    /** Sobe o hotspot e entrega as credenciais para quem for publicá-las (BLE, NFC). */
    fun host(onCredentials: (ssid: String, pass: String) -> Unit) {
        startedAt = System.currentTimeMillis()
        onStatus(LabStatus(Phase.SEARCHING, "subindo hotspot local"))

        val wifi = context.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            fail("WifiManager indisponível")
            return
        }

        wifi.startLocalOnlyHotspot(
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    if (finished) {
                        reservation.close()
                        return
                    }
                    hotspot = reservation
                    val config = reservation.wifiConfiguration
                    val ssid = config?.SSID
                    val pass = config?.preSharedKey
                    if (ssid == null || pass == null) {
                        fail("hotspot sem credenciais")
                        return
                    }
                    LabLog.add("hotspot pronto ($ssid / $pass)")
                    onCredentials(ssid, pass)
                    accept()
                }

                override fun onFailed(reason: Int) {
                    fail("hotspot falhou (reason=$reason)")
                }
            },
            handler,
        )
    }

    private fun accept() {
        pool.execute {
            try {
                val socketServer = ServerSocket()
                socketServer.reuseAddress = true
                socketServer.receiveBufferSize = SOCKET_BUFFER
                socketServer.bind(InetSocketAddress(PORT))
                socketServer.soTimeout = ACCEPT_MS
                server = socketServer

                LabLog.add("TCP: servidor na porta $PORT")
                val socket = socketServer.accept()
                tune(socket)

                if (finished) {
                    runCatching { socket.close() }
                    return@execute
                }

                val elapsed = System.currentTimeMillis() - startedAt
                val peer = socket.inetAddress?.hostAddress
                LabLog.add("TCP: conectado em ${elapsed}ms com $peer")
                onStatus(LabStatus(Phase.CONNECTED, "conectado em ${elapsed}ms", peer, setupMs = elapsed))

                runCatching { Thread.sleep(500) }
                if (finished) return@execute

                onStatus(LabStatus(Phase.TRANSFERRING, "recebendo 16 MB", peer, setupMs = elapsed))
                receivePayload(socket, peer, elapsed)
            } catch (e: Exception) {
                if (!finished) fail("TCP: ${e.message}")
            }
        }
    }

    private fun receivePayload(socket: Socket, peer: String?, setupMs: Long) {
        try {
            val input = DataInputStream(socket.getInputStream())
            val buffer = ByteArray(CHUNK)
            var received = 0L
            val start = System.nanoTime()
            var lastLog = start

            // Taxa por segundo: mostra se a janela TCP está crescendo ou se bate num teto.
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                received += read
                val now = System.nanoTime()
                if (now - lastLog >= 1_000_000_000L) {
                    lastLog = now
                    LabLog.add("TCP: t=${(now - start) / 1_000_000_000.0}s ${received / 1024 / 1024} MB")
                }
            }

            val seconds = max(1L, System.nanoTime() - start) / 1_000_000_000.0
            val mbps = (received / 1024.0 / 1024.0) / seconds
            LabLog.add("TCP: recebidos %.0f MB em %.2f s = %.1f MB/s".format(received / 1024.0 / 1024.0, seconds, mbps))
            onStatus(
                LabStatus(
                    Phase.CONNECTED,
                    "recebido ${received / 1024 / 1024} MB",
                    peer,
                    mbps = mbps,
                    setupMs = System.currentTimeMillis() - startedAt,
                ),
            )
        } catch (e: Exception) {
            if (!finished) fail("TCP: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------- emissor

    fun join(ssid: String, pass: String) {
        startedAt = System.currentTimeMillis()
        onStatus(LabStatus(Phase.CONNECTING, "entrando no hotspot $ssid"))

        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            fail("ConnectivityManager indisponível")
            return
        }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .build()

        /**
         * Sem `removeCapability(INTERNET)` a rede deixa de ser "local-only". O Android
         * marca redes locais como secundárias e o rádio entra em economia de energia —
         * medido em 17 Mbps de Rx contra 229 Mbps de Tx, com RSSI -10. Como os sockets
         * são presos a esta rede explicitamente, nada vaza para a internet.
         */
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                this@HotspotLink.network = network
                val gateway = gatewayOf(cm.getLinkProperties(network))
                if (gateway == null) {
                    fail("hotspot sem gateway")
                    return
                }
                LabLog.add("Wi-Fi: no hotspot, gateway=${gateway.hostAddress}")
                onStatus(LabStatus(Phase.CONNECTED, "no hotspot", gateway.hostAddress))
                acquireWifiLock()
                pool.execute { send(gateway) }
            }

            override fun onUnavailable() {
                fail("diálogo do hotspot recusado")
            }
        }

        networkCallback = callback
        cm.requestNetwork(request, callback, 120_000)
    }

    private fun send(gateway: InetAddress) {
        val network = this.network
        if (network == null) {
            fail("rede do hotspot perdida")
            return
        }

        val socket = Socket()
        try {
            socket.sendBufferSize = SOCKET_BUFFER
            socket.receiveBufferSize = SOCKET_BUFFER
            socket.tcpNoDelay = true
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(gateway, PORT), 10_000)

            val elapsed = System.currentTimeMillis() - startedAt
            LabLog.add("TCP: conectado em ${elapsed}ms com ${gateway.hostAddress}")
            onStatus(LabStatus(Phase.CONNECTED, "conectado em ${elapsed}ms", gateway.hostAddress, setupMs = elapsed))

            runCatching { Thread.sleep(500) }
            if (finished) return

            onStatus(LabStatus(Phase.TRANSFERRING, "enviando 16 MB", gateway.hostAddress, setupMs = elapsed))

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
            LabLog.add("TCP: enviados 16 MB em %.2f s = %.1f MB/s".format(seconds, mbps))
            onStatus(
                LabStatus(
                    Phase.CONNECTED,
                    "enviado 16 MB",
                    gateway.hostAddress,
                    mbps = mbps,
                    setupMs = System.currentTimeMillis() - startedAt,
                ),
            )
        } catch (e: Exception) {
            if (!finished) fail("TCP: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    // -------------------------------------------------------------- comum

    /** Janela grande nos dois sentidos e sem Nagle: o envio vira um fluxo contínuo. */
    private fun tune(socket: Socket) {
        runCatching { socket.sendBufferSize = SOCKET_BUFFER }
        runCatching { socket.receiveBufferSize = SOCKET_BUFFER }
        runCatching { socket.tcpNoDelay = true }
    }

    private fun gatewayOf(props: LinkProperties?): InetAddress? {
        val route = props?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }
        return route?.gateway ?: props?.dhcpServerAddress
    }

    /**
     * Rede WNS é tratada como "secundária" e o rádio entra em economia de energia —
     * medido em 0,8 MB/s contra 12 MB/s da mesma rede conectada normalmente. O lock
     * de baixa latência tira o rádio desse modo enquanto durar a transferência.
     */
    private fun acquireWifiLock() {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "EasyDrop:transfer")
            .apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        LabLog.add("Wi-Fi: lock de baixa latência ativo")
    }

    private fun releaseWifiLock() {
        wifiLock?.let { runCatching { it.release() } }
        wifiLock = null
    }

    private fun fail(message: String) {
        LabLog.add(message)
        onStatus(LabStatus(Phase.ERROR, message))
    }

    fun stop() {
        finished = true

        val cm = context.getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { callback -> cm?.let { runCatching { it.unregisterNetworkCallback(callback) } } }
        networkCallback = null
        network = null
        releaseWifiLock()

        runCatching { hotspot?.close() }
        hotspot = null

        runCatching { server?.close() }
        server = null

        pool.shutdownNow()
    }
}
