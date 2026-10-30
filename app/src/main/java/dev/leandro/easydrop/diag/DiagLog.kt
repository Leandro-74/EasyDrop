package dev.leandro.easydrop.diag

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log único do app. Escreve no logcat (tag EasyDropDiag) e mantém a lista que a UI mostra,
 * para que o mesmo relatório possa ser lido via `adb logcat` sem depender da tela.
 */
object DiagLog {

    const val TAG = "EasyDropDiag"

    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(message: String) {
        Log.i(TAG, message)
        val line = "${time.format(Date())}  $message"
        _lines.update { it + line }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
