// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.rules

/**
 * Resultado de evaluar las reglas contra un SMS entrante.
 *
 * [coincidencias] está en el mismo orden en que se aplicaron las reglas, de modo que la
 * primera posición corresponde a la regla de mayor prioridad que casó.
 *
 * [reglasInvalidas] recoge las reglas que no se pudieron evaluar porque su expresión regular
 * no compila. Se separan del resultado en lugar de hacer fallar toda la evaluación: una regla
 * mal escrita no debe impedir que las demás reenvíen. Quien llama debe registrarlas en el log.
 */
data class ResultadoEvaluacion(
    val coincidencias: List<Coincidencia> = emptyList(),
    val reglasInvalidas: List<ReglaInvalida> = emptyList()
) {
    /** true si al menos una regla casó y, por tanto, hay algo que reenviar. */
    val hayCoincidencias: Boolean get() = coincidencias.isNotEmpty()
}

/** Una regla que casó, con el destino y el texto ya resueltos. */
data class Coincidencia(
    val reglaId: Long,
    val nombreRegla: String,

    /** Destino final: el número literal de la regla, o el remitente si la regla le responde. */
    val destino: String,

    val textoFinal: String,

    /**
     * true si la regla declaraba `{telefono}` como destino, es decir, si el reenvío vuelve al
     * remitente **a propósito**. La protección antibucle usa este campo para no bloquear una
     * circularidad que se ha pedido explícitamente.
     */
    val respondeAlRemitente: Boolean = false
)

/** Una regla descartada porque su expresión regular no compila. */
data class ReglaInvalida(
    val reglaId: Long,
    val nombreRegla: String,
    val campo: CampoRegla,
    val patron: String,
    val motivo: String
)

/** Campo de la regla sobre el que se aplica una expresión regular. */
enum class CampoRegla {
    TELEFONO,
    MENSAJE
}
