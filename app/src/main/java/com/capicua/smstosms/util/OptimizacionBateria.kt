// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.util

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService
import timber.log.Timber

/**
 * Exención de la optimización de batería (modo Doze).
 *
 * Es el ajuste del sistema que más afecta a esta aplicación: con el Doze activo, Android puede
 * posponer `SmsDispatchWorker` durante horas cuando la pantalla está apagada, así que un SMS
 * entra, se guarda y se queda sin reenviar hasta que alguien enciende el teléfono. Sin exención
 * la aplicación no está rota, pero deja de ser puntual, que es justamente su único trabajo.
 *
 * Por eso el estado se consulta y se ofrece desde la propia pantalla de ajustes en lugar de
 * dejarlo solo documentado: quien instala la app en el teléfono dedicado no suele tener ADB
 * delante.
 */
object OptimizacionBateria {

    /** true si el sistema ya exime a esta aplicación de la optimización de batería. */
    fun estaExenta(context: Context): Boolean {
        val power = context.getSystemService<PowerManager>() ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Abre la pantalla del sistema que concede la exención.
     *
     * Se intentan tres destinos, de más directo a más genérico, porque ninguno está garantizado:
     *
     * 1. El diálogo de exención, que la concede con un toque. No todos los fabricantes lo
     *    declaran.
     * 2. La lista de optimización de batería de todas las aplicaciones, donde hay que buscar
     *    SMStoSMS. Existe en prácticamente cualquier ROM.
     * 3. La ficha de la aplicación en Ajustes, desde la que siempre se llega a «Batería». Este
     *    la resuelve cualquier Android.
     *
     * Se prueba lanzando y capturando [ActivityNotFoundException] en lugar de consultar el
     * `PackageManager`: desde Android 11 la visibilidad de paquetes puede hacer que una consulta
     * diga que no hay nada cuando sí lo hay, mientras que lanzar nunca se equivoca.
     *
     * @return true si se abrió alguna de las tres pantallas.
     */
    @SuppressLint("BatteryLife")
    fun abrirAjuste(context: Context): Boolean {
        val candidatos = listOf(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )

        candidatos.forEach { intent ->
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Timber.w("Este dispositivo no resuelve ${intent.action}, pruebo el siguiente")
            } catch (e: SecurityException) {
                // Algunas ROM declaran la actividad pero no permiten lanzarla desde una app.
                Timber.w(e, "El sistema rechazó ${intent.action}")
            }
        }

        Timber.e("Ningún intent de optimización de batería se pudo abrir")
        return false
    }
}
