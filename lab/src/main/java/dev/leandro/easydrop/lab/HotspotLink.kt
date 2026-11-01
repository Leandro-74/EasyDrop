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
import java.net.InetAddress

/**
 * Hotspot local + `WifiNetworkSpecifier`, com o TCP delegado ao [TcpLink].
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

    private val handler = Handler(Looper.getMainLooper())
    private val tcp = TcpLink(onStatus)

    @Volatile
    private var finished = false

    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var network: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ------------------------------------------------------------- receptor

    /** Sobe o hotspot e entrega as credenciais para quem for publicá-las (BLE, NFC). */
    fun host(onCredentials: (ssid: String, pass: String) -> Unit) {
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
                    tcp.serve()
                }

                override fun onFailed(reason: Int) {
                    fail("hotspot falhou (reason=$reason)")
                }
            },
            handler,
        )
    }

    // ------------------------------------------------------------- emissor

    fun join(ssid: String, pass: String) {
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
                tcp.send(gateway.hostAddress) { socket -> network.bindSocket(socket) }
            }

            override fun onUnavailable() {
                fail("diálogo do hotspot recusado")
            }
        }

        networkCallback = callback
        cm.requestNetwork(request, callback, 120_000)
    }

    // -------------------------------------------------------------- comum

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
                    .onSuccess { LabLog.add("Wi-Fi: lock de baixa latência ativo") }
                    .onFailure { LabLog.add("Wi-Fi: lock falhou — ${it.javaClass.simpleName}: ${it.message}") }
            }
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

        tcp.stop()
    }
}
