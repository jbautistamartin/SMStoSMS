// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.rules

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.capicua.smstosms.R
import com.capicua.smstosms.databinding.FragmentProbarReglasBinding
import com.capicua.smstosms.databinding.ItemCoincidenciaBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Prueba las reglas con un SMS de ejemplo.
 *
 * Responde a la única pregunta que importa cuando una regla no hace lo esperado: con este
 * remitente y este texto, ¿qué regla casa, a qué número iría y con qué contenido? Todo sin
 * enviar nada ni esperar a que llegue un SMS real.
 *
 * El botón de envío real está detrás del resultado y a propósito solo manda el **primer**
 * reenvío: una prueba no debería gastar varios SMS sin que quede claro.
 */
@AndroidEntryPoint
class ProbarReglasFragment : Fragment() {

    private var _binding: FragmentProbarReglasBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProbarReglasViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProbarReglasBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.editTextTelefonoPrueba.setText(EJEMPLO_TELEFONO)
        binding.editTextMensajePrueba.setText(EJEMPLO_MENSAJE)

        binding.buttonEvaluar.setOnClickListener {
            val telefono = binding.editTextTelefonoPrueba.text?.toString()?.trim().orEmpty()
            val mensaje = binding.editTextMensajePrueba.text?.toString().orEmpty()

            if (mensaje.isBlank()) {
                binding.tilMensajePrueba.error = getString(R.string.probar_mensaje_obligatorio)
                return@setOnClickListener
            }
            binding.tilMensajePrueba.error = null

            viewModel.probar(telefono, mensaje)
        }

        binding.buttonEnviarPrueba.setOnClickListener { confirmarEnvio() }

        observarPrueba()
        observarEnvio()
    }

    private fun confirmarEnvio() {
        val primera = viewModel.prueba.value?.coincidencias?.firstOrNull() ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.probar_enviar)
            .setMessage(
                getString(
                    R.string.probar_enviar_confirmar,
                    primera.destino,
                    primera.partes
                )
            )
            .setNegativeButton(R.string.cancelar, null)
            .setPositiveButton(R.string.probar_enviar) { _, _ -> viewModel.enviarPrueba() }
            .show()
    }

    // ── Observación ───────────────────────────────────────────────────────────

    private fun observarPrueba() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.prueba.collect { prueba ->
                    if (prueba == null) {
                        binding.layoutResultado.visibility = View.GONE
                        return@collect
                    }

                    binding.layoutResultado.visibility = View.VISIBLE

                    binding.textViewVeredicto.text = if (prueba.hayCoincidencias) {
                        resources.getQuantityString(
                            R.plurals.probar_veredicto_coincide,
                            prueba.coincidencias.size,
                            prueba.coincidencias.size
                        )
                    } else {
                        getString(R.string.probar_veredicto_sin_coincidencia)
                    }
                    binding.textViewVeredicto.setTextColor(
                        resources.getColor(
                            if (prueba.hayCoincidencias) R.color.status_delivered
                            else R.color.status_pending,
                            null
                        )
                    )

                    binding.textViewReglasEvaluadas.text = resources.getQuantityString(
                        R.plurals.probar_reglas_evaluadas,
                        prueba.reglasActivas,
                        prueba.reglasActivas
                    )

                    pintarCoincidencias(prueba)
                    pintarAvisos(prueba)

                    binding.buttonEnviarPrueba.isEnabled = prueba.hayCoincidencias
                }
            }
        }
    }

    private fun pintarCoincidencias(prueba: PruebaReglas) {
        val contenedor = binding.layoutCoincidencias
        contenedor.removeAllViews()

        prueba.coincidencias.forEach { coincidencia ->
            val fila = ItemCoincidenciaBinding.inflate(layoutInflater, contenedor, false)
            fila.textViewPosicion.text = coincidencia.posicion.toString()
            fila.textViewRegla.text = coincidencia.nombreRegla
            fila.textViewDestino.text =
                getString(R.string.reglas_destino, coincidencia.destino)
            fila.textViewTexto.text = coincidencia.textoFinal
            fila.textViewPartes.text = resources.getQuantityString(
                R.plurals.regla_partes,
                coincidencia.partes,
                coincidencia.partes,
                coincidencia.textoFinal.length
            )
            contenedor.addView(fila.root)
        }
    }

    private fun pintarAvisos(prueba: PruebaReglas) {
        if (prueba.avisos.isEmpty()) {
            binding.cardAvisos.visibility = View.GONE
            return
        }
        binding.cardAvisos.visibility = View.VISIBLE
        binding.textViewAvisos.text = prueba.avisos.joinToString("\n\n") { "• $it" }
    }

    private fun observarEnvio() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.envio.collect { estado ->
                    val progreso = binding.progressEnvio
                    val resultado = binding.textViewResultadoEnvio

                    when (estado) {
                        EstadoEnvioPrueba.Inactivo -> {
                            progreso.visibility = View.GONE
                            resultado.visibility = View.GONE
                            binding.buttonEnviarPrueba.isEnabled =
                                viewModel.prueba.value?.hayCoincidencias == true
                        }
                        EstadoEnvioPrueba.Enviando -> {
                            progreso.visibility = View.VISIBLE
                            resultado.visibility = View.GONE
                            binding.buttonEnviarPrueba.isEnabled = false
                        }
                        is EstadoEnvioPrueba.Correcto -> {
                            progreso.visibility = View.GONE
                            resultado.visibility = View.VISIBLE
                            binding.buttonEnviarPrueba.isEnabled = true
                            resultado.text = getString(
                                R.string.probar_envio_correcto, estado.destino, estado.partes
                            )
                            resultado.setTextColor(Color.parseColor("#186A2E"))
                            Snackbar.make(
                                binding.root,
                                getString(R.string.probar_envio_correcto_breve, estado.destino),
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                        is EstadoEnvioPrueba.Fallido -> {
                            progreso.visibility = View.GONE
                            resultado.visibility = View.VISIBLE
                            binding.buttonEnviarPrueba.isEnabled = true
                            resultado.text =
                                getString(R.string.probar_envio_fallido, estado.motivo)
                            resultado.setTextColor(Color.parseColor("#BA1A1A"))
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val EJEMPLO_TELEFONO = "+34600112233"
        const val EJEMPLO_MENSAJE = "Tu codigo de verificacion es 4821"
    }
}
