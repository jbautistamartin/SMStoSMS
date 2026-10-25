# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Toda la documentación de este proyecto está en español, igual que los comentarios y el KDoc
> del código. La línea de arriba se conserva en inglés porque es la cabecera estándar que
> identifica el fichero.

## Comandos de compilación y ejecución

> **Toolchain:** el código se compila para JVM 17 con AGP 9.4.1 sobre Gradle 9.8.2, y Gradle se
> ejecuta con **Temurin 25**: `gradle/gradle-daemon-jvm.properties` lo exige, así que vale el JDK
> de `JAVA_HOME` o el que elija Android Studio, siempre que sea el 25. Hace falta el SDK con la
> **plataforma 37** (`compileSdk 37`: `core-ktx` 1.19 no compila contra una inferior).
> `local.properties` no se versiona; si falta, define `ANDROID_HOME` en su lugar.

```bash
# Compilación debug
./gradlew assembleDebug

# Instalar en el dispositivo conectado
./gradlew installDebug

# Compilación release (requiere las siguientes claves en local.properties):
#   KEYSTORE_PATH=<ruta al almacén, absoluta o relativa al módulo app/>
#   KEYSTORE_PASSWORD=<contraseña del almacén>
#   KEY_ALIAS=<alias de la clave>
#   KEY_PASSWORD=<contraseña de la clave>
# El almacén está fuera del repositorio y se comparte con las demás aplicaciones de
# la casa, así que KEYSTORE_PATH va relativa (`../../Claves/capicua.jks`): el file()
# de app/build.gradle.kts la resuelve desde el módulo, no desde la raíz.
# Sin las cuatro claves el build NO falla: el APK sale sin firmar.
# El APK resultante se genera como smstosms-<versionName>.apk
./gradlew assembleRelease

# Tests unitarios
./gradlew test

# Una sola clase de test
./gradlew test --tests "com.capicua.smstosms.domain.rules.EvaluadorDeReglasTest"

# Conceder permisos en tiempo de ejecución en un dispositivo dedicado (tras instalar)
adb shell pm grant com.capicua.smstosms android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smstosms android.permission.READ_SMS
adb shell pm grant com.capicua.smstosms android.permission.SEND_SMS
adb shell pm grant com.capicua.smstosms android.permission.READ_PHONE_STATE
adb shell pm grant com.capicua.smstosms android.permission.POST_NOTIFICATIONS

# Eximir la app del modo Doze / optimización de batería (OBLIGATORIO para un despacho fiable)
# Sin esto, WorkManager puede posponerse indefinidamente con la pantalla apagada.
# La alternativa por interfaz está en la sección «Preparación del dispositivo», más abajo.
adb shell dumpsys deviceidle whitelist +com.capicua.smstosms

# Verificar que la app está en la lista de exentas
adb shell dumpsys deviceidle whitelist
# Se espera: una línea que contenga "com.capicua.smstosms"

# Quitarla de la lista (si hiciera falta)
adb shell dumpsys deviceidle whitelist -com.capicua.smstosms

# Seguimiento del log en tiempo real
adb logcat -s "SmsReceiver" "SmsIngestionService" "SmsSender" "SmsDispatchWorker" "OrphanRescueWorker" "HealthMonitorWorker"

# Simular un SMS entrante en un emulador (no hace falta SIM)
adb emu sms send +34600112233 "Tu codigo es 4821"
```

## Publicar una versión

1. Sube `versionCode` y `versionName` en `app/build.gradle.kts` (van juntos: Android se niega a
   instalar un APK cuyo `versionCode` no sea mayor que el instalado).
2. **Escribe la entrada en `CHANGELOG.md`**: lo que está bajo `[No publicado]` pasa a ser la
   versión nueva con su fecha. Ese fichero es el resumen de *qué* ha cambiado; las notas del
   release de GitHub son la versión larga —cómo instalarlo, cómo actualizar desde la anterior— y
   pueden repetirlo.
3. `./gradlew assembleRelease` → `app/build/outputs/apk/release/smstosms-<versionName>.apk`,
   firmado con el almacén compartido (ver los comandos de compilación, más arriba).
4. Etiqueta sobre `main` y publica: `gh release create vX.Y.Z --notes-file <notas> <apk>`.

El APK mantiene la misma clave de firma versión tras versión. Es lo que permite al usuario
instalar la nueva encima sin perder reglas, ajustes ni historial — y también la razón de que un
APK con *otra* clave (una compilación debug, por ejemplo) no se pueda actualizar en el sitio.

## Arquitectura

