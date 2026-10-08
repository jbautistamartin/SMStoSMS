// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.data.config

/**
 * Ajustes generales de la aplicación, editables en la pantalla de Configuración y
 * persistidos en DataStore<Preferences>.
 *
 * Aquí no se decide **a quién** se reenvía nada: eso lo determinan las reglas, que viven en la
 * base de datos porque son una lista ordenada. Esto son los parámetros que afectan a todos los
 * reenvíos por igual.
 */
data class AppConfig(
    /** Número máximo de intentos antes de dar un reenvío por fallido permanentemente. */
    val maxReintentos: Int = 10,

    /** Espera inicial entre reintentos, en segundos. WorkManager la aplica como backoff. */
    val intervaloReintentoSegundos: Int = 30,

    /**
     * Segundos que se espera la confirmación del operador (`sentIntent`) antes de dar el
     * intento por perdido y reintentar. El envío de un SMS es asíncrono: `SmsManager` retorna
     * al instante y el resultado llega después por broadcast.
     */
    val timeoutEnvioSegundos: Int = 60,

    /**
     * Tope de reenvíos creados por minuto. Es el cortafuegos económico del sistema: si una
     * configuración desafortunada provoca un bucle de reenvíos, esto lo detiene antes de que
     * se convierta en una factura. El exceso se descarta y queda registrado en el log.
     */
    val maxReenviosPorMinuto: Int = 10,

    /**
     * Si true, se descartan los SMS cuyo remitente sea uno de los destinos configurados y los
     * reenvíos dirigidos al propio remitente. Desactivarlo solo tiene sentido en pruebas
     * controladas.
     */
    val protegerBucles: Boolean = true,

    /**
     * Id de suscripción de la SIM con la que enviar, en dispositivos con más de una.
     * [SIM_POR_DEFECTO] deja que el sistema elija la SIM de SMS predeterminada.
     */
    val subscriptionId: Int = SIM_POR_DEFECTO
) {
    companion object {
        /** La aplicación no fuerza ninguna SIM: envía con la predeterminada del sistema. */
        const val SIM_POR_DEFECTO = -1
    }
}
