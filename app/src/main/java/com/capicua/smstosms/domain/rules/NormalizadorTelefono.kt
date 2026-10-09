// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.rules

/**
 * Compara números de teléfono que pueden venir escritos de formas distintas.
 *
 * El mismo abonado llega como `+34600112233` en la PDU, como `600112233` escrito a mano en una
 * regla y como `0034 600 112 233` copiado de una agenda. Para las protecciones antibucle hay
 * que reconocer que los tres son el mismo número, porque si no, basta un prefijo distinto para
 * que un bucle de reenvíos se cuele.
 *
 * ## Criterio
 * Se quedan solo los dígitos y se comparan los [DIGITOS_SIGNIFICATIVOS] últimos. Es deliberado
 * que sea una comparación por sufijo: evita tener que conocer los prefijos internacionales de
 * cada país, que es exactamente el tipo de dato que se queda obsoleto.
 *
 * Los remitentes alfanuméricos (`BANCO`, `AMAZON`) no tienen dígitos suficientes: nunca se
 * consideran iguales a un número, lo que es correcto, porque a una cabecera alfanumérica no se
 * le puede reenviar nada y por tanto no puede formar un bucle.
 */
object NormalizadorTelefono {

    /**
     * Dígitos finales que se comparan. Nueve es la longitud del número nacional en España
     * y en la mayoría de planes de numeración europeos, de modo que `+34600112233` y
     * `600112233` coinciden mientras dos abonados distintos siguen distinguiéndose.
     */
    const val DIGITOS_SIGNIFICATIVOS = 9

    /** Deja solo los dígitos de [numero]. */
    fun soloDigitos(numero: String?): String =
        numero?.filter { it.isDigit() } ?: ""

    /**
     * Sufijo comparable de [numero], o cadena vacía si no tiene dígitos suficientes
     * (caso de los remitentes alfanuméricos).
     */
    fun clave(numero: String?): String {
        val digitos = soloDigitos(numero)
        return if (digitos.length < DIGITOS_SIGNIFICATIVOS) "" else digitos.takeLast(DIGITOS_SIGNIFICATIVOS)
    }

    /**
     * true si [a] y [b] son, con toda probabilidad, el mismo abonado.
     *
     * Dos valores sin dígitos suficientes nunca se consideran iguales, ni siquiera si el texto
     * coincide: no son números y no participan en bucles de reenvío.
     */
    fun mismoNumero(a: String?, b: String?): Boolean {
        val claveA = clave(a)
        val claveB = clave(b)
        return claveA.isNotEmpty() && claveA == claveB
    }

    /**
     * Dígitos mínimos para que un valor pueda ser un destino al que enviar. Un número corto de
     * servicio tiene cuatro, así que se exige poco a propósito: validar de más impediría usar
     * números especiales.
     */
    const val DIGITOS_MINIMOS_DESTINO = 4

    /**
     * true si a [numero] se le puede enviar un SMS.
     *
     * Importa con las reglas que responden al remitente: si quien escribió es una cabecera
     * alfanumérica (`BANCO`, `AMAZON`), devolverle el mensaje es imposible y hay que decirlo
     * en lugar de intentar un envío que siempre va a fallar.
     */
    fun esDestinoEnviable(numero: String?): Boolean =
        soloDigitos(numero).length >= DIGITOS_MINIMOS_DESTINO

    /** true si [numero] coincide con alguno de [candidatos]. */
    fun estaEnLista(numero: String?, candidatos: Collection<String>): Boolean =
        candidatos.any { mismoNumero(numero, it) }
}
