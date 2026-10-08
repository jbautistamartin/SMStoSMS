// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.settings

/** Estado del envío de prueba a la API. */
sealed interface TestConexionEstado {
    /** Estado inicial: el usuario aún no ha pulsado "Probar". */
    data object Inactivo : TestConexionEstado

    /** Petición en curso. */
    data object Cargando : TestConexionEstado

    /** La API respondió con un código HTTP (puede ser éxito o error HTTP). */
    data class Respuesta(val codigoHttp: Int) : TestConexionEstado

    /** No se pudo establecer conexión (timeout, red, etc.). */
    data class ErrorRed(val detalle: String) : TestConexionEstado
}
