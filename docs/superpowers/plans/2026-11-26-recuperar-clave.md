# Recuperar la contraseña — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que cualquier usuario recupere su contraseña con un código de 6 dígitos enviado a su correo (RF-65), sin revelar qué correos existen.

**Architecture:**
- Tabla `codigos_recuperacion` (V52) con repositorio `JdbcClient`.
- La solicitud responde de inmediato y entrega el trabajo a una tarea asíncrona con una cola acotada.
- La confirmación es transaccional, con `FOR UPDATE` sobre el código.
- El correo sale por una interfaz con dos modos: `registro` y `smtp`.
- En la web, una pantalla de dos pasos.

**Tech Stack:** Java 25, Spring Boot 4 (Security, `@Async`, `spring-boot-starter-mail`), MySQL 9.7, Flyway, React 18.3.1 y Vitest.

**Spec:** `docs/superpowers/specs/2026-11-25-recuperar-clave-design.md` (revisión 2). Léela entera: los topes, los textos, los motivos de auditoría y el `exitoso` de cada motivo están ahí.

## Global Constraints

- **TDD.** Primero el commit rojo `test: …` y después `feat: …`.
  - Mensajes en español y con tildes.
  - Trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Números reservados:**
  - la migración única es `backend/src/main/resources/db/migration/V52__codigos_de_recuperacion.sql`;
  - el ADR único es `docs/decisiones/ADR-0036-correo-saliente-y-recuperacion.md`, que también se agrega al índice `docs/decisiones/README.md`.
- **Horas:** siempre `Hoy.ahora(reloj)`.
- **Rutas:**
  - `POST /api/v1/auth/recuperacion`;
  - `POST /api/v1/auth/recuperacion/confirmar`.
- **Textos exactos:** los de la spec (§5.3, §5.4 y §5.8).
- **Secretos:** nunca credenciales ni configuración de correo en el repositorio. Nunca leer ni crear `application.yml` ni `.env`.
- **Logs:** nunca el código en el log del modo smtp ni en un mensaje de error.
- **Base de datos:** solo el mysqld aislado en un puerto 34xxx. Prohibido el 3306.
- **Git:** prohibidos `--no-verify` y `push --force`.
- **Web:** colores solo de `tokens.css`, skill `impeccable` en modo Operate y el diseño nuevo.
- **Verificación:**
  - backend: `./mvnw -B -q test -DexcludedGroups=conBase` con EXIT=0, más las pruebas `conBase` nuevas o tocadas;
  - web: `npx vitest run`, `npm run -s lint` y `npm run -s build`.

## Review Focus

1. **Token viejo en el navegador al recuperar.** La solicitud funciona y no cierra la sesión de nadie (tarea 5). Con `cambio_clave_req` tampoco da 403 (tarea 3).
2. **Dos confirmaciones correctas a la vez.** Una da 200 y la otra 400, y la contraseña final es la de la ganadora (tarea 4).
3. **Correo con espacios o mayúsculas.** « Ana@Tienda.COM » encuentra a `ana@tienda.com`, tanto al pedir como al confirmar (tareas 3 y 4).
4. **Servidor del sistema en UTC.** El código vence 15 minutos después en hora de Bogotá, no 5 horas antes (tarea 1).
5. **Cola asíncrona llena.** La respuesta sigue siendo 200 y no queda ningún hilo bloqueado (tarea 3).

---

### Task 1: esquema, repositorio y auditoría

**Files:**
- Create:
  - `backend/src/main/resources/db/migration/V52__codigos_de_recuperacion.sql`
  - `backend/src/main/java/co/com/bspstore/sgip/datos/RepositorioCodigosRecuperacion.java`
- Modify:
  - `backend/src/main/java/co/com/bspstore/sgip/modelo/MotivoRechazo.java` (los 6 motivos `RECUPERACION_*`)
  - `backend/src/main/java/co/com/bspstore/sgip/modelo/AuditoriaAcceso.java` (la fábrica `evento`)
