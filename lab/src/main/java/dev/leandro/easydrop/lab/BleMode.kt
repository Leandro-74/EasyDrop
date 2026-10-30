package dev.leandro.easydrop.lab

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
import android.os.ParcelUuid
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * O caminho que o produto pretende usar: o BLE identifica o aparelho por proximidade
 * (UUID + RSSI) e o Wi-Fi faz o trabalho pesado.
 *
 * Emissor: sobe hotspot local, publica SSID/senha por GATT, anuncia BLE e espera o TCP.
 * Receptor: escaneia, escolhe o anúncio mais forte, lê as credenciais, entra no hotspot
 * via `WifiNetworkSpecifier` e transfere pelo IP do gateway (que é o emissor).
 */
class BleMode(
    private val context: Context,
    private val role: Role,
    private val onStatus: (LabStatus) -> Unit,
) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6d2f8a41-3c7e-4b95-a1d6-8f0e2c4b7a93")
        val CONFIG_UUID: UUID = UUID.fromString("6d2f8a42-3c7e-4b95-a1d6-8f0e2c4b7a93")

        const val PORT = 8992
        const val PAYLOAD = 16L * 1024 * 1024
        private const val CHUNK = 64 * 1024
        private const val RSSI_THRESHOLD = -75
        private const val SCAN_MS = 30_000L
        private const val ACCEPT_MS = 90_000
        private const val MAX_GATT_ATTEMPTS = 3
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private val startedAt = System.currentTimeMillis()

    @Volatile
    private var finished = false

    // emissor
    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var server: ServerSocket? = null

    // receptor
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var gattAttempts = 0
    private var gatt: BluetoothGatt? = null
    private var network: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val bluetooth get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun start() {
        if (bluetooth?.isEnabled != true) {
            fail("Bluetooth desligado")
            return
        }
        if (role == Role.SENDER) startSender() else startReceiver()
    }

    // ------------------------------------------------------------- emissor

    private fun startSender() {
        onStatus(LabStatus(Phase.SEARCHING, "subindo hotspot local"))

        val wifi = context.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            fail("WifiManager indisponível")
            return
        }

        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
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
                LabLog.add("BLE: hotspot pronto (${ssid} / ${pass})")
                publishCredentials(ssid, pass)
            }

            override fun onFailed(reason: Int) {
                fail("hotspot falhou (reason=$reason)")
            }
        }

        // 5 GHz exigiria SoftApConfiguration.Builder, que este SDK não expõe.
        wifi.startLocalOnlyHotspot(callback, handler)
    }

    private fun publishCredentials(ssid: String, pass: String) {
        val payload = "$ssid\n$pass".toByteArray()

        val characteristic = BluetoothGattCharacteristic(
            CONFIG_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply { value = payload }

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            .apply { addCharacteristic(characteristic) }

        val manager = context.getSystemService(BluetoothManager::class.java)
        val server = manager?.openGattServer(
            context,
            object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    LabLog.add("BLE: GATT $newState de ${device.address}")
                }

                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    offset: Int,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    LabLog.add("BLE: credenciais lidas por ${device.address}")
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        payload,
                    )
                    // Já entregamos o que precisava: parar de anunciar poupa bateria.
                    stopAdvertising()
                }
            },
        )

        if (server == null) {
            fail("GATT server indisponível")
            return
        }

        if (!server.addService(service)) {
            fail("falha ao registrar serviço GATT")
            return
        }

        gattServer = server
        advertise()
        acceptTransfer()
    }

    private fun advertise() {
        val adv = bluetooth?.bluetoothLeAdvertiser
        if (adv == null) {
            fail("advertise BLE não suportado")
            return
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                LabLog.add("BLE: anunciando, esperando peer")
                onStatus(LabStatus(Phase.SEARCHING, "anunciando, esperando peer"))
            }

            override fun onStartFailure(errorCode: Int) {
                fail("advertise falhou ($errorCode)")
            }
        }

        advertiseCallback = callback
        adv.startAdvertising(settings, data, callback)
    }

    private fun stopAdvertising() {
        val callback = advertiseCallback ?: return
        advertiser?.let { runCatching { it.stopAdvertising(callback) } }
        advertiser = null
        advertiseCallback = null
    }

    private fun acceptTransfer() {
        pool.execute {
            try {
                val socketServer = ServerSocket()
                socketServer.reuseAddress = true
                socketServer.bind(java.net.InetSocketAddress(PORT))
                socketServer.soTimeout = ACCEPT_MS
                server = socketServer

                LabLog.add("TCP: servidor na porta $PORT")
                val socket = socketServer.accept()

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

                onStatus(LabStatus(Phase.TRANSFERRING, "transferindo 16 MB", peer, setupMs = elapsed))
                sendPayload(socket)
            } catch (e: Exception) {
                if (!finished) fail("TCP: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------- receptor

    private fun startReceiver() {
        val scan = bluetooth?.bluetoothLeScanner
        if (scan == null) {
            fail("scanner BLE indisponível")
            return
        }
        scanner = scan
        onStatus(LabStatus(Phase.SEARCHING, "escaneando por EasyDrop"))

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rssi = result.rssi
                LabLog.add("BLE: anúncio de ${result.device.address} rssi=$rssi")
                if (rssi < RSSI_THRESHOLD) return

                stopScan()
                onStatus(LabStatus(Phase.FOUND, "peer a $rssi dBm", result.device.address))
                connectGatt(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                fail("scan falhou ($errorCode)")
            }
        }

        scanCallback = callback
        scan.startScan(listOf(filter), settings, callback)

        handler.postDelayed({
            if (scanCallback != null) {
                stopScan()
                if (!finished) fail("nenhum peer em ${SCAN_MS / 1000}s")
            }
        }, SCAN_MS)
    }

    private fun stopScan() {
        val callback = scanCallback ?: return
        runCatching { scanner?.stopScan(callback) }
        scanCallback = null
        scanner = null
    }

    private fun connectGatt(device: BluetoothDevice) {
        gattAttempts++
        onStatus(LabStatus(Phase.CONNECTING, "lendo credenciais por GATT", device.address))

        runCatching { gatt?.close() }
        gatt = device.connectGatt(
            context,
            false,
            object : android.bluetooth.BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    LabLog.add("BLE: GATT $newState (status=$status)")
                    when {
                        newState == BluetoothGatt.STATE_CONNECTED -> gatt.discoverServices()
                        newState == BluetoothGatt.STATE_DISCONNECTED && !finished && network == null -> {
                            runCatching { gatt.close() }
                            if (gattAttempts < MAX_GATT_ATTEMPTS) {
                                LabLog.add("BLE: GATT caiu, nova tentativa ${gattAttempts + 1}/$MAX_GATT_ATTEMPTS")
                                handler.postDelayed({ if (!finished) connectGatt(device) }, 600)
                            } else {
                                fail("GATT desconectou antes das credenciais")
                            }
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CONFIG_UUID)
                    if (characteristic == null) {
                        fail("serviço GATT sem característica")
                        return
                    }
                    gatt.requestMtu(185)
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CONFIG_UUID)
                    if (characteristic == null) {
                        fail("serviço GATT perdido")
                        return
                    }
                    handler.postDelayed({ gatt.readCharacteristic(characteristic) }, 150)
                }

                @Deprecated("API antiga, mas é a que o Android entrega")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    onCredentials(characteristic.value)
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    onCredentials(value)
                }
            },
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    private fun onCredentials(value: ByteArray?) {
        if (value == null) {
            fail("credenciais vazias")
            return
        }

        val parts = String(value).split("\n")
        if (parts.size < 2) {
            fail("credenciais inválidas: ${String(value)}")
            return
        }

        LabLog.add("BLE: credenciais recebidas (${parts[0]})")
        runCatching { gatt?.close() }
        gatt = null

        joinHotspot(parts[0], parts[1])
    }

    private fun joinHotspot(ssid: String, pass: String) {
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

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                this@BleMode.network = network
                val gateway = gatewayOf(cm.getLinkProperties(network))
                LabLog.add("Wi-Fi: no hotspot, gateway=${gateway?.hostAddress ?: "?"}")
                if (gateway == null) {
                    fail("hotspot sem gateway")
                    return
                }
                onStatus(LabStatus(Phase.CONNECTED, "conectado ao hotspot", gateway.hostAddress))
                acquireWifiLock()
                pool.execute { receive(gateway) }
            }

            override fun onUnavailable() {
                fail("diálogo do hotspot recusado")
            }
        }

        networkCallback = callback
        cm.requestNetwork(request, callback, 120_000)
    }

    private fun gatewayOf(props: LinkProperties?): java.net.InetAddress? {
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

    private fun receive(gateway: java.net.InetAddress) {
        val network = this.network
        if (network == null) {
            fail("rede do hotspot perdida")
            return
        }

        try {
            val socket = Socket()
            network.bindSocket(socket)
            socket.connect(java.net.InetSocketAddress(gateway, PORT), 10_000)

            val elapsed = System.currentTimeMillis() - startedAt
            LabLog.add("TCP: conectado em ${elapsed}ms com ${gateway.hostAddress}")
            onStatus(LabStatus(Phase.CONNECTED, "conectado em ${elapsed}ms", gateway.hostAddress, setupMs = elapsed))

            runCatching { Thread.sleep(500) }
            if (finished) return

            onStatus(LabStatus(Phase.TRANSFERRING, "recebendo 16 MB", gateway.hostAddress, setupMs = elapsed))

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
            LabLog.add("TCP: recebidos %.0f MB em %.2f s = %.1f MB/s".format(received / 1024.0 / 1024.0, seconds, mbps))
            onStatus(
                LabStatus(
                    Phase.CONNECTED,
                    "recebido ${received / 1024 / 1024} MB",
                    gateway.hostAddress,
                    mbps = mbps,
                    setupMs = System.currentTimeMillis() - startedAt,
                ),
            )
            runCatching { socket.close() }
        } catch (e: Exception) {
            if (!finished) fail("TCP: ${e.message}")
        }
    }

    // -------------------------------------------------------------- comum

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
            LabLog.add("TCP: enviados 16 MB em %.2f s = %.1f MB/s".format(seconds, mbps))
            onStatus(
                LabStatus(
                    Phase.CONNECTED,
                    "enviado 16 MB",
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

    private fun fail(message: String) {
        LabLog.add(message)
        onStatus(LabStatus(Phase.ERROR, message))
    }

    fun stop() {
        finished = true

        stopAdvertising()
        stopScan()
        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null

        runCatching { gatt?.close() }
        gatt = null

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
        LabLog.add("BLE: parado")
    }
}
