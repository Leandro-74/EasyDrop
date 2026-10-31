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
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Identificação por proximidade via BLE: quem recebe anuncia um UUID próprio e publica as
 * credenciais do seu hotspot por GATT. Quem envia escaneia, escolhe o anúncio mais forte,
 * lê as credenciais e entra na rede para empurrar os dados.
 *
 * Funciona a alguns metros — é o modo "estou perto", não o modo "encostei".
 */
class BleMode(
    private val context: Context,
    private val role: Role,
    private val onStatus: (LabStatus) -> Unit,
) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6d2f8a41-3c7e-4b95-a1d6-8f0e2c4b7a93")
        val CONFIG_UUID: UUID = UUID.fromString("6d2f8a42-3c7e-4b95-a1d6-8f0e2c4b7a93")

        private const val RSSI_THRESHOLD = -65
        private const val SCAN_MS = 30_000L
        private const val MAX_GATT_ATTEMPTS = 3
    }

    private val handler = Handler(Looper.getMainLooper())
    private val link = HotspotLink(context, onStatus)

    @Volatile
    private var finished = false

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var gattAttempts = 0
    private var gatt: BluetoothGatt? = null

    private val bluetooth get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun start() {
        if (bluetooth?.isEnabled != true) {
            fail("Bluetooth desligado")
            return
        }
        if (role == Role.RECEIVER) startReceiver() else startSender()
    }

    // ------------------------------------------------------------- receptor

    private fun startReceiver() {
        link.host { ssid, pass ->
            publishCredentials(ssid, pass)
            advertise()
        }
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
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload)
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

    // ------------------------------------------------------------- emissor

    private fun startSender() {
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
                        newState == BluetoothGatt.STATE_DISCONNECTED && !finished -> {
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
                    if (gatt.getService(SERVICE_UUID)?.getCharacteristic(CONFIG_UUID) == null) {
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
        stopScan()

        link.join(parts[0], parts[1])
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
        link.stop()
        LabLog.add("BLE: parado")
    }
}