- Test:
  - `backend/src/test/java/co/com/bspstore/sgip/datos/CodigosRecuperacionConBaseTest.java` (`@Tag("conBase")`)
  - `backend/src/test/java/co/com/bspstore/sgip/modelo/AuditoriaAccesoTest.java`

**Interfaces:**
- Produces:
  - `long RepositorioCodigosRecuperacion.crear(Integer usuarioId, String codigoHash, LocalDateTime creadoEn, LocalDateTime expira, String origen)`
  - `int invalidarVigentes(Integer usuarioId, LocalDateTime ahora)`: marca `usado_en`
  - `int contarCreadosDesde(Integer usuarioId, LocalDateTime desde)`
  - `Optional<CodigoVigente> bloquearVigente(Integer usuarioId, LocalDateTime ahora)`: hace `SELECT … FOR UPDATE` del más reciente con `usado_en IS NULL AND expira > :ahora`; `record CodigoVigente(long id, String hash, int intentos)`
  - `void sumarIntento(long codigoId)`
  - `void marcarUsado(long codigoId, LocalDateTime ahora)`
  - en el repositorio de auditoría (consultas nuevas, con `JdbcClient` o en `AuditoriaAccesoRepositorio`):
    - `int contarFallosDeRecuperacion(Integer usuarioId, LocalDateTime desde)`: motivo `RECUPERACION_CODIGO_INVALIDO`
    - `int contarSolicitudesDesdeOrigen(String origen, LocalDateTime desde)`: motivos `RECUPERACION_%`
  - `static AuditoriaAcceso AuditoriaAcceso.evento(Usuario usuarioONull, String correo, boolean exitoso, MotivoRechazo motivo, String origen)`

- [ ] **Step 1: tests rojos**
  - `AuditoriaAccesoTest.eventoSinUsuario`: con usuario null, `correo` de 300 caracteres y `origen` de 100, queda `correo.length() == 120`, `origen.length() == 60` y `exitoso` según el argumento.
  - `CodigosRecuperacionConBaseTest`:
    - `creaYBloqueaElVigente`;
    - `vencidoNoEsVigente`: con `expira` = ahora − 1 s, `bloquearVigente` devuelve vacío;
    - `invalidarDejaSinVigente`;
    - `sumarIntento`;
    - `contarCreadosDesde`;
    - `contarFallosDeRecuperacion`;
    - `contarSolicitudesDesdeOrigen`;
    - `elIndiceExiste`: `(usuario_id, usado_en, expira)`;
    - `borrarUsuarioBorraSusCodigos`.
- [ ] **Step 2: ver el rojo.**
- [ ] **Step 3: implementar** la V52 (tabla del §5.1 de la spec), el repositorio y la fábrica `evento`.
- [ ] **Step 4: ver el verde.**
- [ ] **Step 5: commit.**

### Task 2: envío de correo, ejecutor asíncrono y ADR

**Files:**
- Modify: `backend/pom.xml` (`spring-boot-starter-mail`, versión heredada del parent)
- Create:
  - `backend/src/main/java/co/com/bspstore/sgip/logica/correo/EnviadorDeCorreo.java` (interfaz)
  - `backend/src/main/java/co/com/bspstore/sgip/logica/correo/CorreoEnRegistro.java`
  - `backend/src/main/java/co/com/bspstore/sgip/logica/correo/CorreoSmtp.java`
  - `backend/src/main/java/co/com/bspstore/sgip/config/ConfiguracionCorreo.java`
  - `backend/src/main/java/co/com/bspstore/sgip/config/ConfiguracionAsincrona.java`
  - `docs/decisiones/ADR-0036-correo-saliente-y-recuperacion.md`
- Modify: `docs/decisiones/README.md`
- Test: `backend/src/test/java/co/com/bspstore/sgip/logica/correo/CorreoTest.java` y `backend/src/test/java/co/com/bspstore/sgip/config/ConfiguracionCorreoTest.java` (con `ApplicationContextRunner`)

