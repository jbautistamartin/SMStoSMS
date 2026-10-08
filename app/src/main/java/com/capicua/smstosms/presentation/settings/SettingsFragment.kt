// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.ArrayAdapter
import com.capicua.smstosms.R
import com.capicua.smstosms.data.config.AppConfig
import com.capicua.smstosms.data.sms.SimDisponible
import com.capicua.smstosms.databinding.FragmentSettingsBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Ajustes generales: protecciones y política de reintentos.
 *
 * Las reglas de reenvío no se editan aquí: son una lista ordenada y tienen su propia pantalla.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    private var cargaInicial = true

    /** SIM activas del dispositivo. Vacía o de un elemento significa que no hay que elegir. */
    private var sims: List<SimDisponible> = emptyList()

    /** SIM seleccionada en el formulario. */
    private var simElegida = AppConfig.SIM_POR_DEFECTO

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configurarSelectorSim()
        observarConfiguracion()
        configurarBotonGuardar()
    }

    /**
     * Monta el selector de SIM, pero solo si hay más de una.
     *
     * Con una sola tarjeta el selector no decidiría nada y añadiría una pregunta que el
     * operador no tiene que responder, así que la sección entera se oculta.
     */
    private fun configurarSelectorSim() {
        sims = viewModel.simsDisponibles
        if (sims.size < 2) {
            binding.layoutSim.visibility = GONE
            return
        }

        binding.layoutSim.visibility = VISIBLE
        val etiquetas = buildList {
            add(getString(R.string.settings_sim_por_defecto))
            addAll(sims.map { it.etiqueta })
        }
        binding.dropdownSim.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, etiquetas)
        )
        binding.dropdownSim.setOnItemClickListener { _, _, posicion, _ ->
            simElegida = if (posicion == 0) AppConfig.SIM_POR_DEFECTO else sims[posicion - 1].subscriptionId
        }
    }

    private fun observarConfiguracion() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.config.collect { config ->
                    // config es null mientras DataStore no ha emitido su primer valor real;
                    // esperamos a ese valor antes de rellenar los campos.
                    if (config != null && cargaInicial) {
                        rellenarCampos(config)
                        cargaInicial = false
                    }
                }
            }
        }
    }

    private fun rellenarCampos(config: AppConfig) {
        simElegida = config.subscriptionId
        if (sims.size >= 2) {
            val indice = sims.indexOfFirst { it.subscriptionId == config.subscriptionId }
            binding.dropdownSim.setText(
                if (indice >= 0) sims[indice].etiqueta else getString(R.string.settings_sim_por_defecto),
                false
            )
        }
        binding.switchProtegerBucles.isChecked = config.protegerBucles
        binding.editTextMaxReenviosMinuto.setText(config.maxReenviosPorMinuto.toString())
        binding.editTextTimeoutEnvio.setText(config.timeoutEnvioSegundos.toString())
        binding.editTextMaxReintentos.setText(config.maxReintentos.toString())
        binding.editTextIntervalo.setText(config.intervaloReintentoSegundos.toString())
    }

    private fun configurarBotonGuardar() {
        binding.buttonGuardar.setOnClickListener {
            val maxPorMinuto = binding.editTextMaxReenviosMinuto.entero(VALORES.maxReenviosPorMinuto)
            val timeoutEnvio = binding.editTextTimeoutEnvio.entero(VALORES.timeoutEnvioSegundos)
            val maxReintentos = binding.editTextMaxReintentos.entero(VALORES.maxReintentos)
            val intervalo = binding.editTextIntervalo.entero(VALORES.intervaloReintentoSegundos)

            if (!validar(maxPorMinuto, timeoutEnvio, maxReintentos, intervalo)) {
                return@setOnClickListener
            }

            viewModel.guardar(
                AppConfig(
                    maxReintentos = maxReintentos,
                    intervaloReintentoSegundos = intervalo,
                    timeoutEnvioSegundos = timeoutEnvio,
                    maxReenviosPorMinuto = maxPorMinuto,
                    protegerBucles = binding.switchProtegerBucles.isChecked,
                    subscriptionId = simElegida
                )
            )
            Snackbar.make(binding.root, R.string.settings_guardado, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun validar(
        maxPorMinuto: Int,
        timeoutEnvio: Int,
        maxReintentos: Int,
        intervalo: Int
    ): Boolean {
        var valido = true

        valido = comprobarRango(
            binding.tilMaxReenviosMinuto, maxPorMinuto, 1, 100,
            getString(R.string.settings_error_rango, 1, 100)
        ) && valido

        valido = comprobarRango(
            binding.tilTimeoutEnvio, timeoutEnvio, 10, 300,
            getString(R.string.settings_error_rango_segundos, 10, 300)
        ) && valido

        valido = comprobarRango(
            binding.tilMaxReintentos, maxReintentos, 1, 100,
            getString(R.string.settings_error_rango, 1, 100)
        ) && valido

        valido = comprobarRango(
            binding.tilIntervalo, intervalo, 5, 3600,
            getString(R.string.settings_error_rango_segundos, 5, 3600)
        ) && valido

        return valido
    }

    private fun comprobarRango(
        campo: com.google.android.material.textfield.TextInputLayout,
        valor: Int,
        minimo: Int,
        maximo: Int,
        mensaje: String
    ): Boolean = if (valor < minimo || valor > maximo) {
        campo.error = mensaje
        false
    } else {
        campo.error = null
        true
    }

    private fun com.google.android.material.textfield.TextInputEditText.entero(porDefecto: Int): Int =
        text?.toString()?.trim()?.toIntOrNull() ?: porDefecto

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Valores por defecto, para no repetirlos cuando un campo se queda vacío. */
        val VALORES = AppConfig()
    }
}
