package dev.leandro.easydrop.lab

import android.net.wifi.p2p.WifiP2pConfig

/**
 * Monta a configuração de grupo/entrada sempre com SSID e senha.
 *
 * O `WifiP2pConfig.Builder` lança `IllegalStateException` se a frequência for definida
 * sem credenciais — foi o crash da primeira versão do grupo forçado em 5 GHz. Com
 * SSID e senha, o framework usa o caminho de conexão rápida e não pede confirmação.
 */
internal object P2pConfig {

    fun group(ssid: String, pass: String, frequency: Int = 0): WifiP2pConfig =
        WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .apply { if (frequency > 0) setGroupOperatingFrequency(frequency) }
            .build()
            .apply {
                // 0 = aceita ser cliente; quem cria o grupo já é o dono.
                groupOwnerIntent = 0
            }
}
