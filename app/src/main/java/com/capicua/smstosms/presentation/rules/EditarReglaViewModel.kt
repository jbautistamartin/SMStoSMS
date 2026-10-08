// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstosms.data.repository.ReglaRepository
import com.capicua.smstosms.data.sms.SmsSender
import com.capicua.smstosms.domain.model.Regla
import com.capicua.smstosms.domain.rules.EvaluadorDeReglas
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Errores de validación de una regla, uno por campo. Null significa que el campo está bien. */
data class ErroresRegla(
    val nombre: String? = null,
    val regexTelefono: String? = null,
    val regexMensaje: String? = null,
    val destino: String? = null,
    val plantilla: String? = null
) {
    val hayErrores: Boolean
        get() = listOf(nombre, regexTelefono, regexMensaje, destino, plantilla).any { it != null }
}

@HiltViewModel
class EditarReglaViewModel @Inject constructor(
    private val reglaRepository: ReglaRepository,
    private val evaluador: EvaluadorDeReglas,
    private val smsSender: SmsSender
) : ViewModel() {

    private val _regla = MutableStateFlow<Regla?>(null)
    /** Regla en edición. Null mientras se carga, o para una regla nueva sin valores. */
    val regla: StateFlow<Regla?> = _regla.asStateFlow()

    private val _errores = MutableStateFlow(ErroresRegla())
    val errores: StateFlow<ErroresRegla> = _errores.asStateFlow()

    private val _guardada = MutableStateFlow(false)
    /** Pasa a true cuando la regla se ha persistido, para que la pantalla pueda cerrarse. */
    val guardada: StateFlow<Boolean> = _guardada.asStateFlow()

    /** Carga la regla a editar, o deja el formulario en blanco si [reglaId] es [NUEVA]. */
    fun cargar(reglaId: Long) {
        if (reglaId == NUEVA) return
        viewModelScope.launch {
            _regla.value = reglaRepository.obtenerPorId(reglaId)
        }
    }

    /**
     * Valida un patrón mientras se teclea, para avisar en el momento en lugar de dejar que la
     * regla «no funcione» sin explicación.
     *
     * @return el motivo del error, o null si el patrón compila o está vacío.
     */
    fun validarPatron(patron: String?): String? = evaluador.validarPatron(patron)

    /** Previsualiza el texto que saldría con esta plantilla, para el campo de ayuda. */
    fun previsualizar(plantilla: String, telefono: String, mensaje: String, fecha: String): String =
        evaluador.resolverPlantilla(plantilla, telefono, mensaje, fecha)

    /** Partes en que se dividiría un texto, para avisar del coste de la plantilla. */
    fun contarPartes(texto: String): Int = smsSender.contarPartes(texto)

    /**
     * Valida y guarda. Si hay errores, los publica en [errores] y no escribe nada.
     *
     * @param reglaId [NUEVA] para crear, o el id existente para actualizar.
     */
    fun guardar(reglaId: Long, borrador: Regla) {
        val errores = validar(borrador)
        _errores.value = errores
        if (errores.hayErrores) return

        viewModelScope.launch {
            if (reglaId == NUEVA) {
                reglaRepository.crear(borrador)
            } else {
                // Se conserva el orden que ya tenía: editar una regla no debe cambiar su
                // prioridad en la lista.
                val orden = reglaRepository.obtenerPorId(reglaId)?.orden ?: borrador.orden
                reglaRepository.actualizar(borrador.copy(id = reglaId, orden = orden))
            }
            _guardada.value = true
        }
    }

    private fun validar(regla: Regla): ErroresRegla = ErroresRegla(
        nombre = if (regla.nombre.isBlank()) "Pon un nombre para reconocer la regla" else null,
        regexTelefono = evaluador.validarPatron(regla.regexTelefono),
        regexMensaje = evaluador.validarPatron(regla.regexMensaje),
        destino = when {
            regla.destino.isBlank() -> "El número destino es obligatorio"
            regla.destino.count { it.isDigit() } < MINIMO_DIGITOS_DESTINO ->
                "No parece un número de teléfono"
            else -> null
        },
        plantilla = if (regla.plantilla.isBlank()) "La plantilla no puede estar vacía" else null
    )

    companion object {
        /** Id que indica que se está creando una regla, no editando una existente. */
        const val NUEVA = 0L

        /**
         * Dígitos mínimos para aceptar un destino. Un número corto de servicio tiene 4-5, así
         * que se exige poco a propósito: validar de más impediría usar números especiales.
         */
        private const val MINIMO_DIGITOS_DESTINO = 4
    }
}
