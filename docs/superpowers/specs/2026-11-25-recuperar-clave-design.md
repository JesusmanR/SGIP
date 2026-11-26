# Recuperar la contraseña por correo (RF-65) — diseño

- **Fecha:** 2026-11-25. Revisión 2 del 2026-11-26, tras la revisión EGM (ver §9).
- **Rama:** `feat/recuperar-clave`.
- **Decisiones:** el 2026-11-25 el usuario pidió avanzar sin más preguntas. Las decisiones que no salen de los requisitos van marcadas **(decisión del orquestador)**.

## 1. Para qué

Hoy, si alguien olvida su contraseña, solo la puede restablecer el administrador. El super administrador no tiene a quién pedírsela.
RF-65 (prioridad alta, MVP) pide que cada usuario la recupere solo, con un código que llega a su correo.

## 2. Lo que fijan los requisitos (v3/v4)

- **RF-65.**
  - La solicitud responde **siempre 200**, en menos de 1 s y con el mismo mensaje, exista o no el correo.
  - El código es temporal, de un solo uso y caduca a los **15 minutos**.
  - Al confirmar la contraseña nueva:
    - se guarda su resumen (RNF-06);
    - **se desbloquea la cuenta** (RF-10);
    - **se invalidan las sesiones previas**, que devuelven 401 de inmediato (RNF-09).
  - Un código vencido o ya usado devuelve **400**.
- **RNF-06.** El código se guarda con una función irreversible y con sal única, nunca en claro.
- **RF-11.** Cada intento de recuperación deja **exactamente un** registro inmutable en `auditoria_accesos`.
- **Tabla 10.** «Seguridad (sesión y clave)» es pública para los cuatro roles.

## 3. Objetivos, no objetivos y antiobjetivos

**Objetivos**
- En «Acceso», el enlace «¿Olvidaste tu contraseña?».
- La pantalla `/recuperar-clave`, en dos pasos: correo, y después código y contraseña nueva.
- El envío de correo con dos modos:
  - **registro** (local y muestra): el correo se escribe en el log del servidor;
  - **smtp** (producción): los datos del servidor de correo van solo en variables de entorno.
- Un correo de aviso después del cambio *(decisión del orquestador)*: si alguien entra al buzón de otro, el dueño se entera.

**No objetivos**
- Elegir el proveedor de correo. Se decide al publicar la app; el usuario crea la cuenta.
- Enlaces por correo. RF-65 pide código.
- Recuperar por SMS.
- Mirar la IP real detrás de un proxy (`X-Forwarded-For`). Se configura al publicar la app; hasta entonces `origen` es `getRemoteAddr()`.

**Antiobjetivos**
- Que la respuesta, por su texto o por su tiempo, revele qué correos existen.
- Que el código quede en claro en la base, o que el modo smtp lo escriba en el log.
- Que se pueda adivinar el código probando sin límite.
- Que una sesión abierta antes del cambio siga sirviendo.
- Que el modo registro corra en producción.

## 4. Enfoques considerados

1. **Código de 6 dígitos por correo, guardado como resumen en una tabla propia** ← **elegido**.
   - Es lo que pide RF-65.
   - Se teclea fácil.
   - La tabla permite auditar y poner topes.
2. **Enlace con token largo.** Se descarta: RF-65 dice «código», y la URL pública no existe hasta publicar la app.
3. **Columnas nuevas en `usuarios`.** Se descarta: no guarda historia para los topes y ensucia la entidad.

## 5. Diseño

### 5.1 Datos: migración **V52** (reservada para esta rama; V50 y V51 son de otras ramas)

Tabla `codigos_recuperacion`, en `backend/src/main/resources/db/migration`:

| Columna | Definición |
|---|---|
| `codigo_id` | `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY` |
| `usuario_id` | `INT NOT NULL`, FK a `usuarios` **ON DELETE CASCADE** |
| `codigo_hash` | `VARCHAR(255) NOT NULL` |
| `creado_en` | `DATETIME NOT NULL` |
| `expira` | `DATETIME NOT NULL` |
| `usado_en` | `DATETIME NULL` |
| `intentos` | `TINYINT UNSIGNED NOT NULL DEFAULT 0` |
| `origen` | `VARCHAR(60) NULL` |

- **Índice** `(usuario_id, usado_en, expira)`.
- **ON DELETE CASCADE.** Los usuarios no se borran, se desactivan. La historia que cuenta está en `auditoria_accesos`, que no depende de esta tabla.
- **Repositorio** con `JdbcClient` (`RepositorioCodigosRecuperacion`), no con entidad JPA.
- **Horas:** `creado_en`, `expira`, `usado_en` y todas las comparaciones usan **`Hoy.ahora(reloj)`** (hora de Bogotá, ADR-0030). Nunca `LocalDateTime.now(reloj)` ni la hora de la base.

