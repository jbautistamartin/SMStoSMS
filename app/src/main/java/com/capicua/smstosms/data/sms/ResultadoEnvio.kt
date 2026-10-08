// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.sms

/**
 * Resultado de intentar enviar un SMS.
 *
 * La distinción entre transitorio y permanente es la que gobierna la política de reintentos del
 * worker: un error transitorio vuelve a la cola, uno permanente cierra el reenvío.
 */
sealed interface ResultadoEnvio {

    /** Todas las partes confirmaron `RESULT_OK`. */
    data class Enviado(val partes: Int) : ResultadoEnvio

    /**
     * Fallo que puede desaparecer solo: sin cobertura, radio apagada, fallo genérico del
     * operador o confirmación que no llegó dentro del plazo. Se reintenta.
     */
    data class ErrorTransitorio(val motivo: String) : ResultadoEnvio

    /**
     * Fallo que no se va a arreglar reintentando: falta el permiso, el destino no es un número
     * válido o la PDU es nula. Se cierra el reenvío.
     */
    data class ErrorPermanente(val motivo: String) : ResultadoEnvio
}
