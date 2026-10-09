// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.rules

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstosms.data.repository.ReglaRepository
import com.capicua.smstosms.data.rules.ReglaJson
import com.capicua.smstosms.data.rules.ReglasExportadas
import com.capicua.smstosms.data.rules.aDominio
import com.capicua.smstosms.data.rules.aJson
import com.capicua.smstosms.domain.model.Regla
import com.capicua.smstosms.domain.rules.EvaluadorDeReglas
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

/** Mensajes puntuales para la pantalla: resultado de una acción que hay que comunicar una vez. */
sealed interface AvisoReglas {
    data class Exportadas(val cuantas: Int) : AvisoReglas
    data class Importadas(val aceptadas: Int, val rechazadas: Int) : AvisoReglas
    data class Fallo(val motivo: String) : AvisoReglas
    data class Eliminada(val nombre: String) : AvisoReglas
}

@HiltViewModel
class ReglasViewModel @Inject constructor(
    application: Application,
    private val reglaRepository: ReglaRepository,
    private val evaluador: EvaluadorDeReglas
) : AndroidViewModel(application) {

    /** Lista reactiva de reglas, ya ordenada por prioridad. */
    val reglas: Flow<List<Regla>> = reglaRepository.observarTodas()

    private val _avisos = MutableSharedFlow<AvisoReglas>(extraBufferCapacity = 4)
    val avisos: SharedFlow<AvisoReglas> = _avisos.asSharedFlow()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        // Sin esto, kotlinx.serialization omite los campos cuyo valor coincide con el
        // predeterminado — incluida la versión del formato, que es justo lo que el fichero
        // tiene que declarar para que una importación futura sepa qué está leyendo.
        encodeDefaults = true
    }

    // ── Edición de la lista ───────────────────────────────────────────────────

    fun cambiarActiva(regla: Regla, activa: Boolean) {
        viewModelScope.launch { reglaRepository.cambiarActiva(regla.id, activa) }
    }

    fun eliminar(regla: Regla) {
        viewModelScope.launch {
            reglaRepository.eliminar(regla.id)
            _avisos.emit(AvisoReglas.Eliminada(regla.nombre))
        }
    }

    /** Duplica una regla, dejándola desactivada para que no empiece a reenviar sin revisarla. */
    fun duplicar(regla: Regla) {
        viewModelScope.launch {
            reglaRepository.crear(
                regla.copy(
                    id = 0,
                    nombre = "${regla.nombre} (copia)",
                    activa = false
                )
            )
        }
    }

    /**
     * Sube o baja una regla una posición.
     *
     * Se recalcula el orden de toda la lista en una transacción, en lugar de intercambiar dos
     * valores: así nunca quedan dos reglas con el mismo `orden`, lo que haría la evaluación no
     * determinista.
     */
    fun mover(desde: Int, hacia: Int, listaActual: List<Regla>) {
        if (desde == hacia || desde !in listaActual.indices || hacia !in listaActual.indices) return

        val reordenada = listaActual.toMutableList()
        val movida = reordenada.removeAt(desde)
        reordenada.add(hacia, movida)

        viewModelScope.launch {
            reglaRepository.reordenar(reordenada.map { it.id })
        }
    }

    // ── Exportar e importar ───────────────────────────────────────────────────

    /** Escribe el juego de reglas completo en el fichero elegido por el usuario. */
    fun exportar(destino: Uri) {
        viewModelScope.launch {
            try {
                val reglas = reglaRepository.obtenerTodas()
                val contenido = json.encodeToString(
                    ReglasExportadas.serializer(),
                    ReglasExportadas(
                        exportado = Instant.now().toString(),
                        reglas = reglas.map { it.aJson() }
                    )
                )

                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openOutputStream(destino, "wt")
                        ?.use { it.write(contenido.toByteArray(Charsets.UTF_8)) }
                        ?: error("no se pudo abrir el fichero para escribir")
                }

                _avisos.emit(AvisoReglas.Exportadas(reglas.size))
            } catch (e: Exception) {
                Timber.e(e, "Error exportando reglas")
                _avisos.emit(AvisoReglas.Fallo(e.message ?: "error al exportar"))
            }
        }
    }

    /**
     * Lee un fichero de reglas y añade las válidas al final de la lista actual.
     *
     * No borra nada: importar es añadir. Cada regla se valida antes de escribir —expresiones
     * que compilen y destino no vacío— y las que no pasan se cuentan aparte, porque una regla
     * con una regex rota que entrase silenciosamente no reenviaría nunca y sería muy difícil
     * de diagnosticar.
     */
    fun importar(origen: Uri) {
        viewModelScope.launch {
            try {
                val contenido = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openInputStream(origen)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("no se pudo abrir el fichero para leer")
                }

                val fichero = json.decodeFromString(ReglasExportadas.serializer(), contenido)

                if (fichero.version > ReglasExportadas.VERSION_ACTUAL) {
                    _avisos.emit(
                        AvisoReglas.Fallo(
                            "el fichero usa el formato ${fichero.version} y esta versión " +
                                "entiende hasta el ${ReglasExportadas.VERSION_ACTUAL}"
                        )
                    )
                    return@launch
                }

                val (validas, invalidas) = fichero.reglas.partition { esValida(it) }

                validas.forEachIndexed { indice, regla ->
                    // crear() reasigna el orden al final de la lista; el índice solo mantiene
                    // el orden relativo del fichero.
                    reglaRepository.crear(regla.aDominio(orden = indice))
                }

                _avisos.emit(AvisoReglas.Importadas(validas.size, invalidas.size))
            } catch (e: Exception) {
                Timber.e(e, "Error importando reglas")
                _avisos.emit(AvisoReglas.Fallo(e.message ?: "el fichero no tiene el formato esperado"))
            }
        }
    }

    private fun esValida(regla: ReglaJson): Boolean =
        regla.destino.isNotBlank() &&
            regla.nombre.isNotBlank() &&
            evaluador.validarPatron(regla.regexTelefono) == null &&
            evaluador.validarPatron(regla.regexMensaje) == null

    /** Nombre sugerido para el fichero de exportación. */
    fun nombreFicheroExportacion(): String =
        "reglas-smstosms-${Instant.now().epochSecond}.json"
}
