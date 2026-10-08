// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.dashboard.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.capicua.smstosms.R
import com.capicua.smstosms.databinding.ItemSmsBinding
import com.capicua.smstosms.domain.model.SmsMessage
import com.capicua.smstosms.util.toDisplayString

class SmsListAdapter : ListAdapter<SmsMessage, SmsListAdapter.SmsViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SmsViewHolder {
        val binding = ItemSmsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SmsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SmsViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SmsViewHolder(private val binding: ItemSmsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(sms: SmsMessage) {
            val ctx = binding.root.context
            binding.textViewSender.text     = sms.telefono
            binding.textViewBody.text       = sms.mensaje
            binding.textViewReceivedAt.text = sms.fechaRecepcion.toDisplayString()
            binding.chipStatus.text = if (sms.enviado) {
                ctx.getString(R.string.status_enviado)
            } else {
                ctx.getString(R.string.status_pendiente)
            }
            binding.chipStatus.isChecked = sms.enviado
        }
    }

    private companion object DiffCallback : DiffUtil.ItemCallback<SmsMessage>() {
        override fun areItemsTheSame(oldItem: SmsMessage, newItem: SmsMessage) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SmsMessage, newItem: SmsMessage) =
            oldItem == newItem
    }
}