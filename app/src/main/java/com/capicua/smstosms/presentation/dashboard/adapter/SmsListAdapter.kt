// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.dashboard.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.capicua.smstosms.R
import com.capicua.smstosms.databinding.ItemSmsBinding
import com.capicua.smstosms.domain.model.EstadoReenvio
import com.capicua.smstosms.domain.model.ResumenSms
import com.capicua.smstosms.domain.model.SmsConReenvios
import com.capicua.smstosms.util.toDisplayString

/**
 * Lista de SMS recibidos con lo que se hizo con cada uno.
 *
 * Cada fila responde a tres preguntas en este orden: quién lo mandó, qué decía, y a dónde fue.
 * El estado se codifica dos veces —franja de color y chip— porque es el dato que se busca al
 * abrir la pantalla y conviene que se lea sin detenerse a leer.
 */
class SmsListAdapter : ListAdapter<SmsConReenvios, SmsListAdapter.SmsViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SmsViewHolder {
        val binding = ItemSmsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SmsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SmsViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SmsViewHolder(private val binding: ItemSmsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SmsConReenvios) {
            val ctx = binding.root.context
            val sms = item.sms

            binding.textViewSender.text = sms.telefono
            binding.textViewBody.text = sms.mensaje
            binding.textViewReceivedAt.text = sms.fechaRecepcion.toDisplayString()

            val (etiqueta, color) = when (item.resumen) {
                ResumenSms.PENDIENTE -> R.string.status_pendiente to R.color.status_pending
                ResumenSms.ENVIANDO -> R.string.status_enviando to R.color.status_dispatching
                ResumenSms.REENVIADO -> R.string.status_procesado to R.color.status_delivered
                ResumenSms.FALLIDO -> R.string.status_fallido to R.color.status_failed
                ResumenSms.SIN_REGLA -> R.string.status_sin_regla to R.color.status_pending
                ResumenSms.DESCARTADO -> R.string.status_descartado to R.color.log_bucle
            }

            binding.chipStatus.text = ctx.getString(etiqueta)
            binding.viewIndicador.setBackgroundColor(ContextCompat.getColor(ctx, color))

            binding.textViewRuta.text = describirRuta(item)
            pintarDetalle(item)
        }

        /** Resume a dónde fue el SMS, o por qué no fue a ninguna parte. */
        private fun describirRuta(item: SmsConReenvios): String {
            val ctx = binding.root.context
            return when {
                item.sms.estado == com.capicua.smstosms.domain.model.EstadoSms.DESCARTADO ->
                    ctx.getString(R.string.sms_ruta_descartado)

                item.reenvios.isEmpty() ->
                    ctx.getString(R.string.sms_ruta_sin_regla)

                item.reenvios.size == 1 -> {
                    val reenvio = item.reenvios.first()
                    ctx.getString(R.string.sms_ruta_uno, reenvio.nombreRegla, reenvio.destino)
                }

                else -> ctx.resources.getQuantityString(
                    R.plurals.sms_ruta_varios,
                    item.reenvios.size,
                    item.reenvios.size,
                    item.reenvios.joinToString(", ") { it.destino }
                )
            }
        }

        /**
         * Línea de detalle: el motivo del descarte, el último error, o el desglose por destino
         * cuando hay varios y no todos han corrido la misma suerte.
         */
        private fun pintarDetalle(item: SmsConReenvios) {
            val ctx = binding.root.context
            val campo = binding.textViewDetalle

            val texto = when {
                item.sms.motivoDescarte != null -> item.sms.motivoDescarte

                item.fallidos > 0 -> item.reenvios
                    .firstOrNull { it.estado == EstadoReenvio.FALLIDO && it.ultimoError != null }
                    ?.let { ctx.getString(R.string.sms_detalle_error, it.destino, it.ultimoError) }

                item.reenvios.size > 1 -> ctx.getString(
                    R.string.sms_detalle_desglose,
                    item.enviados, item.pendientes, item.fallidos
                )

                item.pendientes > 0 -> item.reenvios
                    .firstOrNull { it.estado == EstadoReenvio.PENDIENTE && it.intentos > 0 }
                    ?.let { ctx.getString(R.string.sms_detalle_intentos, it.intentos) }

                else -> null
            }

            campo.text = texto.orEmpty()
            campo.visibility = if (texto.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    private companion object DiffCallback : DiffUtil.ItemCallback<SmsConReenvios>() {
        override fun areItemsTheSame(oldItem: SmsConReenvios, newItem: SmsConReenvios) =
            oldItem.sms.id == newItem.sms.id

        override fun areContentsTheSame(oldItem: SmsConReenvios, newItem: SmsConReenvios) =
            oldItem == newItem
    }
}
