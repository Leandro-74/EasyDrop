package dev.leandro.easydrop.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Mede o throughput real sobre a rede que os aparelhos estiverem compartilhando
 * (LAN comum ou LocalOnlyHotspot). É o número que decide se o transporte serve para
 * arquivos grandes.
 */
class LanTransfer(
    private val context: Context,
    private val log: (String) -> Unit,
) {

    companion object {
        const val PORT = 8990
        const val PAYLOAD = 32L * 1024 * 1024
        private const val CHUNK = 64 * 1024
    }

    private val executor = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null

    fun serve() {
        executor.execute {
            try {
                server?.let { runCatching { it.close() } }

                val socketServer = ServerSocket()
                socketServer.reuseAddress = true
                socketServer.bind(InetSocketAddress(PORT))
                socketServer.soTimeout = 60_000
                server = socketServer

                log("LAN: servidor na porta $PORT")
                log("LAN: IPv4 locais — ${localAddresses()}")

                val socket = socketServer.accept()
                log("LAN: cliente conectado de ${socket.inetAddress?.hostAddress}")
                receive(socket)
            } catch (e: Exception) {
                log("LAN: erro no servidor — ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun send(host: String) {
        executor.execute {
            try {
                val target = InetAddress.getByName(host)
                val network = networkFor(target)
                val socket = Socket()
                if (network != null) {
                    network.bindSocket(socket)
                    log("LAN: socket preso à rede ${network}")
                } else {
                    log("LAN: nenhuma rede com rota para $host — tentando a padrão")
                }
                socket.connect(InetSocketAddress(target, PORT), 10_000)
                log("LAN: conectado a $host:$PORT")
                sendPayload(socket)
            } catch (e: Exception) {
                log("LAN: erro no cliente — ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Acha a rede cuja sub-rede contém o destino. Sem isso o socket sai pela rede padrão. */
    private fun networkFor(target: InetAddress): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { network ->
            val properties = cm.getLinkProperties(network) ?: return@firstOrNull false
            properties.linkAddresses.any { link ->
                val address = link.address
                address is Inet4Address && samePrefix(address, target, link.prefixLength)
            }
        }
    }

    private fun samePrefix(a: InetAddress, b: InetAddress, bits: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size) return false
        val fullBytes = bits / 8
        val remaining = bits % 8
        for (i in 0 until fullBytes) {
            if (x[i] != y[i]) return false
        }
        if (remaining == 0) return true
        val mask = (0xFF shl (8 - remaining)) and 0xFF
        return (x[fullBytes].toInt() and mask) == (y[fullBytes].toInt() and mask)
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
            val mb = PAYLOAD / 1024.0 / 1024.0
            log("LAN: enviados %.0f MB em %.2f s = %.1f MB/s".format(mb, seconds, mb / seconds))
        } catch (e: Exception) {
            log("LAN: falha no envio — ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun receive(socket: Socket) {
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
            val mb = received / 1024.0 / 1024.0
            log("LAN: recebidos %.0f MB em %.2f s = %.1f MB/s".format(mb, seconds, mb / seconds))
        } catch (e: Exception) {
            log("LAN: falha no recebimento — ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun localAddresses(): String = buildString {
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                nif.inetAddresses.toList().forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        append("${nif.name}=").append(address.hostAddress).append("  ")
                    }
                }
            }
        }
        if (isEmpty()) append("nenhum")
    }
}
