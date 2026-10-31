package dev.leandro.easydrop.lab

import android.app.Activity
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper

/**
 * Remove grupo P2P de execuções anteriores.
 *
 * O grupo sobrevive ao force-stop do app. Se ele fica para trás, o `connect` do outro
 * aparelho pode formar um grupo novo em vez de entrar no grupo que já existe — e é nesse
 * caminho que o sistema às vezes pede confirmação. Limpar antes de começar deixa o teste
 * repetível e evita o diálogo.
 */
object P2pCleanup {

    private const val TIMEOUT_MS = 2_000L

    fun remove(activity: Activity, onDone: () -> Unit = {}) {
        val manager = activity.getSystemService(WifiP2pManager::class.java)
        val channel = manager?.initialize(activity, Looper.getMainLooper(), null)
        if (manager == null || channel == null) {
            onDone()
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var done = false

        fun finish() {
            if (done) return
            done = true
            handler.post(onDone)
        }

        manager.removeGroup(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = finish()
                override fun onFailure(reason: Int) = finish()
            },
        )

        // Se o framework travar, não deixa o teste parado para sempre.
        handler.postDelayed({ finish() }, TIMEOUT_MS)
    }
}
