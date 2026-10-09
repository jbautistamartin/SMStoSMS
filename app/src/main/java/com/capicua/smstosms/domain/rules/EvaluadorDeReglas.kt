// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.rules

import com.capicua.smstosms.domain.model.Regla
import java.util.regex.PatternSyntaxException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decide a qué números se reenvía un SMS entrante y con qué texto.
 *
 * Es Kotlin puro, sin dependencias de Android: se puede instanciar directamente en tests.
 *
 * ## Algoritmo
 * 1. Descarta las reglas desactivadas y ordena el resto por [Regla.orden] ascendente.
 * 2. Para cada regla, compila sus dos expresiones regulares. Si alguna no compila, la regla
 *    entera se descarta y se anota en [ResultadoEvaluacion.reglasInvalidas]; la evaluación
 *    continúa con las siguientes.
 * 3. Una regla casa si **ambos** criterios se cumplen. Un criterio vacío (null o en blanco)
 *    se considera cumplido, de modo que una regla sin patrones casa con todo.
 * 4. Al casar, resuelve la plantilla y detiene la evaluación, salvo que la regla lleve
 *    [Regla.continuar] activado.
 *
 * La coincidencia es **parcial** ([Regex.containsMatchIn]): el patrón `codigo` casa con
 * «Tu codigo es 4821». Para exigir el texto completo hay que anclar con `^…$`.
 *
 * Las expresiones distinguen mayúsculas salvo que la regla active [Regla.ignorarMayusculas],
 * que aplica [RegexOption.IGNORE_CASE] a las dos a la vez.
 *
 * ## Destino
 * [Regla.destino] es normalmente un número fijo, pero admite el marcador [MARCADOR_TELEFONO]
 * para devolver el mensaje **al propio remitente**. Es el único marcador que se resuelve en el
 * destino, y marca la coincidencia con [Coincidencia.respondeAlRemitente] para que la
 * protección antibucle sepa que esa circularidad es intencionada.
 */
@Singleton
class EvaluadorDeReglas @Inject constructor() {

    /**
     * Evalúa [reglas] contra un SMS.
     *
     * @param telefono número del remitente, tal como llega en la PDU y sin normalizar.
     * @param mensaje  cuerpo completo del SMS.
     * @param fecha    fecha de recepción **ya formateada** para mostrar. El formato lo decide
     *                 quien llama, de modo que el evaluador permanece determinista: no depende
     *                 de la zona horaria ni del idioma del dispositivo.
     * @param reglas   lista completa de reglas; el orden de entrada es irrelevante.
     */
    fun evaluar(
        telefono: String,
        mensaje: String,
        fecha: String,
        reglas: List<Regla>
    ): ResultadoEvaluacion {
        val coincidencias = mutableListOf<Coincidencia>()
        val invalidas = mutableListOf<ReglaInvalida>()

        for (regla in reglas.filter { it.activa }.sortedBy { it.orden }) {
            val patronTelefono = compilar(regla.regexTelefono, regla.ignorarMayusculas)
            val patronMensaje = compilar(regla.regexMensaje, regla.ignorarMayusculas)

            // Una regla con un patrón roto no se puede evaluar con seguridad: se descarta
            // entera y se anota, pero no interrumpe el resto de la lista.
            val errores = listOfNotNull(
                (patronTelefono as? Patron.Invalido)?.let { CampoRegla.TELEFONO to it },
                (patronMensaje as? Patron.Invalido)?.let { CampoRegla.MENSAJE to it }
            )
            if (errores.isNotEmpty()) {
                errores.forEach { (campo, patron) ->
                    invalidas += ReglaInvalida(
                        reglaId = regla.id,
                        nombreRegla = regla.nombre,
                        campo = campo,
                        patron = patron.patron,
                        motivo = patron.motivo
                    )
                }
                continue
            }

            if (!patronTelefono.casaCon(telefono)) continue
            if (!patronMensaje.casaCon(mensaje)) continue

            coincidencias += Coincidencia(
                reglaId = regla.id,
                nombreRegla = regla.nombre,
                destino = resolverDestino(regla.destino, telefono),
                textoFinal = resolverPlantilla(regla.plantilla, telefono, mensaje, fecha),
                respondeAlRemitente = respondeAlRemitente(regla.destino)
            )

            if (!regla.continuar) break
        }

        return ResultadoEvaluacion(coincidencias, invalidas)
    }

