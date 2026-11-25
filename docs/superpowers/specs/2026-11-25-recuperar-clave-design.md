# Recuperar la contraseña por correo (RF-65) — diseño

Fecha: 2026-11-25. Rama: `feat/recuperar-clave`.
El 2026-11-25 el usuario pidió avanzar sin más preguntas. Por eso las decisiones que no salen de los requisitos van marcadas **(decisión del orquestador)**.

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
    - **se invalidan las sesiones previas** (RNF-09, que deben devolver 401 de inmediato).
  - Un código vencido o ya usado devuelve **400**.
- **RNF-06.** El código se guarda con una función irreversible y sal única, nunca en claro.
- **RF-11.** Cada intento de recuperación deja **exactamente un** registro inmutable en `auditoria_accesos`.
- **Tabla 10.** «Seguridad (sesión y clave)» es pública para los cuatro roles.

## 3. Objetivos, no objetivos y antiobjetivos

**Objetivos**
- Enlace «¿Olvidaste tu contraseña?» en la pantalla de ingreso.
- Pantalla de dos pasos: correo → código y contraseña nueva.
- Servicio de envío de correo con dos modos:
  - **registro** (local y muestra): el correo se escribe en el log del servidor y no sale a internet;
  - **SMTP** (producción): datos del servidor de correo solo por variables de entorno.

**No objetivos**
- Elegir el proveedor de correo. Se decide al subir la app a internet; el usuario creará la cuenta.
- Enlaces mágicos por correo. Se usa código, como dice RF-65.
- Recuperar por SMS.

**Antiobjetivos**
- Que la respuesta (su texto o su tiempo) revele qué correos existen.
- Que el código quede en claro en la base, o en el log del modo SMTP.
- Que se pueda adivinar el código probando sin límite.
- Que una sesión abierta antes del cambio siga sirviendo.

## 4. Enfoques considerados

1. **Código de 6 dígitos por correo, guardado como resumen en una tabla propia** ← **elegido**. Es lo que pide RF-65, se teclea fácil desde el celular y la tabla deja auditar y limitar intentos.
2. **Enlace con token largo.** Se descarta: RF-65 dice «código», y el enlace exige conocer la URL pública, que no existe hasta el despliegue.
3. **Guardar el código en columnas de `usuarios`.** Se descarta: un solo código por usuario a la vez se logra igual con la tabla. Además, la tabla conserva la historia para la auditoría y no ensucia la entidad.

## 5. Diseño

### 5.1 Datos: migración **V52** (reservada para esta rama)

Tabla `codigos_recuperacion`:

| Columna | Tipo |
|---|---|
| `codigo_id` | BIGINT, clave primaria |
| `usuario_id` | INT, FK a `usuarios`, ON DELETE CASCADE |
| `codigo_hash` | VARCHAR(255) |
| `creado_en` | DATETIME |
| `expira` | DATETIME |
| `usado_en` | DATETIME NULL |
| `intentos` | TINYINT, por omisión 0 |
| `origen` | VARCHAR(60) |

Índice `(usuario_id, creado_en)`.
Las horas las pone la aplicación con su reloj (ADR-0028, ADR-0030), no la base.

### 5.2 Solicitar: `POST /api/auth/recuperacion` con `{correo}`

**Respuesta**
- Siempre 200 con el mensaje «Si el correo está registrado, te enviamos un código. Revisa también la carpeta de spam.».
- Un correo con formato inválido también recibe 200 *(decisión del orquestador: el mensaje no distingue nada)*.

**Trabajo en segundo plano (`@Async`, ejecutor propio)**
- Todo pasa después de responder, para que el tiempo de respuesta no dependa de si el correo existe:
  - buscar el usuario;
  - derivar el resumen del código, que es el paso costoso;
  - enviar el correo.

**Cuándo se genera un código**
- Solo si el usuario existe y está **activo**.
- Un usuario bloqueado **sí** recibe código: justamente así se desbloquea.

**El código**
- 6 dígitos de `SecureRandom`.
- Se guarda con `Seguridad.derivar`, la misma derivación con sal de las contraseñas (ADR-0002).
- Un código nuevo **invalida los anteriores** del usuario: se marcan usados.

**Límite de solicitudes** *(decisión del orquestador)*
- Máximo **3 por correo cada 15 minutos**.
- Las de más no generan código, pero responden igual.

**Auditoría**
- Una fila por solicitud con `motivo = RECUPERACION_SOLICITADA`, `exitoso = 1`, `usuario_id` si existe y el correo usado.
- Si se descarta por inexistente, inactivo o por el límite, el motivo lo dice:
  - `RECUPERACION_CORREO_INEXISTENTE`;
  - `RECUPERACION_CUENTA_INACTIVA`;
  - `RECUPERACION_LIMITE`.

**El correo**
- Texto en español: «Tu código para recuperar la contraseña de SGIP es 123456. Vence en 15 minutos. Si no lo pediste, ignora este mensaje; tu contraseña no cambia.».

### 5.3 Confirmar: `POST /api/auth/recuperacion/confirmar` con `{correo, codigo, nueva}`

