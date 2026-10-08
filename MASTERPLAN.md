# MASTERPLAN — SMSGateway → SMStoSMS

Plan de migración de la aplicación **SMSGateway** (gateway SMS → API HTTP) a **SMStoSMS**
(reenvío de SMS a otro número de teléfono mediante reglas con expresiones regulares).

- **Rama de trabajo:** `feature/2026_SMStoSMS` (sobre la historia completa de SMSGateway)
- **Base:** v1.0.1 · 44 ficheros Kotlin · 2.819 líneas
- **Decisiones cerradas:** 7 de octubre de 2026
- **Documento visual del plan:** https://claude.ai/code/artifact/22d93cae-7440-4ef0-90cb-17e750c4b821

---

## 1. Idea central

El canal de ingesta de SMS no se toca. Es la parte difícil del problema y ya está resuelta:

```
SIM → SmsReceiver → SmsIngestionService → Room (outbox) → WorkManager → SmsDispatchWorker
```

Lo único que se sustituye es el tramo final del canal:

| | Antes (SMSGateway) | Después (SMStoSMS) |
|---|---|---|
| Destino | URL configurada en Settings | Número de teléfono resuelto por reglas |
| Transporte | OkHttp `GET` | `SmsManager.sendMultipartTextMessage()` |
| Confirmación | Código HTTP 2xx | `sentIntent` con `RESULT_OK` |
| Configuración | Una plantilla de URL | Lista ordenada de reglas con regex |

Se conservan intactos: patrón outbox, idempotencia por UUID, `OrphanRescueWorker` (cada 30 s),
`HealthMonitorWorker` (cada 15 min), `BootReceiver`, Hilt, Room + WAL, DataStore, Material 3,
Navigation con cuatro pestañas y la pantalla de logs con filtros y exportación.

---

## 2. Decisiones cerradas

| # | Decisión | Elegido | Consecuencia |
|---|----------|---------|--------------|
| 01 | Varias reglas casan con el mismo SMS | **Gana la primera, con escape por regla** | Cada regla lleva un flag `continuar`. Obliga a una tabla hija `reenvios`: un SMS, N reenvíos, cada uno con su estado. |
| 02 | Semántica de la expresión regular | **Búsqueda parcial** (`containsMatchIn`) | `codigo` casa con «Tu codigo es 4821». El patrón total sigue disponible con `^…$`. |
| 03 | Texto que llega al destino | **Plantilla por regla** | Marcadores `{mensaje}`, `{telefono}`, `{fecha}`. Por defecto `{mensaje}`. |
| 04a | Selección de SIM (doble SIM) | **Dentro** | `createForSubscriptionId` + permiso `READ_PHONE_STATE`. Fases 3 y 4. |
| 04b | Exportar / importar reglas en JSON | **Dentro** | Fase 4. Reincorpora `kotlinx.serialization` a `libs.versions.toml`, con uso real. |
| 04c | Borrar `test-server/` | **Dentro** | Fase 6. La historia de Git lo conserva. |

### Modelo de datos resultante

```
tabla sms            (sin cambios)
  id, telefono, mensaje, fecha_recepcion

tabla reenvios       ← NUEVA
  id, sms_id, regla_id, destino, texto_final,
  estado, intentos, ultimo_error, fecha_envio

tabla reglas         ← NUEVA
  id, orden, nombre, regex_telefono, regex_mensaje,
  destino, plantilla, activa, continuar

tabla log_entries    (sin codigo_http)
```

Esquema colapsado a la **versión 1**: al cambiar el `applicationId` ningún dispositivo tiene la
base de datos antigua, así que no hay nada que migrar. `room.schemaLocation` se configura (hoy no
lo está, pese a `exportSchema = true`).

---

## 3. Fases

Cada fase termina en un estado compilable y comprobable, con los cambios en el árbol de trabajo.

> **El commit lo pide el usuario, nunca se hace por iniciativa propia.** Al cerrar una fase se
> informa del estado y se para; revisar el diff antes de que entre en la historia es su decisión.

### Fase 1 — Renombrado integral · `[x]` completada el 7 oct 2026