Clean Architecture con separación estricta en tres capas y MVVM en la capa de presentación.
Inyección de dependencias con Hilt en todo el proyecto.

```
Presentación  (Fragments + ViewModels)
    ↓ usa
Dominio       (UseCases + motor de reglas + modelos de Kotlin puro)
    ↓ usa interfaces, implementadas por
Datos         (Room · SmsManager · DataStore · WorkManager)
```

### Ciclo de vida de un SMS

```
SIM → SmsReceiver.onReceive()           ← BroadcastReceiver (hilo principal, < 10 s)
        ↓ startForegroundService()
      SmsIngestionService               ← ForegroundService (debe llamar a startForeground() < 5 s)
        ↓ ProcesarSmsEntranteUseCase
      INSERT en Room (SmsEntity)        ← outbox: persiste ANTES de hacer nada más
        ↓ protecciones de bucle y de ritmo
        ↓ EvaluadorDeReglas
      INSERT en Room (ReenvioEntity × N) ← una fila por destino
        ↓ ColaDeEnvios.encolar()
      SmsDispatchWorker.doWork()        ← un worker por reenvío, con backoff lineal
        ↓ SmsSender → SmsManager.sendMultipartTextMessage()
      broadcast del sentIntent → actualizar Room
```

`OrphanRescueWorker` se reencadena cada 30 s y rescata **dos** cosas: SMS que se quedaron en
`PENDIENTE` cuya evaluación de reglas nunca terminó, y reenvíos en `PENDIENTE` sin tarea viva de
WorkManager.

`HealthMonitorWorker` se ejecuta cada 15 minutos y purga los reenvíos confirmados, los SMS ya
atendidos (de más de `RETENTION_DELIVERED_DAYS`, hoy **30 días**) y las entradas de log antiguas
(de más de `RETENTION_LOGS_DAYS`, también 30 días).

`BootReceiver` reactiva las tareas de WorkManager tras reiniciar el dispositivo.

### Mecanismo de reenvío

No hay HTTP. Quien opera la app configura una lista ordenada de **reglas** en la pantalla de
Reglas; cada regla decide, para un SMS entrante dado, qué número de teléfono lo recibe y con qué
texto.

Una regla casa cuando se cumplen **los dos** criterios. Un criterio vacío se cumple siempre, así
que una regla con ambos vacíos casa con todos los SMS:

- `regexTelefono` — se aplica al remitente **tal como lo entrega la PDU**, sin normalizar. Puede
  ser alfanumérico (`BANCO`, `AMAZON`).
- `regexMensaje` — se aplica al cuerpo completo del SMS.

La coincidencia es **parcial** (`Regex.containsMatchIn`): `codigo` casa con "Tu codigo es 4821".
Ancla con `^…$` para exigir el texto entero. Los patrones distinguen mayúsculas salvo que la regla
active `ignorarMayusculas`, que aplica `RegexOption.IGNORE_CASE` a **las dos** expresiones a la
vez; un `(?i)` en línea sigue funcionando y los dos son acumulativos, no incompatibles.

Las reglas se evalúan por `orden` ascendente. La primera que casa gana y la evaluación se detiene,
**salvo** que la regla tenga `continuar = true`, en cuyo caso la evaluación sigue y el mismo SMS
puede llegar a varios destinos.

El `destino` de la regla es normalmente un número fijo, pero también admite el marcador
`{telefono}`, que resuelve al **remitente del SMS entrante**. Así se contesta a quien escribió. Es
el único marcador que se resuelve en el destino —`{mensaje}` y `{fecha}` se dejan intactos ahí—,
de modo que el cuerpo del SMS (contenido de terceros) nunca puede influir en adónde se envía nada.
El editor de reglas lo ofrece como botón («Contestar al remitente»), porque un marcador que nadie
conoce no es una funcionalidad.

El texto que sale se compone con la `plantilla` de la regla, con tres marcadores opcionales:

- `{mensaje}` — el cuerpo del SMS recibido
- `{telefono}` — el número del remitente
- `{fecha}` — fecha de recepción, formateada para mostrarse en hora **local** (no ISO UTC: la lee
  una persona en su teléfono)

`{mensaje}` se sustituye **el último** a propósito, para que el cuerpo del SMS —contenido de
terceros— no se vuelva a escanear y no pueda inyectar marcadores.

### Protecciones de bucle y de coste

Reenviar SMS cuesta dinero y puede realimentarse, cosa que con HTTP no pasaba. Tres guardas, las
dos primeras controladas por `AppConfig.protegerBucles`:

1. Un SMS cuyo **remitente sea uno de los destinos configurados** se descarta: ha vuelto de un
   número al que reenviamos.
