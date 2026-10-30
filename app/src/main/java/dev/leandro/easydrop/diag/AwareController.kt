package dev.leandro.easydrop.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Exercita o caminho completo do Wi-Fi Aware: attach, publish/subscribe, data path e
 * throughput. É o teste que decide se o EasyDrop pode usar NAN como transporte principal.
 *
 * Fluxo entre dois aparelhos:
 *   1. Ambos tocam "Attach".
 *   2. Um toca "Publish", o outro "Subscribe".
 *   3. O que publicou aparece na lista do que assinou. Ele toca "Conectar".
 *   4. O assinante sobe o servidor, avisa o publicador, e o publicador envia PAYLOAD_BYTES.
 *   5. Os dois lados imprimem o throughput medido.
 */
class AwareController(
    private val context: Context,
    private val log: (String) -> Unit,
) {

    companion object {
        const val SERVICE_NAME = "EasyDropDiag"
        const val PSK = "easydrop-diag-psk"
        const val PORT = 8988
        const val PAYLOAD_BYTES = 32L * 1024 * 1024
        private const val CHUNK = 64 * 1024
        private const val NETWORK_TIMEOUT_MS = 30_000
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val manager = context.getSystemService(WifiAwareManager::class.java)

    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var peer: PeerHandle? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var pendingAction: (() -> Unit)? = null
    private var attaching = false

    // ---------------------------------------------------------------- attach

    fun attach() {
        if (session != null) {
            log("Aware: já conectado ao serviço")
            return
        }
        if (attaching) {
            log("Aware: attach já em andamento")
            return
        }
        if (manager == null) {
            log("Aware: serviço indisponível neste aparelho (getSystemService = null)")
            return
        }
        if (!manager.isAvailable) {
            log("Aware: isAvailable = false — aparelho não suporta NAN ou está desligado")
            return
        }

        attaching = true
        log("Aware: attach iniciado")

        manager.attach(object : AttachCallback() {
            override fun onAttached(awareSession: WifiAwareSession) {
                attaching = false
                session = awareSession
                log("Aware: attach OK")
                pendingAction?.invoke()
                pendingAction = null
            }

            override fun onAttachFailed() {
                attaching = false
                log("Aware: attach FALHOU (onAttachFailed)")
                pendingAction = null
            }
        }, handler)
    }

    private fun withSession(action: () -> Unit) {
        if (session != null) {
            action()
        } else {
            pendingAction = action
            attach()
        }
    }

    // --------------------------------------------------------------- publish

    fun publish() = withSession {
        val awareSession = session ?: return@withSession
        if (publishSession != null) {
            log("Aware: publish já ativo")
            return@withSession
        }

        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(deviceLabel().toByteArray())
            .build()

        awareSession.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(discoverySession: PublishDiscoverySession) {
                publishSession = discoverySession
                log("Aware: publish iniciado como '${deviceLabel()}'")
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val text = String(message)
                log("Aware: mensagem recebida: $text")
                if (text == "GO") {
                    peer = peerHandle
                    startClient(peerHandle)
                }
            }

            override fun onSessionTerminated() {
                log("Aware: publish encerrado")
                publishSession = null
            }
        }, handler)
    }

    // ------------------------------------------------------------- subscribe

    fun subscribe() = withSession {
        val awareSession = session ?: return@withSession
        if (subscribeSession != null) {
            log("Aware: subscribe já ativo")
            return@withSession
        }

        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()

        awareSession.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(discoverySession: SubscribeDiscoverySession) {
                subscribeSession = discoverySession
                log("Aware: subscribe iniciado, procurando '$SERVICE_NAME'")
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: List<ByteArray>?,
            ) {
                peer = peerHandle
                val name = serviceSpecificInfo?.let { String(it) } ?: "sem nome"
                log("Aware: peer descoberto ($name) — toque em Conectar")
            }

            override fun onSessionTerminated() {
                log("Aware: subscribe encerrado")
                subscribeSession = null
            }
        }, handler)
    }

    // --------------------------------------------------------------- connect

    /** Lado assinante: sobe o servidor e avisa o publicador para iniciar o envio. */
    fun connect() {
        val discovery = subscribeSession
        val peerHandle = peer
        if (discovery == null || peerHandle == null) {
            log("Aware: conecte depois de descobrir um peer (subscribe + Publish no outro)")
            return
        }

        val specifier = WifiAwareNetworkSpecifier.Builder(discovery, peerHandle)
            .setPskPassphrase(PSK)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        log("Aware: pedindo data path (servidor)")

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log("Aware: data path disponível (servidor)")
                executor.execute {
                    try {
                        val server = openServerSocket(PORT)
                        log("Aware: servidor escutando na porta $PORT")
                        discovery.sendMessage(peerHandle, 0, "GO".toByteArray())
                        log("Aware: 'GO' enviado ao publicador")
                        server.soTimeout = NETWORK_TIMEOUT_MS
                        val socket = server.accept()
                        log("Aware: cliente conectado de ${socket.inetAddress?.hostAddress}")
                        receiveAndMeasure(socket)
                    } catch (e: Exception) {
                        log("Aware: erro no servidor — ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }

            override fun onUnavailable() {
                log("Aware: data path indisponível (timeout de $NETWORK_TIMEOUT_MS ms)")
            }

            override fun onLost(network: Network) {
                log("Aware: data path perdido")
            }
        }

        networkCallback = callback
        connectivity.requestNetwork(request, callback, NETWORK_TIMEOUT_MS)
    }

    /** Lado publicador: entra na rede e envia o payload. */
    private fun startClient(peerHandle: PeerHandle) {
        val discovery = publishSession ?: return

        val specifier = WifiAwareNetworkSpecifier.Builder(discovery, peerHandle)
            .setPskPassphrase(PSK)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        log("Aware: pedindo data path (cliente)")

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log("Aware: data path disponível (cliente)")
                val info = connectivity.getNetworkCapabilities(network)?.transportInfo as? WifiAwareNetworkInfo
                val address: InetAddress? = info?.peerIpv6Addr
                if (address == null) {
                    log("Aware: peerIpv6Addr nulo — não é possível conectar")
                    return
                }
                executor.execute {
                    try {
                        val socket = network.socketFactory.createSocket(address, PORT)
                        socket.soTimeout = NETWORK_TIMEOUT_MS
                        log("Aware: conectado ao servidor ${address.hostAddress}")
                        sendAndMeasure(socket)
                    } catch (e: Exception) {
                        log("Aware: erro no cliente — ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }

            override fun onUnavailable() {
                log("Aware: data path indisponível no cliente (timeout)")
            }
        }

        networkCallback = callback
        connectivity.requestNetwork(request, callback, NETWORK_TIMEOUT_MS)
    }

    // ----------------------------------------------------------- throughput

    private fun sendAndMeasure(socket: Socket) {
        try {
            val out = socket.getOutputStream()
            val buffer = ByteArray(CHUNK)
            var sent = 0L
            val start = System.nanoTime()

            while (sent < PAYLOAD_BYTES) {
                val size = minOf(CHUNK.toLong(), PAYLOAD_BYTES - sent).toInt()
                out.write(buffer, 0, size)
                sent += size
            }
            out.flush()

            val seconds = max(1L, System.nanoTime() - start) / 1_000_000_000.0
            val mbps = (sent / 1024.0 / 1024.0) / seconds
            log("Aware: enviados ${sent / 1024 / 1024} MB em %.2f s = %.1f MB/s".format(seconds, mbps))
        } catch (e: Exception) {
            log("Aware: falha no envio — ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun receiveAndMeasure(socket: Socket) {
        try {
            val input = socket.getInputStream()
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
            log("Aware: recebidos ${received / 1024 / 1024} MB em %.2f s = %.1f MB/s".format(seconds, mbps))
        } catch (e: Exception) {
            log("Aware: falha no recebimento — ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------------ stop

    fun stop() {
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null

        runCatching { publishSession?.close() }
        publishSession = null
        runCatching { subscribeSession?.close() }
        subscribeSession = null
        runCatching { session?.close() }
        session = null

        peer = null
        pendingAction = null
        attaching = false
        log("Aware: tudo encerrado")
    }

    /**
     * O Android não expõe ServerSocket preso a uma Network (o SocketFactory não tem
     * createServerSocket). O data path do Aware é uma interface isolada e protegida por PSK,
     * então um ServerSocket comum na porta combinada basta para o diagnóstico.
     */
    private fun openServerSocket(port: Int): ServerSocket = ServerSocket(port)

    private fun deviceLabel(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
}
