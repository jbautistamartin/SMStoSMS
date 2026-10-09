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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper sobre DataStore<Preferences> para los ajustes generales.
 *
 * - Las lecturas son reactivas (Flow).
 * - Las escrituras son atómicas (DataStore.edit usa transacción).
 * - Los valores por defecto están declarados una sola vez, en [AppConfig].
 */
@Singleton
class ConfigDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    /** Flujo reactivo con la configuración actual. Emite inmediatamente al suscribirse. */
    val config: Flow<AppConfig> = dataStore.data.map { prefs ->
        val porDefecto = AppConfig()
        AppConfig(
            maxReintentos              = prefs[Keys.MAX_REINTENTOS]        ?: porDefecto.maxReintentos,
            intervaloReintentoSegundos = prefs[Keys.INTERVALO_REINTENTO]   ?: porDefecto.intervaloReintentoSegundos,
            timeoutEnvioSegundos       = prefs[Keys.TIMEOUT_ENVIO]         ?: porDefecto.timeoutEnvioSegundos,
            maxReenviosPorMinuto       = prefs[Keys.MAX_REENVIOS_MINUTO]   ?: porDefecto.maxReenviosPorMinuto,
            protegerBucles             = prefs[Keys.PROTEGER_BUCLES]       ?: porDefecto.protegerBucles,
            subscriptionId             = prefs[Keys.SUBSCRIPTION_ID]       ?: porDefecto.subscriptionId
        )
    }

    /** Guarda toda la configuración de forma atómica. */
    suspend fun guardar(config: AppConfig) {
        dataStore.edit { prefs ->
            prefs[Keys.MAX_REINTENTOS]      = config.maxReintentos
            prefs[Keys.INTERVALO_REINTENTO] = config.intervaloReintentoSegundos
            prefs[Keys.TIMEOUT_ENVIO]       = config.timeoutEnvioSegundos
            prefs[Keys.MAX_REENVIOS_MINUTO] = config.maxReenviosPorMinuto
            prefs[Keys.PROTEGER_BUCLES]     = config.protegerBucles
            prefs[Keys.SUBSCRIPTION_ID]     = config.subscriptionId
        }
    }

    private object Keys {
        val MAX_REINTENTOS      = intPreferencesKey("max_reintentos")
        val INTERVALO_REINTENTO = intPreferencesKey("intervalo_reintento")
        val TIMEOUT_ENVIO       = intPreferencesKey("timeout_envio_segundos")
        val MAX_REENVIOS_MINUTO = intPreferencesKey("max_reenvios_por_minuto")
        val PROTEGER_BUCLES     = booleanPreferencesKey("proteger_bucles")
        val SUBSCRIPTION_ID     = intPreferencesKey("subscription_id")
    }
}