Mecánico y aislado. Va primero para que todo el código posterior se escriba con los nombres
definitivos y el diff funcional quede limpio. Al terminar, la app sigue siendo un gateway HTTP:
solo ha cambiado de nombre.

| Qué | Hoy | Después |
|-----|-----|---------|
| Paquete y `namespace` | `com.capicua.smsgateway` | `com.capicua.smstosms` |
| Clase Application | `GatewayApplication` | `SmsToSmsApplication` |
| Proyecto Gradle | `SmsGateway` | `SMStoSMS` |
| Base de datos | `sms_gateway.db` | `smstosms.db` |
| DataStore | `gateway_config` | `smstosms_config` |
| Canal de notificación | `sms_gateway_channel` | `smstosms_channel` |
| FileProvider authority | `…smsgateway.provider` | `…smstosms.provider` |
| Tema | `Theme.SmsGateway` | `Theme.SmsToSms` |
| APK de release | `smsgateway-1.0.1.apk` | `smstosms-1.0.0.apk` |
| Nombre visible | `SMSGateway` | `SMStoSMS` |

También: cabeceras de licencia de los 44 ficheros, `app_name`, `versionName = "1.0.0"`,
`versionCode = 1`.

**Verificación:** `./gradlew assembleDebug` compila y `grep -ri smsgateway` no devuelve nada
fuera de `.git`.

**No entra:** ningún cambio de lógica, de esquema ni de interfaz.

#### Resultado real

- `assembleDebug` → **BUILD SUCCESSFUL**. Manifest fusionado verificado:
  `package="com.capicua.smstosms.debug"`, `SmsToSmsApplication`, authority
  `com.capicua.smstosms.provider`.
- Grep limpio en todo el repositorio. Las dos únicas ocurrencias restantes están en
  `.claude/settings.local.json` (historial local de permisos, no versionado) y apuntan a otro
  proyecto distinto: `com.bansi.smsgateway` en `/c/Bansi/sms`. No se tocan.
- Los 44 ficheros se movieron con `git mv`, así que Git los registra como *rename* y la historia
  se sigue hasta el commit inicial.

#### Dos desviaciones respecto al plan

1. **Se adelantó de la fase 6 el arreglo de `local.properties`.** No era opcional: el bloque
   `signingConfigs` casteaba `localProps["KEYSTORE_PATH"] as String` en tiempo de configuración,
   de modo que sin claves de firma **ni `assembleDebug` arrancaba**. Ahora `local.properties` se
   lee solo si existe y la firma de release se configura solo si están las cuatro claves. Sin
   ellas, el APK de release sale sin firmar pero el build no rompe.
2. **`./gradlew test` está rojo, y ya lo estaba antes de esta fase.** `SmsRepositoryTest` importa
   `SmsStatus`, que no existe en el proyecto, y `kotlinx.coroutines.test`, que no está declarado
   en `libs.versions.toml`. Verificado contra `git show HEAD`: el fichero llegó así. `CLAUDE.md`
   afirma que «the tests compile as standalone fixtures», lo cual es falso. El borrado de ese
   fichero pasa de la fase 6 al principio de la fase 2, que es donde `./gradlew test` en verde es
   la puerta de salida.

#### Entorno de build verificado