**Interfaces:**
- Produces:
  - `void EnviadorDeCorreo.enviar(String destino, String asunto, String texto)`
  - `static String CorreoSmtp.enmascarar(String correo)`: `"juan@dominio.com"` → `"j***@dominio.com"`
  - el bean `ThreadPoolTaskExecutor` llamado `"ejecutorDeCorreo"`: `corePoolSize` 2, `maxPoolSize` 2, `queueCapacity` 50, `DiscardPolicy` con aviso en el log
  - `@EnableAsync` y un `AsyncUncaughtExceptionHandler` que registra la clase de la excepción y el método, sin argumentos

- [ ] **Step 1: tests rojos**
  - `CorreoTest.registroEscribeElAviso`: con el log capturado (OutputCaptureExtension), contiene `[CORREO LOCAL]`, el destino y el texto.
  - `CorreoTest.enmascarar`: `"juan@dominio.com"` → `"j***@dominio.com"`.
  - `CorreoTest.smtpNoEscribeElTexto`: con un `JavaMailSender` simulado que lanza `MailSendException`, el log tiene el error y el correo enmascarado, no tiene el texto, y no se propaga ninguna excepción.
  - `ConfiguracionCorreoTest`:
    - sin propiedad, el bean es `CorreoEnRegistro`;
    - con `sgip.correo.modo=smtp`, el bean es `CorreoSmtp`;
    - con `registro` y el perfil `prod` activo, el contexto falla al arrancar con el mensaje «El modo de correo «registro» no se permite en producción.».
  - Prueba del ejecutor: con la cola llena, `execute` no bloquea ni lanza.
- [ ] **Step 2: ver el rojo.**
- [ ] **Step 3: implementar.**
  - `CorreoSmtp` usa `JavaMailSender` y `SimpleMailMessage`, con el remitente de `${SGIP_CORREO_REMITENTE}`.
  - `ConfiguracionCorreo` fija `mail.smtp.starttls.enable/required=true` y los tiempos de espera `mail.smtp.connectiontimeout/timeout/writetimeout=10000` solo con `@ConditionalOnProperty(name = "sgip.correo.modo", havingValue = "smtp")`.
  - El ADR-0036 recoge el §5.7 de la spec: los modos, la dependencia nueva, el límite del resumen PBKDF2 y que no hay secreto extra.
- [ ] **Step 4: ver el verde**, con la suite sin base completa.
- [ ] **Step 5: commit.**

### Task 3: solicitar el código

**Files:**
- Modify:
  - `backend/src/main/java/co/com/bspstore/sgip/api/AutenticacionControlador.java`
  - `config/ConfiguracionSeguridad.java` (los dos `permitAll` exactos)
  - `config/FiltroCambioClave.java` (las dos rutas exentas)
  - `config/FiltroEmpresaActiva.java` (las dos rutas exentas)
- Create:
  - `api/dto/SolicitudRecuperacion.java` (`record SolicitudRecuperacion(String correo)`, sin validaciones)
  - `api/dto/MensajeRecuperacion.java` (`record MensajeRecuperacion(String mensaje)`)
  - `logica/recuperacion/ServicioRecuperacion.java`
  - `logica/recuperacion/TareaDeRecuperacion.java`
- Test:
  - `api/RecuperacionControladorTest.java` (MockMvc con la seguridad real)
  - `logica/recuperacion/TareaDeRecuperacionConBaseTest.java` (`@Tag("conBase")`, con el ejecutor reemplazado por uno síncrono y un `EnviadorDeCorreo` falso que guarda los mensajes)

**Interfaces:**
- Consumes: las tareas 1 y 2.
- Produces:
  - `MensajeRecuperacion ServicioRecuperacion.solicitar(String correo, String origen)`: normaliza el correo, llama a `TareaDeRecuperacion.procesar` y devuelve el mensaje fijo. Sin acceso a la base.
  - `@Async("ejecutorDeCorreo") void TareaDeRecuperacion.procesar(String correoNormalizado, String origen)`: transaccional, sigue los pasos 1 a 7 del §5.3 de la spec. El correo se envía con `TransactionSynchronization.afterCommit`.
  - `static String ServicioRecuperacion.normalizar(String correo)`: hace `trim` y minúsculas, recorta a 120 caracteres y convierte null o vacío en `"(vacío)"`.

