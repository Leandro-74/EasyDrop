package dev.leandro.easydrop.lab

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * A transferência TCP de 16 MB, igual para qualquer transporte embaixo (hotspot local,
 * Wi-Fi Direct). Quem recebe chama [serve]; quem envia chama [send].
 */
class TcpLink(private val onStatus: (LabStatus) -> Unit) {

    companion object {
        const val PORT = 8992
        const val PAYLOAD = 16L * 1024 * 1024
        private const val CHUNK = 64 * 1024
        private const val ACCEPT_MS = 300_000

        /** Janela grande: com a padrão (64 KB) o RTT do specifier limita a ~3,7 MB/s. */
        private const val SOCKET_BUFFER = 4 * 1024 * 1024
    }

    private val pool = Executors.newCachedThreadPool()
    private var startedAt = System.currentTimeMillis()
    private var server: ServerSocket? = null

    @Volatile
    private var finished = false

    /** Sobe o servidor e espera alguém enviar. */
    fun serve() {
        startedAt = System.currentTimeMillis()
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
                receive(socket, peer)
            } catch (e: Exception) {
                if (!finished) fail("TCP: ${e.message}")
            }
        }
    }

    /** Conecta no host e empurra os 16 MB. [prepare] ajusta o socket antes do connect. */
    fun send(host: String, prepare: (Socket) -> Unit = {}) {
        startedAt = System.currentTimeMillis()
        val socket = Socket()
        pool.execute {
            try {
                socket.sendBufferSize = SOCKET_BUFFER
                socket.receiveBufferSize = SOCKET_BUFFER
                socket.tcpNoDelay = true
                prepare(socket)
                socket.connect(InetSocketAddress(host, PORT), 10_000)

                val elapsed = System.currentTimeMillis() - startedAt
                LabLog.add("TCP: conectado em ${elapsed}ms com $host")
                onStatus(LabStatus(Phase.CONNECTED, "conectado em ${elapsed}ms", host, setupMs = elapsed))

                runCatching { Thread.sleep(500) }
                if (finished) return@execute

                onStatus(LabStatus(Phase.TRANSFERRING, "enviando 16 MB", host, setupMs = elapsed))

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
                        host,
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
    }

    private fun receive(socket: Socket, peer: String?) {
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

    /** Janela grande nos dois sentidos e sem Nagle: o envio vira um fluxo contínuo. */
    private fun tune(socket: Socket) {
        runCatching { socket.sendBufferSize = SOCKET_BUFFER }
        runCatching { socket.receiveBufferSize = SOCKET_BUFFER }
        runCatching { socket.tcpNoDelay = true }
    }

    private fun fail(message: String) {
        LabLog.add(message)
        onStatus(LabStatus(Phase.ERROR, message))
    }

    fun stop() {
        finished = true
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }
}
