package dev.leandro.easydrop.lab

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var controller: LabController
    private var pendingAction: Intent? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        controller = LabController(this)

        val launcher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            result.forEach { (permission, granted) ->
                LabLog.add("permissão ${permission.substringAfterLast('.')} = $granted")
            }
        }

        setContent { LabScreen(controller) }

        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())

        LabLog.add("EasyDrop Lab · ${Build.MANUFACTURER} ${Build.MODEL}")
        pendingAction = intent
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        pendingAction?.let {
            pendingAction = null
            handleIntent(it)
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    /** Sem isso, sair pelo "voltar" deixa sockets, hotspot e grupo P2P vivos. */
    override fun onDestroy() {
        controller.stop(quiet = true)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (resumed) handleIntent(intent) else pendingAction = intent
    }

    /**
     * Permite dirigir o laboratório por adb, sem tocar na tela:
     *   adb shell am start -n dev.leandro.easydrop.lab/.MainActivity \
     *       --es action start --es mode LAN --es role RECEIVER
     */
    private fun handleIntent(intent: Intent?) {
        val action = intent?.getStringExtra("action") ?: return
        when (action) {
            "start" -> controller.start(
                mode = intent.stringExtra("mode", Mode.entries),
                role = intent.stringExtra("role", Role.entries),
            )
            "stop" -> controller.stop()
            else -> LabLog.add("ação desconhecida: $action")
        }
    }

    private inline fun <reified T : Enum<T>> Intent.stringExtra(key: String, values: List<T>): T =
        getStringExtra(key)
            ?.let { raw -> values.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
            ?: values.first()

    private fun requiredPermissions(): List<String> = requiredPermissions(Build.VERSION.SDK_INT)
}

private fun colorFor(phase: Phase): Color = when (phase) {
    Phase.IDLE -> Color(0xFF37474F)
    Phase.SEARCHING -> Color(0xFF1565C0)
    Phase.FOUND -> Color(0xFFF9A825)
    Phase.CONNECTING -> Color(0xFFEF6C00)
    Phase.CONNECTED -> Color(0xFF2E7D32)
    Phase.TRANSFERRING -> Color(0xFF1B5E20)
    Phase.ERROR -> Color(0xFFC62828)
}

private fun labelFor(phase: Phase): String = when (phase) {
    Phase.IDLE -> "parado"
    Phase.SEARCHING -> "procurando"
    Phase.FOUND -> "peer encontrado"
    Phase.CONNECTING -> "conectando"
    Phase.CONNECTED -> "CONECTADO"
    Phase.TRANSFERRING -> "TRANSFERINDO"
    Phase.ERROR -> "ERRO"
}

@Composable
private fun LabScreen(controller: LabController) {
    val status by controller.status.collectAsState()
    val lines by LabLog.lines.collectAsState()
    val listState = rememberLazyListState()
    val running = status.phase != Phase.IDLE && status.phase != Phase.ERROR

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorFor(status.phase)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            Text(
                text = labelFor(status.phase),
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(status.detail, color = Color.White, fontSize = 15.sp)

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                status.peer?.let { Text("peer $it", color = Color.White, fontSize = 13.sp) }
                status.setupMs?.let { Text("${it}ms", color = Color.White, fontSize = 13.sp) }
                status.mbps?.let {
                    Text("%.1f MB/s".format(it), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))

            Mode.entries.chunked(2).forEach { pair ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    pair.forEach { mode ->
                        Chip(
                            text = mode.label,
                            selected = status.mode == mode,
                            enabled = !running,
                            onClick = { controller.selectMode(mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Role.entries.forEach { role ->
                    Chip(
                        text = role.label,
                        selected = status.role == role,
                        enabled = !running,
                        onClick = { controller.selectRole(role) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { controller.start(status.mode, status.role) },
                    enabled = !running,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF1B5E20),
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Iniciar")
                }
                Button(
                    onClick = { controller.stop() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black.copy(alpha = 0.25f),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Parar")
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black.copy(alpha = 0.22f))
                    .padding(8.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color.White else Color.Black.copy(alpha = 0.25f),
            contentColor = if (selected) Color(0xFF1B5E20) else Color.White,
            disabledContainerColor = if (selected) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.15f),
            disabledContentColor = if (selected) Color(0xFF1B5E20) else Color.White.copy(alpha = 0.7f),
        ),
        modifier = modifier,
    ) {
        Text(text, fontSize = 12.sp)
    }
}