- [ ] **Step 1: tests rojos**
  - `RecuperacionControladorTest`:
    - sin token → 200 con el mensaje exacto;
    - con el token de un usuario con `cambio_clave_req` → 200, no 403;
    - en una empresa en modo consulta → 200;
    - mismo cuerpo para un correo existente, inexistente, `""`, `null` y uno de 300 caracteres;
    - el servicio simulado recibe el correo normalizado (« Ana@Tienda.COM » → «ana@tienda.com»).
  - `TareaDeRecuperacionConBaseTest`:
    - `existenteActivoRecibeCodigo`: el enviador falso recibe un texto que contiene un número de 6 dígitos; la base guarda un resumen distinto del código; queda una fila de auditoría `RECUPERACION_SOLICITADA` con `exitoso=1`;
    - `inexistente`: no se envía nada y hay una fila `RECUPERACION_CORREO_INEXISTENTE` con `exitoso=0`;
    - `inactivo`: lo mismo con `RECUPERACION_CUENTA_INACTIVA`;
    - `bloqueadoSiRecibe`;
    - `cuartoEnQuinceMinutos`: `RECUPERACION_LIMITE` y no hay código nuevo;
    - `onceDesdeLaMismaIp`: la undécima solicitud en una hora da `RECUPERACION_LIMITE`;
    - `diezFallosEn24h`: con 10 filas `RECUPERACION_CODIGO_INVALIDO` en las últimas 24 horas, no se genera código;
    - `nuevoInvalidaAnterior`;
    - `relojUtc`: con un `Clock` fijo en UTC 2026-11-26T15:00Z, `expira` = 2026-11-26T10:15 (hora de Bogotá).
  - `ServicioRecuperacionTest.respondeSinTocarLaBase`: el repositorio de usuarios simulado no recibe llamadas desde `solicitar`.
- [ ] **Step 2: ver el rojo.**
- [ ] **Step 3: implementar.** El código se genera con `String.format("%06d", secureRandom.nextInt(1_000_000))`.
- [ ] **Step 4: ver el verde.**
- [ ] **Step 5: commit.**

### Task 4: confirmar

**Files:**
- Modify:
  - `AutenticacionControlador`
  - `ServicioRecuperacion`
  - `api/ManejadorErrores.java`
- Create:
  - `api/dto/ConfirmacionRecuperacion.java` (`record ConfirmacionRecuperacion(String correo, String codigo, String nueva)`)
  - `logica/CodigoDeRecuperacionInvalidoException.java`
- Test:
  - `logica/recuperacion/ConfirmacionConBaseTest.java` (`@Tag("conBase")`)
  - `logica/recuperacion/ConfirmacionConcurrenteConBaseTest.java` (`@Tag("conBase")`)
  - casos nuevos en `RecuperacionControladorTest`

**Interfaces:**
- Consumes: las tareas 1 a 3, `ServicioSesion.cerrarLasDe(Integer)`, `Usuario.cambiarClave` y `Usuario.desbloquear`.
- Produces:
  - `MensajeRecuperacion ServicioRecuperacion.confirmar(String correo, String codigo, String nueva, String origen)`, con `@Transactional(noRollbackFor = CodigoDeRecuperacionInvalidoException.class)`, siguiendo el orden del §5.4 de la spec;
  - el 400 `ProblemDetail` con `detail` «El código no es válido o ya venció. Pide uno nuevo.».

