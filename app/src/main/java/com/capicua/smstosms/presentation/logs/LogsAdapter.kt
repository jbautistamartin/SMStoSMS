// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.logs

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.capicua.smstosms.R
import com.capicua.smstosms.databinding.ItemLogBinding
import com.capicua.smstosms.domain.model.LogEntry
import com.capicua.smstosms.domain.model.LogTipo
import com.capicua.smstosms.util.toDisplayString

class LogsAdapter : ListAdapter<LogEntry, LogsAdapter.LogViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(private val binding: ItemLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(log: LogEntry) {
            val ctx = binding.root.context

            binding.textViewTimestamp.text = log.timestamp.toDisplayString()
            binding.textViewDetalle.text   = log.detalle
            binding.textViewTipo.text      = log.tipo.name

            binding.textViewDestino.text = log.destino?.let { "→ $it" }.orEmpty()

            val (colorRes, iconRes) = when (log.tipo) {
                LogTipo.SMS_RECIBIDO   -> Pair(R.color.log_recibido,  android.R.drawable.ic_dialog_info)
                LogTipo.REGLA_APLICADA -> Pair(R.color.log_regla,     android.R.drawable.ic_menu_sort_by_size)
                LogTipo.SMS_REENVIADO  -> Pair(R.color.log_reenviado, android.R.drawable.ic_dialog_email)
                LogTipo.SIN_REGLA      -> Pair(R.color.log_sin_regla, android.R.drawable.ic_menu_close_clear_cancel)
                LogTipo.BUCLE_EVITADO  -> Pair(R.color.log_bucle,     android.R.drawable.ic_lock_idle_alarm)
                LogTipo.ERROR          -> Pair(R.color.log_error,     android.R.drawable.ic_dialog_alert)
                LogTipo.SISTEMA        -> Pair(R.color.log_sistema,   android.R.drawable.ic_menu_info_details)
            }

            binding.viewIndicador.setBackgroundColor(ContextCompat.getColor(ctx, colorRes))
            binding.imageViewIcono.setImageResource(iconRes)
        }
    }

    private companion object DiffCallback : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(old: LogEntry, new: LogEntry) = old.id == new.id
        override fun areContentsTheSame(old: LogEntry, new: LogEntry) = old == new
    }
}