package dev.leandro.easydrop.lab

import android.app.Activity
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper

/**
 * Wi-Fi Direct: quem recebe cria o grupo (vira dono, sem diálogo) e publica o próprio
 * nome + SSID + senha pelo toque NFC. Quem envia descobre o peer e entra no grupo
 * existente, empurrando os dados para o dono, que é sempre 192.168.49.1.
 *
 * A vantagem sobre o hotspot local é não depender do `WifiNetworkSpecifier` nem do
 * diálogo de confirmação — o grupo já existe quando o outro lado chega.
 *
 * A descoberta P2P começa assim que o emissor é armado, não depois do toque: quando o
 * NFC entrega o nome, o endereço normalmente já está na lista. Medido: 0,6 s a 14 s
 * quando a descoberta começava só no toque.
 */
class WifiDirectMode(
    private val activity: Activity,
    private val role: Role,
    private val onStatus: (LabStatus) -> Unit,
) {

    companion object {
        private const val CONNECT_MS = 60_000L
        private const val DISCOVER_MS = 60_000L
        private const val REDISCOVER_MS = 8_000L
        private const val POLL_MS = 500L
    }

    private val beam = NfcBeam(activity)
    private val tcp = TcpLink(onStatus)
    private val handler = Handler(Looper.getMainLooper())
    private val manager = activity.getSystemService(WifiP2pManager::class.java)

    private var channel: WifiP2pManager.Channel? = null

    /** Nome do aparelho → endereço P2P, preenchido pela descoberta. */
    private val peers = mutableMapOf<String, String>()

    private var target: String? = null
    private var deadline = 0L
    private var lastDiscover = 0L
    private var polling = false

    @Volatile
    private var finished = false

    fun start() {
        beam.problem()?.let {
            fail(it)
            return
        }
        val manager = manager
        if (manager == null) {
            fail("Wi-Fi Direct indisponível")
            return
        }

        channel = manager.initialize(activity, Looper.getMainLooper(), null)
        if (channel == null) {
            fail("canal Wi-Fi Direct indisponível")
            return
        }

        if (role == Role.RECEIVER) startReceiver() else startSender()
    }

    // ------------------------------------------------------------- receptor

    private fun startReceiver() {
        onStatus(LabStatus(Phase.SEARCHING, "criando grupo Wi-Fi Direct"))
        val manager = manager ?: return
        val channel = channel ?: return

        // Grupo de um teste anterior atrapalha o próximo; remove e segue de qualquer jeito.
        manager.removeGroup(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = createGroup()
                override fun onFailure(reason: Int) = createGroup()
            },
        )
    }

    private fun createGroup() {
        if (finished) return
        val manager = manager ?: return
        val channel = channel ?: return

        manager.createGroup(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (finished) return
                    LabLog.add("P2P: grupo criado")
                    publish()
                }

                override fun onFailure(reason: Int) = fail("createGroup falhou (${reasonName(reason)})")
            },
        )
    }

    private fun publish(attempt: Int = 0) {
        val manager = manager ?: return
        val channel = channel ?: return

        manager.requestGroupInfo(channel) { group ->
            if (finished) return@requestGroupInfo
            val ssid = group?.networkName?.takeIf { it.isNotBlank() }
            val pass = group?.passphrase?.takeIf { it.isNotBlank() }

            if (ssid == null || pass == null) {
                if (attempt >= 20) {
                    fail("grupo sem credenciais")
                    return@requestGroupInfo
                }
                handler.postDelayed({ publish(attempt + 1) }, POLL_MS)
                return@requestGroupInfo
            }

            manager.requestDeviceInfo(channel) { device ->
                if (finished) return@requestDeviceInfo
                // O MAC local vem mascarado desde o Android 10; o nome é o que sobra.
                val name = device?.deviceName?.takeIf { it.isNotBlank() }
                if (name == null) {
                    if (attempt >= 20) {
                        fail("sem nome P2P")
                        return@requestDeviceInfo
                    }
                    handler.postDelayed({ publish(attempt + 1) }, POLL_MS)
                    return@requestDeviceInfo
                }

                LabLog.add("P2P: grupo $ssid, eu sou $name")
                beam.present("$name\n$ssid\n$pass") {
                    onStatus(LabStatus(Phase.CONNECTING, "toque detectado, esperando conexão"))
                }
                onStatus(LabStatus(Phase.SEARCHING, "encoste o outro aparelho"))
                tcp.serve()
            }
        }
    }

    // ------------------------------------------------------------- emissor

    private fun startSender() {
        onStatus(LabStatus(Phase.SEARCHING, "encoste no receptor"))
        startDiscovery()

        beam.read(
            onPayload = { payload ->
                val name = payload.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
                if (name == null) {
                    fail("NFC: payload inválido")
                    return@read
                }
                onStatus(LabStatus(Phase.FOUND, "toque detectado"))
                target = name
                deadline = System.currentTimeMillis() + DISCOVER_MS
                tryConnect()
            },
            onError = { fail(it) },
        )
    }

    private fun startDiscovery() {
        if (finished) return
        val manager = manager ?: return
        val channel = channel ?: return
        lastDiscover = System.currentTimeMillis()

        manager.discoverPeers(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (polling) return
                    polling = true
                    pollPeers()
                }

                override fun onFailure(reason: Int) {
                    LabLog.add("P2P: discoverPeers falhou (${reasonName(reason)})")
                }
            },
        )
    }

    private fun pollPeers() {
        if (finished) return
        val manager = manager ?: return
        val channel = channel ?: return

        manager.requestPeers(channel) { result ->
            if (finished) return@requestPeers

            val list = result?.deviceList.orEmpty()
            list.forEach {
                if (peers.put(it.deviceName, it.deviceAddress) == null) {
                    LabLog.add("P2P: vi ${it.deviceName} em ${it.deviceAddress}")
                }
            }

            if (tryConnect()) return@requestPeers

            // A descoberta do framework estagna; reemite de tempos em tempos.
            if (System.currentTimeMillis() - lastDiscover > REDISCOVER_MS) startDiscovery()

            handler.postDelayed({ pollPeers() }, POLL_MS)
        }
    }

    /** Conecta se o alvo já apareceu. Devolve true quando a conexão foi disparada. */
    private fun tryConnect(): Boolean {
        val name = target ?: return false
        val address = peers[name]
        if (address == null) {
            if (deadline > 0 && System.currentTimeMillis() > deadline) {
                target = null
                fail("peer $name não apareceu")
            }
            return false
        }

        target = null
        connect(address)
        return true
    }

    private fun connect(mac: String) {
        val manager = manager ?: return
        val channel = channel ?: return

        val config = WifiP2pConfig().apply {
            deviceAddress = mac
            wps.setup = WpsInfo.PBC
            // 0 = aceita ser cliente; o grupo já existe do outro lado.
            groupOwnerIntent = 0
        }

        LabLog.add("P2P: entrando no grupo de $mac")
        onStatus(LabStatus(Phase.CONNECTING, "entrando no grupo"))

        manager.connect(
            channel,
            config,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = awaitGroup()
                override fun onFailure(reason: Int) = fail("connect falhou (${reasonName(reason)})")
            },
        )
    }

    private fun awaitGroup() {
        val groupDeadline = System.currentTimeMillis() + CONNECT_MS

        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                val manager = manager ?: return
                val channel = channel ?: return

                manager.requestConnectionInfo(channel) { info ->
                    if (finished) return@requestConnectionInfo

                    if (info != null && info.groupFormed) {
                        val host = info.groupOwnerAddress?.hostAddress
                        if (host == null) {
                            fail("grupo sem endereço")
                            return@requestConnectionInfo
                        }
                        LabLog.add("P2P: grupo formado, dono em $host")
                        onStatus(LabStatus(Phase.CONNECTED, "no grupo P2P", host))
                        tcp.send(host)
                        return@requestConnectionInfo
                    }

                    if (System.currentTimeMillis() > groupDeadline) {
                        fail("grupo não formou em ${CONNECT_MS / 1000}s")
                        return@requestConnectionInfo
                    }
                    handler.postDelayed(this, POLL_MS)
                }
            }
        }

        handler.postDelayed(poll, POLL_MS)
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "não suportado"
        WifiP2pManager.BUSY -> "ocupado"
        WifiP2pManager.ERROR -> "erro"
        else -> "código $reason"
    }

    private fun fail(message: String) {
        LabLog.add(message)
        onStatus(LabStatus(Phase.ERROR, message))
    }

    fun stop() {
        finished = true
        beam.stop()
        tcp.stop()

        val manager = manager
        val ch = channel
        if (manager != null && ch != null) {
            runCatching { manager.stopPeerDiscovery(ch, null) }
            runCatching { manager.cancelConnect(ch, null) }
            runCatching { manager.removeGroup(ch, null) }
        }
        channel = null
        LabLog.add("P2P: parado")
    }
}