2. Una regla que casa cuyo **destino sea igual al remitente** se salta — *salvo* que la regla haya
   declarado `{telefono}` como destino. Escribir ese marcador es una petición explícita de
   contestar al remitente, así que lo que la guarda 2 bloquea es la circularidad accidental, no la
   configurada. Una regla de contestar al remitente tiene por tanto solo el límite de ritmo
   detrás: mantén su `regexMensaje` específica si el otro extremo también pudiera autorresponder.
3. `AppConfig.maxReenviosPorMinuto` limita cuántos reenvíos pueden crearse por minuto. El exceso se
   descarta y se registra. Esta está siempre activa.

Una cuarta comprobación se aplica solo a las reglas de contestar al remitente: si el remitente es
una cabecera alfanumérica (`BANCO`, `AMAZON`), no puede recibir un SMS, así que el reenvío se salta
y se registra como `ERROR` en lugar de intentarse y reintentarse.
`NormalizadorTelefono.esDestinoEnviable()` es quien lo decide.

La comparación de números pasa por `NormalizadorTelefono`, que quita todo lo que no sea dígito y
compara los últimos 9, de modo que `+34600112233` y `600112233` se reconocen como el mismo abonado.
Los remitentes alfanuméricos nunca son iguales a nada: no se les puede reenviar, así que no pueden
formar bucles.

### Navegación por pestañas

Las cinco pantallas principales —Panel, Reglas, Logs, Ajustes y Acerca de— son **páginas de un
`ViewPager2`**, no destinos del grafo de navegación, así que a cada una se llega tanto tocando la
Bottom Navigation como deslizando a izquierda o derecha.

`nav_graph.xml` contiene por tanto solo tres destinos: `homeFragment`, que hospeda el pager, más
las dos subpantallas de reglas `editarReglaFragment` y `probarReglasFragment`. Las dos acciones
cuelgan de `homeFragment`, porque cuando `ReglasFragment` navega, el destino actual del
NavController es el que hospeda el pager y no la lista de reglas. `findNavController()` sigue
resolviendo desde dentro de una página: sube por la cadena de fragments padre.

`Pestana` (`presentation/home/Pestana.kt`) es la única fuente de verdad que ata tres cosas que
antes tenían que coincidir a mano: la posición en el pager, el id del menú de Bottom Navigation y
el Fragment. El orden de declaración **es** el orden de las páginas, y `bottom_nav_menu.xml` tiene
que listar sus elementos en ese mismo orden o la barra se lee distinta de como se desliza.

`setupWithNavController` ya no está. La barra es de `MainActivity` y el pager de `HomeFragment`, y
no comparten tiempo de vida —el pager se destruye en cuanto navegas al editor de reglas—, así que
se coordinan a través de `PestanasViewModel`, un `StateFlow` con alcance de actividad que lleva la
pestaña actual. Volver a seleccionar la pestaña que ya está activa no emite nada, y eso es lo que
evita que la sincronización en dos sentidos (barra → pager, deslizamiento → barra) se realimente.

Atrás en cualquier pestaña que no sea Panel vuelve a Panel en lugar de cerrar la app; en Panel el
gesto conserva su significado normal. La pila de retroceso del NavController daba eso gratis
deshaciendo las pestañas visitadas — un pager no tiene pila, así que `HomeFragment` define el
comportamiento de forma explícita.

**El interruptor general de la recepción está en Ajustes**, arriba del todo porque todo lo que hay
debajo es irrelevante mientras esté apagado. `recepcionActiva` se persiste como cualquier otro
ajuste, pero apagarlo hace algo más que guardar un flag: `ControlRecepcion` desactiva el
**componente** `SmsReceiver` a través del `PackageManager`. Un `if` al principio de `onReceive`
despertaría a la app con cada mensaje que llegase al teléfono solo para descartarlo, y obligaría a
una lectura asíncrona de DataStore en el hilo principal dentro del presupuesto de diez segundos del
broadcast. Desactivar el componente significa que el sistema deja de entregar el broadcast: nada
que competir, nada que decidir. Detiene solo el lado **entrante**: lo que ya estuviera en cola se
sigue reenviando, porque descartarlo perdería mensajes que la app ya había aceptado. El sistema
persiste el estado del componente y sobrevive a los reinicios, así que
`SmsToSmsApplication.sincronizarRecepcion()` solo importa después de una **reinstalación**, donde
el componente vuelve al valor del manifiesto y el ajuste guardado tiene que volver a imponerse.

