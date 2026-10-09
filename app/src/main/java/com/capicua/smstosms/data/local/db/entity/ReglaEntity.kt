// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entidad Room para la tabla de reglas de reenvío.
 *
 * Tabla: [reglas]
 *
 * - [orden]           Prioridad. Menor valor = se evalúa antes. Indexado para ordenar la lista.
 * - [regexTelefono]   Expresión regular sobre el remitente. Null = no filtra por teléfono.
 * - [regexMensaje]    Expresión regular sobre el cuerpo. Null = no filtra por mensaje.
 * - [ignorarMayusculas] true = las dos expresiones se aplican sin distinguir mayúsculas.
 * - [destino]         Número al que se reenvía.
 * - [plantilla]       Texto a enviar, con marcadores `{mensaje}`, `{telefono}` y `{fecha}`.
 * - [activa]          false = la regla se ignora sin borrarla.
 * - [continuar]       true = tras casar, sigue evaluando las reglas siguientes.
 *
 * Las expresiones se guardan como texto sin compilar: validarlas es responsabilidad de la
 * pantalla de reglas y del evaluador, no de la base de datos.
 */
@Entity(
    tableName = "reglas",
    indices = [Index(value = ["orden"])]
)
data class ReglaEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "orden")
    val orden: Int,

    @ColumnInfo(name = "nombre")
    val nombre: String,

    @ColumnInfo(name = "regex_telefono")
    val regexTelefono: String? = null,

    @ColumnInfo(name = "regex_mensaje")
    val regexMensaje: String? = null,

    @ColumnInfo(name = "ignorar_mayusculas", defaultValue = "0")
    val ignorarMayusculas: Boolean = false,

    @ColumnInfo(name = "destino")
    val destino: String,

    @ColumnInfo(name = "plantilla", defaultValue = "'{mensaje}'")
    val plantilla: String = "{mensaje}",

    @ColumnInfo(name = "activa", defaultValue = "1")
    val activa: Boolean = true,

    @ColumnInfo(name = "continuar", defaultValue = "0")
    val continuar: Boolean = false
)
