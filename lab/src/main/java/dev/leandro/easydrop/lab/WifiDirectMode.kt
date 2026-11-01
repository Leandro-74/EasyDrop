package dev.leandro.easydrop.lab

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

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

        /** Canal 149, não-DFS: evita CAC e dá mais espectro que o grupo em 2,4 GHz. */
        private const val GROUP_FREQUENCY = 5745
    }

    private val beam = NfcBeam(activity)
    private val tcp = TcpLink(onStatus)
    private val handler = Handler(Looper.getMainLooper())
    private val manager = activity.getSystemService(WifiP2pManager::class.java)
    private val random = java.security.SecureRandom()

    private var channel: WifiP2pManager.Channel? = null

    /** Nome do aparelho → endereço P2P, preenchido pela descoberta. */
    private val peers = mutableMapOf<String, String>()

    private var target: String? = null
    private var deadline = 0L
    private var lastDiscover = 0L
    private var polling = false

    /** Disparado pelo broadcast de conexão do P2P para acordar o poll na hora. */
    private var groupListener: (() -> Unit)? = null
    private var groupReceiver: BroadcastReceiver? = null

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

        // Um grupo velho sobrevive ao force-stop e faz o connect criar outro grupo em vez
        // de entrar no grupo do outro aparelho. Limpar antes deixa o teste repetível.
        P2pCleanup.remove(activity) {
            if (finished) return@remove
            if (role == Role.RECEIVER) startReceiver() else startSender()
        }
    }

    // ------------------------------------------------------------- receptor

    private fun startReceiver() {
        onStatus(LabStatus(Phase.SEARCHING, "criando grupo Wi-Fi Direct"))
        createGroup()
    }

    /**
     * Cria o grupo na frequência pedida com credenciais conhecidas. O emissor recebe
     * SSID e senha pelo NFC e entra direto no grupo: sem negociação GO, sem WPS e sem
     * o diálogo de autorização do `UserAuthorizingJoinState`.
     *
     * Se o aparelho não conseguir 5 GHz, cai para automático — melhor um grupo em
     * 2,4 GHz do que nenhum grupo.
     */
    private fun createGroup(frequency: Int = GROUP_FREQUENCY) {
        if (finished) return
        val manager = manager ?: return
        val channel = channel ?: return

        val ssid = "DIRECT-ED-${randomToken(4)}"
        val pass = randomToken(12)

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (finished) return
                val where = if (frequency == 0) "auto" else "$frequency MHz"
                LabLog.add("P2P: grupo $ssid criado ($where)")
                publish(ssid, pass)
            }

            override fun onFailure(reason: Int) {
                if (finished) return
                if (frequency != 0) {
                    LabLog.add("P2P: $frequency MHz falhou (${reasonName(reason)}), tentando automático")
                    createGroup(0)
                } else {
                    fail("createGroup falhou (${reasonName(reason)})")
                }
            }
        }

        manager.createGroup(channel, P2pConfig.group(ssid, pass, frequency), listener)
    }

    private fun publish(ssid: String, pass: String, attempt: Int = 0) {
        val manager = manager ?: return
        val channel = channel ?: return

        manager.requestGroupInfo(channel) { group ->
            if (finished) return@requestGroupInfo
            val frequency = group?.frequency ?: 0
            if (frequency == 0 && attempt < 10) {
                handler.postDelayed({ publish(ssid, pass, attempt + 1) }, POLL_MS)
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
                    handler.postDelayed({ publish(ssid, pass, attempt + 1) }, POLL_MS)
                    return@requestDeviceInfo
                }

                LabLog.add("P2P: grupo $ssid em $frequency MHz, eu sou $name")
                // A frequência vai junto para o emissor escanear só o canal do grupo.
                beam.present("$name\n$ssid\n$pass\n$frequency") {
                    onStatus(LabStatus(Phase.CONNECTING, "toque detectado, esperando conexão"))
                }
                onStatus(LabStatus(Phase.SEARCHING, "encoste o outro aparelho"))
                tcp.serve()
            }
        }
    }

    private fun randomToken(length: Int): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString { repeat(length) { append(chars[random.nextInt(chars.length)]) } }
    }

    // ------------------------------------------------------------- emissor

    private fun startSender() {
        onStatus(LabStatus(Phase.SEARCHING, "encoste no receptor"))

        beam.read(
            onPayload = { payload ->
                val lines = payload.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                val name = lines.getOrNull(0)
                val ssid = lines.getOrNull(1)
                val pass = lines.getOrNull(2)
                val frequency = lines.getOrNull(3)?.toIntOrNull() ?: 0
                if (name == null) {
                    fail("NFC: payload inválido")
                    return@read
                }

                onStatus(LabStatus(Phase.FOUND, "toque detectado"))
                if (ssid != null && pass != null) {
                    connectGroup(ssid, pass, frequency)
                } else {
                    // Receptor antigo publicava só o nome; cai na descoberta por peer.
                    target = name
                    deadline = System.currentTimeMillis() + DISCOVER_MS
                    startDiscovery()
                    tryConnect()
                }
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

    /**
     * Entra no grupo com SSID e senha, sem passar por provision discovery. É o caminho
     * que evita o diálogo de confirmação no dono do grupo e não precisa de descoberta.
     */
    private fun connectGroup(ssid: String, pass: String, frequency: Int, retried: Boolean = false) {
        val manager = manager ?: return
        val channel = channel ?: return

        val config = P2pConfig.group(ssid, pass, frequency)

        val where = if (frequency > 0) "$frequency MHz" else "auto"
        LabLog.add("P2P: entrando no grupo $ssid em $where")
        onStatus(LabStatus(Phase.CONNECTING, "entrando no grupo"))

        manager.connect(
            channel,
            config,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = awaitGroup()
                override fun onFailure(reason: Int) {
                    // Se o canal publicado estiver errado, tenta de novo em qualquer canal.
                    if (frequency > 0 && !retried) {
                        LabLog.add("P2P: $where falhou (${reasonName(reason)}), tentando auto")
                        connectGroup(ssid, pass, 0, retried = true)
                    } else {
                        fail("connect falhou (${reasonName(reason)})")
                    }
                }
            },
        )
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
                        stopListeningGroup()
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

        // O broadcast chega assim que o grupo forma; o poll de 500 ms fica como rede de
        // segurança para quando o framework não entrega o aviso.
        listenToGroupChanges {
            handler.removeCallbacks(poll)
            handler.post(poll)
        }
        handler.postDelayed(poll, POLL_MS)
    }

    private fun listenToGroupChanges(onChange: () -> Unit) {
        groupListener = onChange
        if (groupReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    groupListener?.invoke()
                }
            }
        }

        runCatching {
            ContextCompat.registerReceiver(
                activity,
                receiver,
                IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            groupReceiver = receiver
        }.onFailure {
            LabLog.add("P2P: broadcast indisponível (${it.javaClass.simpleName})")
        }
    }

    private fun stopListeningGroup() {
        groupListener = null
        groupReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
        groupReceiver = null
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
        stopListeningGroup()
        tcp.stop()

        val manager = manager
        val ch = channel
        if (manager != null && ch != null) {
            runCatching { manager.stopPeerDiscovery(ch, null) }
            runCatching { manager.cancelConnect(ch, null) }
            runCatching { manager.removeGroup(ch, null) }
        }
        channel = null

        // `removeGroup` pode falhar com BUSY e deixar o grupo para trás. O cleanup
        // repete a remoção e tem timeout próprio, então o próximo teste começa limpo.
        P2pCleanup.remove(activity)
        LabLog.add("P2P: parado")
    }
}
