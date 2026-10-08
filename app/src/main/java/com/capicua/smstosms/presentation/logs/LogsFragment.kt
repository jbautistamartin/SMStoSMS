// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.logs

import android.content.Intent
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
import com.capicua.smstosms.databinding.FragmentLogsBinding
import com.capicua.smstosms.domain.model.LogTipo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LogsViewModel by viewModels()
    private lateinit var adapter: LogsAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = LogsAdapter()
        binding.recyclerViewLogs.adapter = adapter

        configurarChips()
        configurarExportar()
        configurarLimpiar()
        observarLogs()
    }

    private fun configurarChips() {
        binding.chipTodos.isChecked = true

        binding.chipTodos.setOnClickListener         { viewModel.aplicarFiltro(null) }
        binding.chipRecibidos.setOnClickListener     { viewModel.aplicarFiltro(LogTipo.SMS_RECIBIDO) }
        binding.chipEnviados.setOnClickListener      { viewModel.aplicarFiltro(LogTipo.SMS_ENVIADO) }
        binding.chipErrores.setOnClickListener       { viewModel.aplicarFiltro(LogTipo.ERROR) }
        binding.chipSistema.setOnClickListener       { viewModel.aplicarFiltro(LogTipo.SISTEMA) }
    }

    private fun configurarLimpiar() {
        binding.fabLimpiarLogs.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Limpiar logs")
                .setMessage("Se eliminarán todas las entradas de log. Esta acción no se puede deshacer.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpiar") { _, _ -> viewModel.limpiarTodos() }
                .show()
        }
    }

    private fun configurarExportar() {
        binding.fabExportar.setOnClickListener {
            viewModel.exportarLogs { uri ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.logs_exportar)))
            }
        }
    }

    private fun observarLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.logs.collect { logs ->
                        adapter.submitList(logs)
                        binding.textViewSinLogs.visibility =
                            if (logs.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.totalLogs.collect { total ->
                        binding.textViewTotal.text =
                            resources.getQuantityString(R.plurals.logs_total_entradas, total, total)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}