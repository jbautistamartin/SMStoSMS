// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.rules

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.capicua.smstosms.R
import com.capicua.smstosms.databinding.ItemReglaBinding
import com.capicua.smstosms.domain.model.Regla

/**
 * Lista de reglas de reenvío.
 *
 * Cada fila muestra lo que hace falta para entender la regla de un vistazo: su prioridad, los
 * criterios que aplica, a dónde reenvía y si está activa. La reordenación se hace con flechas
 * en lugar de arrastrando, para que no compita con el interruptor de activación en un área tan
 * estrecha.
 */
class ReglasAdapter(
    private val alPulsar: (Regla) -> Unit,
    private val alCambiarActiva: (Regla, Boolean) -> Unit,
    private val alSubir: (Int) -> Unit,
    private val alBajar: (Int) -> Unit,
    private val alAbrirMenu: (Regla, View) -> Unit
) : ListAdapter<Regla, ReglasAdapter.ReglaViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReglaViewHolder {
        val binding = ItemReglaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ReglaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReglaViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ReglaViewHolder(private val binding: ItemReglaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(regla: Regla, posicion: Int) {
            val ctx = binding.root.context

            binding.textViewOrden.text = (posicion + 1).toString()
            binding.textViewNombre.text = regla.nombre
            binding.textViewCriterios.text = describirCriterios(regla)
            binding.textViewDestino.text = ctx.getString(
                if (regla.continuar) R.string.reglas_destino_continua else R.string.reglas_destino,
                regla.destino
            )

            // Una regla desactivada se atenúa entera: así se distingue de un vistazo sin tener
            // que buscar la posición del interruptor.
            val opacidad = if (regla.activa) 1f else 0.45f
            binding.layoutCuerpo.alpha = opacidad

            // setOnCheckedChangeListener se limpia antes de fijar el estado para que el reciclado
            // de vistas no dispare un cambio que el usuario no ha hecho.
            binding.switchActiva.setOnCheckedChangeListener(null)
            binding.switchActiva.isChecked = regla.activa
            binding.switchActiva.setOnCheckedChangeListener { _, marcado ->
                alCambiarActiva(regla, marcado)
            }

            binding.buttonSubir.isEnabled = posicion > 0
            binding.buttonBajar.isEnabled = posicion < itemCount - 1

            binding.buttonSubir.setOnClickListener { alSubir(posicion) }
            binding.buttonBajar.setOnClickListener { alBajar(posicion) }
            binding.buttonMenu.setOnClickListener { alAbrirMenu(regla, it) }
            binding.layoutCuerpo.setOnClickListener { alPulsar(regla) }
        }

        /**
         * Resume los criterios en una línea legible.
         *
         * Una regla sin criterios casa con todo, y eso hay que decirlo explícitamente: es
         * potente y fácil de crear por descuido.
         */
        private fun describirCriterios(regla: Regla): String {
            val ctx = binding.root.context
            val partes = buildList {
                regla.regexTelefono?.takeIf { it.isNotBlank() }?.let {
                    add(ctx.getString(R.string.reglas_criterio_telefono, it))
                }
                regla.regexMensaje?.takeIf { it.isNotBlank() }?.let {
                    add(ctx.getString(R.string.reglas_criterio_mensaje, it))
                }
            }
            return if (partes.isEmpty()) {
                ctx.getString(R.string.reglas_criterio_todo)
            } else {
                partes.joinToString("  ·  ")
            }
        }
    }

    private companion object DiffCallback : DiffUtil.ItemCallback<Regla>() {
        override fun areItemsTheSame(oldItem: Regla, newItem: Regla) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Regla, newItem: Regla) = oldItem == newItem
    }
}
