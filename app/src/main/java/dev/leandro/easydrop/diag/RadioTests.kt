package dev.leandro.easydrop.diag

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Testa os transportes que sobraram depois que o Edge 50 Ultra se revelou sem Wi-Fi Aware:
 * hotspot local (LocalOnlyHotspot) e BLE (advertise + scan).
 */
class RadioTests(
    private val context: Context,
    private val log: (String) -> Unit,
) {

    companion object {
        val EASY_DROP_UUID: UUID = UUID.fromString("0000fe2c-0000-1000-8000-00805f9b34fb")
        private const val SCAN_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null

    // -------------------------------------------------------------- hotspot

    fun startHotspot() {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            log("Hotspot: WifiManager indisponível")
            return
        }
        if (hotspot != null) {
            log("Hotspot: já ativo")
            return
        }

        log("Hotspot: iniciando LocalOnlyHotspot")
        try {
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        hotspot = reservation
                        val config = reservation.wifiConfiguration
                        log("Hotspot: ATIVO ssid='${config?.SSID}' senha='${config?.preSharedKey}'")
                    }

                    override fun onStopped() {
                        log("Hotspot: encerrado")
                        hotspot = null
                    }

                    override fun onFailed(reason: Int) {
                        log("Hotspot: FALHOU reason=$reason")
                        hotspot = null
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            log("Hotspot: exceção ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun stopHotspot() {
        hotspot?.close()
        hotspot = null
        log("Hotspot: solicitado encerramento")
    }

    // ------------------------------------------------------------ join (WNS)

    /**
     * Caminho que o app real usa para entrar no hotspot do outro aparelho.
     * Diferente de `cmd wifi connect-network`, aqui o Android mostra um diálogo do sistema
     * pedindo confirmação — é a UX real do produto.
     */
    fun join(ssid: String, passphrase: String) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm == null) {
            log("Join: ConnectivityManager indisponível")
            return
        }

        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()

        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        log("Join: pedindo '$ssid' (o sistema deve mostrar o diálogo)")

        cm.requestNetwork(request, object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val props = cm.getLinkProperties(network)
                val ips = props?.linkAddresses?.joinToString { it.address.hostAddress ?: "?" }
                log("Join: CONECTADO — ips locais: $ips")
            }

            override fun onUnavailable() {
                log("Join: indisponível (diálogo recusado ou timeout)")
            }

            override fun onLost(network: android.net.Network) {
                log("Join: rede perdida")
            }
        }, 60_000)
    }

    // ------------------------------------------------------------------ BLE

    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun startBleAdvertise() {
        val bt = adapter()
        if (bt == null) {
            log("BLE: adapter nulo")
            return
        }
        if (!bt.isEnabled) {
            log("BLE: adapter desligado")
            return
        }

        val adv = bt.bluetoothLeAdvertiser
        if (adv == null) {
            log("BLE: advertise não suportado")
            return
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(EASY_DROP_UUID))
            .addServiceData(ParcelUuid(EASY_DROP_UUID), byteArrayOf(1, 0, 0, 1))
            .build()

        log("BLE: iniciando advertise")
        adv.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                log("BLE: advertise ATIVO (tx=${settingsInEffect.txPowerLevel})")
            }

            override fun onStartFailure(errorCode: Int) {
                log("BLE: advertise FALHOU ($errorCode)")
            }
        })
    }

    fun stopBleAdvertise() {
        advertiser?.stopAdvertising(null)
        advertiser = null
        log("BLE: advertise encerrado")
    }

    fun startBleScan() {
        val bt = adapter()
        if (bt == null || !bt.isEnabled) {
            log("BLE: adapter indisponível/desligado")
            return
        }
        val scan = bt.bluetoothLeScanner
        if (scan == null) {
            log("BLE: scanner indisponível")
            return
        }
        scanner = scan

        val seen = LinkedHashMap<String, Int>()
        var easyDropHits = 0
        var bestEasyDrop = Int.MIN_VALUE

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                val record = result.scanRecord
                val uuids = record?.serviceUuids?.map { it.uuid } ?: emptyList()
                val ours = record?.getServiceData(ParcelUuid(EASY_DROP_UUID))

                if (uuids.contains(EASY_DROP_UUID)) {
                    easyDropHits++
                    bestEasyDrop = maxOf(bestEasyDrop, result.rssi)
                    log(
                        "BLE: EASYDROP em $address rssi=${result.rssi} " +
                            "data=${ours?.joinToString(",") ?: "sem dados"}",
                    )
                }

                if (seen.put(address, result.rssi) == null && seen.size <= 20) {
                    val name = result.device.name ?: "?"
                    log("BLE: $name $address rssi=${result.rssi} uuids=${uuids.size}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("BLE: scan FALHOU ($errorCode)")
            }
        }

        log("BLE: scan por ${SCAN_MS / 1000}s")
        scan.startScan(callback)

        handler.postDelayed({
            scan.stopScan(callback)
            val strongest = seen.maxByOrNull { it.value }
            log(
                "BLE: scan encerrado — ${seen.size} dispositivos, " +
                    "$easyDropHits anúncios EasyDrop, melhor rssi=$bestEasyDrop, " +
                    "mais forte=${strongest?.key} (${strongest?.value} dBm)",
            )
        }, SCAN_MS)
    }
}
