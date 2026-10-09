// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.capicua.smstosms.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * Activity principal y única de la aplicación.
 *
 * Responsabilidades:
 * 1. Configurar la navegación por fragmentos (Bottom Navigation + NavController).
 * 2. Repartir los insets del sistema, porque con `targetSdk 35` Android 15 dibuja la ventana
 *    de borde a borde y no hay forma de desactivarlo.
 * 3. Solicitar los permisos en tiempo de ejecución necesarios para recibir y reenviar SMS.
 *
 * En un dispositivo dedicado estos permisos se pueden pre-conceder vía MDM o ADB:
 *   adb shell pm grant com.capicua.smstosms android.permission.RECEIVE_SMS
 *   adb shell pm grant com.capicua.smstosms android.permission.READ_SMS
 *   adb shell pm grant com.capicua.smstosms android.permission.SEND_SMS
 *   adb shell pm grant com.capicua.smstosms android.permission.READ_PHONE_STATE
 *   adb shell pm grant com.capicua.smstosms android.permission.POST_NOTIFICATIONS
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── Launcher de permisos ──────────────────────────────────────────────────

    private val solicitarPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados ->
        resultados.forEach { (permiso, concedido) ->
            Timber.d("Permiso '$permiso': ${if (concedido) "CONCEDIDO" else "DENEGADO"}")
        }

        // RECEIVE_SMS y SEND_SMS son los dos imprescindibles: sin el primero no entra nada
        // y sin el segundo no sale nada. READ_PHONE_STATE solo sirve para elegir SIM.
        val faltaAlgunoEsencial = resultados[Manifest.permission.RECEIVE_SMS] == false ||
            resultados[Manifest.permission.SEND_SMS] == false
        if (faltaAlgunoEsencial) {
            mostrarDialogoPermisoObligatorio()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarInsets()
        configurarIconosDeBarras()
        configurarNavegacion()
        verificarYSolicitarPermisos()
    }

    // ── Insets ────────────────────────────────────────────────────────────────

    /**
     * Reparte los insets del sistema entre la raíz y la barra inferior.
     *
     * Con `targetSdk 35` Android 15 ignora `fitsSystemWindows` y dibuja siempre de borde a
     * borde: sin esto, el recorte de la cámara se come la cabecera de cada pantalla y la barra
     * de navegación del sistema queda **encima** del Bottom Navigation, que deja de poder
     * pulsarse. Eso es lo que hacía que no se pudiera salir de las pantallas que no están en
     * la barra inferior.
     *
     * Se usa `systemBars or displayCutout` porque en horizontal el recorte cae en un lateral,
     * donde no hay ninguna barra de sistema que lo cubra.
     */
    private fun configurarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { raiz, insets ->
            val barras = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            raiz.updatePadding(left = barras.left, top = barras.top, right = barras.right)
            // El padding inferior va en la barra, no en la raíz: así el fondo de la barra sigue
            // llegando hasta el borde de la pantalla y solo su contenido se aparta.
            binding.bottomNavigation.updatePadding(bottom = barras.bottom)
            insets
        }
    }

    /**
     * Pide iconos oscuros en las barras del sistema cuando el tema es claro.
     *
     * Es la otra mitad del borde a borde: al dibujar **bajo** la barra de estado, el fondo que
     * hay detrás de la hora y los iconos de señal pasa a ser el de la aplicación, y el sistema
     * por sí solo mantiene los iconos claros. Sobre el fondo casi blanco del tema eso deja la
     * barra de estado ilegible, que es justo como se veía en el primer teléfono.
     *
     * Se consulta el modo noche en lugar de fijarlo a `true` porque el tema es `DayNight`: si
     * algún día el fondo se vuelve oscuro, los iconos tienen que volver a ser claros solos.
     */
    private fun configurarIconosDeBarras() {
        val esModoNoche = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !esModoNoche
            isAppearanceLightNavigationBars = !esModoNoche
        }
    }

    // ── Navegación ────────────────────────────────────────────────────────────

    private fun configurarNavegacion() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)
    }

    // ── Permisos ──────────────────────────────────────────────────────────────

    private fun verificarYSolicitarPermisos() {
        val permisosPendientes = permisosNecesarios().filter { permiso ->
            ContextCompat.checkSelfPermission(this, permiso) != PackageManager.PERMISSION_GRANTED
        }

        if (permisosPendientes.isEmpty()) {
            Timber.d("Todos los permisos ya concedidos")
            return
        }

        Timber.d("Solicitando ${permisosPendientes.size} permiso(s): $permisosPendientes")
        solicitarPermisos.launch(permisosPendientes.toTypedArray())
    }

    /**
     * Lista de permisos en tiempo de ejecución que la aplicación necesita.
     * POST_NOTIFICATIONS solo es obligatorio en Android 13+ (API 33).
     */
    private fun permisosNecesarios(): List<String> = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun mostrarDialogoPermisoObligatorio() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permiso_sms_titulo)
            .setMessage(R.string.permiso_sms_mensaje)
            .setPositiveButton(R.string.permiso_entendido, null)
            .show()
    }
}