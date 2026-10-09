# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run commands

> **Toolchain:** the project targets JVM 17 and builds with AGP 8.5.2. If the JDK on `PATH` is
> newer than 21, point `JAVA_HOME` at a JDK 17 or 21 — AGP 8.5.2 does not support JDK 25.
> `local.properties` is not versioned; if it is missing, set `ANDROID_HOME` instead.

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
# Sin las cuatro claves el build NO falla: el APK sale sin firmar.
# El APK resultante se genera como smstosms-<versionName>.apk
./gradlew assembleRelease

# Unit tests
./gradlew test

# Single test class
./gradlew test --tests "com.capicua.smstosms.domain.rules.EvaluadorDeReglasTest"

# Grant runtime permissions on a dedicated device (run after install)
adb shell pm grant com.capicua.smstosms android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smstosms android.permission.READ_SMS
adb shell pm grant com.capicua.smstosms android.permission.SEND_SMS
adb shell pm grant com.capicua.smstosms android.permission.READ_PHONE_STATE
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
adb logcat -s "SmsReceiver" "SmsIngestionService" "SmsSender" "SmsDispatchWorker" "OrphanRescueWorker" "HealthMonitorWorker"

# Simulate an incoming SMS on an emulator (no SIM required)
adb emu sms send +34600112233 "Tu codigo es 4821"
```

## Architecture

Clean Architecture with strict 3-layer separation and MVVM in the presentation layer. Dependency injection throughout via Hilt.

```
Presentation  (Fragments + ViewModels)
    ↓ uses
Domain        (UseCases + rules engine + pure Kotlin models)
    ↓ uses interfaces, implemented by
Data          (Room · SmsManager · DataStore · WorkManager)
```

### SMS lifecycle

```
SIM → SmsReceiver.onReceive()           ← BroadcastReceiver (main thread, < 10 s)
        ↓ startForegroundService()
      SmsIngestionService               ← ForegroundService (must call startForeground() < 5 s)
        ↓ ProcesarSmsEntranteUseCase
      Room INSERT (SmsEntity)           ← outbox: persists BEFORE doing anything else
        ↓ loop / rate protections
        ↓ EvaluadorDeReglas
      Room INSERT (ReenvioEntity × N)   ← one row per destination
        ↓ ColaDeEnvios.encolar()
      SmsDispatchWorker.doWork()        ← one worker per reenvío, with linear backoff
        ↓ SmsSender → SmsManager.sendMultipartTextMessage()
      sentIntent broadcast → update Room
