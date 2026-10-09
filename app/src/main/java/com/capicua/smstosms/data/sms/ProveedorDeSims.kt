// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.capicua.smstosms.data.config.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Una SIM activa del dispositivo, lista para mostrar en un selector. */
data class SimDisponible(
    val subscriptionId: Int,
    val etiqueta: String
)

/**
 * Enumera las SIM activas para poder elegir con cuál enviar.
 *
 * Requiere `READ_PHONE_STATE`. Si el permiso no está concedido —o el dispositivo no expone
 * suscripciones— devuelve una lista vacía, y la pantalla de configuración simplemente no ofrece
 * el selector: enviar con la SIM predeterminada del sistema es un comportamiento válido y es el
 * que usa la inmensa mayoría de los dispositivos.
 */
@Singleton
class ProveedorDeSims @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * SIM activas, con una etiqueta legible.
     *
     * La etiqueta combina el nombre que el usuario dio a la SIM en los ajustes del sistema y el
     * operador, porque «SIM 1» a secas no distingue nada en un dispositivo con dos líneas del
     * mismo operador.
     */
    fun simsDisponibles(): List<SimDisponible> {
        if (!tienePermiso()) {
            Timber.d("ProveedorDeSims: sin READ_PHONE_STATE, no se enumeran SIM")
            return emptyList()
        }

        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java)
                ?: return emptyList()

            manager.activeSubscriptionInfoList.orEmpty().map { info ->
                val nombre = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                val operador = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                val etiqueta = listOfNotNull(nombre, operador)
                    .distinct()
                    .joinToString(" · ")
                    .ifBlank { "SIM ${info.simSlotIndex + 1}" }

                SimDisponible(
                    subscriptionId = info.subscriptionId,
                    etiqueta = etiqueta
                )
            }
        } catch (e: SecurityException) {
            Timber.w(e, "ProveedorDeSims: permiso denegado al enumerar SIM")
            emptyList()
        } catch (e: Exception) {
            Timber.e(e, "ProveedorDeSims: fallo al enumerar SIM")
            emptyList()
        }
    }

    /** true si hay más de una SIM y por tanto tiene sentido ofrecer un selector. */
    fun hayVariasSims(): Boolean = simsDisponibles().size > 1

    /** Etiqueta de la SIM elegida, o null si se usa la predeterminada del sistema. */
    fun etiquetaDe(subscriptionId: Int): String? =
        if (subscriptionId == AppConfig.SIM_POR_DEFECTO) {
            null
        } else {
            simsDisponibles().firstOrNull { it.subscriptionId == subscriptionId }?.etiqueta
        }

    private fun tienePermiso(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
}