**Validaciones**
- La contraseña nueva cumple el mínimo de 8 caracteres de siempre. Si no, 400 con el mismo texto que `cambiarClave`.
- Se busca el código vigente más reciente del usuario: no usado, no vencido, con menos de **5 intentos**.
- Si falla cualquier cosa, responde **400** con un único mensaje: «El código no es válido o ya venció. Pide uno nuevo.». Los casos son:
  - correo inexistente;
  - sin código vigente;
  - código errado;
  - vencido;
  - ya usado.
- Un código errado suma un intento al código vigente. Al quinto queda inutilizado *(decisión del orquestador: 5 intentos frente a 1 000 000 de combinaciones)*.
- La rama sin código vigente gasta el mismo tiempo de verificación (patrón `HASH_FICTICIO`).

**Si es correcto, en una sola transacción**
- marca el código usado;
- cambia el resumen de la contraseña;
- desbloquea la cuenta e intentos vuelven a 0;
- quita `cambio_clave_req`, porque la eligió el propio usuario;
- cierra **todas** las sesiones del usuario.
- Responde 200. No inicia sesión: la persona entra con la contraseña nueva *(decisión del orquestador)*.

**Auditoría**
- Una fila por intento:
  - `RECUPERACION_EXITOSA`;
  - `RECUPERACION_CODIGO_INVALIDO`.
- La fila del intento fallido se conserva aunque la respuesta sea 400: la transacción no revierte la auditoría ni el contador (mismo patrón `noRollbackFor` que `cambiarClave`).

Los valores nuevos van en `MotivoRechazo`.

### 5.4 Envío de correo (ADR nuevo, el **ADR-0036**)

- La interfaz `EnviadorDeCorreo` tiene el método `enviar(destino, asunto, texto)`.
- El modo se elige con `sgip.correo.modo`.
- **`registro`** (por omisión):
  - escribe destino, asunto y texto en el log con nivel INFO y la marca `[CORREO LOCAL]`;
  - sirve en desarrollo y en la muestra;
  - al arrancar con este modo, el log avisa «Los correos no salen: modo registro.».
- **`smtp`**:
  - usa `spring-boot-starter-mail` (dependencia nueva, por eso el ADR);
  - toma host, puerto, usuario y contraseña de las variables de entorno `SPRING_MAIL_*`;
  - el remitente sale de `SGIP_CORREO_REMITENTE`;
  - no escribe el texto del correo en el log;
  - un fallo de envío queda en el log como error, sin el código, y la respuesta al usuario no cambia.
- **Ninguna credencial va al repositorio ni a `application.yml`.**

### 5.5 Pantallas (diseño nuevo, `impeccable`, modo Operate)

- En «Acceso», bajo el botón de ingresar, va el enlace «¿Olvidaste tu contraseña?».
- **Paso 1.** «Escribe el correo con el que entras a SGIP.» Campo correo y botón [Enviar código]. Luego muestra el mensaje genérico y pasa al paso 2.
- **Paso 2.**
  - Campos: código de 6 dígitos (`inputMode="numeric"`, `autocomplete="one-time-code"`), contraseña nueva y confirmar contraseña.
  - Botón [Cambiar contraseña].
  - Enlace «¿No te llegó? Pedir otro código», activo a los 60 segundos *(decisión del orquestador)*.
  - Que las dos contraseñas no coincidan se valida en la pantalla.
- **Al terminar** vuelve a «Acceso» con el aviso «Listo. Ya puedes entrar con tu contraseña nueva.».

## 6. Pruebas que fijan el comportamiento

**Solicitar**
- Mismo 200 y mismo cuerpo con un correo existente, inexistente, inactivo o con formato inválido.
- No hay acceso al repositorio de usuarios en el hilo de la petición: la búsqueda va en la tarea asíncrona.

**Código y confirmación**
- Al pedirlo, se genera, se guarda como resumen distinto del código y el enviador falso recibe un texto con 6 dígitos.
- El cuarto pedido en 15 minutos no genera código.
- Confirmar con el código correcto:
  - cambia la contraseña;
  - desbloquea una cuenta bloqueada;
  - una sesión abierta antes devuelve 401;
  - se puede ingresar con la nueva.
- Errores de confirmación:
  - el mismo código otra vez devuelve 400;
  - a los 15 min + 1 s (reloj fijo) devuelve 400;
  - con un código errado devuelve 400, y al quinto error ni el correcto sirve;
  - el código anterior deja de servir cuando se pide uno nuevo.

**Auditoría y correo**
- Exactamente una fila de auditoría por solicitud y por confirmación.
- El modo `registro` escribe el aviso. En modo `smtp`, un fallo del servidor de correo no cambia la respuesta.

**Web**
- Enlace en Acceso.
- Paso 1 → paso 2.
- Contraseñas que no coinciden.
- Error 400 mostrado.
- Éxito vuelve a Acceso con el aviso.

## 7. Lo que queda al constructor

- Nombres de clases y su ubicación, siguiendo `ServicioAutenticacion`, `AutenticacionControlador` y `SesionRepositorio`.
- Cómo prueba la parte asíncrona: ejecutor síncrono en las pruebas o una espera con tope.
- El límite de 3 se cuenta con la tabla, no en memoria, para que sobreviva a un reinicio.
