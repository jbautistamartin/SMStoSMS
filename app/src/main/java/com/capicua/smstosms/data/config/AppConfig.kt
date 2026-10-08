// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.config

import java.net.URLEncoder

/**
 * Configuración de la aplicación editada por el operador en la pantalla de Configuración.
 * Se persiste en DataStore<Preferences>.
 *
 * La URL se define como plantilla con marcadores que se sustituyen por los valores del SMS:
 *
 *   {mensaje}   → texto del SMS                  **OBLIGATORIO**
 *   {telefono}  → número de teléfono del remitente  (opcional)
 *   {fecha}     → timestamp ISO-8601 UTC             (opcional)
 *
 * Los marcadores presentes en la plantilla se sustituyen y URL-encoden automáticamente.
 * Los marcadores ausentes se ignoran; no es necesario incluirlos todos.
 *
 * Ejemplos:
 *   Mínimo:   https://api.empresa.com/sms?msg={mensaje}
 *   Completo: https://api.empresa.com/notify?phone={telefono}&msg={mensaje}&ts={fecha}
 */
data class AppConfig(
    /** Plantilla de URL completa incluyendo query params con marcadores */
    val urlTemplate: String = "",

    /** Timeout de red en segundos para cada intento HTTP */
    val timeoutSegundos: Int = 30,

    /** Número máximo de intentos antes de marcar el SMS como fallido permanentemente */
    val maxReintentos: Int = 10,

    /** Backoff inicial en segundos entre reintentos (WorkManager aplica backoff exponencial) */
    val intervaloReintentoSegundos: Int = 30,

    /** Si true, acepta certificados SSL autofirmados o caducados (usar solo en redes privadas) */
    val aceptarCertificadosInvalidos: Boolean = false
) {
    /**
     * Construye la URL final sustituyendo los marcadores presentes en la plantilla.
     * Solo se sustituyen los marcadores que aparecen en [urlTemplate]; los ausentes se ignoran.
     * Cada valor se URL-encodea para uso seguro en query strings.
     */
    fun construirUrl(telefono: String, mensaje: String, fecha: String): String =
        urlTemplate
            .replace("{telefono}", URLEncoder.encode(telefono, "UTF-8"))
            .replace("{mensaje}",  URLEncoder.encode(mensaje,  "UTF-8"))
            .replace("{fecha}",    URLEncoder.encode(fecha,    "UTF-8"))

    /**
     * true si la configuración mínima está completa:
     * - La plantilla no está vacía
     * - La plantilla contiene {mensaje}, el único marcador obligatorio
     */
    fun esValida(): Boolean = urlTemplate.isNotBlank() && urlTemplate.contains("{mensaje}")
}