### 5.2 Rutas públicas

Van en `AutenticacionControlador` (`/api/v1/auth`):

- `POST /api/v1/auth/recuperacion`
- `POST /api/v1/auth/recuperacion/confirmar`

**`ConfiguracionSeguridad`.** Dos `requestMatchers(HttpMethod.POST, …).permitAll()` exactos. Una prueba comprueba que responden sin token.

**`FiltroCambioClave` y `FiltroEmpresaActiva`.** Las dos rutas quedan exentas, igual que `/clave` y `/logout`. Si llega un token viejo, no puede bloquearlas.

**Web (`api.js`).**
- Un conjunto `RUTAS_PUBLICAS = ['/auth/login', '/auth/recuperacion', '/auth/recuperacion/confirmar']`.
- Para esas rutas, `pedir` no envía `Authorization` y un 401 no borra la sesión.

### 5.3 Solicitar

**Entrada:** `{correo}`.

**Respuesta**
- Siempre `200 {"mensaje": "Si el correo está registrado, te enviamos un código. Revisa también la carpeta de spam."}`.
- Esto incluye un correo con formato inválido, vacío o demasiado largo.
- Un cuerpo JSON mal formado da el 400 normal de Spring. No revela nada sobre el correo.

**Hilo de la petición**
- Normaliza el correo: `trim`, minúsculas y un máximo de 120 caracteres; si viene vacío, se registra «(vacío)». También se toma `origen`.
- Entrega la tarea al ejecutor asíncrono y responde.
- No consulta la base.

**Tarea asíncrona** (un bean aparte `TareaDeRecuperacion` con `@Async("ejecutorDeCorreo")`, sin autoinvocación), en una transacción:

1. **Tope por IP.** Si el mismo `origen` hizo más de **10 solicitudes en la última hora**, se descarta con `RECUPERACION_LIMITE`. Se cuenta en `auditoria_accesos` con los motivos `RECUPERACION_*`.
2. **Usuario.**
   - No existe: se descarta con `RECUPERACION_CORREO_INEXISTENTE`.
   - Está inactivo: se descarta con `RECUPERACION_CUENTA_INACTIVA`.
   - Un usuario bloqueado **sí** recibe código: así se desbloquea. El vencimiento del bloqueo no se consulta.
3. **Tope por correo.** Si ya hubo **3 códigos en 15 minutos**, se descarta con `RECUPERACION_LIMITE`.
4. **Tope de fallos.** Si la cuenta acumula **10 confirmaciones fallidas en 24 horas**, se descarta con `RECUPERACION_LIMITE` *(decisión del orquestador; ver el §5.5)*.
5. **Código nuevo.**
   - 6 dígitos de `SecureRandom`.
   - Se guarda con `Seguridad.derivar`.
   - Los códigos anteriores sin usar se marcan usados.
6. **Auditoría:** `RECUPERACION_SOLICITADA`.
7. **Correo**, después del commit de la transacción:
   - Asunto: «Tu código para recuperar la contraseña de SGIP».
   - Texto: «Tu código es 123456. Vence en 15 minutos. Si pediste más de uno, usa el último que recibas. Si no lo pediste, ignora este mensaje: tu contraseña no cambia.».

Cada descarte deja una sola fila de auditoría y no envía nada.

**Ejecutor** (`ConfiguracionAsincrona`, con `@EnableAsync`)
- `ThreadPoolTaskExecutor` llamado `ejecutorDeCorreo`: 2 hilos y una cola de 50.
- Si la cola está llena, la tarea se **descarta** con un aviso en el log, sin el correo. No se usa `CallerRuns`, que devolvería la diferencia de tiempo.
- Un `AsyncUncaughtExceptionHandler` escribe los errores en el log, sin el código.

**Riesgo aceptado.** Si el servidor se cae justo después de responder, esa solicitud no deja fila. El usuario simplemente pide otro código.

### 5.4 Confirmar

**Entrada:** `{correo, codigo, nueva}`.

**Orden**, en una transacción con `noRollbackFor = CodigoDeRecuperacionInvalidoException.class`:

1. **Contraseña nueva.** Debe tener al menos 8 caracteres. Si no, 400 con el mismo texto que `cambiarClave`. Se valida antes de buscar nada, así que no revela nada.
2. **Usuario.** Se busca por el correo normalizado.
3. **Código.** El más reciente no usado y no vencido del usuario, con `SELECT … FOR UPDATE`. Así dos confirmaciones a la vez se ordenan y los intentos no se pisan.
4. **Rechazo.** Si no hay usuario, si está inactivo, si no hay código, si el código tiene 5 o más intentos o si la cuenta tiene 10 fallos en 24 horas:
   - se gasta **un** `Seguridad.verificar` contra `HASH_FICTICIO`;
   - se escribe la auditoría `RECUPERACION_CODIGO_INVALIDO`;
   - responde 400.
