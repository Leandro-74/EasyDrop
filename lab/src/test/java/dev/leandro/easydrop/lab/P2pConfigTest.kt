package dev.leandro.easydrop.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class P2pConfigTest {

    @Test
    fun `grupo sempre leva ssid e senha`() {
        val config = P2pConfig.group("DIRECT-ED-ABCD", "12345678", 5745)

        assertEquals("DIRECT-ED-ABCD", config.networkName)
        assertEquals("12345678", config.passphrase)
        assertEquals(5745, config.groupOwnerBand)
    }

    @Test
    fun `sem frequencia tambem leva credenciais`() {
        val config = P2pConfig.group("DIRECT-ED-ABCD", "12345678")

        assertNotNull(config.networkName)
        assertNotNull(config.passphrase)
    }
}
