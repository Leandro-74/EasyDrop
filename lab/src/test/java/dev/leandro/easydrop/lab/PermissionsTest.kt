package dev.leandro.easydrop.lab

import android.Manifest
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {

    @Test
    fun `api 35 pede apenas as permissoes nearby`() {
        val permissions = requiredPermissions(35)

        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
    }

    @Test
    fun `api 32 ainda pede localizacao e nao pede nearby`() {
        val permissions = requiredPermissions(32)

        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
    }

    @Test
    fun `api 29 pede localizacao sem permissoes de bluetooth proximo`() {
        val permissions = requiredPermissions(Build.VERSION_CODES.Q)

        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertFalse(Manifest.permission.BLUETOOTH_SCAN in permissions)
    }
}