5. **Verificación del código.** Se verifica con `Seguridad.verificar` (un solo cálculo, en todas las ramas).
   - **Si falla:** suma 1 a `intentos` y responde 400 con la auditoría `RECUPERACION_CODIGO_INVALIDO`.
   - **Si acierta:**
     - marca `usado_en`;
     - `usuario.cambiarClave(Seguridad.derivar(nueva))`, que ya quita `cambio_clave_req`;
     - `usuario.desbloquear()`;
     - **`ServicioSesion.cerrarLasDe(usuarioId)`**, que cierra todas sus sesiones;
     - auditoría `RECUPERACION_EXITOSA`;
     - después del commit, correo de aviso: «Tu contraseña de SGIP se cambió el {fecha y hora}. Si no fuiste tú, avisa de inmediato al administrador de tu empresa.».
     - Responde `200 {"mensaje": "Listo. Ya puedes entrar con tu contraseña nueva."}`. No inicia sesión.

**El 400** es un `ProblemDetail` con `detail` = «El código no es válido o ya venció. Pide uno nuevo.». Lo genera la excepción nueva `CodigoDeRecuperacionInvalidoException`, mapeada en `ManejadorErrores`.

### 5.5 Cuántos intentos tiene un atacante

- Cada intento acierta con probabilidad 1 en 1 000 000.
- Con 5 intentos por código y un tope de **10 confirmaciones fallidas por cuenta en 24 horas**, un atacante tiene como máximo 10 intentos al día por cuenta: alrededor de 0,03 % en 30 días.
- Probar muchas cuentas lo frena el tope de 10 solicitudes por IP por hora.
- **Precio aceptado:** un atacante puede gastar los 10 intentos diarios de una víctima y dejarla sin recuperar la contraseña por 24 horas. Sigue pudiendo entrar con su contraseña, o pedirle al administrador que la restablezca.

### 5.6 Auditoría

Fábrica nueva `AuditoriaAcceso.evento(Usuario usuarioONull, String correo, boolean exitoso, MotivoRechazo motivo, String origen)`. Acepta un usuario nulo y recorta `correo` a 120 y `origen` a 60.

| Motivo (nuevo en `MotivoRechazo`) | `exitoso` |
|---|---|
| `RECUPERACION_SOLICITADA` | 1 |
| `RECUPERACION_CORREO_INEXISTENTE` | 0 |
| `RECUPERACION_CUENTA_INACTIVA` | 0 |
| `RECUPERACION_LIMITE` | 0 |
| `RECUPERACION_EXITOSA` | 1 |
| `RECUPERACION_CODIGO_INVALIDO` | 0 |

Hoy ningún informe lee `exitoso` como «último ingreso». Si uno llega a hacerlo, deberá filtrar los motivos `RECUPERACION_*`.

### 5.7 Envío de correo (**ADR-0036**)

- **Interfaz.** `EnviadorDeCorreo.enviar(String destino, String asunto, String texto)`, elegida con `sgip.correo.modo`.
- **`registro`** (por omisión):
  - escribe en el log, nivel INFO, la marca `[CORREO LOCAL]` con destino, asunto y texto;
  - al arrancar avisa «Los correos no salen: modo registro.»;
  - **no arranca** si el perfil activo incluye `prod`: el arranque falla con un mensaje claro.
- **`smtp`:**
  - usa `spring-boot-starter-mail`. El bean va con `@ConditionalOnProperty(sgip.correo.modo=smtp)`;
  - host, puerto, usuario y clave van en las variables `SPRING_MAIL_*`; el remitente, en `SGIP_CORREO_REMITENTE`;
  - STARTTLS es obligatorio (`mail.smtp.starttls.required=true`);
  - los tiempos de espera de conexión, lectura y escritura son de 10 s;
  - el log nunca lleva el texto ni el código; el destinatario va enmascarado (`j***@dominio.com`);
  - un fallo del envío se registra como error y no cambia la respuesta.
- **El ADR deja escrito:**
  - el resumen PBKDF2 de un código de 6 dígitos no resiste un ataque fuera de línea si se filtra la base;
  - la protección real son los 15 minutos y los topes;
  - no se agrega un secreto adicional para no tener otra clave que administrar.
- **Nunca** van credenciales en el repositorio ni en `application.yml`.

### 5.8 Pantallas (diseño nuevo, `impeccable`, modo Operate)

- **En «Acceso».** Bajo el botón de ingresar va el enlace «¿Olvidaste tu contraseña?» a `/recuperar-clave`. Se actualiza el comentario de cabecera de `Acceso.jsx`, que hoy dice que la recuperación no existe.
- **Paso 1.**
  - «Escribe el correo con el que entras a SGIP.», un campo de correo y [Enviar código].
  - Al enviar, muestra el mensaje del servidor y pasa al paso 2.
