# Registro de cambios

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/);
versionado según [SemVer](https://semver.org/lang/es/).

## [No publicado]

### Cambiado

- **Orden común en «Configuración».** Las cuatro aplicaciones de la familia presentan ahora las
  secciones en el mismo orden: **Recepción de SMS**, **Fiabilidad en segundo plano**, la sección
  propia del destino, **Límites**, **Envío y reintentos** y **Copia de seguridad**. Quien salta de
  una a otra encuentra lo mismo en el mismo sitio.

- **La SIM de envío pasa a ser la tercera sección**, donde las otras tres aplicaciones ponen su
  destino; antes iba entre los reintentos y la copia de seguridad. Sigue apareciendo solo en
  teléfonos con más de una tarjeta.

- **«Protecciones» se llama ahora «Límites»**, como en SMStoTelegram y SMStoWhats.

## [1.2.0] - 2026-10-11

### Añadido

- **Aviso de versión nueva.** Una vez al día comprueba si hay publicado un release más reciente y
  lo marca con un punto en la pestaña **Acerca de**, sin diálogos ni notificaciones. Desde la
  tarjeta de esa pestaña se puede descargar e instalar el APK —lo baja el gestor de descargas del
  sistema y la instalación la confirma Android, como cualquier APK—, buscar a mano cuando se quiera
  o apagar la comprobación automática. La actualización conserva reglas, ajustes y registro, porque
  va firmada con la misma clave.

- **Enlace al proyecto en «Acerca de».** Una tarjeta *Código abierto*, justo encima de la del
  donativo, con un botón que abre la página del repositorio en el navegador: el código, el
  registro de cambios y los APK publicados.

## [1.1.0] - 2026-10-11

La aplicación hace lo mismo que la 1.0.0 —recibir SMS en un teléfono con SIM y reenviarlos a otro
número según reglas— y esta entrega va sobre cómo se maneja.

### Añadido

- **Pestañas deslizables.** Las cinco pantallas (Inicio, Reglas, Logs, Config y Acerca de) se
  recorren deslizando el dedo, además de pulsando en la barra inferior. Volver atrás desde
  cualquiera lleva a Inicio en lugar de cerrar la aplicación.
- **Interruptor de recepción**, lo primero que hay en Config: apaga la lectura de los SMS que
  llegan sin tocar reglas, permisos ni instalación. No es una comprobación por mensaje — se
  desactiva el receptor del sistema, así que el SMS ni despierta a la aplicación. Corta la
  entrada, no la salida: lo que ya estuviera en cola se termina de enviar.
- **Copia de seguridad de toda la configuración** (Config → Copia de seguridad): los ajustes *y*
  las reglas en un solo fichero, para restaurarlos aquí o clonarlos en otro teléfono. Al
  importar, los ajustes se sustituyen y las reglas se **añaden** a las que haya, de modo que una
  importación equivocada nunca deja el teléfono sin reenviar nada. El identificador de la SIM no
  viaja en el fichero, porque señala a una tarjeta de *este* teléfono. El fichero contiene los
  números de destino: trátalo como información sensible.
- **Acceso al inicio automático del fabricante** desde Config (MIUI, HyperOS, EMUI, ColorOS y
  Funtouch). Es el permiso que impide arrancar tras reiniciar en esos teléfonos y que no se puede
  conceder ni consultar desde la aplicación.
- **Aviso de responsabilidad** en «Acerca de», junto al de privacidad, y un enlace opcional para
  invitar a un café. La aplicación no lo recuerda y ninguna función depende de él.

### Cambiado

- La exportación de reglas, que estaba en la pantalla de Reglas, es ahora parte de la copia de
  seguridad de Config: las reglas son configuración y viajan con ella.

### Corregido

- **Las variantes de depuración y de publicación ya pueden convivir en el mismo teléfono.** La
  *authority* del `FileProvider` estaba escrita a mano, así que las dos declaraban la misma y
  Android se negaba a tener ambas instaladas (`INSTALL_FAILED_CONFLICTING_PROVIDER`). Ahora se
  deriva del `applicationId`. Solo afecta a quien compila desde el código.

## [1.0.0] - 2026-10-09

Primera versión. Sustituye por completo la capa HTTP de SMSGateway: ya no hay servidor ni
*endpoint*, el destino es otro teléfono.

### Añadido

- **Reglas de reenvío** en una lista ordenada por prioridad. Cada regla aplica una expresión
  regular al remitente y otra al mensaje; gana la primera que casa, y puede dejar que la
  evaluación continúe para reenviar a varios números. La coincidencia es parcial (`codigo` casa
  con «Tu codigo es 4821») y cada regla decide si distingue mayúsculas.
- **Plantillas** para el texto que sale, con `{mensaje}`, `{telefono}` y `{fecha}`, de modo que
  quien lo recibe sepa de quién venía el original.
- **Contestar al remitente**: `{telefono}` como número destino devuelve el mensaje a quien
  escribió, en lugar de reenviarlo a un tercero.
- **Panel de prueba**: se pega un SMS de ejemplo y la aplicación dice qué regla casa, a dónde
  iría y con qué texto saldría, sin enviar nada.
- **Sin pérdida de mensajes.** Patrón *outbox*: el SMS se persiste en Room antes de hacer
  cualquier otra cosa.
- **Confirmación real de envío.** Se espera el `sentIntent` del operador; un reenvío solo se da
  por bueno cuando todas sus partes confirman.
- **Protección antibucle y límite por minuto.** Se descartan los SMS que vuelven de un destino
  configurado y no se reenvía al propio remitente, porque cada SMS cuesta dinero.
- **Reintentos automáticos** con WorkManager y *backoff* configurable, más un rescate cada 30
  segundos de lo que se quede atascado.
- **Arranque automático** tras reiniciar el dispositivo, **registro de auditoría** con filtros y
  exportación a fichero, y **selección de SIM** en teléfonos de doble tarjeta.
