// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.repository

import com.capicua.smstosms.data.local.db.dao.ReglaDao
import com.capicua.smstosms.data.local.db.entity.ReglaEntity
import com.capicua.smstosms.domain.model.Regla
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementación de [ReglaRepository].
 *
 * Traduce entre [ReglaEntity] y [Regla], y es el único sitio donde se decide el `orden`
 * de una regla nueva: siempre la última de la lista.
 */
@Singleton
class ReglaRepositoryImpl @Inject constructor(
    private val reglaDao: ReglaDao
) : ReglaRepository {

    // ── Escritura ─────────────────────────────────────────────────────────────

    override suspend fun crear(regla: Regla): Long {
        // El orden lo asigna el repositorio, no quien llama: una regla nueva va al final
        // para no alterar la prioridad de las que ya funcionaban.
        val siguienteOrden = reglaDao.ordenMaximo() + 1
        return reglaDao.insertar(regla.copy(orden = siguienteOrden).aEntidad())
    }

    override suspend fun actualizar(regla: Regla) {
        reglaDao.actualizar(regla.aEntidad())
    }

    override suspend fun eliminar(id: Long) {
        reglaDao.eliminarPorId(id)
    }

    override suspend fun cambiarActiva(id: Long, activa: Boolean) {
        reglaDao.cambiarActiva(id, activa)
    }

    override suspend fun reordenar(idsEnOrden: List<Long>) {
        reglaDao.reordenar(idsEnOrden)
    }

    override suspend fun eliminarTodas() {
        reglaDao.eliminarTodas()
    }

    // ── Lectura reactiva ──────────────────────────────────────────────────────

    override fun observarTodas(): Flow<List<Regla>> =
        reglaDao.observarTodas().map { lista -> lista.map { it.aDominio() } }

    override fun observarContadorActivas(): Flow<Int> =
        reglaDao.observarContadorActivas()

    // ── Lectura puntual ──────────────────────────────────────────────────────

    override suspend fun obtenerActivas(): List<Regla> =
        reglaDao.obtenerActivas().map { it.aDominio() }

    override suspend fun obtenerTodas(): List<Regla> =
        reglaDao.obtenerTodas().map { it.aDominio() }

    override suspend fun obtenerPorId(id: Long): Regla? =
        reglaDao.obtenerPorId(id)?.aDominio()

    override suspend fun obtenerDestinosActivos(): List<String> =
        reglaDao.obtenerDestinosActivos()

    // ── Mappers ───────────────────────────────────────────────────────────────

    private fun Regla.aEntidad() = ReglaEntity(
        id = id,
        orden = orden,
        nombre = nombre,
        regexTelefono = regexTelefono,
        regexMensaje = regexMensaje,
        ignorarMayusculas = ignorarMayusculas,
        destino = destino,
        plantilla = plantilla,
        activa = activa,
        continuar = continuar
    )

    private fun ReglaEntity.aDominio() = Regla(
        id = id,
        orden = orden,
        nombre = nombre,
        regexTelefono = regexTelefono,
        regexMensaje = regexMensaje,
        ignorarMayusculas = ignorarMayusculas,
        destino = destino,
        plantilla = plantilla,
        activa = activa,
        continuar = continuar
    )
}