- **Paso 2.**
  - Código de 6 dígitos (`inputMode="numeric"`, `autocomplete="one-time-code"`), contraseña nueva y confirmación, y [Cambiar contraseña].
  - Enlace «¿No te llegó? Pedir otro código», activo a los 60 segundos *(decisión del orquestador)*.
  - La coincidencia de las dos contraseñas se valida en la pantalla.
- **Al terminar,** vuelve a «Acceso» con el aviso «Listo. Ya puedes entrar con tu contraseña nueva.».

## 6. Pruebas que fijan el comportamiento

**Solicitud**
- Las dos rutas responden sin token. Con un token viejo de un usuario con `cambio_clave_req` también responden, sin 403.
- Mismo 200 y mismo cuerpo para un correo existente, inexistente, inactivo, inválido o de 300 caracteres.
- El hilo de la petición no consulta la base: el servicio de usuarios simulado no se llama antes de que corra la tarea.
- Al pedir, el código se guarda con un resumen distinto del código, y el enviador falso recibe 6 dígitos.
- El cuarto pedido en 15 minutos no genera código. La undécima solicitud de la misma IP en una hora tampoco.

**Confirmación**
- Con el código correcto:
  - la contraseña cambia;
  - una cuenta bloqueada queda desbloqueada;
  - una sesión anterior devuelve 401;
  - se ingresa con la nueva;
  - sale el correo de aviso.
- El mismo código otra vez da 400.
- A los 15 min + 1 s, con reloj fijo, da 400.
- Errado, da 400. Al quinto error ni el correcto sirve.
- Después de 10 fallos en 24 horas no se generan ni aceptan códigos.
- Un código anterior deja de servir cuando se pide uno nuevo.
- Si la cuenta se desactiva entre pedir y confirmar, da 400.
- **Concurrencia:** dos confirmaciones correctas a la vez dan un 200 y un 400.

**Auditoría, reloj, correo y ejecutor**
- Exactamente una fila de auditoría por solicitud y por confirmación, con el `exitoso` de la tabla del §5.6.
- Con el reloj del sistema en UTC, `expira` es la hora de Bogotá + 15 minutos.
- Modo `registro` con perfil `prod`: la aplicación no arranca. En modo `smtp`, un fallo del servidor de correo no cambia la respuesta.
- Con la cola llena, la tarea se descarta y la respuesta sigue siendo 200.

**Web**
- El enlace en Acceso.
- El paso 1 lleva al paso 2.
- Las contraseñas que no coinciden.
- El error 400 se muestra.
- El éxito vuelve a Acceso con el aviso.
- Sin `Authorization` hacia las rutas públicas, y un 401 en ellas no borra la sesión.

## 7. Lo que queda al constructor

- Los nombres de clases internas y su paquete, siguiendo `ServicioAutenticacion` y `SesionRepositorio`.
- Cómo esperan las pruebas la tarea asíncrona: ejecutor síncrono en pruebas, o espera con tope.

## 8. Riesgos

- **Filtración de la base:** ver el ADR-0036 (§5.7).
- **Bloqueo de 24 horas por fallos:** ver el §5.5.
- **Proxy:** sin la IP real, el tope por IP cuenta la del proxy. Se ajusta al publicar la app.

## 9. Hallazgos de la revisión (2026-11-26) y cómo se resolvieron

| Hallazgo | Resolución |
|---|---|
| B1 prefijo y rutas públicas | §5.2 |
| B2 token viejo desde la web | §5.2: `RUTAS_PUBLICAS` y filtros exentos |
| S1 carrera en la confirmación | `FOR UPDATE` sobre el código |
| S2 presupuesto de intentos | 10 fallos por cuenta en 24 horas (§5.5) |
| S3 abuso de recursos | Tope por IP y cola acotada que descarta |
| S4 no había `@EnableAsync` | `ConfiguracionAsincrona` |
| S5 auditoría asíncrona | Va en la transacción de la tarea; riesgo aceptado |
| S6 fábrica de auditoría | `evento(...)` y la tabla de `exitoso` |
| S7 cuenta inactiva al confirmar | 400 |
| S8 tiempo igual en todas las ramas | Un `verificar` por rama |
| S9 modo registro en producción | No arranca; SMTP con STARTTLS y tiempos de espera |
| S10 reloj | `Hoy.ahora(reloj)` |
| S11 límite del resumen | Documentado en el ADR-0036 |
| S12 cierre de sesiones | `cerrarLasDe` |
| M1–M9 | CASCADE explicado; columnas completas con `JdbcClient`; V52 con huecos reservados; ruta `/recuperar-clave`; correo de aviso; el bloqueo no impide recuperar; proxy en el despliegue; forma JSON; «usa el último código» |