**En «Acerca de» vive el enlace de donación**, y es la única pantalla que lo menciona: una tarjeta
con un botón *Invitar a un café* que abre `https://paypal.me/jbautistamartin` en el navegador
(`URL_DONATIVO`, una const privada de `AboutFragment`). El corazón del botón
(`drawable/ic_corazon.xml`) es el mismo trazado de cuatro béziers y el mismo dorado que el que
SilentRunner dibuja en su menú de bandeja, de modo que el icono se lee igual en toda la casa; lleva
`app:iconTint="@null"`, porque teñirlo con el color del tema tiraría por la borda lo único que lo
identifica. El intent se lanza capturando `ActivityNotFoundException` en lugar de preguntar antes al
`PackageManager` —el mismo razonamiento que en `OptimizacionBateria`: desde Android 11 la
visibilidad de paquetes puede afirmar que no hay navegador cuando sí lo hay—. Si no resuelve nada,
un Snackbar lo dice en vez de dejar un botón muerto. Es opcional de principio a fin: la app no
insiste nunca y ninguna funcionalidad depende de ello.

**La misma pantalla lleva una tarjeta de *Código abierto*** con un botón *Ver el proyecto en
GitHub* (`URL_PROYECTO`, al lado de `URL_DONATIVO` en `AboutFragment`), y va justo encima de la de
donación porque es lo que da contexto a la donación: el código está publicado, y la tarjeta dice
dónde. Su icono (`drawable/ic_codigo.xml`) es el caso contrario al del corazón: no hay color que lo
identifique, así que se deja teñir por el tema. Los dos botones pasan por el mismo `abrirEnlace(url)`
y comparten el Snackbar para un dispositivo sin navegador: esa cadena pasó de llamarse
`about_donativo_sin_navegador` a `about_sin_navegador` cuando llegó el segundo enlace, porque nunca
dijo nada sobre donar.

**«Acerca de» comprueba además si hay versión nueva**, y por eso **`INTERNET` ha vuelto al
manifiesto**. Léelo antes de darlo por un resto olvidado: la app reenvía SMS por la radio del
teléfono y **el contenido de los mensajes no viaja nunca por la red**. El permiso está para que
`data/update/ComprobadorDeActualizaciones` pueda preguntar a `api.github.com` por el último release
y para que `DescargaDeApk` pueda descargar el APK cuando alguien pulsa el botón. Retrofit y OkHttp
**no** han vuelto con él: la petición son una docena de líneas de `HttpURLConnection`, y la
respuesta se parsea con `kotlinx.serialization`, que ya estaba aquí para la exportación de reglas.

Cómo se comporta, y por qué:

- **`MainActivity` pregunta al arrancar** y, si hay una versión más nueva, pone una **marca en la
  pestaña «Acerca de»** — ni diálogo ni notificación. Esto corre en un teléfono que reenvía SMS sin
  que nadie lo mire; cualquier cosa que se ponga delante al arrancar tapa justo lo que venías a ver.
  La tarjeta de «Acerca de» es donde se explica, con el interruptor que apaga la pregunta automática
  y un *Buscar ahora* que ignora el intervalo diario.
- **Una pregunta al día, con `If-None-Match`.** La API sin autenticar permite 60 peticiones por hora
  y por IP, y un 304 no cuenta. La marca de tiempo y el ETag viven en `EstadoActualizacion`, en el
  **mismo DataStore** que los ajustes pero con sus propias claves — deliberadamente **fuera** de
  `AppConfig`: eso es configuración de reenvío, viaja en el fichero de copia de seguridad, y que
  este teléfono quiera avisos de actualización no tiene nada que ver con cómo deba reenviar SMS otro
  teléfono.
- **Lo que se encontró se recuerda, no solo cuándo se preguntó.** `EstadoActualizacion` guarda
  también la etiqueta, la página y el APK del release encontrado, y `comprobar()` lo devuelve desde
  ahí mientras siga ganando a la versión instalada. Sin eso, el aviso vivía solo en el proceso que
  lo descubrió: la marca salía en el primer arranque del día y ya no estaba en el segundo, porque la
  barrera diaria respondía `SinPreguntar` y nada recordaba el hallazgo. Es además la respuesta
  correcta a un **304** —«el último release no ha cambiado» no significa que sea el instalado— y se
  limpia sola, porque una vez instalada la actualización la comparación deja de cumplirse y se
  ejecuta `olvidarNovedad()`.
- **Las versiones se comparan como números** (`VersionPublicada`), nunca como texto: `"1.10.0" <
  "1.9.0"` alfabéticamente, lo que dejaría el aviso mudo a partir del décimo release. Es la única
  pieza de todo esto que puede probarse con tests unitarios sin red, y `VersionPublicadaTest` la
  cubre.