- [ ] **Step 1: tests rojos**
  - `ConfirmacionConBaseTest`:
    - `correctoCambiaDesbloqueaYCierra`:
      - partiendo de un usuario bloqueado, con una sesión abierta y con `cambio_clave_req`;
      - la confirmación correcta da 200;
      - después, la sesión vieja da 401, se ingresa con la nueva, `bloqueado=0`, `cambio_clave_req=0`;
      - hay una fila `RECUPERACION_EXITOSA` y el enviador falso recibió el aviso de cambio;
    - `mismoCodigoOtraVez` → 400;
    - `vencido`: con el reloj + 15 min + 1 s → 400;
    - `errado`: da 400 y `intentos=1`, con la fila de auditoría conservada pese al 400;
    - `quintoErrorAgota`: tras 5 errores, el código correcto da 400;
    - `diezFallos24h` → 400 aunque el código sea correcto;
    - `inactivoEntrePedirYConfirmar` → 400;
    - `claveCorta`: 7 caracteres → 400 con el texto de `cambiarClave` y sin tocar el código;
    - `correoConMayusculas` → funciona.
  - `ConfirmacionConcurrenteConBaseTest.dosCorrectas`: dos hilos con un `CyclicBarrier` dan exactamente un 200 y un 400, y la contraseña final verifica con la del hilo que obtuvo 200.
  - `RecuperacionControladorTest`: sin token → la ruta es pública; `CodigoDeRecuperacionInvalidoException` → 400 con el `detail` exacto.
- [ ] **Step 2: ver el rojo.**
- [ ] **Step 3: implementar.** Toda rama de rechazo gasta un solo `Seguridad.verificar`: contra `HASH_FICTICIO` cuando no hay código. La constante se mueve de `ServicioAutenticacion` a `Seguridad.HASH_FICTICIO` (pública) y ambos servicios la usan.
- [ ] **Step 4: ver el verde.**
- [ ] **Step 5: commit.**

### Task 5: pantallas web

**Files:**
- Modify:
  - `web/src/servicios/api.js` (`RUTAS_PUBLICAS` sin `Authorization` y sin borrar la sesión ante un 401; además `api.pedirCodigoRecuperacion(correo)` y `api.confirmarRecuperacion({correo, codigo, nueva})`)
  - `web/src/paginas/Acceso.jsx` (el enlace y el comentario de cabecera)
  - `web/src/App.jsx` (la ruta pública `/recuperar-clave`)
- Create:
  - `web/src/paginas/RecuperarClave.jsx`
  - `web/src/paginas/RecuperarClave.prueba.jsx`
- Test: los casos nuevos en `api` (su archivo de prueba existente) y en `Acceso.prueba.jsx`.

**Interfaces:** consume las rutas de las tareas 3 y 4.

- [ ] **Step 1: tests rojos**
  - `api`:
    - con un token guardado, `pedirCodigoRecuperacion` no manda `Authorization`;
    - un 401 en `/auth/recuperacion/confirmar` no llama a `borrarSesion`;
    - `/auth/login` sigue igual.
  - `Acceso.prueba.jsx`: el enlace «¿Olvidaste tu contraseña?» lleva a `/recuperar-clave`.
  - `RecuperarClave.prueba.jsx`:
    - el paso 1 envía el correo, muestra el mensaje del servidor y pasa al paso 2;
    - las contraseñas distintas no se envían y muestran «Las contraseñas no coinciden.»;
    - un 400 muestra el `detail`;
    - el éxito navega a `/acceso` con el aviso «Listo. Ya puedes entrar con tu contraseña nueva.»;
    - «Pedir otro código» está desactivado hasta los 60 s (con temporizadores falsos);
    - el campo del código tiene `inputMode="numeric"` y `autocomplete="one-time-code"`.
- [ ] **Step 2: ver el rojo.**
- [ ] **Step 3: implementar** con `impeccable`, el diseño nuevo, el estilo de `Acceso.jsx` y los componentes `comun/*`.
- [ ] **Step 4: ver el verde:** vitest, lint y build.
- [ ] **Step 5: capturas** a 1440 y a 390 de Acceso y de los dos pasos. Van a `C:/dev/SGIP/.superpowers/sdd/recuperar-clave/capturas/` y no se commitean.
- [ ] **Step 6: commit.**
