// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.logs

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstosms.data.repository.LogRepository
import com.capicua.smstosms.domain.model.LogEntry
import com.capicua.smstosms.domain.model.LogTipo
import com.capicua.smstosms.util.Constants
import com.capicua.smstosms.util.toDisplayString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    application: Application,
    private val logRepository: LogRepository
) : AndroidViewModel(application) {

    /** Filtro activo: null = todos los tipos */
    private val _filtroActivo = MutableStateFlow<LogTipo?>(null)
    val filtroActivo: StateFlow<LogTipo?> = _filtroActivo

    /** Lista de logs según el filtro activo */
    val logs: Flow<List<LogEntry>> = _filtroActivo.flatMapLatest { tipo ->
        if (tipo == null) {
            logRepository.observarRecientes(500)
        } else {
            logRepository.observarPorTipo(tipo, 500)
        }
    }

    val totalLogs: Flow<Int> = logRepository.observarTotal()

    fun aplicarFiltro(tipo: LogTipo?) {
        _filtroActivo.value = tipo
    }

    fun limpiarTodos() {
        viewModelScope.launch {
            logRepository.limpiarTodo()
        }
    }

    /**
     * Exporta todos los logs a un archivo de texto en la caché de la app.
     * Devuelve un [Uri] compatible con FileProvider para compartir.
     * Usa Application context para evitar referencias a Activities o Fragments destruidos.
     */
    fun exportarLogs(onUri: (Uri) -> Unit) {
        viewModelScope.launch {
            try {
                val logs = logRepository.obtenerTodosParaExportar()
                val uri = generarArchivo(logs)
                onUri(uri)
            } catch (e: Exception) {
                Timber.e(e, "Error exportando logs")
            }
        }
    }

    private fun generarArchivo(logs: List<LogEntry>): Uri {
        val context = getApplication<Application>()
        val formatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .withZone(ZoneId.systemDefault())

        val contenido = buildString {
            appendLine("=== SMStoSMS — Registro exportado ===")
            appendLine("Fecha exportación: ${formatter.format(Instant.now())}")
            appendLine("Total entradas: ${logs.size}")
            appendLine("=".repeat(50))
            appendLine()
            logs.forEach { log ->
                append("[${formatter.format(log.timestamp)}]")
                append(" [${log.tipo.name.padEnd(14)}]")
                log.smsId?.let { append(" [SMS: ${it.take(8)}…]") }
                log.destino?.let { append(" [→ $it]") }
                append(" ${log.detalle}")
                appendLine()
            }
        }

        val nombreArchivo = "smstosms_logs_${System.currentTimeMillis()}.txt"
        val archivo = File(context.cacheDir, nombreArchivo)
        archivo.writeText(contenido, Charsets.UTF_8)

        return FileProvider.getUriForFile(
            context,
            Constants.FILE_PROVIDER_AUTHORITY,
            archivo
        )
    }
}