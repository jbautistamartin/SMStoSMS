// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.domain.usecase

import com.capicua.smstosms.data.config.ConfigDataStore
import com.capicua.smstosms.data.repository.LogRepository
import com.capicua.smstosms.data.repository.ReenvioRepository
import com.capicua.smstosms.data.repository.ReglaRepository
import com.capicua.smstosms.data.repository.SmsRepository
import com.capicua.smstosms.domain.model.EstadoSms
import com.capicua.smstosms.domain.model.LogEntry
import com.capicua.smstosms.domain.model.LogTipo
import com.capicua.smstosms.domain.model.Reenvio
import com.capicua.smstosms.domain.model.SmsMessage
import com.capicua.smstosms.domain.rules.EvaluadorDeReglas
import com.capicua.smstosms.domain.rules.NormalizadorTelefono
import com.capicua.smstosms.util.toDisplayString
import com.capicua.smstosms.worker.ColaDeEnvios
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject

/**
 * Tramita un SMS entrante: lo evalúa contra las reglas, crea sus reenvíos y los encola.
 *
 * Es la única entrada de mensajes al sistema. Sustituye al `SaveSmsUseCase` de la versión HTTP,
 * que solo guardaba y encolaba porque el destino era siempre el mismo.
 *
 * ## Destino de un reenvío
 * Normalmente es el número fijo de la regla. Si la regla declara `{telefono}` como destino, el
 * reenvío vuelve **al propio remitente**, que es la forma de contestar a quien escribió. Esa
 * circularidad es intencionada y la protección antibucle no la bloquea; lo que sí se comprueba
 * es que el remitente sea un número al que se pueda enviar.
 *
 * ## Orden de las operaciones
 * ```
 * 1. INSERT del SMS en estado PENDIENTE        ← outbox: nada se pierde a partir de aquí
 * 2. Protecciones antibucle y de frecuencia    ← si salta alguna: DESCARTADO y fin
 * 3. Evaluar reglas
 * 4. INSERT de los reenvíos (lote atómico)
 * 5. Marcar el SMS como PROCESADO o SIN_REGLA
 * 6. Encolar un worker por reenvío
 * ```
 *
 * Si el proceso muere a mitad, el rescate de huérfanos retoma el trabajo. Para que eso no
 * genere envíos duplicados, [procesar] es **idempotente**: antes de crear reenvíos comprueba si
 * el SMS ya los tiene, y en ese caso se limita a reencolar los que quedan. Es la misma defensa
 * que hace el worker al comprobar el estado antes de cada intento.
 */
