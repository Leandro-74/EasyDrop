package dev.leandro.easydrop.diag

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var aware: AwareController
    private lateinit var radio: RadioTests

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        aware = AwareController(this) { DiagLog.add(it) }
        radio = RadioTests(this) { DiagLog.add(it) }

        val launcher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            result.forEach { (permission, granted) ->
                DiagLog.add("permissão ${permission.substringAfterLast('.')} = $granted")
            }
        }

        setContent { DiagScreen(aware) }

        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        }

        DiagLog.add("=== EasyDrop Fase 0 ===")
        Capabilities.collect(this).forEach { cap ->
            DiagLog.add("${if (cap.ok) "[OK]" else "[--]"} ${cap.label}: ${cap.detail}")
        }

        handleIntentAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentAction(intent)
    }

    /**
     * Permite dirigir o diagnóstico por adb, sem tocar na tela:
     *   adb shell am start -n dev.leandro.easydrop.diag/.MainActivity --es action autorun
     */
    private fun handleIntentAction(intent: Intent?) {
        val action = intent?.getStringExtra("action") ?: return
        DiagLog.add("ação recebida: $action")

        lifecycleScope.launch {
            when (action) {
                "attach" -> aware.attach()
                "publish" -> {
                    aware.attach()
                    delay(1_500)
                    aware.publish()
                }
                "subscribe" -> {
                    aware.attach()
                    delay(1_500)
                    aware.subscribe()
                }
                "connect" -> aware.connect()
                "stop" -> aware.stop()
                "hotspot" -> radio.startHotspot()
                "hotspot-stop" -> radio.stopHotspot()
                "bleadv" -> radio.startBleAdvertise()
                "bleadv-stop" -> radio.stopBleAdvertise()
                "blescan" -> radio.startBleScan()
                "autorun" -> {
                    aware.attach()
                    delay(2_000)
                    aware.publish()
                    delay(2_000)
                    aware.subscribe()
                    delay(6_000)
                    DiagLog.add("autorun: fim")
                }
            }
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
}

@Composable
private fun DiagScreen(aware: AwareController) {
    val context = LocalContext.current
    val lines by DiagLog.lines.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text("EasyDrop · Fase 0", style = MaterialTheme.typography.titleLarge)
            Text(
                "Diagnóstico de transporte por proximidade",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { aware.attach() }, modifier = Modifier.weight(1f)) {
                    Text("Attach")
                }
                Button(onClick = { aware.publish() }, modifier = Modifier.weight(1f)) {
                    Text("Publish")
                }
                Button(onClick = { aware.subscribe() }, modifier = Modifier.weight(1f)) {
                    Text("Subscribe")
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { aware.connect() }, modifier = Modifier.weight(1f)) {
                    Text("Conectar")
                }
                OutlinedButton(onClick = { aware.stop() }, modifier = Modifier.weight(1f)) {
                    Text("Parar")
                }
                OutlinedButton(onClick = { DiagLog.clear() }, modifier = Modifier.weight(1f)) {
                    Text("Limpar")
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val path = saveReport(context)
                    DiagLog.add("relatório salvo em $path")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Salvar relatório")
            }

            Spacer(Modifier.height(12.dp))

            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun saveReport(context: Context): String {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val file = File(dir, "easydrop-diag.txt")
    file.writeText(DiagLog.lines.value.joinToString("\n"))
    return file.absolutePath
}
