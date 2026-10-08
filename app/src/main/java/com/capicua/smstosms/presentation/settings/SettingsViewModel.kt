// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstosms.data.config.AppConfig
import com.capicua.smstosms.data.config.ConfigDataStore
import com.capicua.smstosms.util.HttpClientFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configDataStore: ConfigDataStore,
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    /**
     * Estado actual de la configuración.
     * Emite null mientras DataStore no ha cargado aún su primer valor,
     * para que el fragment no rellene los campos con valores vacíos por defecto
     * antes de que llegue el valor real persistido.
     */
    val config: StateFlow<AppConfig?> = configDataStore.config
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private val _testEstado = MutableStateFlow<TestConexionEstado>(TestConexionEstado.Inactivo)
    /** Estado del envío de prueba a la API. */
    val testEstado: StateFlow<TestConexionEstado> = _testEstado.asStateFlow()

    /** Guarda la configuración editada. */
    fun guardar(config: AppConfig) {
        viewModelScope.launch {
            configDataStore.guardar(config)
        }
    }

    /**
     * Realiza un GET de prueba a la URL configurada usando datos ficticios,
     * y actualiza [testEstado] con el resultado.
     *
     * Se usa para validar la conectividad antes de recibir SMS reales.
     */
    fun probarConexion(config: AppConfig) {
        if (_testEstado.value == TestConexionEstado.Cargando) return

        viewModelScope.launch {
            _testEstado.value = TestConexionEstado.Cargando

            val url = config.construirUrl(
                telefono = "+34000000000",
                mensaje  = "SMS de prueba desde SMStoSMS",
                fecha    = Instant.now().toString()
            )

            Timber.d("Test de conexión → $url")

            val client = HttpClientFactory.crear(okHttpClient, config)

            try {
                val codigo = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(url).get().build())
                        .execute().use { it.code }
                }
                Timber.i("Test de conexión → HTTP $codigo")
                _testEstado.value = TestConexionEstado.Respuesta(codigo)

            } catch (e: IOException) {
                val detalle = e.message ?: "Error de red"
                Timber.w("Test de conexión → error: $detalle")
                _testEstado.value = TestConexionEstado.ErrorRed(detalle)
            }
        }
    }

    /** Reinicia el estado del test (útil al cambiar la URL). */
    fun resetearTest() {
        _testEstado.value = TestConexionEstado.Inactivo
    }
}
