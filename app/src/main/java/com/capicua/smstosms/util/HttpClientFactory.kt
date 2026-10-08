// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.util

import com.capicua.smstosms.data.config.AppConfig
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Construye un [OkHttpClient] listo para usar a partir del cliente base inyectado por Hilt,
 * aplicando el timeout y la política SSL definidos en [AppConfig].
 *
 * Si [AppConfig.aceptarCertificadosInvalidos] es true se crea un cliente que confía en
 * cualquier certificado. Usar solo en redes privadas / entornos de prueba.
 */
object HttpClientFactory {

    fun crear(base: OkHttpClient, config: AppConfig): OkHttpClient {
        val builder = base.newBuilder()
            .connectTimeout(config.timeoutSegundos.toLong(), TimeUnit.SECONDS)
            .readTimeout(config.timeoutSegundos.toLong(), TimeUnit.SECONDS)

        if (config.aceptarCertificadosInvalidos) {
            val trustAll = trustAllManager()
            val sslContext = SSLContext.getInstance("TLS").also {
                it.init(null, arrayOf(trustAll), SecureRandom())
            }
            builder
                .sslSocketFactory(sslContext.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
        }

        return builder.build()
    }

    private fun trustAllManager() = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
