// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val displayFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    .withZone(ZoneId.systemDefault())

private val isoFormatter = DateTimeFormatter.ISO_INSTANT

/** Formatea un [Instant] para mostrar en la UI usando la zona horaria local del dispositivo. */
fun Instant.toDisplayString(): String = displayFormatter.format(this)

/** Formatea un [Instant] como cadena ISO-8601 UTC para incluir en el payload de la API. */
fun Instant.toIsoString(): String = isoFormatter.format(this)