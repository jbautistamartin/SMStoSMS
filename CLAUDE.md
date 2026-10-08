# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run commands

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Release build (requiere las siguientes claves en local.properties):
#   KEYSTORE_PATH=<ruta absoluta al .keystore>
#   KEYSTORE_PASSWORD=<contraseña del almacén>
#   KEY_ALIAS=<alias de la clave>
#   KEY_PASSWORD=<contraseña de la clave>
# El APK resultante se genera como smstosms-<versionName>.apk
./gradlew assembleRelease

# Unit tests
./gradlew test

# Single test class
./gradlew test --tests "com.capicua.smstosms.data.repository.SmsRepositoryTest"

# Grant runtime permissions on a dedicated device (run after install)
adb shell pm grant com.capicua.smstosms android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smstosms android.permission.READ_SMS
adb shell pm grant com.capicua.smstosms android.permission.POST_NOTIFICATIONS

# Exempt the app from Doze mode / battery optimization (REQUIRED for reliable dispatch)
# Without this, WorkManager may be deferred indefinitely when the screen is off.
# See "Device setup" section below for the on-device UI alternative.
adb shell dumpsys deviceidle whitelist +com.capicua.smstosms

# Verify the app is in the whitelist
adb shell dumpsys deviceidle whitelist
# Expected: a line containing "com.capicua.smstosms"

# Remove from whitelist (if needed)
adb shell dumpsys deviceidle whitelist -com.capicua.smstosms

# Real-time log monitoring
adb logcat -s "SmsReceiver" "SmsIngestionService" "SmsDispatchWorker" "HealthMonitorWorker"
```

## Architecture

Clean Architecture with strict 3-layer separation and MVVM in the presentation layer. Dependency injection throughout via Hilt.

```
Presentation  (Fragments + ViewModels)
    ↓ uses
Domain        (UseCases + pure Kotlin models)
    ↓ uses interfaces, implemented by
Data          (Room · OkHttp · DataStore · WorkManager)
```

### SMS lifecycle

```
SIM → SmsReceiver.onReceive()           ← BroadcastReceiver (main thread, < 10 s)
        ↓ startForegroundService()
      SmsIngestionService               ← ForegroundService (must call startForeground() < 5 s)
        ↓ SaveSmsUseCase
      Room INSERT (SmsEntity)           ← outbox: persists BEFORE dispatching
        ↓ WorkManager.enqueueUniqueWork()
      SmsDispatchWorker.doWork()        ← runs when network is available, with exponential backoff
        ↓ OkHttp GET <url_from_template>
      HTTP response → update Room
```

`HealthMonitorWorker` runs every 15 minutes to re-enqueue orphaned SMS (no active WorkManager task) and purge old records (SMS > 7 days, logs > 30 days).

`BootReceiver` re-enables WorkManager tasks after device restart.

### HTTP mechanism

The app makes a plain **GET** request. There is no fixed endpoint or JSON body. The operator configures a full URL **template** in the Settings screen, with optional markers that get URL-encoded and substituted at send time:

- `{mensaje}` — SMS body (**required** in the template)
- `{telefono}` — sender number
- `{fecha}` — ISO-8601 UTC timestamp

`AppConfig.construirUrl()` performs the substitution. `AppConfig.esValida()` returns false if the template is blank or missing `{mensaje}`. Authentication (if needed) goes inside the URL itself (e.g. `?token=secret&msg={mensaje}`).

> **HTTP y HTTPS:** el tráfico cleartext (HTTP) está **permitido** (`cleartextTrafficPermitted="true"`) para dar soporte a servidores locales o corporativos sin TLS. Para restringir a HTTPS, cambia `usesCleartextTraffic` en `AndroidManifest.xml` y `cleartextTrafficPermitted` en `network_security_config.xml`.

### Key files

| File | Role |
|------|------|
| `data/config/AppConfig.kt` | Config data class + `construirUrl()` + `esValida()` |
| `data/config/ConfigDataStore.kt` | DataStore wrapper — reactive reads, atomic writes |
| `worker/SmsDispatchWorker.kt` | Sends one SMS; handles retry/failure logic |
| `worker/HealthMonitorWorker.kt` | Periodic cleanup + re-enqueue of stuck SMS |
| `util/HttpClientFactory.kt` | Derives an OkHttpClient from the base (timeout + optional trust-all SSL) |
| `di/NetworkModule.kt` | Provides base OkHttpClient (HEADERS logging in debug, NONE in release) |

### Idempotency

Idempotency is **client-side only**: `SmsDispatchWorker` checks `sms.enviado == true` in Room at the start of each attempt and returns `Result.success()` without making an HTTP call if already confirmed. The UUID stored in Room as the SMS primary key can be included in the URL template if the server also needs to deduplicate.

### Room database

Two tables, schema versioned in `SmsDatabase.kt`:
- `sms` (v1) — one row per received SMS, tracks `enviado`, `intentos`, `ultimoError`
- `log_entries` (v2) — audit log; types: `SMS_RECIBIDO`, `SMS_ENVIADO`, `ERROR`, `SISTEMA`

WAL mode enabled. No `fallbackToDestructiveMigration` — migrations must be explicit.

## Device setup (dedicated production phone)

These steps must be completed once on the dedicated Android device that receives and forwards SMS.
Skip any step already done.

### Why battery optimization must be disabled

Android's **Doze mode** suspends background processes when the screen is off for more than a few
minutes. WorkManager respects Doze and may delay `SmsDispatchWorker` by hours. Exempting the app
ensures every SMS is forwarded within seconds of arrival, regardless of screen or battery state.

### Option A — ADB command (fastest, requires USB + developer tools)

```bash
adb shell dumpsys deviceidle whitelist +com.capicua.smstosms
```

Run once after install. To verify the app was added to the whitelist:

```bash
adb shell dumpsys deviceidle whitelist
# Expected output includes a line with: com.capicua.smstosms
```

### Option B — On-device UI (no USB required)

The exact path varies by manufacturer. Use the closest match:

**Stock Android / Pixel**
1. **Ajustes** → **Aplicaciones** → **SMStoSMS**
2. **Batería** → seleccionar **Sin restricciones**

**Samsung (One UI)**
1. **Ajustes** → **Aplicaciones** → **SMStoSMS**
2. **Batería** → desactivar **Permitir actividad en segundo plano** NO — en su lugar:
   - **Ajustes** → **Mantenimiento del dispositivo** → **Batería**
   - **Límites de uso en segundo plano** → **Aplicaciones sin suspender** → **Añadir** → SMStoSMS

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

### Other recommended settings (all manufacturers)

| Setting | Where | Value |
|---------|-------|-------|
| Inicio automático | Ajustes → Aplicaciones → SMStoSMS | **Activado** |
| Ejecutar en segundo plano | Ajustes → Aplicaciones → SMStoSMS → Batería | **Permitido** |
| Optimización de batería | Ajustes → Batería → Optimización → SMStoSMS | **No optimizar** |

---

## Important notes

- `libs.versions.toml` is the single source of truth for all dependency versions. Retrofit and kotlinx.serialization entries were removed (unused); do not re-add them unless actually wiring up Retrofit.
- The `data/remote/` package is currently empty (reserved for future typed HTTP clients).
- The existing unit test (`SmsRepositoryTest`) is a placeholder — its `SmsMessage` constructor calls use English field names (`sender`, `body`) that do **not** match the current domain model (which uses `telefono`, `mensaje`). The tests compile as standalone fixtures but do not exercise real repository code yet.
- Debug variant uses `applicationId = com.capicua.smstosms.debug`, so it can coexist with the release build on the same device.
