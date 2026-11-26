# ADR-0036 · El correo saliente tiene dos modos y se envía fuera de la petición

Estado: aceptada · 2026-11-26

## Contexto

Hasta ahora SGIP no enviaba correo: quien olvidaba su contraseña dependía de que
otro usuario con permiso la cambiara. La recuperación por correo
(`docs/superpowers/specs/2026-11-25-recuperar-clave-design.md`) manda un código de
6 dígitos al correo del usuario, y eso exige tres cosas que el proyecto no tenía:
una forma de enviar correo, una forma de no hacer esperar a la petición mientras
se envía, y una decisión explícita sobre qué protege a un código tan corto.

## Decisión

**Una interfaz y dos modos.** `EnviadorDeCorreo.enviar(destino, asunto, texto)`,
elegida con la propiedad `sgip.correo.modo` en `ConfiguracionCorreo`:

- **`registro`** (por omisión). `CorreoEnRegistro` escribe en el log, nivel INFO,
  la marca `[CORREO LOCAL]` con destino, asunto y texto. Al arrancar avisa «Los
  correos no salen: modo registro.». **No arranca** si el perfil activo incluye
  `prod`: «El modo de correo «registro» no se permite en producción.». El texto
  lleva el código, así que este modo es solo para desarrollo y pruebas.
- **`smtp`.** `CorreoSmtp` usa `JavaMailSender` y `SimpleMailMessage`.
  - Host, puerto, usuario y clave van en las variables `SPRING_MAIL_*`; el
    remitente, en `SGIP_CORREO_REMITENTE`. Nunca van en el repositorio ni en
    `application.yml`.
  - STARTTLS es obligatorio (`mail.smtp.starttls.enable` y `.required` en `true`)
    y los tiempos de espera de conexión, lectura y escritura son de 10 s. Las dos
    cosas las fija `ConfiguracionCorreo` solo en este modo.
  - El log nunca lleva el texto ni el código, ni el mensaje de la excepción (un
    servidor puede repetir en él lo que recibió). El destinatario va enmascarado
    (`j***@dominio.com`) y de un fallo solo se anotan la clase de la excepción y la
    de su causa raíz.
  - Un fallo del envío se registra como error y no cambia la respuesta al usuario.

El modo se lee con `trim` y en minúsculas: «SMTP» y « smtp » valen como `smtp`. Un
valor que no sea `registro` ni `smtp`, incluido el vacío (la variable definida pero
sin valor), impide arrancar con «sgip.correo.modo debe ser «registro» o «smtp»; se
recibió «...».». Una errata no deja la aplicación sin correo ni cae en silencio al
registro. Un único método `@Bean` elige la implementación, para que la
normalización no pueda contradecirse con una condición aparte.

El modo `smtp` tampoco arranca a medias: falla con un mensaje claro si falta
`SGIP_CORREO_REMITENTE` (o está en blanco), si no hay un `JavaMailSenderImpl` (es
decir, falta `SPRING_MAIL_HOST`), o si `spring.mail.protocol` es algo distinto de
`smtp` (por ejemplo `smtps`). Con otro protocolo las propiedades `mail.smtp.*`
(STARTTLS obligatorio y tiempos de espera) no rigen, y se prefiere no arrancar a
enviar sin ellas. Si algún día hace falta SMTPS (puerto 465), es una decisión nueva.

**Dependencia nueva: `spring-boot-starter-mail`**, con la versión que fija el
parent de Spring Boot (4.1.2). Trae `jakarta.mail-api` y la implementación Angus
(Eclipse, licencia EPL/BSD) en tiempo de ejecución. Hay que seguirla con el resto
de las versiones (ADR-0027). Solo el modo `smtp` la usa; con `registro` ni siquiera
se crea un `JavaMailSender`.

**El envío no espera la petición.** `ConfiguracionAsincrona` activa `@EnableAsync` y
define el ejecutor `ejecutorDeCorreo`: 2 hilos y una cola de 50. Con la cola llena
la tarea se **descarta** con un aviso en el log (`DiscardPolicy`); no se usa
`CallerRunsPolicy`, que haría el envío en el hilo de la petición y devolvería, en el
tiempo de respuesta, la diferencia que la ejecución asíncrona esconde (por ejemplo,
entre un correo registrado y uno que no lo está). Un error que se escape de un
método `@Async` se anota con la clase de la excepción y el método, sin los
argumentos, que pueden llevar el código.

**El límite del resumen PBKDF2 de un código de 6 dígitos.** El código se guarda con
`Seguridad.derivar` (ADR-0002), igual que una contraseña. Pero solo hay un millón
de códigos posibles: si se filtra la base de datos mientras hay códigos vigentes,
un ataque fuera de línea los recupera en minutos por mucho que el resumen sea
lento. **El resumen no es la protección.** La protección real son los 15 minutos de
vida del código, que solo sirve una vez, y los topes de solicitudes y de intentos
fallidos descritos en la spec. Lo que cubre el resumen es que un código ya usado o
vencido no se pueda leer a simple vista en una copia de la base.

**No se agrega un secreto adicional.** Se pudo derivar el resumen con una clave del
servidor (un «pimienta») para que la base sola no bastara. Se descartó: es otra
clave que guardar, rotar y proteger, y para los 15 minutos de vida de un código no
compensa. Si esta decisión se revisa, es una decisión nueva con su ADR.

**Despliegue: la IP del tope.** El tope de 10 solicitudes por hora se cuenta por
`getRemoteAddr()`. No se debe activar `server.forward-headers-strategy` (ni confiar en
`X-Forwarded-For`) salvo detrás de un proxy propio que **reemplace** esa cabecera con
la dirección real del cliente. Si la aplicación acepta la cabecera tal como llega, quien
pide códigos puede falsificarla en cada petición y saltarse el tope por IP. Sin proxy,
la dirección es la del último salto; con un proxy sin configurar, el tope cuenta la del
proxy (ver el §8 de la spec).

**Solicitudes simultáneas.** La tarea toma la fila del usuario (`FOR UPDATE`) antes de
contar los topes por correo, y su transacción va en `READ COMMITTED`: con el nivel por
omisión de MySQL, la segunda solicitud leería los conteos con la foto anterior a su
espera y no vería el código de la primera.

## Consecuencias

- Una dependencia más en el `pom.xml`, y tres variables de entorno más en
  producción (`SPRING_MAIL_*` y `SGIP_CORREO_REMITENTE`) y `sgip.correo.modo=smtp`.
  Sin ellas, producción no arranca: es lo que se quiere.
- Un solo ejecutor para todo lo asíncrono: por ahora solo envía correos. Si llega
  otro trabajo asíncrono, debe nombrarlo `@Async("...")` o tener su propio
  ejecutor. Al existir un bean de ejecutor, Spring Boot ya no crea el suyo
  (`applicationTaskExecutor`).
- Cuando el servidor se cae justo después de responder, la solicitud en cola se
  pierde y el usuario pide otro código. Se acepta.
- Con `DiscardPolicy` una tarea descartada no se ejecuta ni se completa: un
  `@Async` futuro que devuelva `CompletableFuture` (o `Future`) en este ejecutor
  dejaría ese futuro sin completar para siempre si la cola está llena. Hoy los
  métodos son `void`; quien añada uno con retorno debe decidirlo (otro ejecutor o
  un tiempo de espera).
- Cuando la cola está llena el usuario recibe la misma respuesta de siempre y no
  llega correo; solo el log lo dice.
- En el modo `registro` el código queda en el log. Quien lea logs de un entorno de
  desarrollo ve los códigos: por eso no se permite en `prod`.