- **Un 404 significa que todavía no hay releases**, no un error. Sin red, no decir nada.
- **Instalar es cosa del sistema.** `DownloadManager` descarga el APK al directorio externo privado
  de la app (sin permiso de almacenamiento) y `REQUEST_INSTALL_PACKAGES` permite abrir el diálogo
  del instalador de Android — las instalaciones silenciosas necesitan ser device owner, y esto no lo
  es. Desde Android 8 el permiso del manifiesto tampoco basta: el usuario tiene que autorizar a
  *esta* app en Ajustes, y eso se comprueba **antes** de descargar tres megas para nada.
- **La firma es el modelo de seguridad.** Android solo actualiza un APK firmado con la misma clave,
  así que nadie puede colar un APK falsificado encima de este y aquí no hay hashes que verificar. La
  otra cara es que no puede instalarse sobre una compilación debug
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), que es por lo que la tarjeta dice que la actualización
  conserva reglas, ajustes e historial.

**Un hijo con desplazamiento horizontal dentro del pager necesita `NestedScrollableHost`**
(`presentation/common/NestedScrollableHost.kt`, la clase de ejemplo de AndroidX adaptada a este
código). Si no, los dos gestos son el mismo gesto y gana el pager: el carrusel de filtros de la
pantalla de Logs dejaría de desplazarse, y arrastrar sobre él cambiaría de pantalla. El host
arbitra: el hijo se queda el gesto mientras le quede recorrido en esa dirección y lo cede en su
borde. `fragment_logs.xml` es hoy el único layout que lo necesita; cualquier nuevo desplazador
horizontal, carrusel o deslizador que se ponga en una pestaña necesita el mismo envoltorio.

### Ficheros clave

| Fichero | Cometido |
|------|------|
| `domain/rules/EvaluadorDeReglas.kt` | Motor de coincidencia, resolución de plantillas, validación de patrones |
| `domain/rules/NormalizadorTelefono.kt` | Comparación de números para las guardas de bucle |
| `domain/usecase/ProcesarSmsEntranteUseCase.kt` | Guardas → evaluar → crear reenvíos → encolar |
| `data/sms/SmsSender.kt` | Envoltorio de `SmsManager` que **espera** el `sentIntent` |
| `data/sms/ProveedorDeSims.kt` | Lista las SIM activas para el selector de doble SIM |
| `worker/ColaDeEnvios.kt` | El único sitio que encola una tarea de despacho |
| `worker/SmsDispatchWorker.kt` | Envía un reenvío; gestiona la lógica de reintento y de fallo |
| `worker/OrphanRescueWorker.kt` | Rescata SMS y reenvíos atascados cada 30 s |
| `data/config/AppConfig.kt` | Reintentos, timeout, límite de ritmo, guardas, SIM |
| `data/rules/ReglasJson.kt` | Formato versionado de exportación e importación de reglas |
| `data/local/db/Migraciones.kt` | Migraciones de Room escritas a mano, registradas por `DatabaseModule` |
| `util/OptimizacionBateria.kt` | Exención de Doze: consulta del estado + el botón de Ajustes |
| `util/InicioAutomatico.kt` | Pantalla de inicio automático del fabricante (MIUI/EMUI/ColorOS/Funtouch) + el botón de Ajustes |
| `util/ControlRecepcion.kt` | Activa y desactiva el componente `SmsReceiver` — el interruptor de recepción |
| `data/config/ConfigBackup.kt` | Exportar e importar la configuración entera: ajustes **y** reglas |
| `data/update/ComprobadorDeActualizaciones.kt` | Pregunta a GitHub por el último release y decide si gana al instalado |
| `data/update/VersionPublicada.kt` | Comparación SemVer — la única parte de la comprobación con tests unitarios |
| `data/update/DescargaDeApk.kt` | Descarga con `DownloadManager` más el intent del instalador del sistema |
| `data/update/EstadoActualizacion.kt` | Interruptor del aviso, última consulta y ETag, en sus propias claves de DataStore |

### Por qué SmsSender suspende

`SmsManager.sendMultipartTextMessage()` retorna de inmediato, antes de que el mensaje salga del
dispositivo. El desenlace llega después como un broadcast a un `PendingIntent` que se suministra por
cada parte. Si el worker no lo esperase, todo envío parecería correcto y los fallos reales se
desvanecerían.

Por eso `SmsSender.enviar()` registra un receptor `RECEIVER_NOT_EXPORTED` sobre una acción con UUID
propio de cada envío, entrega a la radio un `PendingIntent` `FLAG_IMMUTABLE` por parte, y suspende
hasta que todas las partes informan o expira `AppConfig.timeoutEnvioSegundos`.

