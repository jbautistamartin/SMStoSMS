// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper sobre DataStore<Preferences> para la configuración de la aplicación.
 *
 * - Las lecturas son reactivas (Flow).
 * - Las escrituras son atómicas (DataStore.edit usa transacción).
 * - Los valores por defecto están en AppConfig.
 */
@Singleton
class ConfigDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    /** Flujo reactivo con la configuración actual. Emite inmediatamente al suscribirse. */
    val config: Flow<AppConfig> = dataStore.data.map { prefs ->
        AppConfig(
            urlTemplate                = prefs[Keys.URL_TEMPLATE]           ?: "",
            timeoutSegundos            = prefs[Keys.TIMEOUT_SEGUNDOS]        ?: 30,
            maxReintentos              = prefs[Keys.MAX_REINTENTOS]          ?: 10,
            intervaloReintentoSegundos = prefs[Keys.INTERVALO_REINTENTO]     ?: 30,
            aceptarCertificadosInvalidos = prefs[Keys.ACEPTAR_CERTS_INVALIDOS] ?: false
        )
    }

    /** Guarda toda la configuración de forma atómica. */
    suspend fun guardar(config: AppConfig) {
        dataStore.edit { prefs ->
            prefs[Keys.URL_TEMPLATE]              = config.urlTemplate
            prefs[Keys.TIMEOUT_SEGUNDOS]          = config.timeoutSegundos
            prefs[Keys.MAX_REINTENTOS]            = config.maxReintentos
            prefs[Keys.INTERVALO_REINTENTO]       = config.intervaloReintentoSegundos
            prefs[Keys.ACEPTAR_CERTS_INVALIDOS]   = config.aceptarCertificadosInvalidos
        }
    }

    private object Keys {
        val URL_TEMPLATE              = stringPreferencesKey("url_template")
        val TIMEOUT_SEGUNDOS          = intPreferencesKey("timeout_segundos")
        val MAX_REINTENTOS            = intPreferencesKey("max_reintentos")
        val INTERVALO_REINTENTO       = intPreferencesKey("intervalo_reintento")
        val ACEPTAR_CERTS_INVALIDOS   = booleanPreferencesKey("aceptar_certs_invalidos")
    }
}