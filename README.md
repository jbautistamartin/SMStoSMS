# SMStoSMS

Aplicación Android que convierte un dispositivo con SIM en un **reenviador de SMS**: recibe los
mensajes que llegan a su número y los reenvía automáticamente a otro teléfono, según reglas con
expresiones regulares sobre el remitente y el texto.

**Versión actual: [v1.0.0](https://github.com/jbautistamartin/SMStoSMS/releases/tag/v1.0.0)** —
descarga el APK firmado desde la [página de releases](https://github.com/jbautistamartin/SMStoSMS/releases).
No está en Google Play: `SEND_SMS` es un permiso restringido y su publicación exige una
declaración aprobada.

---

## Para qué sirve

El caso típico: un número al que llegan avisos —códigos de verificación, notificaciones de un
proveedor, alertas de un sistema de monitorización— y una persona que necesita recibirlos en su
propio móvil sin cambiar de tarjeta.

Las reglas permiten separar ese tráfico: los SMS del banco a una persona, los de la alarma a
otra, y el resto a ninguna parte.

---

## Características principales

- **Reglas ordenadas** — expresiones regulares sobre el remitente y el mensaje; gana la primera
  que casa, y una regla puede dejar que la evaluación continúe para reenviar a varios números
- **Plantilla por regla** — el texto que sale se compone con `{mensaje}`, `{telefono}` y
  `{fecha}`, para que el destinatario sepa de quién venía el original
- **Panel de prueba** — se pega un SMS de ejemplo y la app dice qué regla casa, a dónde iría y
  con qué texto, sin enviar nada
- **Sin pérdida de mensajes** — patrón outbox: el SMS se persiste en Room antes de hacer nada más
- **Confirmación real de envío** — se espera el `sentIntent` del operador; un reenvío solo se da
  por bueno cuando todas sus partes confirman
- **Contestar al remitente** — `{telefono}` como destino devuelve el mensaje a quien lo envió
- **Mayúsculas opcionales** — cada regla decide si sus expresiones distinguen mayúsculas
- **Protección antibucle** — descarta los SMS que vuelven de un destino configurado, no reenvía
  al propio remitente y limita los reenvíos por minuto, porque cada SMS cuesta dinero
- **Reintentos automáticos** — WorkManager con backoff configurable, y rescate cada 30 segundos
  de lo que se quede atascado
- **Arranque automático** — los workers se reactivan tras reiniciar el dispositivo
- **Registro de auditoría** — cada paso queda anotado, con filtros y exportación a fichero
- **Exportar e importar reglas** — en JSON, para clonar la configuración en otro dispositivo
- **Doble SIM** — se puede elegir con qué tarjeta se envía

---

## Arquitectura

```
Presentation  →  Domain  →  Data
   MVVM          Reglas      Room · SmsManager · DataStore · WorkManager
```

Clean Architecture con tres capas estrictas, inyección de dependencias con **Hilt** y
reactividad completa mediante **Kotlin Flow**.

El recorrido de cada SMS:

```
SIM → SmsReceiver → SmsIngestionService → Room (outbox)
                                             ↓
                                      protecciones antibucle
                                             ↓
                                      EvaluadorDeReglas
                                             ↓
                                   reenvíos (uno por destino)
                                             ↓
                                      SmsDispatchWorker
                                             ↓
                              SmsManager → nº destino → sentIntent
```

Consulta [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) para la documentación técnica completa
y [`MASTERPLAN.md`](MASTERPLAN.md) para la historia de la migración desde SMSGateway.

---

## Stack tecnológico

| Categoría | Tecnología |
|-----------|-----------|
| Lenguaje | Kotlin 2.0.21 |
| SDK mínimo | Android 8.0 (API 26) |
| SDK objetivo | Android 15 (API 35) |
| Inyección de dependencias | Hilt 2.52 |
| Base de datos local | Room 2.6.1 + WAL mode |
| Tareas en background | WorkManager 2.9.1 |
| Envío de SMS | `android.telephony.SmsManager` |
| Configuración | DataStore Preferences 1.1.1 |
| Serialización | kotlinx.serialization 1.7.3 |
| Concurrencia | Coroutines + Flow 1.9.0 |
| UI | Material Design 3 + Navigation Component |

No hay ninguna dependencia de red: la aplicación no necesita internet y no declara el permiso.

---

## Requisitos

- Android Studio Ladybug (2024.2.1) o superior
- **JDK 17 o 21** — AGP 8.5.2 no admite JDK 25
- Android SDK con API 35
- Dispositivo Android 8.0+ con SIM activa y saldo o tarifa de SMS

---

## Instalar la versión publicada

La vía corta si no vas a tocar el código:

1. Descarga `smstosms-1.0.0.apk` de la
   [release v1.0.0](https://github.com/jbautistamartin/SMStoSMS/releases/tag/v1.0.0).
2. Pásalo al teléfono e instálalo, aceptando «instalar de orígenes desconocidos» cuando lo pida.
3. Concede los permisos que la app solicita al arrancar.
4. **Ajustes → Fiabilidad en segundo plano → Desactivar ahorro de batería.** Sin esto el modo
   Doze puede retrasar los reenvíos horas con la pantalla apagada.

Por ADB, `adb install smstosms-1.0.0.apk`.

> El APK está firmado con la clave de Capicua. Las futuras actualizaciones se firman con la
> misma: un APK de otra procedencia no se instalará encima, habrá que desinstalar primero y se
> perderán las reglas. Expórtalas antes desde **Reglas → ⋮ → Exportar**.

---

## Compilar e instalar desde el código

### 1. Compilar

```bash
# Si el JDK del PATH es más nuevo que el 21
export JAVA_HOME="/ruta/al/jdk-21"

# Build debug
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk

# Build release (requiere claves de firma en local.properties; ver CLAUDE.md)
./gradlew assembleRelease
# APK → app/build/outputs/apk/release/smstosms-<versionName>.apk
```

Sin las claves de firma el build de release **no falla**: produce un APK sin firmar, que habrá
que firmar aparte antes de instalarlo.

### 2. Instalar mediante ADB

> Requiere [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)
> y el dispositivo en modo depuración USB (**Ajustes → Opciones de desarrollador → Depuración USB**).

```bash
# Instalar APK debug
adb install app/build/outputs/apk/debug/app-debug.apk

# Reemplazar una versión ya instalada conservando sus datos
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Verificar que quedó instalada
adb shell pm list packages | grep capicua
```

Alternativamente, `./gradlew installDebug` compila e instala en un solo paso.

### 3. Conceder permisos y configurar el dispositivo

```bash
adb shell pm grant com.capicua.smstosms android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smstosms android.permission.READ_SMS
adb shell pm grant com.capicua.smstosms android.permission.SEND_SMS
adb shell pm grant com.capicua.smstosms android.permission.READ_PHONE_STATE
adb shell pm grant com.capicua.smstosms android.permission.POST_NOTIFICATIONS

# Excluir de la optimización de batería (obligatorio para un reenvío fiable)
adb shell dumpsys deviceidle whitelist +com.capicua.smstosms
```

Sin la exención de batería, el modo Doze de Android puede retrasar los reenvíos horas cuando la
pantalla está apagada.

Si no tienes el cable a mano, la propia app lo resuelve: **Ajustes** → **Fiabilidad en segundo
plano** dice si la exención está concedida y el botón abre la pantalla del sistema que la
concede. Los pasos manuales por fabricante están en [`CLAUDE.md`](CLAUDE.md).

---

## Primeros pasos en la app

1. **Reglas** → **+** para crear la primera regla.
2. Rellena el nombre, el número destino y al menos un criterio. Deja los dos criterios vacíos
   solo si quieres reenviar *todos* los SMS.
3. **Probar** → pega un SMS de ejemplo y comprueba que la regla casa y que el texto sale como
   esperas, antes de que llegue uno real.
4. Opcionalmente, **Enviar de prueba** manda un SMS real al destino para confirmar el circuito
   completo. Tiene coste.

Mientras no haya ninguna regla activa, la pantalla de Inicio lo advierte: los SMS se reciben y
se marcan como «sin regla», pero no se reenvía nada.

### Las reglas, en detalle

Una regla casa cuando se cumplen **sus dos criterios**. Un criterio vacío se considera cumplido.

| Campo | Qué hace |
|-------|----------|
| Expresión del remitente | Se aplica al número tal como llega, sin normalizar. Puede ser alfanumérico: `BANCO`, `AMAZON` |
| Expresión del mensaje | Se aplica al texto completo del SMS |
| Número destino | A dónde se reenvía. Un número, o `{telefono}` para contestar a quien escribió |
| Plantilla | Qué texto se envía, con `{mensaje}`, `{telefono}` y `{fecha}` |
| Ignorar mayúsculas | Aplica las dos expresiones sin distinguir mayúsculas de minúsculas |
| Activa | Desactívala para que no se evalúe, sin borrarla |
| Seguir evaluando | Tras casar, continúa con las reglas siguientes |

La búsqueda es **parcial**: el patrón `codigo` casa con «Tu codigo es 4821». Para exigir el texto
completo, ánclalo con `^…$`.

Las expresiones **distinguen mayúsculas** salvo que actives **Ignorar mayúsculas** en la regla,
que las aplica sin distinguir a las dos a la vez. También puedes escribir `(?i)` dentro de una
expresión concreta si solo quieres que afecte a esa; activar las dos cosas no da problemas.

Ejemplos:

```
# Solo los SMS del banco, al móvil personal
Remitente: ^BANCO$          Mensaje: (vacío)       → +34600112233

# Cualquier mensaje que contenga un código de verificación
Remitente: (vacío)          Mensaje: (?i)codigo    → +34600112233

# Todo lo que llegue de un número concreto, a dos destinos
Remitente: ^\+34700        Mensaje: (vacío)        → +34600112233  [seguir evaluando]
Remitente: ^\+34700        Mensaje: (vacío)        → +34600112244

# Acuse de recibo: contesta al que escribió, sin tocar a nadie más
Remitente: (vacío)          Mensaje: (?i)^alta      → {telefono}
```

### Contestar al mismo móvil que escribió

Pon `{telefono}` en el **número destino**, o pulsa «Contestar al remitente» en el editor de la
regla. El reenvío sale al número del que llegó el SMS, y la plantilla decide qué se le devuelve:
`Recibido: {mensaje}` acusa recibo con el texto original, y una plantilla fija como
`Hemos registrado tu alta` contesta sin devolver nada de lo que escribió.

La protección antibucle **no** bloquea estas reglas: escribir `{telefono}` es pedir la
circularidad a propósito, y lo que la protección evita es la accidental. Eso deja el límite de
reenvíos por minuto como única red, así que si el otro extremo también responde solo, acota la
regla con una expresión del mensaje concreta en lugar de dejarla casando con todo.

Si quien escribe es una cabecera alfanumérica (`BANCO`, `AMAZON`), no se le puede devolver nada:
ese reenvío se omite y queda anotado en el registro.

---

## Permisos

| Permiso | Para qué |
|---------|----------|
| `RECEIVE_SMS` | Recibir el broadcast de SMS entrantes |
| `READ_SMS` | Leer el contenido de las PDU |
| `SEND_SMS` | Enviar los reenvíos |
| `READ_PHONE_STATE` | Enumerar las SIM para poder elegir con cuál enviar |
| `POST_NOTIFICATIONS` | Notificación del servicio en primer plano (Android 13+) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Mantener vivo el proceso durante la ingesta |
| `RECEIVE_BOOT_COMPLETED` | Reactivar los workers tras reiniciar |
| `WAKE_LOCK` | Completar el trabajo en curso con la pantalla apagada |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Solicitar la exención de Doze |

`SEND_SMS` es un permiso restringido por Google Play. Para distribución por APK, como es el caso,
no supone ningún problema.

---

## Estructura del proyecto

```
app/src/main/java/com/capicua/smstosms/
├── data/
│   ├── config/          # AppConfig, ConfigDataStore
│   ├── local/db/        # Room: entidades, DAOs, relaciones, SmsDatabase
│   ├── repository/      # Sms, Regla, Reenvio y Log + sus implementaciones
│   ├── rules/           # ReglasJson: formato de exportación
│   └── sms/             # SmsSender, ProveedorDeSims, ResultadoEnvio
├── di/                  # Módulos Hilt: Database, Config, Repository, Worker
├── domain/
│   ├── model/           # SmsMessage, Regla, Reenvio, estados
│   ├── rules/           # EvaluadorDeReglas, NormalizadorTelefono
│   └── usecase/         # ProcesarSmsEntranteUseCase, ObtenerListaSmsUseCase
├── presentation/
│   ├── dashboard/       # Inicio: SMS recibidos y qué se hizo con ellos
│   ├── rules/           # Lista de reglas, editor y panel de prueba
│   ├── logs/            # Registro con filtros y exportación
│   ├── settings/        # Protecciones, reintentos y SIM
│   └── about/           # Acerca de
├── receiver/            # SmsReceiver, BootReceiver
├── service/             # SmsIngestionService
├── util/                # Constants, Extensions
├── worker/              # SmsDispatchWorker, ColaDeEnvios, OrphanRescue, HealthMonitor
└── SmsToSmsApplication.kt
app/schemas/             # Esquemas Room exportados (versionados)
docs/
└── ARCHITECTURE.md      # Documentación técnica completa
```

---

## Base de datos

SQLite vía Room, cuatro tablas:

- **`sms`** — mensajes recibidos, con su estado de tramitación
- **`reglas`** — reglas de reenvío, ordenadas por prioridad
- **`reenvios`** — un envío a un destino, con su propio estado, intentos y último error
- **`log_entries`** — registro de auditoría de todos los eventos

Los esquemas se exportan a `app/schemas/` y se versionan: son la referencia para escribir
migraciones. No se usa `fallbackToDestructiveMigration`, porque perdería mensajes pendientes.

---

## Tests

```bash
./gradlew test
```

Cubren el motor de reglas, la comparación de números en que se apoyan las protecciones
antibucle, y el formato de intercambio de reglas. Son Kotlin puro, sin Android, sin Robolectric
y sin base de datos en memoria.

---

## Contribuir

1. Haz un fork del repositorio
2. Crea una rama para tu feature: `git checkout -b feature/nombre-feature`
3. Respeta las convenciones de nombres del proyecto, descritas en [`CLAUDE.md`](CLAUDE.md)
4. Realiza tus cambios y añade tests
5. Abre un Pull Request con una descripción clara del cambio

---

## Licencia

Este proyecto se distribuye bajo la **GNU Lesser General Public License v2.1**.
Consulta el fichero [`LICENSE`](LICENSE) para más detalles.

---

## Aviso legal

Este software se proporciona **"tal cual"**, sin garantía de ningún tipo, expresa o implícita.

Esta aplicación **reenvía el contenido de mensajes SMS al teléfono de otra persona**. Eso implica
que datos personales —códigos de acceso, avisos bancarios, comunicaciones privadas— salen del
dispositivo que los recibió y llegan a un tercero. Antes de desplegarla, considera:

- El **remitente original no sabe** que su mensaje se está reenviando y no ha dado su
  consentimiento para ello.
- Los reenvíos **tienen coste** y, con una configuración desafortunada, pueden multiplicarse. Las
  protecciones antibucle reducen el riesgo, pero no lo eliminan.
- El contenido de los SMS queda almacenado en el dispositivo durante el periodo de retención
  configurado, y el registro de auditoría guarda los números implicados.

El autor no se hace responsable de:

- Pérdida, alteración o interceptación de mensajes SMS durante el tránsito o el almacenamiento.
- Uso indebido de la aplicación para capturar o redirigir comunicaciones sin el consentimiento de
  los remitentes.
- Costes de telefonía derivados de los reenvíos, previstos o no.
- Daños directos o indirectos derivados del uso, mal uso o imposibilidad de uso del software.
- Incumplimientos normativos (RGPD, LOPD u otras regulaciones de privacidad) derivados de una
  configuración o despliegue inadecuados.

**El operador es el único responsable** de garantizar que el uso de esta aplicación cumple con la
legislación vigente en su jurisdicción, incluyendo la obtención del consentimiento necesario para
el tratamiento de los datos personales contenidos en los SMS.

---

## Desarrollo asistido por IA

El diseño, la arquitectura y el código de este proyecto se desarrollaron con asistencia de
[Claude](https://claude.ai) (Anthropic). El código fue revisado, validado y adaptado por el autor
antes de cada commit.
