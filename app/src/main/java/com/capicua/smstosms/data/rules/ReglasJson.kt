// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.rules

import com.capicua.smstosms.domain.model.Regla
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Formato de intercambio del juego de reglas, para clonar la configuración entre dispositivos.
 *
 * El fichero lleva [version] por delante: si algún día cambia la forma de una regla, una
 * importación podrá reconocer el formato antiguo en lugar de fallar con un error de parseo
 * incomprensible.
 *
 * Los ids **no** se exportan a propósito. Un id es local a la base de datos del dispositivo;
 * reutilizarlo al importar provocaría colisiones con reglas existentes. Al importar, cada regla
 * entra como nueva. Lo que sí se conserva es el orden relativo, que es lo que tiene significado.
 */
@Serializable
data class ReglasExportadas(
    @SerialName("version") val version: Int = VERSION_ACTUAL,
    @SerialName("exportado") val exportado: String,
    @SerialName("reglas") val reglas: List<ReglaJson>
) {
    companion object {
        /**
         * Versión del formato.
         *
         * | Versión | Cambio                                  |
         * |---------|-----------------------------------------|
         * | 1       | Formato inicial.                        |
         * | 2       | La regla gana `ignorar_mayusculas`.     |
         *
         * Subir la versión al añadir un campo opcional puede parecer excesivo, porque un
         * fichero de la 1 se sigue importando sin problema. Lo que evita es el caso contrario:
         * una versión antigua de la aplicación leyendo un fichero de la 2 descartaría
         * `ignorar_mayusculas` en silencio —`ignoreUnknownKeys` está activado— y la regla
         * entraría distinguiendo mayúsculas sin avisar. Con la versión por delante, esa
         * importación se rechaza con un mensaje que explica lo que pasa.
         */
        const val VERSION_ACTUAL = 2
    }
}

/** Una regla tal como viaja en el fichero. */
@Serializable
data class ReglaJson(
    @SerialName("nombre") val nombre: String,
    @SerialName("regex_telefono") val regexTelefono: String? = null,
    @SerialName("regex_mensaje") val regexMensaje: String? = null,
    @SerialName("ignorar_mayusculas") val ignorarMayusculas: Boolean = false,
    @SerialName("destino") val destino: String,
    @SerialName("plantilla") val plantilla: String = Regla.PLANTILLA_POR_DEFECTO,
    @SerialName("activa") val activa: Boolean = true,
    @SerialName("continuar") val continuar: Boolean = false
)

/** Convierte una regla del dominio a su forma exportable, descartando el id local. */
fun Regla.aJson() = ReglaJson(
    nombre = nombre,
    regexTelefono = regexTelefono,
    regexMensaje = regexMensaje,
    ignorarMayusculas = ignorarMayusculas,
    destino = destino,
    plantilla = plantilla,
    activa = activa,
    continuar = continuar
)

/**
 * Convierte una regla importada al dominio.
 *
 * El [orden] lo decide quien importa, según la posición en el fichero; el id queda a 0 para que
 * Room asigne uno nuevo.
 */
fun ReglaJson.aDominio(orden: Int) = Regla(
    id = 0,
    orden = orden,
    nombre = nombre,
    regexTelefono = regexTelefono?.takeIf { it.isNotBlank() },
    regexMensaje = regexMensaje?.takeIf { it.isNotBlank() },
    ignorarMayusculas = ignorarMayusculas,
    destino = destino,
    plantilla = plantilla.ifBlank { Regla.PLANTILLA_POR_DEFECTO },
    activa = activa,
    continuar = continuar
)
