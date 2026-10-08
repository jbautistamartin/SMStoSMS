// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstosms.data.repository.ReenvioRepository
import com.capicua.smstosms.data.repository.ReglaRepository
import com.capicua.smstosms.data.repository.SmsRepository
import com.capicua.smstosms.domain.model.SmsConReenvios
import com.capicua.smstosms.domain.usecase.ObtenerListaSmsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Cifras de cabecera de la pantalla de inicio. */
data class ResumenInicio(
    val totalSms: Int = 0,
    val reenviosPendientes: Int = 0,
    val reglasActivas: Int = 0
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val obtenerListaSms: ObtenerListaSmsUseCase,
    private val smsRepository: SmsRepository,
    reenvioRepository: ReenvioRepository,
    reglaRepository: ReglaRepository
) : ViewModel() {

    /** Lista reactiva de SMS con sus reenvíos, más recientes primero. */
    val smsList: Flow<List<SmsConReenvios>> = obtenerListaSms()

    /**
     * Cifras de cabecera.
     *
     * Incluye las reglas activas a propósito: con cero reglas la aplicación recibe SMS y no
     * reenvía nada, y eso tiene que ser visible en la primera pantalla en lugar de parecer un
     * fallo de envío.
     */
    val resumen: Flow<ResumenInicio> = combine(
        smsRepository.observarContadorTotal(),
        reenvioRepository.observarContadorPendientes(),
        reglaRepository.observarContadorActivas()
    ) { total, pendientes, reglas ->
        ResumenInicio(total, pendientes, reglas)
    }

    fun limpiarTodos() {
        viewModelScope.launch {
            smsRepository.limpiarTodos()
        }
    }
}
