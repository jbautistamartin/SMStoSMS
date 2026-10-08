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
9. [Configuración](#9-configuración)
10. [Flujo completo del sistema](#10-flujo-completo-del-sistema)
11. [Base de datos](#11-base-de-datos)
12. [API corporativa](#12-api-corporativa)
13. [Mantenimiento](#13-mantenimiento)
14. [Posibles incidencias](#14-posibles-incidencias)

---

## 1. Descripción del sistema

**SMStoSMS** es una aplicación Android diseñada para ejecutarse en un **dispositivo dedicado** (p. ej. un terminal Android industrial o un teléfono fijo con SIM) conectado a la red WiFi corporativa. Su función es recibir SMS enviados al número de la SIM y reenviarlos automáticamente a una API corporativa vía HTTPS.

### Casos de uso típicos

- Recepción centralizada de SMS de clientes en un CRM
- Códigos OTP de proveedores externos reenviados al backend
- Notificaciones de sistemas de monitorización vía SMS

### Garantías del sistema

| Garantía | Mecanismo |
|----------|-----------|
| Ningún SMS se pierde aunque el proceso sea matado | `ForegroundService` + outbox en Room |
| Los SMS llegan a la API exactamente una vez | `X-Idempotency-Key` + check `enviado=true` al inicio del Worker |
| Los SMS atascados se recuperan solos | `HealthMonitorWorker` periódico cada 15 min |
| La app arranca sola tras un reinicio del dispositivo | `BootReceiver` + WorkManager |

---

## 2. Arquitectura

La aplicación sigue **Clean Architecture** con separación estricta en tres capas y el patrón **MVVM** en presentación.

```
┌─────────────────────────────────────────────────────────┐
│                   PRESENTATION LAYER                    │
│  MainActivity · DashboardFragment · LogsFragment        │
│  SettingsFragment · DashboardViewModel · LogsViewModel  │
│  SettingsViewModel · SmsListAdapter · LogsAdapter       │
├─────────────────────────────────────────────────────────┤
│                     DOMAIN LAYER                        │
│  SmsMessage · LogEntry · LogTipo                        │
│  SaveSmsUseCase · GetSmsListUseCase                     │
├─────────────────────────────────────────────────────────┤
│                      DATA LAYER                         │
│  Room (SmsEntity, LogEntity, SmsDao, LogDao)            │
│  OkHttp (peticiones GET con plantilla de URL)           │
│  DataStore (ConfigDataStore, AppConfig)                 │
│  WorkManager (SmsDispatchWorker, HealthMonitorWorker)   │
│  SmsRepositoryImpl · LogRepositoryImpl                  │
├─────────────────────────────────────────────────────────┤
│              ANDROID SYSTEM LAYER                       │
│  SmsReceiver · SmsIngestionService · BootReceiver       │
│  NetworkMonitor · SmsToSmsApplication                    │
└─────────────────────────────────────────────────────────┘
```

### Principios aplicados

| Principio | Aplicación |
|-----------|------------|
| **Single Responsibility** | Cada clase tiene una responsabilidad: Receiver parsea, Service persiste, Worker envía |
| **Open/Closed** | `SmsRepository` es una interfaz; la implementación se puede sustituir sin tocar la UI |
| **Dependency Inversion** | Las capas superiores dependen de interfaces, no de implementaciones (Hilt inyecta) |
| **Outbox Pattern** | Room es el outbox: persiste primero, despacha después — nunca al revés |
| **Idempotencia** | UUID del SMS: el Worker verifica `enviado=true` en Room antes de cada intento |

---

## 3. Diagrama de flujo

```
┌──────────────────────────────────────────────────────────────────────┐
│                         SMS ENTRANTE                                  │
└──────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────┐
│   SmsReceiver       │  onReceive() — hilo principal, max 10 s
│   (BroadcastReceiver│  1. Parsear PDUs (multipart)
│    prioridad max)   │  2. Extraer: número, texto, timestamp
└─────────────────────┘  3. Construir SmsMessage + UUID
          │
          │ startForegroundService()
          ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    SmsIngestionService (ForegroundService)           │
│   onStartCommand()                                                   │
│   1. startForeground() — notificación visible, < 5 s obligatorio     │
│   2. SaveSmsUseCase.invoke(sms) en Dispatchers.IO                   │
│      ├─ SmsRepository.guardar()    → Room INSERT (atómico)           │
│      ├─ SmsRepository.encolarEnvio() → WorkManager.enqueueUnique()  │
│      └─ LogRepository.insertar(SMS_RECIBIDO)                        │
│   3. stopSelf(startId) — se para solo si era el último SMS activo   │
└─────────────────────────────────────────────────────────────────────┘
          │
          │ WorkManager ejecuta cuando hay red
          ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      SmsDispatchWorker                               │
│   doWork()                                                           │
│   1. Leer configuración de ConfigDataStore (plantilla URL, reintentos)│
│   2. Obtener SMS de Room por ID                                       │
│   3. Verificar que no esté ya enviado (idempotencia interna)          │
│   4. GET <url_plantilla con {telefono},{mensaje},{fecha} sustituidos> │
│      ├─ HTTP 2xx  → marcarComoEnviado() + log SMS_ENVIADO           │
│      ├─ HTTP 4xx (≠429) → registrarError() + log ERROR → FAILURE    │
│      ├─ HTTP 5xx/429 → registrarError() + log ERROR → RETRY         │
│      ├─ Timeout/IOException → registrarError() + RETRY              │
│      └─ Max reintentos → log ERROR → FAILURE                        │
└─────────────────────────────────────────────────────────────────────┘
          │
          │ Cada 15 minutos
          ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    HealthMonitorWorker (periódico)                   │
│   doWork()                                                           │
│   1. Buscar SMS con enviado=false y sin Worker activo en WorkManager│
│   2. Re-encolar cada SMS huérfano → encolarEnvio()                  │
│   3. Eliminar SMS enviados > 7 días                                  │
│   4. Eliminar logs > 30 días                                         │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 4. Diagrama de capas

```
  ╔═══════════════════════════════════════════════╗
  ║           PRESENTATION (UI/UX)                ║
  ║  Fragment → ViewModel → UseCase/Repository    ║
  ╚═══════════════╤═══════════════════════════════╝
                  │ observa Flows
  ╔═══════════════╧═══════════════════════════════╗
  ║              DOMAIN (negocio puro)            ║
  ║  SmsMessage  LogEntry  LogTipo                ║
  ║  SaveSmsUseCase  GetSmsListUseCase            ║
  ╚═══════════════╤═══════════════════════════════╝
                  │ interfaces
  ╔═══════════════╧═══════════════════════════════╗
  ║                DATA (infraestructura)         ║
  ║  ┌──────────┐ ┌──────────┐ ┌──────────────┐ ║
  ║  │  Room    │ │  OkHttp  │ │  DataStore   │ ║
  ║  │ SmsDao   │ │(GET calls)│ │ ConfigDS    │ ║
  ║  │ LogDao   │ │          │ │ AppConfig   │ ║
  ║  └──────────┘ └──────────┘ └──────────────┘ ║
  ║  ┌──────────────────────────────────────────┐ ║
  ║  │         WorkManager                      │ ║
  ║  │  SmsDispatchWorker  HealthMonitorWorker  │ ║
  ║  └──────────────────────────────────────────┘ ║
  ╚═══════════════════════════════════════════════╝
```

---

## 5. Dependencias

### Versiones principales

| Librería | Versión | Propósito |
|----------|---------|-----------|
| Android Gradle Plugin | 8.5.2 | Build system |
| Kotlin | 2.0.21 | Lenguaje |
| compileSdk / targetSdk | 35 | Android 15 |
| minSdk | 26 | Android 8.0 (Oreo) |
| Hilt | 2.52 | Inyección de dependencias |
| Hilt Work | 1.2.0 | Integración Hilt + WorkManager |
| Room | 2.6.1 | Base de datos local (SQLite) |
| WorkManager | 2.9.1 | Tareas en background con reintentos |
| DataStore Preferences | 1.1.1 | Configuración persistente reactiva |
| OkHttp | 4.12.0 | HTTP client + logging |
| Coroutines | 1.9.0 | Concurrencia asíncrona |
| Coroutines Guava | 1.9.0 | `ListenableFuture.await()` en Workers |
| Timber | 5.0.1 | Logging estructurado |
| Navigation Component | 2.8.2 | Navegación entre Fragments |
| Material Design 3 | 1.12.0 | Componentes UI |

### Árbol de dependencias relevante

```
app
├── hilt-android (DI container)
│   └── hilt-work (Workers con @AssistedInject)
├── room-runtime + room-ktx (persistence)
│   └── SQLite WAL mode
├── work-runtime-ktx (background tasks)
│   └── work-runtime-guava (ListenableFuture compat)
│       └── kotlinx-coroutines-guava (.await() extension)
├── okhttp + logging-interceptor (HTTP client)
├── datastore-preferences (config storage)
├── kotlinx-coroutines-android (Flow, viewModelScope)
└── navigation-fragment-ktx + navigation-ui-ktx
```

---

## 6. Cómo compilar

### Requisitos previos

- **Android Studio** Ladybug (2024.2.1) o superior
- **JDK 17** (incluido en Android Studio)
- **Android SDK** con API 35 instalada
- Acceso a internet para descargar dependencias Gradle

### Pasos

```bash
# 1. Clonar / descomprimir el proyecto
cd C:\Bansi\sms

# 2. Build debug (instala en dispositivo conectado)
./gradlew assembleDebug

# 3. Build release (requiere keystore configurado)
./gradlew assembleRelease

# 4. Instalar directamente en dispositivo
./gradlew installDebug
```

### Variables de entorno opcionales

No hay variables de entorno necesarias. La URL del servidor y el token se configuran **en tiempo de ejecución** desde la pantalla de Configuración de la app.

### Variantes de build

| Variante | applicationId | Características |
|----------|--------------|-----------------|
| `debug` | `com.capicua.smstosms.debug` | Logs Timber en consola, HTTP logging HEADERS, sin ProGuard |
| `release` | `com.capicua.smstosms` | ProGuard habilitado, HTTP logging deshabilitado |

---

## 7. Cómo instalar

### Instalación vía ADB (dispositivo dedicado)

```bash
# 1. Habilitar depuración USB en el dispositivo
#    Ajustes → Acerca del teléfono → Número de compilación (×7) → Opciones de desarrollador → Depuración USB

# 2. Verificar conexión
adb devices

# 3. Instalar APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4. Conceder permisos automáticamente (sin diálogo de usuario)
adb shell pm grant com.capicua.smstosms android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smstosms android.permission.READ_SMS
adb shell pm grant com.capicua.smstosms android.permission.POST_NOTIFICATIONS

# 5. Eximir de Doze mode / optimización de batería (OBLIGATORIO)
adb shell dumpsys deviceidle whitelist +com.capicua.smstosms

# Verificar que la app está en la lista
adb shell dumpsys deviceidle whitelist
# Debe aparecer una línea con: com.capicua.smstosms

# Para revertir (quitar de la lista):
adb shell dumpsys deviceidle whitelist -com.capicua.smstosms
```

### Por qué es obligatorio el paso 5

Android activa el **modo Doze** cuando detecta que el dispositivo lleva varios minutos estático y con la pantalla apagada. En ese estado el sistema suspende:
- Acceso a red
- Alarmas y timers
- Sincronización en background
- **WorkManager** (salvo excepciones explícitas)

Sin la exención, el `SmsDispatchWorker` puede quedar aplazado indefinidamente aunque el dispositivo tenga WiFi activo. El SMS aparece como "Pendiente" con `intentos = 0` porque el worker nunca llegó a ejecutarse.

Con la exención, el sistema incluye la app en la lista blanca de Doze y WorkManager puede ejecutar workers en cualquier momento, incluso con pantalla apagada.

### Método alternativo desde el propio dispositivo (sin USB)

Si no hay acceso a ADB, el mismo efecto se consigue desde la interfaz del teléfono. La ruta exacta varía por fabricante:

| Fabricante | Ruta |
|-----------|------|
| **Stock Android / Pixel** | Ajustes → Aplicaciones → SMStoSMS → Batería → **Sin restricciones** |
| **Samsung (One UI)** | Ajustes → Mantenimiento del dispositivo → Batería → Límites de uso en segundo plano → Aplicaciones sin suspender → Añadir → SMStoSMS |
| **Xiaomi / MIUI / HyperOS** | Ajustes → Aplicaciones → Administrar aplicaciones → SMStoSMS → Ahorro de batería → **Sin restricciones** + activar **Inicio automático** |
| **Huawei / EMUI** | Ajustes → Aplicaciones → SMStoSMS → Consumo de batería → desactivar Gestión inteligente → **Sin restricciones** |
| **OnePlus / OxygenOS** | Ajustes → Aplicaciones → SMStoSMS → Batería → Optimización de batería → **No optimizar** |
| **Cualquier fabricante** | Buscar "Optimización de batería" en Ajustes → Todas las aplicaciones → SMStoSMS → **No optimizar** |

### Instalación vía MDM (producción)

Para despliegue masivo en flotas de dispositivos, se recomienda:

1. Generar APK/AAB firmado en release
2. Subir a la consola MDM (ej. VMware Workspace ONE, Microsoft Intune)
3. Pre-conceder los permisos vía política MDM:
   ```xml
   <!-- Ejemplo Android Management API -->
   <permissionGrants>
     <packageName>com.capicua.smstosms</packageName>
     <permissions>RECEIVE_SMS READ_SMS POST_NOTIFICATIONS</permissions>
     <policy>GRANT</policy>
   </permissionGrants>
   ```
4. Configurar URL y token vía Managed Configurations (opcional, para config remota)

---

## 8. Permisos requeridos

| Permiso | Tipo | Obligatorio | Razón |
|---------|------|-------------|-------|
| `RECEIVE_SMS` | Peligroso | ✅ Sí | Recibir SMS del sistema de radio |
| `READ_SMS` | Peligroso | ✅ Sí | Declarado como complemento a RECEIVE_SMS |
| `INTERNET` | Normal | ✅ Sí | Enviar SMS a la API corporativa |
| `ACCESS_NETWORK_STATE` | Normal | ✅ Sí | Detectar conectividad de red |
| `FOREGROUND_SERVICE` | Normal | ✅ Sí | Mantener proceso vivo durante persistencia |
| `FOREGROUND_SERVICE_DATA_SYNC` | Normal | ✅ Sí | Tipo de foreground service (API 34+) |
| `RECEIVE_BOOT_COMPLETED` | Normal | ✅ Sí | Re-iniciar Workers tras reinicio del dispositivo |
| `WAKE_LOCK` | Normal | ✅ Sí | WorkManager necesita mantener CPU despierta |
| `POST_NOTIFICATIONS` | Peligroso | ✅ Sí (API 33+) | Mostrar notificación del ForegroundService |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Normal | ⚠️ Recomendado | Evitar que el sistema mate el proceso en espera |

### Seguridad del BroadcastReceiver

El `SmsReceiver` declara:
```xml
android:permission="android.permission.BROADCAST_SMS"
```

Esto garantiza que **solo el proceso del sistema de telefonía** puede enviar el broadcast `SMS_RECEIVED`. Cualquier aplicación que intente falsificar este broadcast sin ese permiso será ignorada por Android.

---

## 9. Configuración

Toda la configuración operativa se gestiona en la pantalla **"Config"** de la aplicación y se persiste en `DataStore<Preferences>` en el almacenamiento privado del dispositivo.

### Parámetros configurables

| Campo | Descripción | Validación | Defecto |
|-------|-------------|------------|---------|
| **Plantilla de URL** | URL completa con marcadores `{telefono}`, `{mensaje}` y `{fecha}`. El marcador `{mensaje}` es obligatorio. | No vacía, debe contener `{mensaje}` | — |
| **Timeout (s)** | Segundos máximos por petición HTTP | Entre 5 y 120 | 30 |
| **Máx. reintentos** | Intentos antes de marcar el SMS como error permanente | Entre 1 y 100 | 10 |
| **Intervalo reintento (s)** | Backoff inicial entre reintentos (WorkManager aplica exponencial) | Entre 5 y 3600 | 30 |
| **Aceptar certs. inválidos** | Confía en certificados SSL autofirmados o caducados | Booleano | false |

### Petición generada

La app sustituye los marcadores de la plantilla con los valores del SMS (URL-encoded) y lanza un `GET`:

```
# Plantilla mínima:
https://api.empresa.com/sms?msg={mensaje}

# Plantilla completa:
https://api.empresa.com/notify?phone={telefono}&msg={mensaje}&ts={fecha}
```

| Marcador | Valor | Obligatorio |
|----------|-------|-------------|
| `{mensaje}` | Cuerpo del SMS, URL-encoded | ✅ Sí |
| `{telefono}` | Número remitente, URL-encoded | No |
| `{fecha}` | Timestamp ISO-8601 UTC, URL-encoded | No |

### Seguridad de red

Por defecto la app **rechaza tráfico HTTP en claro** (`android:usesCleartextTraffic="false"`). Solo HTTPS está permitido.

Para añadir una CA corporativa de confianza, editar `res/xml/network_security_config.xml`:

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
            <!-- CA corporativa: -->
            <certificates src="@raw/mi_ca_corporativa" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

---

## 10. Flujo completo del sistema

### Recepción de SMS

```
Dispositivo remoto
    │ SMS vía red GSM/LTE
    ▼
SIM del dispositivo dedicado
    │ Radio → Sistema Android (SMS_RECEIVED broadcast)
    ▼
SmsReceiver.onReceive()                          [hilo principal]
    ├─ Validar acción del intent
    ├─ Telephony.Sms.Intents.getMessagesFromIntent()
    │   └─ Fragmentos PDU ya ordenados (3GPP + 3GPP2)
    ├─ displayOriginatingAddress (fallback: originatingAddress)
    ├─ timestampMillis del SMSC
    ├─ joinToString("") de todos los cuerpos (multipart)
    └─ UUID.randomUUID() → SmsMessage
         └─ context.startForegroundService(SmsIngestionService)
```

### Persistencia

```
SmsIngestionService.onStartCommand()            [hilo principal]
    ├─ startForeground(1001, notificación)       [< 5 s obligatorio]
    └─ serviceScope.launch(Dispatchers.IO)
         ├─ SaveSmsUseCase.invoke(sms)
         │   ├─ smsDao.insertar(SmsEntity)       [Room, ABORT si duplicado]
         │   └─ workManager.enqueueUniqueWork(   [ExistingWorkPolicy.KEEP]
         │       "sms_dispatch_<uuid>", ...)
         ├─ logRepository.insertar(SMS_RECIBIDO)
         └─ stopSelf(startId)
```

### Despacho HTTP

```
SmsDispatchWorker.doWork()                      [WorkManager thread pool]
    ├─ configDataStore.config.first()           [DataStore]
    ├─ Guardia: runAttemptCount >= maxReintentos → FAILURE
    ├─ Guardia: !config.esValida() → FAILURE
    ├─ smsDao.obtenerPorId(smsId)
    ├─ Guardia: sms.enviado == true → SUCCESS   [idempotencia]
    └─ client.newCall(Request.Builder().url(url).get().build()).execute()
         ├─ 2xx  → marcarEnviado() + log SMS_ENVIADO → SUCCESS
         ├─ 4xx≠429 → registrarError() + log ERROR → FAILURE
         ├─ 5xx/429 → registrarError() + log ERROR → RETRY
         ├─ IOException/Timeout → registrarError() + RETRY
         └─ Exception → registrarError() + RETRY
```

### Petición HTTP generada

```http
GET https://api.empresa.com/notify?phone=%2B34600000000&msg=Texto+del+SMS&ts=2024-01-15T10%3A30%3A00Z HTTP/1.1
```

No se añaden cabeceras de autenticación por defecto. Si la API requiere autenticación, incluye el token directamente en la plantilla de URL (p. ej. como query param `?token=<valor>&msg={mensaje}`).

### Monitorización de salud

```
HealthMonitorWorker.doWork()                    [cada 15 min, requiere red]
    ├─ Para cada SMS con enviado=false:
    │   └─ workManager.getWorkInfosForUniqueWork("sms_dispatch_<uuid>")
    │       └─ Si no hay Worker ENQUEUED/RUNNING/BLOCKED → encolarEnvio()
    ├─ Registrar número de re-encolados en log SISTEMA
    ├─ smsDao.eliminarEnviadosAnterioresA(now - 7 días)
    └─ logDao.eliminarAnterioresA(now - 30 días)
```

---

## 11. Base de datos

### Esquema

**Tabla `sms`** (versión 1)

| Columna | Tipo | Descripción |
|---------|------|-------------|
| `id` | TEXT PRIMARY KEY | UUID generado en recepción. Clave de idempotencia. |
| `telefono` | TEXT NOT NULL | Número remitente (formato E.164 cuando posible) |
| `mensaje` | TEXT NOT NULL | Cuerpo completo del SMS (concatenado si multipart) |
| `fecha_recepcion` | INTEGER NOT NULL | Epoch milisegundos — timestamp del SMSC |
| `enviado` | INTEGER NOT NULL DEFAULT 0 | 0=pendiente, 1=confirmado por API |
| `fecha_envio` | INTEGER NULL | Epoch ms de confirmación HTTP 2xx |
| `intentos` | INTEGER NOT NULL DEFAULT 0 | Contador acumulado de intentos |
| `ultimo_error` | TEXT NULL | Descripción del último error |

**Tabla `log_entries`** (añadida en versión 2)

| Columna | Tipo | Descripción |
|---------|------|-------------|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT | — |
| `tipo` | TEXT NOT NULL | Uno de: SMS_RECIBIDO, SMS_ENVIADO, ERROR, SISTEMA |
| `sms_id` | TEXT NULL | UUID del SMS relacionado (FK lógica) |
| `detalle` | TEXT NOT NULL | Mensaje descriptivo del evento |
| `codigo_http` | INTEGER NULL | Código HTTP si aplica (200, 404, 500…) |
| `timestamp` | INTEGER NOT NULL | Epoch milisegundos del evento |

**Índices en `log_entries`:**
- `index_log_entries_sms_id` — consultas por SMS relacionado
- `index_log_entries_timestamp` — ordenación y purga por fecha

### Historial de migraciones

| Versión | Cambio |
|---------|--------|
| 1 | Tabla `sms` inicial |
| 2 | Añadir tabla `log_entries` con índices |

### Configuración de Room

- **WAL mode** (`WRITE_AHEAD_LOGGING`): lecturas no bloquean escrituras
- **Multi-instance invalidation**: varios procesos ven los cambios en tiempo real
- **Sin `fallbackToDestructiveMigration`**: nunca se borran datos en producción

---

## 12. API corporativa

### Integración

La app lanza un `GET` a la URL construida desde la plantilla configurada por el operador. No hay un endpoint fijo ni un contrato de cabeceras obligatorio: la plantilla define completamente la forma de la petición.

### Parámetros disponibles en la plantilla

| Marcador | Ejemplo de valor (URL-encoded) | Descripción |
|----------|-------------------------------|-------------|
| `{telefono}` | `%2B34600123456` | Número E.164 del remitente |
| `{mensaje}` | `Texto+del+SMS` | Cuerpo completo del SMS (**obligatorio en la plantilla**) |
| `{fecha}` | `2024-01-15T10%3A30%3A00Z` | Timestamp ISO-8601 UTC del SMSC |

### Ejemplo de plantilla y petición generada

```
# Plantilla configurada:
https://api.empresa.com/sms?token=secret123&phone={telefono}&msg={mensaje}&ts={fecha}

# GET resultante:
GET https://api.empresa.com/sms?token=secret123&phone=%2B34600123456&msg=Texto+del+SMS&ts=2024-01-15T10%3A30%3A00Z
```

### Respuestas esperadas

| Código HTTP | Comportamiento en la app |
|-------------|--------------------------|
| 2xx (200, 201, 204) | SMS marcado como enviado. Worker termina con éxito. |
| 4xx excepto 429 | Error permanente. No se reintenta. SMS queda con `enviado=false` y `ultimoError`. |
| 429 (rate limit) | Reintento con backoff exponencial. |
| 5xx | Reintento con backoff exponencial. |
| Timeout / sin red | Reintento con backoff exponencial. |

### Idempotencia

La idempotencia se implementa **en el cliente**: antes de cada intento, el Worker verifica en Room si `enviado=true`. Si el SMS ya fue marcado como enviado (p. ej. en un reintento anterior cuya respuesta no llegó a tiempo), el Worker termina con éxito sin repetir la petición.

---

## 13. Mantenimiento

### Tareas periódicas automáticas

El `HealthMonitorWorker` (cada 15 min, requiere red) ejecuta automáticamente:

- **Re-encolado de SMS huérfanos**: busca `enviado=false` sin Worker activo y los reencola
- **Purga de SMS**: elimina mensajes enviados con más de 7 días
- **Purga de logs**: elimina entradas de log con más de 30 días

### Ajustar políticas de retención

En `util/Constants.kt`:

```kotlin
const val RETENTION_DELIVERED_DAYS = 7L   // Días de retención de SMS enviados
const val RETENTION_LOGS_DAYS      = 30L  // Días de retención de logs
```

### Exportar logs manualmente

En la pantalla "Logs" → botón **Exportar**. Genera un archivo `.txt` en la caché de la app y abre el selector de compartición.

### Monitorización via ADB

```bash
# Ver logs en tiempo real (filtrado por tag de la app)
adb logcat -s "SmsReceiver" "SmsIngestionService" "SmsDispatchWorker" "HealthMonitorWorker"

# Ver base de datos SQLite
adb shell run-as com.capicua.smstosms
cat databases/smstosms.db | sqlite3 :memory: ".tables"

# Ver DataStore (configuración)
adb shell run-as com.capicua.smstosms cat files/datastore/smstosms_config.preferences_pb
```

### Actualizar la aplicación

El receptor `BootReceiver` escucha `MY_PACKAGE_REPLACED`: al actualizar la APK, el `HealthMonitorWorker` se reprograma automáticamente sin pérdida de datos.

---

## 14. Posibles incidencias

### SMS recibido pero no enviado a la API

**Síntomas**: La pantalla Inicio muestra SMS en estado "Pendiente" durante más de unos segundos.

**Diagnóstico por casos:**

**Caso A — `intentos = 0`, sin logs de error**
El `SmsDispatchWorker` nunca llegó a ejecutarse. Causa habitual: **modo Doze** bloqueando WorkManager.

```bash
# Comprobar si la app está en la lista blanca de Doze
adb shell dumpsys deviceidle whitelist
# Si com.capicua.smstosms NO aparece → aplicar la exención:
adb shell dumpsys deviceidle whitelist +com.capicua.smstosms
```

Alternativa desde el teléfono: Ajustes → Aplicaciones → SMStoSMS → Batería → **Sin restricciones** (ver tabla en §7 para rutas por fabricante).

**Caso B — `intentos > 0`, logs con ERROR de red**
El worker corrió pero no pudo contactar al servidor.

1. Verificar conectividad: chip "Online/Offline" en la pantalla Inicio
2. Verificar que el servidor de destino responde: `curl "<url_con_valores_reales>"`
3. Si la red está disponible, el `HealthMonitorWorker` reencola automáticamente en ≤ 15 min

**Caso C — `intentos > 0`, logs con ERROR HTTP 4xx**
Error permanente de configuración. El worker no reintentará.

1. Revisar la plantilla de URL en Configuración (parámetros, credenciales, sintaxis)
2. Corregir y guardar — el `HealthMonitorWorker` detectará el SMS huérfano y lo reencolará

---

### La app no recibe SMS tras reiniciar el dispositivo

**Síntomas**: Después de reiniciar, los SMS no se reciben.

**Diagnóstico**:
```bash
# Verificar permiso RECEIVE_BOOT_COMPLETED
adb shell pm list permissions -g com.capicua.smstosms

# Verificar que BootReceiver está registrado
adb shell dumpsys activity broadcasts | grep "BOOT_COMPLETED"
```

**Resolución**:
- Abrir la app una vez tras el reinicio (en algunos fabricantes es necesario un primer launch)
- Verificar que la optimización de batería está desactivada para la app
- En MIUI/EMUI: habilitar "Inicio automático" para la app en Ajustes del sistema

---

### Error "ForegroundServiceStartNotAllowedException"

**Síntomas**: Crash en Android 12+ al intentar procesar SMS con app en background.

**Causa**: Android 12+ restringe el inicio de `startForegroundService()` desde BroadcastReceivers en ciertos estados del proceso.

**Resolución**:
- Verificar que `SmsReceiver` usa `context.startForegroundService()` (no `startService()`)
- Asegurarse de que `SmsIngestionService` llama `startForeground()` en los primeros 5 segundos
- En Android 14+: solicitar `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` al usuario

---

### Logs de Room crecen indefinidamente

**Síntomas**: La tabla `log_entries` tiene millones de filas, la base de datos ocupa varios GB.

**Causa**: El `HealthMonitorWorker` no pudo ejecutarse (sin red, dispositivo apagado) durante más de 30 días.

**Resolución**:
```kotlin
// Purga manual desde la UI — añadir botón en LogsFragment:
viewModelScope.launch {
    logRepository.eliminarAntiguos(Instant.now().minus(30, ChronoUnit.DAYS))
}
```

---

### SMS duplicados en la API

**Síntomas**: La API recibe el mismo SMS dos o más veces.

**Causa**: El Worker verifica `sms.enviado == true` antes de enviar (idempotencia interna), pero si el proceso fue matado entre `marcarEnviado()` y el `Result.success()`, WorkManager puede reintentar y generar una segunda petición GET.

**Verificación**:
- Los logs muestran múltiples `SMS_ENVIADO` con el mismo `smsId`

**Resolución**:
1. Implementar idempotencia en el servidor usando el ID del SMS (que puede incluirse como parámetro en la plantilla de URL)
2. La doble petición del lado Android es extremadamente improbable pero teóricamente posible en caso de kill del proceso en el momento exacto de confirmación

---

### "Online" pero SMS no se envían (captive portal)

**Síntomas**: El chip muestra "Online" pero los Workers no pueden contactar con la API.

**Causa**: La red WiFi requiere autenticación en portal cautivo. El `NetworkMonitor` verifica `NET_CAPABILITY_VALIDATED` para distinguir redes realmente conectadas de portales cautivos.

**Resolución**:
- Autenticarse en el portal cautivo abriendo el navegador
- Si es una red corporativa, configurar certificados de CA en `network_security_config.xml`

---

### La app consume batería excesiva

**Síntomas**: Informe de batería muestra la app entre los mayores consumidores.

**Diagnóstico**:
```bash
adb shell dumpsys batterystats --charged com.capicua.smstosms
```

**Causas y soluciones**:

| Causa | Solución |
|-------|----------|
| WorkManager con muchos reintentos | Reducir `maxReintentos` en Config |
| HealthMonitorWorker tardando en completarse | Revisar logs SISTEMA para errores |
| NetworkMonitor con callbacks muy frecuentes | Verificar que `distinctUntilChanged()` filtre emisiones repetidas |
| Muchos SMS pendientes con backoff exponencial | Verificar conectividad y configuración de la API |

---

*Documentación generada para SMStoSMS v1.0 — Android SDK 26-35 — Kotlin 2.0.21*