El JDK del PATH es el 25, con el que AGP 8.5.2 no funciona. Para compilar:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"   # no hay local.properties en esta copia
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```

El wrapper es Gradle **9.3.0** con AGP **8.5.2**: compila, pero avisa de features que no existirán
en Gradle 10 (`android.applicationVariants.all` entre ellas). No bloquea nada ahora; conviene
decidir en la fase 6 si se sube AGP o se baja el wrapper.

---

### Fase 2 — Modelo de reglas y motor de coincidencia · `[x]` completada el 7 oct 2026

La pieza nueva del dominio, construida y probada antes de conectarla a nada. El evaluador es
Kotlin puro, sin Android, así que se cubre con tests unitarios de verdad.

- **Primero:** borrar `SmsRepositoryTest`, el placeholder que no compila (heredado de la fase 1),
  y añadir `kotlinx-coroutines-test` a `libs.versions.toml` para los tests nuevos.
- Tablas `reglas` y `reenvios` con sus entidades, DAOs y repositorios.
- `EvaluadorDeReglas`: recorre las reglas activas por `orden`, para en la primera que case salvo
  que lleve `continuar`, y resuelve la plantilla.
- Esquema colapsado a v1 y `room.schemaLocation` configurado.
- ~~El worker pasa a trabajar sobre `reenvioId`~~ → **movido a la fase 3**, ver más abajo.

**Verificación:** `./gradlew test` en verde con casos reales — regex inválida, regla desactivada,
sin coincidencia, orden respetado, `continuar` activo, plantilla con marcadores, reenvío a uno mismo.

**No entra:** nada de interfaz ni de envío; el motor queda inyectable pero sin usar.

#### Resultado real

- `./gradlew test assembleDebug` → **BUILD SUCCESSFUL**. 32 tests en
  `EvaluadorDeReglasTest`, 0 fallos.
- Esquema v1 exportado a `app/schemas/com.capicua.smstosms.data.local.db.SmsDatabase/1.json`
  con las cuatro tablas (`sms`, `reglas`, `reenvios`, `log_entries`) y las dos claves ajenas de
  `reenvios` correctas: `sms_id → sms.id` en CASCADE y `regla_id → reglas.id` en SET NULL.
- El warning de KSP «Schema export directory was not provided» ha desaparecido.

#### Ficheros nuevos

| Fichero | Papel |
|---------|-------|
| `domain/model/Regla.kt` | Regla de reenvío |
| `domain/model/Reenvio.kt`, `EstadoReenvio.kt` | Unidad de despacho y su estado |
| `domain/rules/EvaluadorDeReglas.kt` | Motor: evalúa, resuelve plantilla, valida patrones |
| `domain/rules/ResultadoEvaluacion.kt` | `Coincidencia`, `ReglaInvalida`, `CampoRegla` |
| `data/local/db/entity/ReglaEntity.kt`, `ReenvioEntity.kt` | Tablas |
| `data/local/db/dao/ReglaDao.kt`, `ReenvioDao.kt` | Consultas |
| `data/repository/Regla{Repository,RepositoryImpl}.kt` | Contrato e implementación |
| `data/repository/Reenvio{Repository,RepositoryImpl}.kt` | Contrato e implementación |

#### Tres desviaciones respecto al plan

1. **El worker sigue trabajando sobre `smsId`.** Cambiarlo a `reenvioId` en esta fase habría
   dejado la app justo en el estado que el plan prohíbe: el worker buscando reenvíos que nada
   crea todavía. La migración del worker va con la fase 3, donde toda la ruta de despacho cambia
   de golpe. La fase 2 queda así puramente aditiva: la app sigue funcionando como hoy.
2. **No se añadió `kotlinx-coroutines-test`.** El evaluador es síncrono y ningún test lo
   necesita aún. Añadir una dependencia sin uso contradice la política que el propio
   `CLAUDE.md` fija para `libs.versions.toml`. Entrará cuando haya tests de funciones `suspend`.
3. **La tabla `sms` se deja intacta**, todavía con `enviado`, `intentos` y `ultimo_error`, que
   en el modelo nuevo pertenecen a `reenvios`. Normalizarla arrastra al worker, a los dos
   workers periódicos, al dashboard y al adaptador, así que se hace en la fase 3. El esquema
   sigue en v1 y es editable hasta la 1.0.0.

> **Al actualizar un dispositivo que ya tenga la build de la fase 1:** su base de datos está en
> la versión 2 y pasar a la v1 es un *downgrade*, que Room rechaza. En desarrollo se resuelve
> desinstalando la app o borrando sus datos. No se añade `fallbackToDestructiveMigration`.

---

### Fase 3 — Reenvío real por SmsManager · `[ ]`

El corazón del cambio. `SmsDispatchWorker` deja de hablar HTTP y pasa a enviar SMS, conservando
intacta su tabla de decisiones.

- **Heredado de la fase 2:** migrar `SmsDispatchWorker` de `smsId` a `reenvioId`, normalizar la
  tabla `sms` (sacar `enviado`, `intentos` y `ultimo_error`, que ahora viven en `reenvios`) y
  adaptar `OrphanRescueWorker` y `HealthMonitorWorker` a la cola de reenvíos.
- `SmsSender`: `divideMessage` + `sendMultipartTextMessage`, un `PendingIntent` por parte y un
  receptor que convierte el broadcast en una corrutina suspendida con timeout.
- Mapeo de resultados:

  | Resultado | WorkManager | Estado |
  |-----------|-------------|--------|
  | `RESULT_OK` (todas las partes) | `success()` | enviado |
  | `RESULT_ERROR_NO_SERVICE`, `RADIO_OFF` | `retry()` | intentos++ |
  | `RESULT_ERROR_GENERIC_FAILURE` | `retry()` | intentos++ |
  | `RESULT_ERROR_NULL_PDU`, destino inválido, permiso denegado | `failure()` | fallo permanente |
  | Timeout esperando `sentIntent` | `retry()` | intentos++ |

- **Protecciones antibucle** (requisito, no extra): nunca reenviar al número de la propia SIM,
  descartar mensajes cuyo remitente sea un destino configurado, y limitar los reenvíos por minuto
  registrando el exceso en el log.
- Doble SIM: `createForSubscriptionId` cuando hay SIM elegida, `getSystemService(SmsManager.class)`
  cuando no. Permiso `READ_PHONE_STATE` para enumerarlas.
- Permiso `SEND_SMS` en el manifiesto y en la petición en tiempo de ejecución de `MainActivity`.
- **Baja:** OkHttp, `NetworkModule`, `HttpClientFactory`, `network_security_config.xml`,
  `usesCleartextTraffic`, permisos `INTERNET` y `ACCESS_NETWORK_STATE`, `TestConexionEstado`.
- **Arreglo:** `intervaloReintentoSegundos` se guarda en configuración y nunca se usa, porque
  `SmsRepositoryImpl.encolarEnvio()` lee la constante `WORKER_INITIAL_BACKOFF_SECONDS`.

**Verificación:** un SMS recibido llega al número destino y el log recoge la cadena completa
(recibido → regla aplicada → enviado). Dos emuladores conectados por sus puertos sirven para
validar sin gasto real.

**No entra:** las reglas se dan de alta a mano en la base de datos.

---

### Fase 4 — Pantalla de reglas · `[ ]`

La configuración pasa de un formulario con una sola URL a una lista ordenable de reglas. Es la
fase con más trabajo de interfaz.

- Lista con reordenación, interruptor de activación, duplicar y borrar con confirmación.
- Alta y edición con validación de la regex al teclear (`PatternSyntaxException` capturada y
  mostrada en el campo) y validación del número destino.
- **Panel de prueba:** se pega un teléfono y un mensaje de ejemplo y la pantalla dice qué regla
  casa, qué texto saldría y en cuántas partes, sin enviar nada. Botón aparte para envío real.
- Exportar e importar reglas en JSON con `CreateDocument` / `OpenDocument` y
  `kotlinx.serialization`. La importación valida cada regla antes de escribir.
- La configuración general se queda con reintentos, intervalo, retención y protecciones
  antibucle, y gana el selector de SIM cuando hay más de una.

**Verificación:** crear tres reglas, reordenarlas, desactivar la primera y comprobar que un SMS
real toma la ruta esperada. Exportar, borrarlas todas, importar y acabar con las mismas tres en
el mismo orden.

---

### Fase 5 — Inicio y logs al día · `[ ]`

La información que se muestra cambia: ya no hay códigos HTTP, hay destinos y reglas.

- Cada SMS muestra remitente, destino y regla aplicada, con un estado por reenvío cuando hay varios.
- Nuevos tipos de log: `REGLA_APLICADA`, `SMS_REENVIADO`, `SIN_REGLA`.
- Baja de `codigo_http` en entidad, modelo, adaptador y exportación.
- Un SMS sin regla que case queda marcado como **descartado**, no como pendiente eterno.

**Verificación:** un SMS sin coincidencia y otro con dos destinos se leen correctamente en Inicio,
en Logs y en el fichero exportado.

**No entra:** rediseño visual. Se mantienen las pestañas y el tema actuales.

---

### Fase 6 — Documentación, limpieza y release · `[ ]`

Los tres documentos del repositorio describen hoy un gateway HTTP: se reescriben, no se parchean.

- `README.md`, `docs/ARCHITECTURE.md` (las secciones «API corporativa» y «Configuración» enteras)
  y `CLAUDE.md`.
- Pantalla «Acerca de» y aviso legal: gana peso cuando el contenido de los SMS sale hacia el
  teléfono de una persona.
- **Deuda heredada que se salda aquí:**
  - ~~`local.properties` leído sin comprobar que exista~~ → **resuelto en la fase 1**.
  - `CLAUDE.md` afirma que `SmsRepositoryTest` compila; no compilaba. Corregir la afirmación
    (el fichero desaparece en la fase 2).
  - `CLAUDE.md` dice «do not re-add kotlinx.serialization» → la fase 4 la reincorpora con uso real.
  - Decidir Gradle 9.3 + AGP 8.5.2: subir AGP o bajar el wrapper antes de Gradle 10.
  - `RETENTION_DELIVERED_DAYS = 30` mientras `CLAUDE.md` dice 7 días.
  - El README afirma «solo HTTPS» mientras el manifiesto permite tráfico en claro.
  - `CLAUDE.md` atribuye el rescate de huérfanos a `HealthMonitorWorker`, cuando lo hace
    `OrphanRescueWorker`.
- Borrado de `test-server/` y de `SmsRepositoryTest` (placeholder con campos en inglés que no
  existen en el modelo).
- Este `MASTERPLAN.md` actualizado con el estado final, y APK firmado `smstosms-1.0.0.apk`.

**Verificación:** `assembleRelease` produce el APK firmado, se instala en limpio y reenvía un SMS
siguiendo solo las instrucciones del README.

**No entra:** publicación en el repositorio remoto.

---

### Fase 7 — Opcional · `[ ]`

Nada de esto bloquea una 1.0 utilizable: destino extraído de un grupo de captura de la propia
regex, reglas con ventana horaria, y tests instrumentados de Room y del worker.

---

## 4. Riesgos propios de reenviar SMS

| Riesgo | Mitigación |
|--------|-----------|
| **Bucles de reenvío.** Si un destino contesta, o si se configura el propio número, el mensaje vuelve a entrar, casa otra vez y se reenvía — con reintentos automáticos detrás, eso es una factura. | Las tres protecciones de la fase 3. |
| **El envío es asíncrono.** `SmsManager` retorna al instante; el resultado llega después por broadcast. Sin esperarlo, todo parece enviado y los fallos se pierden en silencio. | Puente suspendido sobre `sentIntent` con timeout. |
| **Multipart multiplica el coste.** Un mensaje largo se parte y cada parte confirma por separado. Si una falla y otra no, el reenvío queda incompleto. | Se considera fallido y se reintenta entero, aceptando posibles duplicados en el destino. |
| **`SEND_SMS` es un permiso restringido.** | Irrelevante para distribución por APK; cierra la puerta a Google Play sin justificación aprobada. |
| **Datos personales en tránsito.** El contenido de los SMS pasa a enviarse al teléfono de una persona. | Aviso legal explícito en README y «Acerca de». |
| **Remitentes alfanuméricos** («BANCO», «AMAZON») en lugar de números. | La regex trabaja sobre la cadena cruda de la PDU, sin normalizar. Documentado y visible en el panel de prueba. |

---

## 5. Fuera del plan, a propósito

- **No se toca el canal de ingesta.** `SmsReceiver`, `SmsIngestionService`, `OrphanRescueWorker`,
  `BootReceiver` y el patrón outbox funcionan y están documentados.
- **No se migra a Compose ni se rediseña.** Sigue en Fragments con View Binding y Material 3.
- **No se conserva el modo HTTP.** Nada de una app que reenvíe por SMS y además llame a una API:
  duplicaría configuración, modelo de datos y pruebas. SMSGateway sigue existiendo en su propia
  historia de Git.

---

## 6. Git

La historia completa de SMSGateway se conserva. `develop` y `main` quedan intactas y no hay
remoto configurado todavía. Cada fase da lugar a un commit en `feature/2026_SMStoSMS` **cuando el
usuario lo pide**, y al cerrar la fase 6 a una etiqueta `v1.0.0` lista para empujar al repositorio
nuevo.
