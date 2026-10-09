// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la comparación de números en que se apoya la protección antibucle.
 *
 * Si esta comparación falla, basta escribir el destino con otro prefijo para que un bucle de
 * reenvíos se cuele y empiece a generar coste. De ahí el detalle de los casos.
 */
class NormalizadorTelefonoTest {

    // ── Mismo abonado escrito de formas distintas ─────────────────────────────

    @Test
    fun `reconoce el mismo numero con y sin prefijo internacional`() {
        assertTrue(NormalizadorTelefono.mismoNumero("+34600112233", "600112233"))
    }

    @Test
    fun `reconoce el mismo numero con prefijo en formato 00`() {
        assertTrue(NormalizadorTelefono.mismoNumero("0034600112233", "+34600112233"))
    }

    @Test
    fun `ignora espacios, guiones y parentesis`() {
        assertTrue(NormalizadorTelefono.mismoNumero("+34 600-11 22 33", "600112233"))
        assertTrue(NormalizadorTelefono.mismoNumero("(+34) 600 112 233", "+34600112233"))
    }

    // ── Abonados distintos ────────────────────────────────────────────────────

    @Test
    fun `dos numeros distintos no se confunden`() {
        assertFalse(NormalizadorTelefono.mismoNumero("+34600112233", "+34600112234"))
    }

    @Test
    fun `numeros que solo comparten el final no se confunden`() {
        assertFalse(NormalizadorTelefono.mismoNumero("600112233", "700112233"))
    }

    // ── Remitentes alfanuméricos ──────────────────────────────────────────────

    @Test
    fun `un remitente alfanumerico nunca equivale a un numero`() {
        assertFalse(NormalizadorTelefono.mismoNumero("BANCO", "+34600112233"))
    }

    @Test
    fun `dos remitentes alfanumericos iguales tampoco se consideran el mismo abonado`() {
        // No son números: no se les puede reenviar nada, así que no pueden formar un bucle
        // y no deben activar la protección.
        assertFalse(NormalizadorTelefono.mismoNumero("BANCO", "BANCO"))
    }

    @Test
    fun `un numero demasiado corto no se compara`() {
        assertFalse(NormalizadorTelefono.mismoNumero("1234", "1234"))
    }

    // ── Valores ausentes ──────────────────────────────────────────────────────

    @Test
    fun `null y vacio nunca coinciden con nada`() {
        assertFalse(NormalizadorTelefono.mismoNumero(null, "+34600112233"))
        assertFalse(NormalizadorTelefono.mismoNumero("+34600112233", null))
        assertFalse(NormalizadorTelefono.mismoNumero(null, null))
        assertFalse(NormalizadorTelefono.mismoNumero("", ""))
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    @Test
    fun `soloDigitos descarta todo lo que no sea cifra`() {
        assertEquals("34600112233", NormalizadorTelefono.soloDigitos("+34 (600) 11-22-33"))
        assertEquals("", NormalizadorTelefono.soloDigitos("BANCO"))
        assertEquals("", NormalizadorTelefono.soloDigitos(null))
    }

    @Test
    fun `clave devuelve los ultimos nueve digitos`() {
        assertEquals("600112233", NormalizadorTelefono.clave("+34600112233"))
        assertEquals("600112233", NormalizadorTelefono.clave("600112233"))
    }

    @Test
    fun `clave esta vacia si no hay digitos suficientes`() {
        assertEquals("", NormalizadorTelefono.clave("12345678"))
        assertEquals("", NormalizadorTelefono.clave("AMAZON"))
    }

    // ── Búsqueda en lista ─────────────────────────────────────────────────────

    @Test
    fun `estaEnLista encuentra el numero aunque el formato sea distinto`() {
        val destinos = listOf("+34600112233", "711223344")

        assertTrue(NormalizadorTelefono.estaEnLista("600 11 22 33", destinos))
        assertTrue(NormalizadorTelefono.estaEnLista("+34711223344", destinos))
    }

    @Test
    fun `estaEnLista devuelve false si el numero no esta`() {
        val destinos = listOf("+34600112233")

        assertFalse(NormalizadorTelefono.estaEnLista("+34699999999", destinos))
        assertFalse(NormalizadorTelefono.estaEnLista("BANCO", destinos))
    }

    @Test
    fun `estaEnLista con lista vacia devuelve false`() {
        assertFalse(NormalizadorTelefono.estaEnLista("+34600112233", emptyList()))
    }

    // ── Destinos a los que se puede enviar ────────────────────────────────────

    @Test
    fun `un numero normal es un destino enviable`() {
        assertTrue(NormalizadorTelefono.esDestinoEnviable("+34600112233"))
        assertTrue(NormalizadorTelefono.esDestinoEnviable("600112233"))
    }

    @Test
    fun `un numero corto de servicio sigue siendo enviable`() {
        // Cuatro dígitos es el mínimo a propósito: hay números de servicio de esa longitud.
        assertTrue(NormalizadorTelefono.esDestinoEnviable("2255"))
    }

    @Test
    fun `una cabecera alfanumerica no es un destino enviable`() {
        assertFalse(NormalizadorTelefono.esDestinoEnviable("BANCO"))
        assertFalse(NormalizadorTelefono.esDestinoEnviable("AMAZON"))
        assertFalse(NormalizadorTelefono.esDestinoEnviable(""))
        assertFalse(NormalizadorTelefono.esDestinoEnviable(null))
    }
}