Correspondencia de resultados: `RESULT_OK` → éxito; `NO_SERVICE`, `RADIO_OFF`, `GENERIC_FAILURE` y
timeout → reintentar; `NULL_PDU`, destino inválido y permiso ausente → fallo permanente.

**Multiparte:** si una parte falla y otra tiene éxito, se reintenta el reenvío entero, asumiendo un
posible duplicado en el destino. Dar por entregado un mensaje truncado es peor.

### Idempotencia

De cliente, en dos niveles:

- `ProcesarSmsEntranteUseCase.procesar()` comprueba si el SMS ya tiene reenvíos antes de crear
  ninguno. Eso es lo que hace seguro ejecutar el rescate de huérfanos sobre un SMS procesado a
  medias.
- `SmsDispatchWorker` comprueba `reenvio.estado` al principio de cada intento y devuelve éxito sin
  tocar la radio si ya está en `ENVIADO`.

`ColaDeEnvios` usa `ExistingWorkPolicy.KEEP`, así que un reenvío nunca tiene dos tareas
simultáneas.

### Base de datos Room

Cuatro tablas, versión de esquema **2** (el KDoc de `SmsDatabase` explica por qué volvió a empezar
en 1):

- `sms` — una fila por SMS recibido. Solo lo que llegó, más `estado`
  (`PENDIENTE`/`PROCESADO`/`SIN_REGLA`/`DESCARTADO`) y `motivo_descarte`.
- `reglas` — reglas de reenvío ordenadas, incluida `ignorar_mayusculas` (añadida en la v2).
- `reenvios` — la unidad de despacho: una fila por destino, con su propio `estado`, `intentos` y
  `ultimo_error`. La FK a `sms` es CASCADE; la FK a `reglas` es SET NULL, y `nombre_regla` está
  desnormalizado para que el historial sobreviva al borrado de la regla.
- `log_entries` — registro de auditoría. Tipos: `SMS_RECIBIDO`, `REGLA_APLICADA`, `SMS_REENVIADO`,
  `SIN_REGLA`, `BUCLE_EVITADO`, `ERROR`, `SISTEMA`.

Modo WAL activado. Los esquemas se exportan a `app/schemas/` y se versionan. Sin
`fallbackToDestructiveMigration`: las migraciones tienen que ser explícitas.

> El esquema dejó de poder editarse en el sitio en el momento en que la app se instaló en un
> teléfono con reglas dentro: cambiar una versión sin migrar hace que Room aborte al abrir la base
> de datos. Ahora todo cambio sube la versión y escribe su `Migration` en
> `data/local/db/Migraciones.kt`, que `DatabaseModule` registra. Tanto `1.json` como `2.json` están
> versionados bajo `app/schemas/`.

## Convenciones de nombres

El código mezcla idiomas deliberadamente, y el código nuevo debe seguir el mismo reparto:

- **Los miembros** —funciones, propiedades, parámetros, nombres de columna de Room, constantes de
  enum— van en **español**: `guardar`, `observarTodos`, `obtenerPendientes`, `telefono`,
  `fecha_recepcion`, `SMS_REENVIADO`. Los únicos miembros en inglés son sobrescrituras del framework
  donde no hay elección (`onCreate`, `doWork`, `onBindViewHolder`, `provideDatabase`).
- **Los nombres de tipo** llevan un **sufijo arquitectónico en inglés** sobre un sustantivo español
  o inglés: `SmsRepository`, `ReglaDao`, `ReenvioEntity`, `SettingsFragment`, `ReglasViewModel`. Los
  tipos de dominio puro pueden ir enteros en español: `LogTipo`, `EstadoSms`, `EvaluadorDeReglas`.
- **Los comentarios y el KDoc** van en **español**.
- **Los nombres de clase de caso de uso siguen la misma regla:** una frase verbal en español más el
  sufijo `UseCase` — `ProcesarSmsEntranteUseCase`, `ObtenerListaSmsUseCase`. El `GetSmsListUseCase`
  en inglés heredado de SMSGateway se renombró por coherencia.

Este reparto se verificó contra el código previo a la migración, no se dio por supuesto: de las 89
funciones que el proyecto original declaraba con elección real de idioma, **89 estaban en español y
ninguna en inglés**, y las 13 columnas de Room estaban también en español. Los únicos miembros en
inglés eran sobrescrituras del framework. No "restaures" nombres en inglés: no había ninguno que
restaurar.

## Preparación del dispositivo (teléfono dedicado de producción)

