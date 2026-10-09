// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstosms.data.config.AppConfig
import com.capicua.smstosms.data.config.ConfigDataStore
import com.capicua.smstosms.data.sms.ProveedorDeSims
import com.capicua.smstosms.data.sms.SimDisponible
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configDataStore: ConfigDataStore,
    private val proveedorDeSims: ProveedorDeSims
) : ViewModel() {

    /**
     * SIM activas del dispositivo.
     *
     * Lista vacía o de un solo elemento significa que no hay nada que elegir, y la pantalla
     * oculta el selector: enviar con la SIM predeterminada del sistema es lo correcto en la
     * inmensa mayoría de los dispositivos.
     */
    val simsDisponibles: List<SimDisponible> get() = proveedorDeSims.simsDisponibles()

    /**
     * Estado actual de la configuración.
     *
     * Emite null mientras DataStore no ha cargado su primer valor, para que el fragment no
     * rellene los campos con los valores por defecto antes de que llegue lo realmente
     * persistido y el operador vea cambiar las cifras delante de sus ojos.
     */
    val config: StateFlow<AppConfig?> = configDataStore.config
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** Guarda la configuración editada. */
    fun guardar(config: AppConfig) {
        viewModelScope.launch {
            configDataStore.guardar(config)
        }
    }
}
