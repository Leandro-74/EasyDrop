package dev.leandro.easydrop.lab

import android.Manifest
import android.os.Build

/**
 * Permissões que o laboratório precisa pedir, por versão do Android.
 *
 * Em API 33+ o Wi-Fi usa `NEARBY_WIFI_DEVICES`; pedir localização junto só gera um
 * diálogo a mais. Abaixo disso (e no Android 12), a localização continua necessária.
 */
internal fun requiredPermissions(sdk: Int): List<String> = buildList {
    if (sdk < Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (sdk >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    if (sdk >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}