Estos pasos se hacen una sola vez en el dispositivo Android dedicado que recibe y reenvía los SMS.
Sáltate los que ya estén hechos.

### Por qué hay que desactivar la optimización de batería

El **modo Doze** de Android suspende los procesos en segundo plano cuando la pantalla lleva unos
minutos apagada. WorkManager respeta Doze y puede retrasar `SmsDispatchWorker` durante horas.
Eximir a la app garantiza que cada SMS se reenvíe a los pocos segundos de llegar, sin importar el
estado de la pantalla ni de la batería.

### Opción A — desde la app (sin USB y sin rutas propias de cada fabricante)

**Ajustes** → **Fiabilidad en segundo plano**. La tarjeta indica si la exención ya está concedida y
el botón abre la pantalla del sistema que la concede. Prueba tres destinos en orden —el diálogo de
exención de un toque, la lista completa de optimización de batería y la ficha de Ajustes de la
app— porque ningún fabricante garantiza el primero. Es la vía preferible en un teléfono que tienes
en la mano; el estado se refresca al volver a la pantalla.

### Opción B — comando ADB (lo más rápido si el teléfono ya está conectado)

```bash
adb shell dumpsys deviceidle whitelist +com.capicua.smstosms
```

Se ejecuta una vez tras instalar. Para verificar que la app quedó en la lista:

```bash
adb shell dumpsys deviceidle whitelist
# La salida debe incluir una línea con: com.capicua.smstosms
```

### Opción C — interfaz del dispositivo, a mano

Solo hace falta si el botón de dentro de la app no consigue abrir nada. La ruta exacta varía según
el fabricante; usa la que más se parezca:

**Android puro / Pixel**
1. **Ajustes** → **Aplicaciones** → **SMStoSMS**
2. **Batería** → seleccionar **Sin restricciones**

**Samsung (One UI)**
1. **Ajustes** → **Mantenimiento del dispositivo** → **Batería**
2. **Límites de uso en segundo plano** → **Aplicaciones sin suspender** → **Añadir** → SMStoSMS

**Xiaomi / MIUI / HyperOS**
1. **Ajustes** → **Aplicaciones** → **Administrar aplicaciones** → **SMStoSMS**
2. **Ahorro de batería** → **Sin restricciones**
3. Volver a la ficha de la app → activar **Inicio automático**

**Huawei / EMUI**
1. **Ajustes** → **Aplicaciones** → **SMStoSMS** → **Consumo de batería**
2. Desactivar **Gestión inteligente de energía** y seleccionar **Sin restricciones**

**OnePlus / OxygenOS / ColorOS**
1. **Ajustes** → **Aplicaciones** → **SMStoSMS** → **Batería**
2. **Optimización de batería** → **No optimizar**

> **Nota:** en cualquier fabricante también puedes buscar "Optimización de batería" directamente
> en el buscador de Ajustes, seleccionar **Todas las aplicaciones** y cambiar SMStoSMS
> a **No optimizar**.

### Otros ajustes recomendados (todos los fabricantes)

| Ajuste | Dónde | Valor |
|---------|-------|-------|
| Inicio automático | Ajustes → Aplicaciones → SMStoSMS | **Activado** |
| Ejecutar en segundo plano | Ajustes → Aplicaciones → SMStoSMS → Batería | **Permitido** |
| Optimización de batería | Ajustes → Batería → Optimización → SMStoSMS | **No optimizar** |

---

## Notas importantes

- `libs.versions.toml` es la única fuente de verdad para las versiones de todas las dependencias.
  Retrofit y OkHttp se eliminaron cuando desapareció la capa HTTP; no los vuelvas a añadir.
  `navigation-ui` corrió la misma suerte cuando las cinco pestañas dejaron de ser destinos de
  navegación: ya nadie llama a `setupWithNavController`. `kotlinx.serialization` **sí** se usa, por
  el formato de exportación e importación de reglas.
- `SEND_SMS` es un permiso restringido. Irrelevante para distribuir un APK, pero cierra la puerta a
  Google Play sin una declaración aprobada.
- **Que no arranque nada tras un reinicio no suele ser un fallo de `BootReceiver`.** El receptor de
  SMS está declarado en el manifiesto, así que el sistema lo arranca por su cuenta — el receptor
  solo reprograma los workers de segundo plano. Dos cosas fuera del alcance de la app lo rompen: la
  lista de **inicio automático** del fabricante (MIUI, HyperOS, EMUI, ColorOS y Funtouch la traen
  vacía, y el permiso no se puede conceder ni consultar desde código — `InicioAutomatico` abre esa
  pantalla y Ajustes lo explica) y el **estado detenido** de Android: una app recién instalada que
  nadie ha abierto todavía no recibe ningún broadcast, SMS incluidos, hasta que alguien la lanza una
  vez. Después de instalar el APK, abre la app una vez.
