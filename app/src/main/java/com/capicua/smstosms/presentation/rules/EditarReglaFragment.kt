// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.rules

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.capicua.smstosms.R
import com.capicua.smstosms.databinding.FragmentEditarReglaBinding
import com.capicua.smstosms.domain.model.Regla
import com.capicua.smstosms.domain.rules.EvaluadorDeReglas
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Alta y edición de una regla.
 *
 * Las expresiones regulares se validan **mientras se teclean**: una regla con un patrón roto se
 * descarta en silencio durante la evaluación, así que el único momento razonable para avisar es
 * aquí. La previsualización de la plantilla cumple la misma función con el texto de salida, e
 * incluye el número de partes porque cada parte de un SMS se tarifica aparte.
 */
@AndroidEntryPoint
class EditarReglaFragment : Fragment() {

    private var _binding: FragmentEditarReglaBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EditarReglaViewModel by viewModels()
    private val args: EditarReglaFragmentArgs by navArgs()

    private var cargaInicial = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditarReglaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonVolver.setOnClickListener { findNavController().navigateUp() }

        val esNueva = args.reglaId == EditarReglaViewModel.NUEVA
        binding.textViewTitulo.setText(
            if (esNueva) R.string.regla_titulo_nueva else R.string.regla_titulo_editar
        )

        if (esNueva) {
            // Una regla nueva arranca con la plantilla por defecto y activa.
            binding.editTextPlantilla.setText(Regla.PLANTILLA_POR_DEFECTO)
            binding.switchActiva.isChecked = true
            cargaInicial = false
        }

        viewModel.cargar(args.reglaId)

        configurarValidacionEnVivo()
        configurarAtajoRemitente()
        configurarBotonGuardar()
        observarRegla()
        observarErrores()
        observarGuardado()

        actualizarPrevisualizacion()
    }

    /**
     * Rellena el destino con el marcador que contesta al remitente.
     *
     * El marcador se podría teclear a mano, pero solo si se sabe que existe; el botón es lo que
     * convierte «devolver el SMS a quien lo mandó» en algo que se encuentra sin leer la
     * documentación.
     */
    private fun configurarAtajoRemitente() {
        binding.buttonDestinoRemitente.setOnClickListener {
            binding.editTextDestino.setText(EvaluadorDeReglas.MARCADOR_TELEFONO)
            binding.tilDestino.error = null
        }
    }

    // ── Validación en vivo ────────────────────────────────────────────────────

    private fun configurarValidacionEnVivo() {
        binding.editTextRegexTelefono.doAfterTextChanged { texto ->
            binding.tilRegexTelefono.error = viewModel.validarPatron(texto?.toString())
        }
        binding.editTextRegexMensaje.doAfterTextChanged { texto ->
            binding.tilRegexMensaje.error = viewModel.validarPatron(texto?.toString())
        }
        binding.editTextNombre.doAfterTextChanged { binding.tilNombre.error = null }
        binding.editTextDestino.doAfterTextChanged { binding.tilDestino.error = null }
        binding.editTextPlantilla.doAfterTextChanged {
            binding.tilPlantilla.error = null
            actualizarPrevisualizacion()
        }
    }

    /**
     * Pinta cómo quedaría el texto reenviado, usando un SMS de ejemplo fijo.
     *
     * El ejemplo es siempre el mismo a propósito: lo que se quiere ver es el efecto de la
     * plantilla, no el de unos datos cambiantes.
     */
    private fun actualizarPrevisualizacion() {
        val plantilla = binding.editTextPlantilla.text?.toString().orEmpty()
        if (plantilla.isBlank()) {
            binding.textViewPrevisualizacion.text = getString(R.string.regla_previsualizacion_vacia)
            binding.textViewPartes.text = ""
            return
        }

        val texto = viewModel.previsualizar(
            plantilla = plantilla,
            telefono = EJEMPLO_TELEFONO,
            mensaje = EJEMPLO_MENSAJE,
            fecha = EJEMPLO_FECHA
        )
        binding.textViewPrevisualizacion.text = texto

        val partes = viewModel.contarPartes(texto)
        binding.textViewPartes.text = resources.getQuantityString(
            R.plurals.regla_partes, partes, partes, texto.length
        )
        binding.textViewPartes.setTextColor(
            resources.getColor(
                if (partes > 1) R.color.status_pending else R.color.status_delivered,
                null
            )
        )
    }

    // ── Observación ───────────────────────────────────────────────────────────

    private fun observarRegla() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.regla.collect { regla ->
                    if (regla != null && cargaInicial) {
                        rellenarCampos(regla)
                        cargaInicial = false
                    }
                }
            }
        }
    }

    private fun observarErrores() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.errores.collect { errores ->
                    binding.tilNombre.error = errores.nombre
                    binding.tilRegexTelefono.error = errores.regexTelefono
                    binding.tilRegexMensaje.error = errores.regexMensaje
                    binding.tilDestino.error = errores.destino
                    binding.tilPlantilla.error = errores.plantilla
                }
            }
        }
    }

    private fun observarGuardado() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.guardada.collect { guardada ->
                    if (guardada) findNavController().navigateUp()
                }
            }
        }
    }

    private fun rellenarCampos(regla: Regla) {
        binding.editTextNombre.setText(regla.nombre)
        binding.editTextRegexTelefono.setText(regla.regexTelefono.orEmpty())
        binding.editTextRegexMensaje.setText(regla.regexMensaje.orEmpty())
        binding.switchIgnorarMayusculas.isChecked = regla.ignorarMayusculas
        binding.editTextDestino.setText(regla.destino)
        binding.editTextPlantilla.setText(regla.plantilla)
        binding.switchActiva.isChecked = regla.activa
        binding.switchContinuar.isChecked = regla.continuar
        actualizarPrevisualizacion()
    }

    // ── Guardado ──────────────────────────────────────────────────────────────

    private fun configurarBotonGuardar() {
        binding.buttonGuardarRegla.setOnClickListener {
            viewModel.guardar(args.reglaId, construirBorrador())
        }
    }

    private fun construirBorrador() = Regla(
        id = args.reglaId,
        // El orden real lo asigna el repositorio al crear, o se conserva al actualizar.
        orden = 0,
        nombre = binding.editTextNombre.text?.toString()?.trim().orEmpty(),
        regexTelefono = binding.editTextRegexTelefono.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() },
        regexMensaje = binding.editTextRegexMensaje.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() },
        ignorarMayusculas = binding.switchIgnorarMayusculas.isChecked,
        destino = binding.editTextDestino.text?.toString()?.trim().orEmpty(),
        plantilla = binding.editTextPlantilla.text?.toString()?.trim().orEmpty(),
        activa = binding.switchActiva.isChecked,
        continuar = binding.switchContinuar.isChecked
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val EJEMPLO_TELEFONO = "+34600112233"
        const val EJEMPLO_MENSAJE = "Tu codigo de verificacion es 4821"
        const val EJEMPLO_FECHA = "07/10/2026 19:30:00"
    }
}