```

`OrphanRescueWorker` self-chains every 30 s and rescues **two** things: SMS left in `PENDIENTE`
whose rule evaluation never completed, and `PENDIENTE` reenvíos with no live WorkManager job.

`HealthMonitorWorker` runs every 15 minutes and purges confirmed reenvíos, already-handled SMS
(> `RETENTION_DELIVERED_DAYS`, currently **30 days**) and old log entries
(> `RETENTION_LOGS_DAYS`, also 30 days).

`BootReceiver` re-enables WorkManager tasks after device restart.

### Forwarding mechanism

There is no HTTP. The operator configures an ordered list of **rules** on the Reglas screen;
each rule decides, for a given incoming SMS, which phone number receives it and with what text.

A rule matches when **both** criteria hold. An empty criterion always holds, so a rule with both
empty matches every SMS:

- `regexTelefono` — applied to the sender **as the PDU delivers it**, unnormalized. May be
  alphanumeric (`BANCO`, `AMAZON`).
- `regexMensaje` — applied to the full SMS body.

Matching is **partial** (`Regex.containsMatchIn`): `codigo` matches "Tu codigo es 4821". Anchor
with `^…$` to require the whole text. Patterns are case-sensitive unless the rule sets
`ignorarMayusculas`, which applies `RegexOption.IGNORE_CASE` to **both** expressions at once;
an inline `(?i)` still works and the two are cumulative, not conflicting.

Rules are evaluated in ascending `orden`. The first match wins and evaluation stops, **unless**
the rule has `continuar = true`, in which case evaluation carries on and the same SMS can reach
several destinations.

The rule's `destino` is normally a fixed phone number, but it also accepts the `{telefono}`
marker, which resolves to **the sender of the incoming SMS**. That is how you reply to whoever
wrote in. It is the only marker resolved in the destination — `{mensaje}` and `{fecha}` are left
untouched there, so the SMS body (third-party content) can never influence where anything is
sent. The rule editor offers it as a button ("Contestar al remitente"), because a marker nobody
knows about is not a feature.

The forwarded text comes from the rule's `plantilla`, with three optional markers:

- `{mensaje}` — the received SMS body
- `{telefono}` — the sender's number
- `{fecha}` — reception date, formatted for display in **local** time (not ISO UTC: a person
  reads it on their phone)

`{mensaje}` is substituted **last** on purpose, so the SMS body — third-party content — is never
re-scanned and cannot inject markers.

### Loop and cost protections

Forwarding SMS costs money and can feed back on itself, which HTTP never did. Three guards, the
first two controlled by `AppConfig.protegerBucles`:

1. An SMS whose **sender is one of the configured destinations** is discarded: it came back from
   a number we forward to.
2. A matched rule whose **destination equals the sender** is skipped — *unless* the rule declared
   `{telefono}` as its destination. Writing that marker is an explicit request to reply to the
   sender, so what guard 2 blocks is accidental circularity, not configured circularity. A
   reply-to-sender rule therefore has only the rate limit behind it: keep its `regexMensaje`
   specific if the other end might also auto-reply.
3. `AppConfig.maxReenviosPorMinuto` caps how many reenvíos can be created per minute. The excess
   is discarded and logged. This one is always active.

A fourth check applies only to reply-to-sender rules: if the sender is an alphanumeric header
(`BANCO`, `AMAZON`), it cannot receive an SMS, so the reenvío is skipped and logged as `ERROR`
rather than attempted and retried. `NormalizadorTelefono.esDestinoEnviable()` decides.

Number comparison goes through `NormalizadorTelefono`, which strips non-digits and compares the
last 9, so `+34600112233` and `600112233` are recognised as the same subscriber. Alphanumeric
senders never compare equal to anything — you cannot forward to them, so they cannot form loops.

### Key files

| File | Role |
|------|------|
| `domain/rules/EvaluadorDeReglas.kt` | Matching engine, template resolution, pattern validation |
| `domain/rules/NormalizadorTelefono.kt` | Phone comparison for the loop guards |
| `domain/usecase/ProcesarSmsEntranteUseCase.kt` | Guards → evaluate → create reenvíos → enqueue |
| `data/sms/SmsSender.kt` | `SmsManager` wrapper that **waits** for the `sentIntent` |
| `data/sms/ProveedorDeSims.kt` | Lists active SIMs for the dual-SIM selector |
| `worker/ColaDeEnvios.kt` | The only place that enqueues a dispatch job |
| `worker/SmsDispatchWorker.kt` | Sends one reenvío; handles retry/failure logic |
| `worker/OrphanRescueWorker.kt` | Rescues stalled SMS and reenvíos every 30 s |
| `data/config/AppConfig.kt` | Retries, timeout, rate limit, guards, SIM |
| `data/rules/ReglasJson.kt` | Versioned rule export/import format |
| `data/local/db/Migraciones.kt` | Hand-written Room migrations, registered by `DatabaseModule` |
| `util/OptimizacionBateria.kt` | Doze exemption: status query + the Settings screen's button |

### Why SmsSender suspends

`SmsManager.sendMultipartTextMessage()` returns immediately, before the message leaves the
device. The outcome arrives later as a broadcast to a `PendingIntent` supplied per part. If the
worker did not wait for it, every send would look successful and real failures would vanish.

`SmsSender.enviar()` therefore registers a `RECEIVER_NOT_EXPORTED` receiver on a per-send
UUID action, hands one `FLAG_IMMUTABLE` `PendingIntent` per part to the radio, and suspends
until all parts report or `AppConfig.timeoutEnvioSegundos` expires.

Result mapping: `RESULT_OK` → success; `NO_SERVICE`, `RADIO_OFF`, `GENERIC_FAILURE` and timeout
→ retry; `NULL_PDU`, invalid destination and missing permission → permanent failure.

**Multipart:** if one part fails and another succeeds, the whole reenvío is retried, accepting a
possible duplicate at the destination. Treating a truncated message as delivered is worse.

### Idempotency

Client-side, at two levels:

- `ProcesarSmsEntranteUseCase.procesar()` checks whether the SMS already has reenvíos before
  creating any. That is what makes the orphan rescue safe to run on a half-processed SMS.
- `SmsDispatchWorker` checks `reenvio.estado` at the start of every attempt and returns success
  without touching the radio if it is already `ENVIADO`.

`ColaDeEnvios` uses `ExistingWorkPolicy.KEEP`, so a reenvío never gets two concurrent jobs.

### Room database

Four tables, schema version **2** (see the KDoc on `SmsDatabase` for why it restarted at 1):

- `sms` — one row per received SMS. Only what arrived plus `estado`
  (`PENDIENTE`/`PROCESADO`/`SIN_REGLA`/`DESCARTADO`) and `motivo_descarte`.
- `reglas` — ordered forwarding rules, including `ignorar_mayusculas` (added in v2).
- `reenvios` — the dispatch unit: one row per destination, with its own `estado`, `intentos`
  and `ultimo_error`. FK to `sms` is CASCADE; FK to `reglas` is SET NULL, and `nombre_regla`
  is denormalized so history survives rule deletion.
- `log_entries` — audit log. Types: `SMS_RECIBIDO`, `REGLA_APLICADA`, `SMS_REENVIADO`,
  `SIN_REGLA`, `BUCLE_EVITADO`, `ERROR`, `SISTEMA`.

WAL mode enabled. Schemas are exported to `app/schemas/` and versioned. No
`fallbackToDestructiveMigration` — migrations must be explicit.

> The schema stopped being editable in place the moment the app was installed on a phone with
> rules in it: changing a version without migrating makes Room abort when opening the database.
> Every change now bumps the version and writes its `Migration` in `data/local/db/Migraciones.kt`,
> which `DatabaseModule` registers. Both `1.json` and `2.json` are versioned under `app/schemas/`.

## Naming conventions

The codebase mixes languages deliberately, and new code must follow the same split:

- **Members** — functions, properties, parameters, Room column names, enum constants — are in
  **Spanish**: `guardar`, `observarTodos`, `obtenerPendientes`, `telefono`, `fecha_recepcion`,
  `SMS_REENVIADO`. The only English members are framework overrides where there is no choice
  (`onCreate`, `doWork`, `onBindViewHolder`, `provideDatabase`).
- **Type names** carry an **English architectural suffix** on a Spanish or English noun:
  `SmsRepository`, `ReglaDao`, `ReenvioEntity`, `SettingsFragment`, `ReglasViewModel`.
  Pure-domain types may be fully Spanish: `LogTipo`, `EstadoSms`, `EvaluadorDeReglas`.
- **Comments and KDoc** are in **Spanish**.
- **Use-case class names follow the same rule:** a Spanish verb phrase plus the `UseCase`
  suffix — `ProcesarSmsEntranteUseCase`, `ObtenerListaSmsUseCase`. The English
  `GetSmsListUseCase` inherited from SMSGateway was renamed for consistency.

This split was verified against the pre-migration code, not assumed: of the 89 functions the
original project declared with a real choice of language, **89 were in Spanish and none in
English**, and all 13 Room columns were Spanish too. The only English members were framework
overrides. Do not "restore" English names — there were none to restore.

## Device setup (dedicated production phone)

These steps must be completed once on the dedicated Android device that receives and forwards SMS.
Skip any step already done.

### Why battery optimization must be disabled

Android's **Doze mode** suspends background processes when the screen is off for more than a few
minutes. WorkManager respects Doze and may delay `SmsDispatchWorker` by hours. Exempting the app
ensures every SMS is forwarded within seconds of arrival, regardless of screen or battery state.

### Option A — in the app (no USB, no manufacturer-specific path)

**Ajustes** → **Fiabilidad en segundo plano**. The card states whether the exemption is already
granted and the button opens the system screen that grants it. It tries three destinations in
order — the one-tap exemption dialog, the full battery-optimization list, the app's Settings
entry — because no manufacturer guarantees the first. This is the route to prefer on a phone
you are holding; the status refreshes when you come back to the screen.

### Option B — ADB command (fastest when the phone is already plugged in)

```bash
adb shell dumpsys deviceidle whitelist +com.capicua.smstosms
```

Run once after install. To verify the app was added to the whitelist:

```bash
adb shell dumpsys deviceidle whitelist
# Expected output includes a line with: com.capicua.smstosms
```

### Option C — On-device UI, by hand

Only needed if the in-app button cannot open anything. The exact path varies by manufacturer;
use the closest match:

**Stock Android / Pixel**
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

### Other recommended settings (all manufacturers)

| Setting | Where | Value |
|---------|-------|-------|
| Inicio automático | Ajustes → Aplicaciones → SMStoSMS | **Activado** |
| Ejecutar en segundo plano | Ajustes → Aplicaciones → SMStoSMS → Batería | **Permitido** |
| Optimización de batería | Ajustes → Batería → Optimización → SMStoSMS | **No optimizar** |

---

## Important notes

- `libs.versions.toml` is the single source of truth for all dependency versions. Retrofit and
  OkHttp were removed when the HTTP layer went away; do not re-add them.
  `kotlinx.serialization` **is** used, by the rule export/import format.
- `SEND_SMS` is a restricted permission. Irrelevant for APK distribution, but it closes the door
  to Google Play without an approved declaration.
- Debug variant uses `applicationId = com.capicua.smstosms.debug`, so it can coexist with the
  release build on the same device. They do **not** share a database.
- **Edge-to-edge is mandatory here.** With `targetSdk 35`, Android 15 draws the window under the
  status and navigation bars and ignores `fitsSystemWindows`; there is no opt-out. `MainActivity`
  distributes the insets (`systemBars or displayCutout`) and is the only place that does, so any
  new top-level container inherits it. Without it the camera cutout covered each screen's header
  and the system navigation bar sat **on top of** the Bottom Navigation, which made the screens
  that are not in that bar impossible to leave. `ProbarReglasFragment` and `EditarReglaFragment`
  also carry their own back button, since neither has an entry in the bottom bar.
- **Known toolchain item:** the wrapper is Gradle 9.3.0 while AGP is 8.5.2. It builds, but warns
  about APIs that disappear in Gradle 10 — among them `android.applicationVariants.all`, the
  block that renames the release APK. Before moving to Gradle 10, either raise AGP or pin the
  wrapper to Gradle 8.x. Nothing is broken today, so this was deliberately left alone.
- Three unit test suites cover the parts worth covering, all pure Kotlin with no Android
  dependencies: `EvaluadorDeReglasTest` (45), `NormalizadorTelefonoTest` (18) and
  `ReglasJsonTest` (10). The `SmsRepositoryTest` placeholder that never compiled is gone.
- `MASTERPLAN.md` records the migration from SMSGateway phase by phase, including the decisions
  taken and the deviations from the original plan. Read it before changing anything structural.