    /**
     * Sustituye los marcadores de [plantilla] por los valores del SMS.
     *
     * `{mensaje}` se sustituye **en último lugar** a propósito: así el cuerpo del SMS, que es
     * contenido ajeno, no se vuelve a escanear y no puede inyectar marcadores. Un mensaje que
     * contenga literalmente `{fecha}` se reenvía con ese texto intacto.
     */
    fun resolverPlantilla(
        plantilla: String,
        telefono: String,
        mensaje: String,
        fecha: String
    ): String = plantilla
        .replace(MARCADOR_TELEFONO, telefono)
        .replace(MARCADOR_FECHA, fecha)
        .replace(MARCADOR_MENSAJE, mensaje)

    /**
     * Resuelve el destino de una regla, que puede ser un número fijo o el marcador
     * `{telefono}` para devolver el mensaje al propio remitente.
     *
     * Solo se sustituye `{telefono}`: `{mensaje}` y `{fecha}` no tienen sentido como destino y
     * dejarlos fuera evita que el cuerpo del SMS —contenido ajeno— pueda influir en a dónde
     * se envía nada.
     */
    fun resolverDestino(destino: String, telefono: String): String =
        destino.replace(MARCADOR_TELEFONO, telefono).trim()

    /**
     * true si el destino de la regla es el propio remitente.
     *
     * Quien llama lo necesita para saber que la circularidad es **intencionada**: escribir
     * `{telefono}` en el destino es la forma explícita de pedir «contesta a quien me escribió»,
     * y la protección antibucle no debe bloquear lo que se ha pedido a propósito.
     */
    fun respondeAlRemitente(destino: String?): Boolean =
        destino?.contains(MARCADOR_TELEFONO) == true

    /**
     * Comprueba si un patrón es utilizable.
     *
     * No recibe el ajuste de mayúsculas a propósito: ignorarlas no puede hacer que un patrón
     * válido deje de compilar, así que la validación es la misma en los dos casos.
     *
     * @return null si compila o está vacío; si no, la descripción del error de sintaxis,
     *         lista para mostrar bajo el campo en la pantalla de reglas.
     */
    fun validarPatron(patron: String?): String? =
        (compilar(patron) as? Patron.Invalido)?.motivo

    // ── Interno ───────────────────────────────────────────────────────────────

    /**
     * @param ignorarMayusculas añade [RegexOption.IGNORE_CASE]. Es acumulativo con un `(?i)`
     *        escrito dentro del patrón: activar los dos no es un error, solo redundante.
     */
    private fun compilar(patron: String?, ignorarMayusculas: Boolean = false): Patron =
        if (patron.isNullOrBlank()) {
            Patron.Ausente
        } else {
            try {
                val opciones = if (ignorarMayusculas) setOf(RegexOption.IGNORE_CASE) else emptySet()
                Patron.Valido(Regex(patron, opciones))
            } catch (e: PatternSyntaxException) {
                Patron.Invalido(patron, e.description ?: e.message ?: "expresión regular no válida")
            }
        }

    private fun Patron.casaCon(texto: String): Boolean = when (this) {
        is Patron.Ausente -> true
        is Patron.Valido -> regex.containsMatchIn(texto)
        is Patron.Invalido -> false
    }

    /** Estado de un criterio de la regla una vez intentada su compilación. */
    private sealed interface Patron {
        /** El criterio está vacío: no filtra, siempre se cumple. */
        data object Ausente : Patron

        /** El criterio compila y se puede aplicar. */
        data class Valido(val regex: Regex) : Patron

        /** El criterio no compila. */
        data class Invalido(val patron: String, val motivo: String) : Patron
    }

    companion object {
        const val MARCADOR_MENSAJE = "{mensaje}"
        const val MARCADOR_TELEFONO = "{telefono}"
        const val MARCADOR_FECHA = "{fecha}"

        /** Los tres marcadores admitidos en una plantilla, para documentación y validación. */
        val MARCADORES = listOf(MARCADOR_MENSAJE, MARCADOR_TELEFONO, MARCADOR_FECHA)
    }
}
