package dev.leandro.easydrop.lab

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * A transferência TCP de 16 MB, igual para qualquer transporte embaixo (hotspot local,
 * Wi-Fi Direct). Quem recebe chama [serve]; quem envia chama [send].
 *
 * O socket ativo fica guardado para que [stop] consiga interromper uma transferência em
 * andamento: fechar o `ServerSocket` libera o `accept`, mas não o socket já aceito.
 */
class TcpLink(
    private val onStatus: (LabStatus) -> Unit,
    private val ackMs: Int = ACK_MS,
    private val idleMs: Int = IDLE_MS,
) {

    companion object {
        const val PORT = 8992
        const val PAYLOAD = 16L * 1024 * 1024
        private const val CHUNK = 64 * 1024
        private const val ACCEPT_MS = 300_000

        /** Depois de conectar, quanto esperar por dados antes de considerar a sessão morta. */
        private const val IDLE_MS = 30_000

        /** Quanto o emissor espera o ACK final do receptor. */
        private const val ACK_MS = 10_000

        /** Janela grande: com a padrão (64 KB) o RTT do specifier limita a ~3,7 MB/s. */
        private const val SOCKET_BUFFER = 4 * 1024 * 1024
    }

    private val pool = Executors.newCachedThreadPool()

    @Volatile
    private var startedAt = System.currentTimeMillis()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var active: Socket? = null

    @Volatile
    private var finished = false

    /** Sobe o servidor e espera alguém enviar. */
    fun serve() = serve(PORT)

    fun serve(port: Int) {
        startedAt = System.currentTimeMillis()
        pool.execute {
            try {
                val socketServer = ServerSocket()
                socketServer.reuseAddress = true
                socketServer.receiveBufferSize = SOCKET_BUFFER
                socketServer.bind(InetSocketAddress(port))
                socketServer.soTimeout = ACCEPT_MS
                server = socketServer

                LabLog.add("TCP: servidor na porta $port")
                val socket = socketServer.accept()
                active = socket
                tune(socket)

                if (finished) {
                    closeQuietly(socket)
                    return@execute
                }

                val setupMs = System.currentTimeMillis() - startedAt
                val peer = socket.inetAddress?.hostAddress
                LabLog.add("TCP: conectado em ${setupMs}ms com $peer")
                onStatus(LabStatus(Phase.CONNECTED, "conectado em ${setupMs}ms", peer, setupMs = setupMs))

                if (finished) return@execute

                onStatus(LabStatus(Phase.TRANSFERRING, "recebendo 16 MB", peer, setupMs = setupMs))
                receive(socket, peer, setupMs)
            } catch (e: Exception) {
                if (!finished) fail("TCP: ${e.message}")
            } finally {
                active = null
                runCatching { server?.close() }
                server = null
            }
        }
    }

    /** Conecta no host e empurra os 16 MB. [prepare] ajusta o socket antes do connect. */
    fun send(host: String, prepare: (Socket) -> Unit = {}) = send(host, PORT, prepare)

    fun send(host: String, port: Int, prepare: (Socket) -> Unit = {}) {
        startedAt = System.currentTimeMillis()
        val socket = Socket()
        active = socket
        pool.execute {
            try {
                runCatching { socket.sendBufferSize = SOCKET_BUFFER }
                runCatching { socket.receiveBufferSize = SOCKET_BUFFER }
                runCatching { socket.tcpNoDelay = true }
                prepare(socket)
                socket.connect(InetSocketAddress(host, port), 10_000)

                if (finished) return@execute

                val setupMs = System.currentTimeMillis() - startedAt
                LabLog.add("TCP: conectado em ${setupMs}ms com $host")
                onStatus(LabStatus(Phase.CONNECTED, "conectado em ${setupMs}ms", host, setupMs = setupMs))

                if (finished) return@execute

                onStatus(LabStatus(Phase.TRANSFERRING, "enviando 16 MB", host, setupMs = setupMs))

                val out = DataOutputStream(socket.getOutputStream())
                val buffer = ByteArray(CHUNK)
                var sent = 0L
                val start = System.nanoTime()

                while (sent < PAYLOAD) {
                    if (finished) return@execute
                    val size = minOf(CHUNK.toLong(), PAYLOAD - sent).toInt()
                    out.write(buffer, 0, size)
                    sent += size
                }
                out.flush()

                // Só termina quando o receptor confirma que drenou os 16 MB. Sem isso,
                // o buffer do socket faz o emissor parecer mais rápido do que a rede.
                socket.soTimeout = ackMs
                val ack = try {
                    socket.getInputStream().read()
                } catch (e: SocketTimeoutException) {
                    fail("TCP: receptor não confirmou em ${ackMs / 1000}s")
                    return@execute
                }
                check(ack >= 0) { "receptor não confirmou" }

                val seconds = max(1L, System.nanoTime() - start) / 1_000_000_000.0
                val mbps = (PAYLOAD / 1024.0 / 1024.0) / seconds
                LabLog.add("TCP: enviados 16 MB em %.2f s = %.1f MB/s".format(seconds, mbps))
                onStatus(
                    LabStatus(
                        Phase.CONNECTED,
                        "enviado 16 MB",
                        host,
                        mbps = mbps,
                        setupMs = setupMs,
                    ),
                )
            } catch (e: Exception) {
                if (!finished) fail("TCP: ${e.message}")
            } finally {
                active = null
                closeQuietly(socket)
            }
        }
    }

    private fun receive(socket: Socket, peer: String?, setupMs: Long) {
        try {
            socket.soTimeout = idleMs
            val input = DataInputStream(socket.getInputStream())
            val buffer = ByteArray(CHUNK)
            var received = 0L
            val start = System.nanoTime()
            var lastLog = start

            // Taxa por segundo: mostra se a janela TCP está crescendo ou se bate num teto.
            while (received < PAYLOAD) {
                if (finished) return
                val want = minOf(buffer.size.toLong(), PAYLOAD - received).toInt()
                val read = try {
                    input.read(buffer, 0, want)
                } catch (e: SocketTimeoutException) {
                    fail("TCP: sem dados por ${idleMs / 1000}s")
                    return
                }
                if (read <= 0) break
                received += read
                val now = System.nanoTime()
                if (now - lastLog >= 1_000_000_000L) {
                    lastLog = now
                    LabLog.add("TCP: t=${(now - start) / 1_000_000_000.0}s ${received / 1024 / 1024} MB")
                }
            }

            // Confirma para o emissor que tudo chegou; ele espera este byte.
            if (received == PAYLOAD) {
                socket.getOutputStream().write(1)
                socket.getOutputStream().flush()
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
                    setupMs = setupMs,
                ),
            )
        } catch (e: Exception) {
            if (!finished) fail("TCP: ${e.message}")
        } finally {
            closeQuietly(socket)
        }
    }

    /** Janela grande nos dois sentidos e sem Nagle: o envio vira um fluxo contínuo. */
    private fun tune(socket: Socket) {
        runCatching { socket.sendBufferSize = SOCKET_BUFFER }
        runCatching { socket.receiveBufferSize = SOCKET_BUFFER }
        runCatching { socket.tcpNoDelay = true }
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }

    private fun fail(message: String) {
        LabLog.add(message)
        onStatus(LabStatus(Phase.ERROR, message))
    }

    fun stop() {
        finished = true
        runCatching { active?.close() }
        active = null
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }
}
