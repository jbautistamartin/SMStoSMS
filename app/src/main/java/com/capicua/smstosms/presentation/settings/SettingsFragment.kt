// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.settings

import android.graphics.Color
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
import com.capicua.smstosms.data.config.AppConfig
import com.capicua.smstosms.databinding.FragmentSettingsBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    private var cargaInicial = true

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

        observarConfiguracion()
        observarTestEstado()
        configurarBotonGuardar()
        configurarBotonProbar()

        binding.editTextUrlTemplate.doAfterTextChanged { viewModel.resetearTest() }
    }

    private fun observarConfiguracion() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.config.collect { config ->
                    // config es null mientras DataStore no ha emitido su primer valor real;
                    // esperamos a ese primer valor antes de rellenar los campos.
                    if (config != null && cargaInicial) {
                        rellenarCampos(config)
                        cargaInicial = false
                    }
                }
            }
        }
    }

    private fun observarTestEstado() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.testEstado.collect { estado ->
                    actualizarUiTest(estado)
                }
            }
        }
    }

    private fun actualizarUiTest(estado: TestConexionEstado) {
        val progress  = binding.progressTest
        val resultado = binding.textTestResultado

        when (estado) {
            TestConexionEstado.Inactivo -> {
                progress.visibility  = View.GONE
                resultado.visibility = View.GONE
            }
            TestConexionEstado.Cargando -> {
                progress.visibility  = View.VISIBLE
                resultado.visibility = View.GONE
                binding.buttonProbar.isEnabled = false
            }
            is TestConexionEstado.Respuesta -> {
                progress.visibility  = View.GONE
                resultado.visibility = View.VISIBLE
                binding.buttonProbar.isEnabled = true
                val ok = estado.codigoHttp in 200..299
                resultado.text = if (ok) "✓ HTTP ${estado.codigoHttp}" else "✗ HTTP ${estado.codigoHttp}"
                resultado.setTextColor(if (ok) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
            }
            is TestConexionEstado.ErrorRed -> {
                progress.visibility  = View.GONE
                resultado.visibility = View.VISIBLE
                binding.buttonProbar.isEnabled = true
                resultado.text = "✗ ${estado.detalle}"
                resultado.setTextColor(Color.parseColor("#F44336"))
            }
        }
    }

    private fun rellenarCampos(config: AppConfig) {
        binding.editTextUrlTemplate.setText(config.urlTemplate)
        binding.switchAceptarCertsInvalidos.isChecked = config.aceptarCertificadosInvalidos
        binding.editTextTimeout.setText(config.timeoutSegundos.toString())
        binding.editTextMaxReintentos.setText(config.maxReintentos.toString())
        binding.editTextIntervalo.setText(config.intervaloReintentoSegundos.toString())
    }

    private fun configurarBotonProbar() {
        binding.buttonProbar.setOnClickListener {
            val urlTemplate  = binding.editTextUrlTemplate.text?.toString()?.trim() ?: ""
            val aceptarCerts = binding.switchAceptarCertsInvalidos.isChecked
            val timeout      = binding.editTextTimeout.text?.toString()?.toIntOrNull() ?: 30

            if (urlTemplate.isBlank() || !urlTemplate.contains("{mensaje}")) {
                Snackbar.make(binding.root, "Configura una URL válida con {mensaje} antes de probar", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.probarConexion(
                AppConfig(urlTemplate = urlTemplate, aceptarCertificadosInvalidos = aceptarCerts, timeoutSegundos = timeout)
            )
        }
    }

    private fun configurarBotonGuardar() {
        binding.buttonGuardar.setOnClickListener {
            val urlTemplate  = binding.editTextUrlTemplate.text?.toString()?.trim() ?: ""
            val aceptarCerts = binding.switchAceptarCertsInvalidos.isChecked
            val timeout      = binding.editTextTimeout.text?.toString()?.toIntOrNull() ?: 30
            val maxRetries   = binding.editTextMaxReintentos.text?.toString()?.toIntOrNull() ?: 10
            val intervalo    = binding.editTextIntervalo.text?.toString()?.toIntOrNull() ?: 30

            if (!validar(urlTemplate, timeout, maxRetries, intervalo)) return@setOnClickListener

            viewModel.guardar(AppConfig(urlTemplate = urlTemplate, aceptarCertificadosInvalidos = aceptarCerts, timeoutSegundos = timeout, maxReintentos = maxRetries, intervaloReintentoSegundos = intervalo))
            Snackbar.make(binding.root, "Configuración guardada", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun validar(urlTemplate: String, timeout: Int, maxRetries: Int, intervalo: Int): Boolean {
        var valido = true

        if (urlTemplate.isBlank()) {
            binding.tilUrlTemplate.error = "La plantilla de URL es obligatoria"
            valido = false
        } else if (!urlTemplate.startsWith("http://") && !urlTemplate.startsWith("https://")) {
            binding.tilUrlTemplate.error = "Debe comenzar con http:// o https://"
            valido = false
        } else if (!urlTemplate.contains("{mensaje}")) {
            binding.tilUrlTemplate.error = "Debe contener el marcador {mensaje}"
            valido = false
        } else {
            binding.tilUrlTemplate.error = null
        }

        if (timeout < 5 || timeout > 120) {
            binding.tilTimeout.error = "Debe estar entre 5 y 120 segundos"
            valido = false
        } else binding.tilTimeout.error = null

        if (maxRetries < 1 || maxRetries > 100) {
            binding.tilMaxReintentos.error = "Debe estar entre 1 y 100"
            valido = false
        } else binding.tilMaxReintentos.error = null

        if (intervalo < 5 || intervalo > 3600) {
            binding.tilIntervalo.error = "Debe estar entre 5 y 3600 segundos"
            valido = false
        } else binding.tilIntervalo.error = null

        return valido
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