- **Las reglas son configuración, así que su copia de seguridad está en Ajustes, no en la pestaña
  Reglas.** Un único fichero lleva los dos bloques (`ConfigBackup` + versión de formato 3): los
  ajustes se **reemplazan** al importar, las reglas se **añaden** — importar no borra nunca, porque
  una importación equivocada que vaciase la lista dejaría el teléfono sin reenviar nada. El id de la
  SIM no viaja en el fichero: identifica una tarjeta de *este* teléfono y desviaría los reenvíos en
  otro.
- **«Acerca de» lleva un descargo de responsabilidad** junto al aviso de privacidad: la aplicación
  se entrega tal cual y quien la instala y la configura es el único responsable de su uso y de la
  ley que le aplique. En una app que reenvía SMS de otras personas, eso no es relleno.
- La variante debug usa `applicationId = com.capicua.smstosms.debug`, así que puede convivir con la
  de release en el mismo dispositivo. **No** comparten base de datos.
- **Todo lo que tenga alcance por instalación debe derivarse de `applicationId`, nunca escribirse a
  mano.** La autoridad del FileProvider estaba escrita como `com.capicua.smstosms.provider`, de modo
  que ambas variantes declaraban la *misma* autoridad — y una autoridad es única en todo el
  dispositivo, así que Android se negaba a tener las dos instaladas
  (`INSTALL_FAILED_CONFLICTING_PROVIDER`), echando por tierra el sentido mismo del
  `applicationIdSuffix`. Ahora se lee `${applicationId}.provider` en el manifiesto y
  `BuildConfig.APPLICATION_ID + ".provider"` en `Constants`; los dos tienen que ir a la par. El
  síntoma despista: aparece al instalar, nunca al compilar.
- **Aquí el edge-to-edge es obligatorio.** Con `targetSdk 35`, Android 15 dibuja la ventana por
  debajo de las barras de estado y de navegación e ignora `fitsSystemWindows`; no hay forma de
  renunciar. `MainActivity` reparte los insets (`systemBars or displayCutout`) y es el único sitio
  que lo hace, así que cualquier contenedor principal nuevo lo hereda. Sin eso, el recorte de la
  cámara tapaba la cabecera de cada pantalla y la barra de navegación del sistema quedaba **encima**
  de la Bottom Navigation, lo que hacía imposible salir de las pantallas que no están en esa barra.
  `ProbarReglasFragment` y `EditarReglaFragment` llevan además su propio botón de atrás, ya que
  ninguno tiene entrada en la barra inferior.
- **AGP 9 cambió tres cosas del build, y las tres se ven en `app/build.gradle.kts`.** Kotlin va
  integrado en AGP: no hay plugin `kotlin-android`, la versión de Kotlin la fija el plugin de
  serialización declarado en el `build.gradle.kts` raíz, y `kotlinOptions` es ahora
  `kotlin { compilerOptions { … } }`. La API de variantes antigua desapareció, así que el
  renombrado del APK de release pasa por `androidComponents.onVariants` y `VariantOutputImpl`, que
  es interna: si una versión futura de AGP la cambia, el APK vuelve a llamarse
  `app-release.apk`. Y R8 aplica el modo completo estricto: un `-keep class X` ya no conserva el
  constructor, así que las reglas de `proguard-rules.pro` lo piden con `{ <init>(); }`.
- **`INTERNET` vuelve a estar declarado**, solo para la comprobación de versión (ver «Acerca de»,
  más arriba). Si esa funcionalidad desapareciera alguna vez, el permiso se va con ella: que esta
  app no necesite red es parte de lo que es.
- Tres conjuntos de tests unitarios cubren lo que merece cubrirse, todos en Kotlin puro y sin
  dependencias de Android: `EvaluadorDeReglasTest` (45), `NormalizadorTelefonoTest` (18) y
  `ReglasJsonTest` (10). El placeholder `SmsRepositoryTest`, que nunca compiló, ya no está.
- `MASTERPLAN-SMS.md` recoge la migración desde SMSGateway fase a fase, con las decisiones tomadas y
  las desviaciones respecto al plan original. `MASTERPLAN-GATEWAY.md` cuenta el origen de la tubería
  en SMSGateway; se escribió a posteriori (25 oct 2026) para que las cuatro aplicaciones tengan la
  misma estructura de planes. Léelos antes de cambiar nada estructural.
