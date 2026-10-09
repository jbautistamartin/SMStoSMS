// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.model

import java.time.Instant

/**
 * Un envío concreto a un número destino, derivado de un [SmsMessage] y de la [Regla] que casó.
 *
 * Es la unidad de despacho del sistema: un SMS entrante puede generar varios reenvíos si
 * varias reglas casan con él (ver [Regla.continuar]), y cada uno lleva su propio estado,
 * su contador de intentos y su último error.
 *
 * El texto a enviar se congela en [textoFinal] en el momento de la evaluación. Así, editar
 * o borrar la regla más tarde no altera lo que ya estaba encolado.
 */
data class Reenvio(
    /** UUID generado al crear el reenvío. Clave primaria y de idempotencia. */
    val id: String,

    /** Id del SMS entrante que lo originó. */
    val smsId: String,

    /** Id de la regla que casó. Null si la regla se borró después. */
    val reglaId: Long? = null,

    /** Nombre de la regla, copiado para que el historial sobreviva al borrado de la regla. */
    val nombreRegla: String,

    /** Número de teléfono al que se envía. */
    val destino: String,

    /** Texto ya resuelto a partir de la plantilla de la regla. */
    val textoFinal: String,

    /** Estado del envío. */
    val estado: EstadoReenvio = EstadoReenvio.PENDIENTE,

    /** Número acumulado de intentos de envío. */
    val intentos: Int = 0,

    /** Descripción del último error. Null si nunca falló. */
    val ultimoError: String? = null,

    /** Instante en que se creó el reenvío (tras evaluar las reglas). */
    val fechaCreacion: Instant,

    /** Instante en que se confirmó el envío. Null mientras no esté [EstadoReenvio.ENVIADO]. */
    val fechaEnvio: Instant? = null,

    /**
     * Número de partes en que se dividirá el SMS. Un mensaje de más de 160 caracteres GSM-7
     * se fragmenta, y cada parte se tarifica por separado.
     */
    val partes: Int = 1
)