class ProcesarSmsEntranteUseCase @Inject constructor(
    private val smsRepository: SmsRepository,
    private val reglaRepository: ReglaRepository,
    private val reenvioRepository: ReenvioRepository,
    private val logRepository: LogRepository,
    private val evaluador: EvaluadorDeReglas,
    private val colaDeEnvios: ColaDeEnvios,
    private val configDataStore: ConfigDataStore
) {

    /** Persiste el SMS y lo tramita. Para mensajes recién recibidos. */
    suspend operator fun invoke(sms: SmsMessage) {
        smsRepository.guardar(sms)
        logRepository.insertar(
            LogEntry(
                tipo = LogTipo.SMS_RECIBIDO,
                smsId = sms.id,
                detalle = "SMS recibido de ${sms.telefono} (${sms.mensaje.length} caracteres)",
                timestamp = Instant.now()
            )
        )
        procesar(sms)
    }

    /**
     * Tramita un SMS ya persistido. Lo llama tanto [invoke] como el rescate de huérfanos para
     * los mensajes que se quedaron en PENDIENTE.
     */
    suspend fun procesar(sms: SmsMessage) {
        // ── Idempotencia: ¿ya se tramitó este SMS? ────────────────────────────
        val existentes = reenvioRepository.obtenerPorSms(sms.id)
        if (existentes.isNotEmpty()) {
            Timber.d("Procesar[${sms.id}]: ya tiene ${existentes.size} reenvío(s), solo reencolo")
            smsRepository.actualizarEstado(sms.id, EstadoSms.PROCESADO)
            colaDeEnvios.encolarVarios(existentes.map { it.id })
            return
        }

        val config = configDataStore.config.first()

        // ── Protección: el remitente es uno de nuestros destinos ──────────────
        //
        // Si contestan a un número al que reenviamos, ese SMS entra por el receptor como
        // cualquier otro. Reenviarlo otra vez cierra el círculo y, con los reintentos
        // automáticos detrás, convierte un error de configuración en una factura.
        if (config.protegerBucles) {
            val destinos = reglaRepository.obtenerDestinosActivos()
            if (NormalizadorTelefono.estaEnLista(sms.telefono, destinos)) {
                descartar(
                    sms,
                    "el remitente ${sms.telefono} es un destino configurado: posible bucle de reenvío"
                )
                return
            }
        }

        // ── Protección: límite de reenvíos por minuto ─────────────────────────
        val desde = Instant.now().minus(1, ChronoUnit.MINUTES)
        val recientes = reenvioRepository.contarCreadosDesde(desde)
        if (recientes >= config.maxReenviosPorMinuto) {
            descartar(
                sms,
                "límite de ${config.maxReenviosPorMinuto} reenvíos por minuto alcanzado " +
                    "($recientes en los últimos 60 s)"
            )
            return
        }

        // ── Evaluación ────────────────────────────────────────────────────────
        val reglas = reglaRepository.obtenerActivas()
        val resultado = evaluador.evaluar(
            telefono = sms.telefono,
            mensaje = sms.mensaje,
            fecha = sms.fechaRecepcion.toDisplayString(),
            reglas = reglas
        )

        // Las reglas con una expresión regular rota no paran la evaluación, pero el operador
        // tiene que poder enterarse: de lo contrario la regla «no funciona» sin explicación.
        resultado.reglasInvalidas.forEach { invalida ->
            logRepository.insertar(
                LogEntry(
                    tipo = LogTipo.ERROR,
                    smsId = sms.id,
                    detalle = "Regla «${invalida.nombreRegla}» descartada: " +
                        "la expresión de ${invalida.campo.name.lowercase()} " +
                        "«${invalida.patron}» no es válida (${invalida.motivo})",
                    timestamp = Instant.now()
                )
            )
        }

        if (!resultado.hayCoincidencias) {
            smsRepository.actualizarEstado(sms.id, EstadoSms.SIN_REGLA)
            logRepository.insertar(
                LogEntry(
                    tipo = LogTipo.SIN_REGLA,
                    smsId = sms.id,
                    detalle = "Ninguna de las ${reglas.size} regla(s) activa(s) casó con el mensaje",
                    timestamp = Instant.now()
                )
            )
            Timber.i("Procesar[${sms.id}]: sin regla que case, nada que reenviar")
            return
        }

        // ── Protección: no reenviar al propio remitente ───────────────────────
        //
        // Una regla con `{telefono}` en el destino pide explícitamente contestar a quien
        // escribió, así que queda fuera de esta protección: lo que se bloquea es la
        // circularidad accidental, no la que se ha configurado a propósito.
        val (validas, circulares) = if (config.protegerBucles) {
            resultado.coincidencias.partition { coincidencia ->
                coincidencia.respondeAlRemitente ||
                    !NormalizadorTelefono.mismoNumero(coincidencia.destino, sms.telefono)
            }
        } else {
            resultado.coincidencias to emptyList()
        }

        circulares.forEach { coincidencia ->
            logRepository.insertar(
                LogEntry(
                    tipo = LogTipo.BUCLE_EVITADO,
                    smsId = sms.id,
                    destino = coincidencia.destino,
                    detalle = "Regla «${coincidencia.nombreRegla}» reenviaría al propio " +
                        "remitente ${sms.telefono}: omitida. Si la intención era contestarle, " +
                        "pon {telefono} como destino de la regla",
                    timestamp = Instant.now()
                )
            )
        }

        // ── Protección: el remitente no es un número al que se pueda enviar ───
        //
        // Solo afecta a las reglas que contestan al remitente. Una cabecera alfanumérica
        // (BANCO, AMAZON) no recibe SMS, así que el envío fallaría siempre y con reintentos
        // detrás: mejor decirlo aquí.
        val (enviables, inalcanzables) = validas.partition { coincidencia ->
            !coincidencia.respondeAlRemitente ||
                NormalizadorTelefono.esDestinoEnviable(coincidencia.destino)
        }

        inalcanzables.forEach { coincidencia ->
            logRepository.insertar(
                LogEntry(
                    tipo = LogTipo.ERROR,
                    smsId = sms.id,
                    destino = coincidencia.destino,
                    detalle = "Regla «${coincidencia.nombreRegla}» contesta al remitente, pero " +
                        "«${sms.telefono}» no es un número al que se pueda enviar un SMS: omitida",
                    timestamp = Instant.now()
                )
            )
        }

        if (enviables.isEmpty()) {
            descartar(
                sms,
                if (circulares.isNotEmpty()) {
                    "todas las reglas que casaron reenviaban al propio remitente"
                } else {
                    "las reglas que casaron contestaban a «${sms.telefono}», " +
                        "que no es un número al que se pueda enviar"
                }
            )
            return
        }

        // ── Creación de los reenvíos ──────────────────────────────────────────
        val ahora = Instant.now()
        val reenvios = enviables.map { coincidencia ->
            Reenvio(
                id = UUID.randomUUID().toString(),
                smsId = sms.id,
                reglaId = coincidencia.reglaId,
                nombreRegla = coincidencia.nombreRegla,
                destino = coincidencia.destino,
                textoFinal = coincidencia.textoFinal,
                fechaCreacion = ahora
            )
        }

        reenvioRepository.guardarVarios(reenvios)
        smsRepository.actualizarEstado(sms.id, EstadoSms.PROCESADO)

        reenvios.forEach { reenvio ->
            logRepository.insertar(
                LogEntry(
                    tipo = LogTipo.REGLA_APLICADA,
                    smsId = sms.id,
                    reenvioId = reenvio.id,
                    destino = reenvio.destino,
                    detalle = "Regla «${reenvio.nombreRegla}» → ${reenvio.destino}",
                    timestamp = Instant.now()
                )
            )
        }

        colaDeEnvios.encolarVarios(reenvios.map { it.id })

        Timber.i("Procesar[${sms.id}]: ${reenvios.size} reenvío(s) encolado(s)")
    }

    private suspend fun descartar(sms: SmsMessage, motivo: String) {
        smsRepository.marcarDescartado(sms.id, motivo)
        logRepository.insertar(
            LogEntry(
                tipo = LogTipo.BUCLE_EVITADO,
                smsId = sms.id,
                detalle = "SMS descartado: $motivo",
                timestamp = Instant.now()
            )
        )
        Timber.w("Procesar[${sms.id}]: descartado — $motivo")
    }
}
