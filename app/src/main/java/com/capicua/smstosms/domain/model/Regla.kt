// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.model

/**
 * Regla de reenvío configurada por el operador.
 *
 * Una regla decide **a qué número** se reenvía un SMS entrante y **con qué texto**.
 * Las reglas se evalúan en orden ascendente de [orden]: gana la primera que case, salvo
 * que lleve [continuar] activado, en cuyo caso la evaluación sigue con las siguientes.
 *
 * ## Criterios de coincidencia
 * - [regexTelefono] se aplica sobre el número del remitente **tal como llega en la PDU**,
 *   sin normalizar. Puede ser alfanumérico (p. ej. `BANCO`, `AMAZON`).
 * - [regexMensaje] se aplica sobre el cuerpo completo del SMS.
 * - Ambos son opcionales: null o vacío significa «no filtres por este campo». Una regla con
 *   los dos vacíos casa con **todos** los SMS, útil como última regla de la lista.
 * - La coincidencia es **parcial**: el patrón `codigo` casa con «Tu codigo es 4821».
 *   Para exigir el texto completo hay que anclarlo con `^…$`.
 * - Las expresiones distinguen mayúsculas. Para ignorarlas se usa el modificador `(?i)`.
 *
 * ## Plantilla
 * [plantilla] define el texto que se envía al destino, con tres marcadores opcionales:
 * `{mensaje}`, `{telefono}` y `{fecha}`. El valor por defecto es [PLANTILLA_POR_DEFECTO],
 * que reenvía el cuerpo original sin añadir nada.
 */
data class Regla(
    /** 0 mientras la regla no se ha persistido; Room asigna el valor real al insertar. */
    val id: Long = 0,

    /** Posición en la lista. Menor valor = mayor prioridad. */
    val orden: Int,

    /** Etiqueta legible para identificar la regla en la lista y en los logs. */
    val nombre: String,

    /** Expresión regular sobre el número del remitente. Null o vacío = no filtra. */
    val regexTelefono: String? = null,

    /** Expresión regular sobre el cuerpo del SMS. Null o vacío = no filtra. */
    val regexMensaje: String? = null,

    /** Número de teléfono al que se reenvía el mensaje si la regla casa. */
    val destino: String,

    /** Plantilla del texto a enviar. Ver [PLANTILLA_POR_DEFECTO]. */
    val plantilla: String = PLANTILLA_POR_DEFECTO,

    /** Si false, la regla se ignora durante la evaluación sin necesidad de borrarla. */
    val activa: Boolean = true,

    /**
     * Si true, tras casar esta regla la evaluación continúa con las siguientes, permitiendo
     * que un mismo SMS se reenvíe a varios destinos. Si false, la evaluación se detiene aquí.
     */
    val continuar: Boolean = false
) {
    companion object {
        /** Plantilla por defecto: reenvía el cuerpo original tal cual. */
        const val PLANTILLA_POR_DEFECTO = "{mensaje}"
    }
}
