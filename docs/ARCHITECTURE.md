# SMStoSMS — Documentación Técnica

## Índice

1. [Descripción del sistema](#1-descripción-del-sistema)
2. [Arquitectura](#2-arquitectura)
3. [Diagrama de flujo](#3-diagrama-de-flujo)
4. [Diagrama de capas](#4-diagrama-de-capas)
5. [Dependencias](#5-dependencias)
6. [Cómo compilar](#6-cómo-compilar)
7. [Cómo instalar](#7-cómo-instalar)
8. [Permisos requeridos](#8-permisos-requeridos)
9. [Reglas de reenvío](#9-reglas-de-reenvío)
10. [Mecanismo de envío](#10-mecanismo-de-envío)
11. [Protecciones antibucle y de coste](#11-protecciones-antibucle-y-de-coste)
12. [Base de datos](#12-base-de-datos)
13. [Mantenimiento](#13-mantenimiento)
14. [Posibles incidencias](#14-posibles-incidencias)

---

## 1. Descripción del sistema

**SMStoSMS** es una aplicación Android diseñada para ejecutarse en un **dispositivo con SIM**
cuya función es recibir los SMS que llegan a su número y reenviarlos a otro teléfono, decidiendo
el destino mediante reglas con expresiones regulares.

### Casos de uso típicos

- Un número corporativo que recibe códigos de verificación y los reparte al móvil de la persona
  responsable de cada servicio.
- Alertas de un sistema de monitorización que llegan por SMS y deben alcanzar a quien esté de
  guardia.
- Una línea antigua que se quiere mantener operativa sin tener que llevar encima su tarjeta.

### Garantías del sistema

| Garantía | Mecanismo |
|----------|-----------|
| Ningún SMS entrante se pierde aunque el proceso sea eliminado | `ForegroundService` + outbox en Room |
| Un reenvío solo se da por bueno si el operador lo confirma | Espera del `sentIntent` de cada parte |
| Ningún SMS se reenvía dos veces por un reintento | Comprobación del estado al inicio de cada intento |
| Lo que se queda atascado se recupera solo | `OrphanRescueWorker` cada 30 s |
| Un bucle de reenvíos no genera una factura | Tres protecciones, descritas en §11 |
| La app arranca sola tras un reinicio del dispositivo | `BootReceiver` + WorkManager |

### Lo que el sistema **no** garantiza

- **Entrega al destinatario.** El `sentIntent` confirma que el mensaje salió hacia el operador,
  no que llegara al teléfono destino. La confirmación de entrega (`deliveryIntent`) no se usa:
  depende del operador y no todos la emiten.
- **Ausencia de duplicados en mensajes largos.** Si una parte de un SMS multipart falla y otra
  no, el reenvío se reintenta completo y el destino puede ver repetida la parte que sí llegó.
  Ver §10.

---

## 2. Arquitectura

La aplicación sigue **Clean Architecture** con separación estricta en tres capas y el patrón
**MVVM** en presentación. Toda la inyección de dependencias pasa por **Hilt**.

```
┌─────────────────────────────────────────────────────────┐
│  PRESENTATION                                           │
│  Fragments + ViewModels + Adapters                      │
│  Dashboard · Reglas · Probar · Logs · Settings · About   │
└───────────────────────────┬─────────────────────────────┘
                            │ usa
┌───────────────────────────▼─────────────────────────────┐
│  DOMAIN                                                 │
│  Modelos puros · EvaluadorDeReglas ·                    │
│  NormalizadorTelefono · UseCases                        │
│  Sin una sola dependencia de Android                    │
└───────────────────────────┬─────────────────────────────┘
                            │ define interfaces,
                            │ implementadas por
┌───────────────────────────▼─────────────────────────────┐
│  DATA                                                   │
│  Room · SmsManager · DataStore · WorkManager            │
└─────────────────────────────────────────────────────────┘
```

### Principios aplicados

- **La capa de dominio no conoce Android.** `EvaluadorDeReglas` y `NormalizadorTelefono` son
  Kotlin puro, y por eso se pueden probar con JUnit a secas: sin Robolectric, sin instrumentación
  y sin base de datos en memoria.
- **El evaluador recibe la fecha ya formateada**, no un `Instant`. Es lo que lo mantiene
  determinista: no depende de la zona horaria ni del idioma del dispositivo, y quien llama
  decide el formato.
- **Las reglas viven en la base de datos, no en DataStore.** Son una lista ordenada con
  identidad propia, consultable y migrable; DataStore guarda solo los ajustes escalares.
- **Un único punto de encolado.** Todo reenvío entra en WorkManager por `ColaDeEnvios`, que lee
  el backoff de la configuración. Centralizarlo evitó el error que arrastraba la versión HTTP,
  donde el intervalo configurable nunca se aplicaba porque el encolado leía una constante.

---

## 3. Diagrama de flujo

```
                        ┌──────────────┐
                        │     SIM      │
                        └──────┬───────┘
                               │ SMS_RECEIVED (PDU)
                     ┌─────────▼──────────┐
                     │   SmsReceiver      │  BroadcastReceiver
                     │   onReceive()      │  hilo principal, < 10 s
                     └─────────┬──────────┘  concatena partes multipart
                               │ startForegroundService()
                   ┌───────────▼────────────┐
                   │  SmsIngestionService   │  startForeground() < 5 s
                   └───────────┬────────────┘  mantiene vivo el proceso
                               │
                 ┌─────────────▼──────────────┐
                 │ ProcesarSmsEntranteUseCase │
                 └─────────────┬──────────────┘
                               │
            ┌──────────────────▼───────────────────┐
            │ 1. INSERT sms (PENDIENTE)   ← outbox │
            └──────────────────┬───────────────────┘
                               │
            ┌──────────────────▼───────────────────┐
            │ 2. ¿remitente es un destino nuestro? │──sí──→ DESCARTADO
            │    ¿se superó el límite por minuto?  │
            └──────────────────┬───────────────────┘
                               │ no
            ┌──────────────────▼───────────────────┐
            │ 3. EvaluadorDeReglas                 │
            │    reglas activas, por orden         │
            └──────────────────┬───────────────────┘
                               │
                  ┌────────────┴────────────┐
             sin coincidencias        con coincidencias
                  │                         │
              SIN_REGLA        ┌────────────▼─────────────┐
                               │ 4. ¿destino = remitente? │──sí──→ omitida
                               └────────────┬─────────────┘
                                            │ no
                               ┌────────────▼─────────────┐
                               │ 5. INSERT reenvios × N   │
                               │    UPDATE sms PROCESADO  │
                               └────────────┬─────────────┘
                                            │ ColaDeEnvios.encolar()
                               ┌────────────▼─────────────┐
                               │   SmsDispatchWorker      │  uno por reenvío
                               └────────────┬─────────────┘
                                            │
                               ┌────────────▼─────────────┐
                               │        SmsSender         │
                               │  divideMessage()         │
                               │  sendMultipartTextMessage│
                               │  … suspende …            │
                               └────────────┬─────────────┘
                                            │ sentIntent × partes
                               ┌────────────▼─────────────┐
                               │  RESULT_OK → ENVIADO     │
                               │  NO_SERVICE → retry      │
                               │  NULL_PDU → FALLIDO      │
                               └──────────────────────────┘
```

En paralelo, dos workers vigilan el sistema:

```
OrphanRescueWorker   cada 30 s (auto-encadenado)
  ├─ SMS en PENDIENTE sin evaluar      → vuelve a tramitarlos
  └─ reenvíos PENDIENTE sin worker vivo → los reencola

HealthMonitorWorker  cada 15 min (periódico)
  ├─ borra reenvíos ENVIADO con más de 30 días
  ├─ borra SMS ya tramitados con más de 30 días (CASCADE sobre sus reenvíos)
  └─ borra entradas de log con más de 30 días
```

### Por qué el receptor delega en un servicio

`BroadcastReceiver.onReceive()` se ejecuta en el hilo principal con un límite de unos 10 segundos,
y el proceso puede ser eliminado en cuanto retorna. El `ForegroundService` mantiene el proceso
vivo —con notificación visible obligatoria— hasta que el `INSERT` en Room se completa. Eso es lo
que garantiza que **ningún SMS se pierde**, incluso con el sistema bajo presión de memoria.

Cada llamada a `onStartCommand()` recibe un `startId` único, y `stopSelf(startId)` solo detiene
el servicio si era el último activo. Dos SMS simultáneos se procesan sin interferirse.

---

## 4. Diagrama de capas

```
presentation/
├── dashboard/      SMS recibidos, con regla aplicada, destinos y estado por reenvío
├── rules/          Lista ordenada, editor con validación en vivo, panel de prueba
├── logs/           Registro con filtros por tipo y exportación a fichero
├── settings/       Protecciones, reintentos, timeout y selector de SIM
└── about/          Versión, autoría, licencia

domain/
├── model/          SmsMessage · Regla · Reenvio · SmsConReenvios
│                   EstadoSms · EstadoReenvio · ResumenSms · LogEntry · LogTipo
├── rules/          EvaluadorDeReglas · ResultadoEvaluacion · NormalizadorTelefono
└── usecase/        ProcesarSmsEntranteUseCase · ObtenerListaSmsUseCase

data/
├── config/         AppConfig · ConfigDataStore
├── local/db/       SmsDatabase · entidades · DAOs · relaciones
├── repository/     Sms · Regla · Reenvio · Log (contrato + implementación)
├── rules/          ReglasJson: formato de intercambio versionado
└── sms/            SmsSender · ResultadoEnvio · ProveedorDeSims

worker/             SmsDispatchWorker · ColaDeEnvios
                    OrphanRescueWorker · HealthMonitorWorker
receiver/           SmsReceiver · BootReceiver
service/            SmsIngestionService
di/                 DatabaseModule · ConfigModule · RepositoryModule · WorkerModule
util/               Constants · Extensions
```

---

## 5. Dependencias

### Versiones principales

| Componente | Versión |
|------------|---------|
| Kotlin | 2.0.21 |
| AGP | 8.5.2 |
| KSP | 2.0.21-1.0.25 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| JVM target | 17 |
| Hilt | 2.52 |
| Room | 2.6.1 |
| WorkManager | 2.9.1 |
| DataStore Preferences | 1.1.1 |
| kotlinx.serialization | 1.7.3 |
| Coroutines | 1.9.0 |
| Material | 1.12.0 |
| Navigation | 2.8.2 |
| Timber | 5.0.1 |

`gradle/libs.versions.toml` es la única fuente de verdad de las versiones. **No hay ninguna
dependencia de red**: OkHttp y Retrofit se eliminaron al desaparecer la capa HTTP, y la
aplicación no declara el permiso `INTERNET`.

### Nota sobre la cadena de herramientas

El wrapper es **Gradle 9.3.0** y AGP es **8.5.2**. La combinación compila, pero emite avisos de
deprecación sobre APIs que desaparecen en Gradle 10, entre ellas `android.applicationVariants.all`
—el bloque que renombra el APK de release—. No bloquea nada hoy. Antes de pasar a Gradle 10 hay
que decidir entre subir AGP o fijar el wrapper a Gradle 8.x.

---

## 6. Cómo compilar

### Requisitos previos

- JDK **17 o 21**. AGP 8.5.2 no funciona con JDK 25, que es el que muchos sistemas traen por
  defecto.
- Android SDK con API 35.
- `local.properties` con `sdk.dir`, o la variable `ANDROID_HOME` apuntando al SDK.

### Pasos

```bash
# 1. Clonar el proyecto
git clone <url-del-repositorio> && cd SMStoSMS

# 2. Apuntar al JDK correcto si el del PATH es más nuevo
export JAVA_HOME="/ruta/al/jdk-21"

# 3. Build debug
./gradlew assembleDebug

# 4. Tests
./gradlew test

# 5. Build release (firma opcional)
./gradlew assembleRelease
```

### Firma de release

Las claves se leen de `local.properties`, que no se versiona:

```properties
KEYSTORE_PATH=/ruta/absoluta/al/almacen.keystore
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

Si falta alguna de las cuatro, el build **no falla**: se omite la configuración de firma y el APK
sale sin firmar. Es deliberado, para que clonar el repositorio y compilar funcione sin tener que
conseguir primero el almacén de claves.

### Variantes de build

| Variante | applicationId | Minificación | Firma |
|----------|---------------|--------------|-------|
| `debug` | `com.capicua.smstosms.debug` | No | Clave de depuración |
| `release` | `com.capicua.smstosms` | R8 activado | Clave propia, si está configurada |

Los dos `applicationId` son distintos, así que las dos variantes pueden convivir en el mismo
dispositivo. **No comparten base de datos**: son aplicaciones separadas para Android.

---

## 7. Cómo instalar

```bash
# 1. Habilitar depuración USB en el dispositivo
#    Ajustes → Acerca del teléfono → Número de compilación (×7)
#            → Opciones de desarrollador → Depuración USB

# 2. Verificar conexión
adb devices

# 3. Instalar
adb install app/build/outputs/apk/debug/app-debug.apk

# 4. Conceder permisos sin diálogos
adb shell pm grant com.capicua.smstosms android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smstosms android.permission.READ_SMS
adb shell pm grant com.capicua.smstosms android.permission.SEND_SMS
adb shell pm grant com.capicua.smstosms android.permission.READ_PHONE_STATE
adb shell pm grant com.capicua.smstosms android.permission.POST_NOTIFICATIONS

# 5. Eximir del modo Doze (OBLIGATORIO)
adb shell dumpsys deviceidle whitelist +com.capicua.smstosms

# Verificar
adb shell dumpsys deviceidle whitelist | grep capicua
```

### Por qué el paso 5 es obligatorio

El **modo Doze** suspende los procesos en segundo plano cuando la pantalla lleva unos minutos
apagada. WorkManager respeta Doze y puede retrasar `SmsDispatchWorker` horas. Eximir la
aplicación es lo que hace que un SMS se reenvíe en segundos y no cuando al sistema le parezca.

Los pasos equivalentes desde la interfaz del dispositivo, fabricante por fabricante, están en
[`CLAUDE.md`](../CLAUDE.md).

### Probar sin SIM ni coste

Un emulador permite simular la recepción:

```bash
adb emu sms send +34600112233 "Tu codigo es 4821"
```

Para probar también el envío, dos emuladores pueden mandarse SMS usando su número de puerto
(`5554`, `5556`…) como número de teléfono.

---

## 8. Permisos requeridos

| Permiso | Tipo | Para qué |
|---------|------|----------|
| `RECEIVE_SMS` | Peligroso | Recibir el broadcast de SMS entrantes |
| `READ_SMS` | Peligroso | Leer el contenido de las PDU |
| `SEND_SMS` | Peligroso, restringido | Enviar los reenvíos |
| `READ_PHONE_STATE` | Peligroso | Enumerar las SIM para el selector |
| `POST_NOTIFICATIONS` | Peligroso (API 33+) | Notificación del foreground service |
| `FOREGROUND_SERVICE` | Normal | Ejecutar el servicio de ingesta |
| `FOREGROUND_SERVICE_DATA_SYNC` | Normal | Tipo de servicio declarado (API 34+) |
| `RECEIVE_BOOT_COMPLETED` | Normal | Reprogramar los workers tras reiniciar |
| `WAKE_LOCK` | Normal | Completar el trabajo con la pantalla apagada |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Normal | Solicitar la exención de Doze |

También se declara `<uses-feature android:name="android.hardware.telephony" required="true" />`:
sin telefonía la aplicación no tiene nada que hacer.

### Seguridad del BroadcastReceiver

`SmsReceiver` se declara con `android:permission="android.permission.BROADCAST_SMS"`, de modo que
solo el proceso del sistema (radio/teléfono) puede dispararlo. Ninguna aplicación de terceros
puede inyectar un SMS falso.

El receptor de confirmaciones de envío, que vive dentro de `SmsSender`, se registra con
`ContextCompat.RECEIVER_NOT_EXPORTED` sobre una acción que incluye un UUID distinto por envío.
Ninguna otra aplicación puede falsificar una confirmación, y dos reenvíos simultáneos no se
cruzan las respuestas.

---

## 9. Reglas de reenvío

Las reglas son el centro de la configuración. Viven en la tabla `reglas` y se editan en la
pestaña **Reglas**.

### Campos

| Campo | Obligatorio | Descripción |
|-------|-------------|-------------|
| `orden` | Automático | Prioridad. Menor valor = se evalúa antes |
| `nombre` | Sí | Etiqueta para reconocerla en la lista y en el registro |
| `regexTelefono` | No | Expresión sobre el remitente. Vacío = no filtra |
| `regexMensaje` | No | Expresión sobre el cuerpo. Vacío = no filtra |
| `destino` | Sí | Número al que se reenvía |
| `plantilla` | Sí | Texto a enviar. Por defecto `{mensaje}` |
| `activa` | — | Si es false, la regla se ignora sin borrarla |
| `continuar` | — | Si es true, tras casar sigue evaluando las siguientes |

### Semántica de la coincidencia

Una regla casa cuando **ambos** criterios se cumplen. Un criterio vacío (null o en blanco) se
considera cumplido, de modo que **una regla sin patrones casa con todos los SMS**. Es potente y
fácil de crear por descuido, así que la lista lo indica explícitamente.

La búsqueda es **parcial**, con `Regex.containsMatchIn`:

```
patrón: codigo          mensaje: "Tu codigo es 4821"     → CASA
patrón: ^codigo$        mensaje: "Tu codigo es 4821"     → NO casa
patrón: ^codigo$        mensaje: "codigo"                → CASA
patrón: CODIGO          mensaje: "tu codigo es 4821"     → NO casa
patrón: (?i)CODIGO      mensaje: "tu codigo es 4821"     → CASA
```

El remitente se compara **tal como llega en la PDU**, sin normalizar, porque muchos avisos llegan
de cabeceras alfanuméricas (`BANCO`, `AMAZON`) y no de un número.

### Orden de evaluación

```
reglas activas, ordenadas por `orden` ascendente
  │
  ├─ regla con regex inválida  → se descarta, se anota en el log, sigue la evaluación
  ├─ regla que no casa         → sigue la evaluación
  └─ regla que casa            → crea un reenvío
                                 │
                                 ├─ continuar = false → FIN
                                 └─ continuar = true  → sigue la evaluación
```

Una expresión regular que no compila **no interrumpe la evaluación**: la regla se descarta y las
demás siguen funcionando. Pero queda registrada como `ERROR` en el log, porque si no, la regla
«no funcionaría» sin explicación. El editor valida los patrones mientras se teclean para que ese
caso no llegue a producción.

### Plantilla

```
{mensaje}    → cuerpo del SMS recibido
{telefono}   → número del remitente
{fecha}      → fecha y hora de recepción, en hora local
```

`{mensaje}` se sustituye **en último lugar**, a propósito. El cuerpo del SMS es contenido ajeno:
si se sustituyera primero, un mensaje que contuviera literalmente `{telefono}` acabaría
revelando el número del remitente en un sitio donde la plantilla no lo pedía. Sustituyéndolo al
final, el texto recibido nunca se vuelve a escanear.

```
plantilla: "De {telefono}: {mensaje}"
mensaje:   "hoy es {fecha}"
resultado: "De +34600112233: hoy es {fecha}"   ← el marcador del cuerpo queda literal
```

### Coste de la plantilla

Un SMS GSM-7 admite 160 caracteres. Un prefijo como `De +34600112233: ` consume 17, así que un
mensaje de 160 caracteres reenviado con ese prefijo se convierte en **dos SMS**, y se tarifica
como dos. El editor muestra el número de partes que generaría la plantilla con un ejemplo, y el
panel de prueba lo muestra para el texto real.

### Exportar e importar

El juego de reglas se exporta a JSON versionado:

```json
{
  "version": 1,
  "exportado": "2026-10-07T19:30:00Z",
  "reglas": [
    {
      "nombre": "Banco",
      "regex_telefono": "^BANCO$",
      "regex_mensaje": "",
      "destino": "+34600112233",
      "plantilla": "De {telefono}: {mensaje}",
      "activa": true,
      "continuar": false
    }
  ]
}
```

Los **ids no viajan** en el fichero: son locales a la base de datos de cada dispositivo y
reutilizarlos provocaría colisiones. Importar **añade** reglas nuevas al final, conservando el
orden relativo del fichero; nunca reemplaza las existentes. Cada regla se valida antes de
escribir —expresiones que compilen, destino y nombre no vacíos— y las rechazadas se cuentan
aparte.

---

## 10. Mecanismo de envío

### Por qué `SmsSender` suspende

`SmsManager.sendMultipartTextMessage()` retorna de inmediato, **antes** de que el mensaje haya
salido del dispositivo. El resultado llega después, como un broadcast dirigido al `PendingIntent`
que se entrega por cada parte.

Si el worker no esperara ese broadcast, todos los envíos parecerían correctos y los fallos reales
desaparecerían: el reenvío quedaría marcado como enviado sin que nadie hubiera recibido nada. Por
eso `SmsSender.enviar()`:

1. Comprueba el permiso `SEND_SMS` y la validez del destino.
2. Obtiene el `SmsManager` adecuado: desde API 31 el servicio del sistema, y
   `createForSubscriptionId()` si hay una SIM elegida; por debajo, los métodos estáticos, que
   están obsoletos pero son los únicos disponibles con `minSdk 26`.
3. Divide el texto con `divideMessage()`, que respeta la codificación.
4. Registra un receptor `RECEIVER_NOT_EXPORTED` en una acción con UUID propio de ese envío.
5. Entrega un `PendingIntent` por parte, con `FLAG_ONE_SHOT or FLAG_IMMUTABLE` y un `requestCode`
   distinto — con el mismo código y la misma acción, `getBroadcast()` devolvería el mismo
   `PendingIntent` para todas las partes.
6. Suspende hasta que todas las partes reportan, o hasta que se agota
   `AppConfig.timeoutEnvioSegundos`.

### Mapeo de resultados

| Código de resultado | Interpretación | Acción WorkManager | Estado |
|---------------------|----------------|--------------------|--------|
| `RESULT_OK` en todas las partes | Enviado | `success()` | `ENVIADO` |
| `RESULT_ERROR_NO_SERVICE` | Sin cobertura | `retry()` | `PENDIENTE`, intentos++ |
| `RESULT_ERROR_RADIO_OFF` | Radio apagada o modo avión | `retry()` | `PENDIENTE`, intentos++ |
| `RESULT_ERROR_GENERIC_FAILURE` | El operador no concreta | `retry()` | `PENDIENTE`, intentos++ |
| Sin confirmación en plazo | Timeout | `retry()` | `PENDIENTE`, intentos++ |
| `RESULT_ERROR_NULL_PDU` | Mensaje inválido | `failure()` | `FALLIDO` |
| Destino no válido | `IllegalArgumentException` | `failure()` | `FALLIDO` |
| Permiso denegado | `SecurityException` | `failure()` | `FALLIDO` |
| `runAttemptCount >= maxReintentos` | Agotado | `failure()` | `FALLIDO` |

### Mensajes multipart

Cada parte se tarifica y se confirma por separado. La decisión tomada es: **si cualquier parte
falla, el reenvío entero se considera fallido y se reintenta completo**, aceptando que el destino
pueda ver duplicada la parte que sí llegó.

La alternativa —dar por bueno un mensaje al que le falta un trozo— deja al destinatario con un
texto truncado y sin forma de saberlo, lo que es peor que un duplicado visible.

### Idempotencia

Es de cliente, en dos niveles:

- **Antes de crear reenvíos:** `ProcesarSmsEntranteUseCase.procesar()` comprueba si el SMS ya
  tiene reenvíos. Si los tiene, no crea más y solo reencola los pendientes. Esto es lo que hace
  seguro que el rescate de huérfanos reprocese un SMS que se quedó a medias.
- **Antes de cada intento:** `SmsDispatchWorker` lee el estado del reenvío y devuelve éxito sin
  tocar la radio si ya está `ENVIADO`.

Además, `ColaDeEnvios` usa `ExistingWorkPolicy.KEEP`, así que un reenvío nunca tiene dos trabajos
simultáneos.

---

## 11. Protecciones antibucle y de coste

Reenviar SMS tiene dos propiedades que una petición HTTP no tenía: **cuesta dinero** y **puede
realimentarse**. Si el destino contesta, su respuesta entra por `SmsReceiver` como cualquier otro
SMS; si casa con una regla, se reenvía; y con los reintentos automáticos detrás, un error de
configuración se convierte en una factura.

Tres protecciones, en el orden en que actúan:

### 1. El remitente es un destino configurado

Antes de evaluar nada, se comprueba si el remitente del SMS coincide con alguno de los destinos
de las reglas activas. Si coincide, el SMS se marca `DESCARTADO` con su motivo y se registra como
`BUCLE_EVITADO`. Controlada por `AppConfig.protegerBucles`.

### 2. El destino de una regla es el propio remitente

Tras evaluar, las coincidencias cuyo destino sea el remitente del SMS se omiten una a una, cada
una con su entrada en el log. Si **todas** las coincidencias eran circulares, el SMS queda
`DESCARTADO`. También controlada por `protegerBucles`.

### 3. Límite de reenvíos por minuto

`AppConfig.maxReenviosPorMinuto` (10 por defecto) limita cuántos reenvíos pueden crearse en los
últimos 60 segundos. El exceso se descarta y se registra. **Esta protección está siempre
activa**, incluso con `protegerBucles` desactivado: es el cortafuegos económico del sistema.

### Comparación de números

Las dos primeras protecciones dependen de reconocer que `+34600112233`, `0034 600 112 233` y
`600112233` son el mismo abonado. `NormalizadorTelefono` se queda con los dígitos y compara los
**nueve últimos**.

La comparación por sufijo es deliberada: evita tener que conocer los prefijos internacionales de
cada país, que es exactamente el tipo de dato que se queda obsoleto. Nueve dígitos es la longitud
del número nacional en España y en la mayoría de planes europeos.

Los remitentes alfanuméricos no tienen dígitos suficientes, así que **nunca** se consideran
iguales a nada, ni siquiera a otro texto idéntico. Es correcto: a una cabecera como `BANCO` no se
le puede reenviar nada, luego no puede formar un bucle.

---

## 12. Base de datos

### Esquema

SQLite vía Room, versión **1**, con WAL activado.

```sql
CREATE TABLE sms (
    id               TEXT    PRIMARY KEY NOT NULL,  -- UUID de recepción
    telefono         TEXT    NOT NULL,              -- tal como llega en la PDU
    mensaje          TEXT    NOT NULL,              -- partes multipart concatenadas
    fecha_recepcion  INTEGER NOT NULL,              -- epoch ms
    estado           TEXT    NOT NULL,              -- EstadoSms
    motivo_descarte  TEXT                           -- solo si estado = DESCARTADO
);
CREATE INDEX index_sms_estado ON sms(estado);
CREATE INDEX index_sms_fecha_recepcion ON sms(fecha_recepcion);

CREATE TABLE reglas (
    id              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    orden           INTEGER NOT NULL,                -- prioridad ascendente
    nombre          TEXT    NOT NULL,
    regex_telefono  TEXT,                            -- null = no filtra
    regex_mensaje   TEXT,                            -- null = no filtra
    destino         TEXT    NOT NULL,
    plantilla       TEXT    NOT NULL DEFAULT '{mensaje}',
    activa          INTEGER NOT NULL DEFAULT 1,
    continuar       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX index_reglas_orden ON reglas(orden);

CREATE TABLE reenvios (
    id              TEXT    PRIMARY KEY NOT NULL,   -- UUID
    sms_id          TEXT    NOT NULL,
    regla_id        INTEGER,                         -- null si la regla se borró
    nombre_regla    TEXT    NOT NULL,                -- copia: sobrevive al borrado
    destino         TEXT    NOT NULL,
    texto_final     TEXT    NOT NULL,                -- congelado al evaluar
    estado          TEXT    NOT NULL,                -- EstadoReenvio
    intentos        INTEGER NOT NULL DEFAULT 0,
    ultimo_error    TEXT,
    fecha_creacion  INTEGER NOT NULL,
    fecha_envio     INTEGER,
    partes          INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (sms_id)   REFERENCES sms(id)    ON DELETE CASCADE,
    FOREIGN KEY (regla_id) REFERENCES reglas(id) ON DELETE SET NULL
);
CREATE INDEX index_reenvios_sms_id   ON reenvios(sms_id);
CREATE INDEX index_reenvios_regla_id ON reenvios(regla_id);
CREATE INDEX index_reenvios_estado   ON reenvios(estado);

CREATE TABLE log_entries (
    id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    tipo        TEXT    NOT NULL,                    -- LogTipo
    sms_id      TEXT,
    reenvio_id  TEXT,
    destino     TEXT,                                -- desnormalizado
    detalle     TEXT    NOT NULL,
    timestamp   INTEGER NOT NULL
);
CREATE INDEX index_log_entries_sms_id    ON log_entries(sms_id);
CREATE INDEX index_log_entries_timestamp ON log_entries(timestamp);
```

### Estados

```
EstadoSms
  PENDIENTE   recibido, las reglas aún no se han evaluado
  PROCESADO   evaluado, al menos una regla casó
  SIN_REGLA   evaluado, ninguna regla casó. No es un error
  DESCARTADO  bloqueado por una protección

EstadoReenvio
  PENDIENTE   pendiente de enviar, o con un intento fallido que se reintentará
  ENVIADO     todas las partes confirmaron RESULT_OK
  FALLIDO     fallo permanente, no se volverá a intentar

LogTipo
  SMS_RECIBIDO · REGLA_APLICADA · SMS_REENVIADO
  SIN_REGLA · BUCLE_EVITADO · ERROR · SISTEMA
```

### Decisiones de diseño

- **`sms` no guarda estado de envío.** Los intentos, los errores y las fechas de confirmación
  están en `reenvios`, una fila por destino. Un SMS que casa con dos reglas tiene dos reenvíos
  con suertes posiblemente distintas, y un único campo `enviado` no podría representarlo.
- **`texto_final` se congela** en el momento de la evaluación. Editar o borrar la regla después
  no altera lo que ya estaba encolado.
- **`nombre_regla` está desnormalizado** para que el historial siga siendo legible cuando la
  regla se borre: la clave ajena es `SET NULL`, no `CASCADE`.
- **`log_entries.destino` también está desnormalizado**, para poder leer el registro de principio
  a fin sin cruzar tablas, y para que siga teniendo sentido cuando el reenvío se haya purgado.

### Por qué el esquema empieza en la versión 1

SMSGateway llegó a la versión 2 con una migración 1→2. Al cambiar el `applicationId`, Android
trata SMStoSMS como una aplicación distinta con su propio directorio de datos, y además el fichero
pasó a llamarse `smstosms.db`. Ningún dispositivo tiene esta base de datos, así que no hay nada
que migrar y la historia anterior no aportaba información: se colapsó en un esquema v1 limpio.

### Migraciones

Los esquemas se exportan a `app/schemas/` y **se versionan**: son la referencia para escribir
migraciones. Nunca se usa `fallbackToDestructiveMigration()`, porque perdería SMS y reenvíos
pendientes.

> Mientras no haya una 1.0.0 publicada, el esquema v1 sigue siendo editable y la forma de aplicar
> un cambio en desarrollo es desinstalar la app o borrar sus datos. A partir de la primera
> release, cualquier cambio exige subir la versión y escribir su `Migration`.

---

## 13. Mantenimiento

### Tareas periódicas automáticas

| Worker | Intervalo | Qué hace |
|--------|-----------|----------|
| `OrphanRescueWorker` | 30 s (auto-encadenado) | Retoma SMS sin evaluar y reencola reenvíos sin worker vivo |
| `HealthMonitorWorker` | 15 min (periódico) | Purga reenvíos enviados, SMS tramitados y logs antiguos |

`OrphanRescueWorker` se auto-encadena porque WorkManager no admite trabajos periódicos de menos
de 15 minutos, y media hora de retraso en un reenvío es inaceptable.

Ninguno de los dos borra nada pendiente: los SMS en `PENDIENTE` y los reenvíos sin confirmar se
conservan por antiguos que sean, porque todavía tienen trabajo que hacer.

### Ajustar las políticas de retención

En `util/Constants.kt`:

```kotlin
const val RETENTION_DELIVERED_DAYS = 30L   // SMS tramitados y reenvíos enviados
const val RETENTION_LOGS_DAYS      = 30L   // entradas del registro
```

### Parámetros ajustables en la app

En **Configuración**, persistidos en DataStore:

| Parámetro | Por defecto | Rango | Qué controla |
|-----------|-------------|-------|--------------|
| Evitar bucles de reenvío | Activado | — | Protecciones 1 y 2 de §11 |
| Máximo de reenvíos por minuto | 10 | 1–100 | Protección 3 de §11 |
| Espera de confirmación | 60 s | 10–300 | Cuánto se espera el `sentIntent` |
| Máximo de reintentos | 10 | 1–100 | Cuándo se da un reenvío por perdido |
| Intervalo inicial de reintento | 30 s | 5–3600 | Backoff lineal de WorkManager |
| SIM de envío | Predeterminada | — | Solo visible con dos o más tarjetas |

### Monitorización vía ADB

```bash
# Logs en tiempo real
adb logcat -s "SmsReceiver" "SmsIngestionService" "SmsSender" \
              "SmsDispatchWorker" "OrphanRescueWorker" "HealthMonitorWorker"

# Base de datos
adb shell run-as com.capicua.smstosms ls databases/
adb exec-out run-as com.capicua.smstosms cat databases/smstosms.db > smstosms.db
sqlite3 smstosms.db "SELECT estado, COUNT(*) FROM sms GROUP BY estado;"
sqlite3 smstosms.db "SELECT destino, estado, intentos, ultimo_error FROM reenvios;"

# Configuración
adb shell run-as com.capicua.smstosms cat files/datastore/smstosms_config.preferences_pb

# Estado de los workers
adb shell dumpsys jobscheduler | grep -A 5 capicua
```

### Exportar el registro desde la app

**Logs** → **Exportar** genera un fichero de texto en la caché y lo comparte mediante
`FileProvider`. Incluye el tipo, el SMS, el destino y el detalle de cada entrada.

---

## 14. Posibles incidencias

### El SMS se recibe pero no se reenvía

Lo más probable es que **ninguna regla case**. La pantalla de Inicio lo dice: el SMS aparece como
«Sin regla». Para confirmarlo:

1. **Reglas** → **Probar**, pega el remitente y el texto exactos, y mira qué dice.
2. Revisa que la regla esté **activa**. Una desactivada no se evalúa.
3. Comprueba las mayúsculas: los patrones las distinguen. `CODIGO` no casa con `codigo`; usa
   `(?i)`.
4. Comprueba el remitente: si llega como `BANCO` y tu patrón espera `^\+34`, no casará nunca.

Si la regla sí casa pero el reenvío no sale, revisa el registro filtrando por **Errores**.

### El SMS aparece como «Descartado»

Lo bloqueó una protección. El motivo está en la propia fila de Inicio y en el registro, filtrando
por **Bucles**. Los tres motivos posibles:

- El remitente es uno de los destinos configurados.
- Todas las reglas que casaron reenviaban al propio remitente.
- Se superó el límite de reenvíos por minuto.

Si el primero es un falso positivo —por ejemplo, quieres reenviar de A a B y también de B a A—
tendrás que desactivar **Evitar bucles de reenvío** en Configuración y asumir el riesgo.

### Los reenvíos fallan con «sin servicio de red»

El dispositivo no tiene cobertura de voz/SMS en ese momento. Es un fallo transitorio y el worker
reintenta con el backoff configurado. Si persiste:

```bash
adb shell dumpsys telephony.registry | grep -i "mServiceState\|mSignalStrength"
```

Revisa también que la SIM elegida en Configuración sea la que tiene cobertura y saldo.

### Los reenvíos fallan con «fallo genérico del operador»

`RESULT_ERROR_GENERIC_FAILURE` es lo que devuelve el operador cuando no concreta el motivo. Causas
habituales: saldo agotado, tarifa sin SMS incluidos, número destino bloqueado, o un límite
antispam del propio operador por enviar muchos mensajes seguidos.

Se trata como transitorio y se reintenta, pero si todos los reenvíos fallan así, el problema está
en la línea y no en la aplicación.

### Nada se reenvía con la pantalla apagada

Falta la exención del modo Doze:

```bash
adb shell dumpsys deviceidle whitelist | grep capicua
# Si no aparece:
adb shell dumpsys deviceidle whitelist +com.capicua.smstosms
```

En Xiaomi, Huawei y Samsung hace falta además activar el **inicio automático** y quitar la app de
la lista de aplicaciones suspendidas. Ver [`CLAUDE.md`](../CLAUDE.md).

### La app no recibe SMS tras reiniciar el dispositivo

```bash
# Verificar que el permiso de arranque está declarado y concedido
adb shell dumpsys package com.capicua.smstosms | grep -i boot

# Verificar que los workers están programados
adb shell dumpsys jobscheduler | grep -A 5 capicua
```

En algunos fabricantes hay que abrir la aplicación una vez después de instalarla para que el
`BOOT_COMPLETED` se entregue.

### `ForegroundServiceStartNotAllowedException`

A partir de Android 12 no se puede iniciar un foreground service desde segundo plano, salvo en
casos exentos. La recepción de un SMS **es** uno de ellos, así que no debería ocurrir. Si aparece
en el log, comprueba que el crash viene de `SmsReceiver` y no de otra ruta, y que el permiso
`RECEIVE_SMS` está concedido: sin él, el broadcast no otorga la exención.

### Un reenvío llegó duplicado al destino

Es el comportamiento documentado de los mensajes multipart: si una parte falló y otra no, se
reintenta el reenvío completo. Revisa en el registro si ese reenvío tuvo más de un intento y si
`partes` es mayor que 1.

Para reducirlo, acorta la plantilla hasta que el reenvío quepa en un solo SMS. El editor de reglas
muestra cuántas partes genera.

### La base de datos crece sin control

`HealthMonitorWorker` purga cada 15 minutos, pero solo lo ya tramitado. Si crece, es que hay
mucho pendiente acumulado:

```sql
SELECT estado, COUNT(*) FROM sms GROUP BY estado;
SELECT estado, COUNT(*) FROM reenvios GROUP BY estado;
```

Muchos `PENDIENTE` apuntan a un problema de envío sostenido, no de retención. Muchos `FALLIDO` se
purgan con el resto de SMS tramitados a los 30 días.

### Consumo de batería excesivo

Normalmente es `OrphanRescueWorker` encontrando trabajo cada 30 segundos, lo que significa que
hay reenvíos atascados reintentándose en bucle. Revisa los pendientes y sus `ultimo_error`; si
son fallos permanentes mal clasificados, el worker seguirá insistiendo hasta agotar los
reintentos.

Subir `Intervalo inicial de reintento` y bajar `Máximo de reintentos` reduce el consumo a costa
de tardar más en entregar lo que sí es recuperable.
