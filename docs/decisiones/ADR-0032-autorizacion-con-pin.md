# ADR-0032 · El administrador autoriza en la caja con un PIN

Estado: aceptada · 2026-11-09

## Contexto

En la caja, un vendedor no puede por sí solo dar un descuento, vender sin existencia
(encargo), crear un producto que no está registrado ni hacer una devolución. Esas cuatro
excepciones las autoriza un administrador (diseño de la caja, apartado 9). El usuario
decidió que la autorización ocurra **en la misma pantalla del vendedor**, con un PIN
personal del administrador, y dejó fuera la autorización a distancia.

El ADR fija cómo se guarda ese PIN, cómo se protege de quien tantea y cómo se relaciona
con la transacción de la operación que autoriza.

## Decisión

**Un PIN y no la contraseña.** El administrador escribe su PIN en un teclado compartido,
con el vendedor al lado. Una contraseña tecleada ahí se vería y se aprendería; el PIN
sirve solo para autorizar en la caja (no abre sesión ni cambia datos), es corto a
propósito para que se pueda escribir de pie, y por eso se protege con intentos
limitados. Son de 4 a 6 dígitos (`[0-9]{4,6}`), no pueden ser iguales a la contraseña
del mismo administrador, y lo crea y lo cambia él desde su perfil
(`PUT /api/v1/perfil/pin`; `GET` solo dice si está configurado). Un cambio exige el PIN
actual.

**Se guarda derivado.** `usuarios.pin_hash` usa el mismo derivador que las contraseñas
(`Seguridad.derivar`, PBKDF2 con SHA-256, 120.000 iteraciones y sal por usuario,
ADR-0002). Ninguna respuesta, mensaje ni registro lleva un PIN; los DTO que lo
transportan ocultan el PIN en `toString`.

**El administrador se elige por su nombre.** El vendedor ve la lista de administradores
activos de la empresa que tienen PIN (`GET /api/v1/caja/administradores`, solo id y
nombre) y elige a quién llamar. El servidor **nunca prueba un PIN contra todos** los
administradores: con 10⁶ posibles PIN, probar contra N administradores multiplica por N
la posibilidad de acertar con cada intento, y los fallos no se podrían atribuir a nadie.

**Bloqueo.** Los fallos se cuentan **por administrador**: cinco seguidos bloquean su PIN
quince minutos, aunque el PIN que llegue después sea el correcto. Es la regla de las
cuentas (ADR-0028): el máximo es `sgip.seguridad.intentos-maximos` y el plazo
`sgip.seguridad.minutos-de-bloqueo`, que sigue leyendo solo `PoliticaDeBloqueo`; la
política recibe ahora la sobrecarga `(marcado, desde, ahora)` y la regla del plazo vive
en un solo sitio. Al vencer el plazo, el contador parte de cero. Un acierto lo reinicia.
Cambiar el PIN con un PIN actual errado cuenta contra el mismo contador y respeta el
mismo bloqueo (con una sesión robada, esa puerta sería la más barata para adivinarlo).
El bloqueo y cada fallo quedan en `auditoria_accesos` con motivos nuevos
(`PIN_INCORRECTO`, `PIN_OBJETIVO_INVALIDO`, `PIN_BLOQUEADO`), a nombre de quien intentó;
el bloqueo mismo, además, a nombre de la cuenta bloqueada.

**El objetivo que no vale cuenta contra quien lo pidió.** Si el administrador indicado es
de otra empresa, no es administrador, está inactivo o no tiene PIN, la respuesta es el
mismo 403 con el mismo texto que un PIN errado, y el fallo se cuenta contra el
**solicitante** (`pin_errados_emitidos`, con el mismo plazo y el mismo máximo). Así quien
tantea ids ajenos no puede bloquear el PIN de un administrador de otra empresa, ni este
puede enterarse de que existe. Un solicitante bloqueado por esta vía recibe el 403 de
bloqueo aunque mande un administrador y un PIN válidos. La rama de objetivo inválido
deriva el PIN contra uno ficticio para gastar el mismo tiempo que la de PIN errado.

**Se valida antes de la transacción de la operación, y la validación no se revierte.**
La fachada de cada operación (venta, devolución) llama a `ServicioAutorizacion.autorizar`
**antes** de abrir la transacción de la operación. `autorizar` corre en la suya
(`REQUIRES_NEW`, con `noRollbackFor` de las dos excepciones del PIN) y escribe los
intentos, el bloqueo y la bitácora en su propio `commit`, de modo que sobreviven a que la
operación falle después. Sin eso, un vendedor podría probar PIN por venta y la venta
fallida deshacería el contador. Las excepciones se lanzan desde el propio `autorizar`, no
desde un bean transaccional anidado, que marcaría la transacción para revertirla.

**Una autorización es de una sola petición.** `autorizar` devuelve un `Autorizacion` en
memoria (administrador y excepciones que cubre) que el bean transaccional consume con
`exigir(...)` dentro de la misma petición. Nunca se guarda ni se reutiliza: no hay un
«token de autorización» que robar. Si el usuario en sesión es administrador, se autoriza a
sí mismo sin PIN. Sin nada que autorizar no se valida ni se cuenta nada.

**Bloqueos de fila.** Cada verificación toma la fila con `FOR UPDATE`, para que dos
intentos simultáneos no se salten el contador: primero la de quien pide y después la del
administrador. Quien pide nunca es administrador (esos se autorizan solos) y el objetivo
válido siempre lo es, así que dos peticiones no toman las mismas dos filas en orden
inverso: no hay ciclo. La fila de un objetivo que no vale no se toma jamás (se averigua
con una lectura sin bloqueo), de modo que quien tantea no frena a un administrador
ajeno; y el bloqueo del administrador es solo de `usuarios` (`FOR UPDATE OF u`), no de la
fila de `roles` que comparten todas las empresas. Una prueba con base lo comprueba.

**Esquema (V42).** Cinco columnas en `usuarios`: `pin_hash`, `pin_intentos`,
`pin_bloqueado_en`, `pin_errados_emitidos` y `pin_emision_bloqueada_en`, y dos
restricciones (los contadores van de 0 a 50; un PIN bloqueado es un PIN que existe). Los
instantes los escribe y los lee JDBC tal cual, con el reloj de la aplicación, sin pasar
por la entidad `Usuario` (Hibernate convertiría la zona entre la conexión y la JVM).

## Evaluación de un *pepper* de servidor

Un PIN de 4 a 6 dígitos tiene como mucho 1.111.110 valores. Derivado con PBKDF2, un
atacante que se lleve la base los recorre todos sin conexión en minutos con una GPU. Un
*pepper* (un secreto del servidor, fuera de la base, con el que se firma el PIN antes de
derivarlo) lo impediría: la base sola no bastaría.

**Decisión: no se añade ahora.** La razón decisiva es de operación: ¿quién entrega ese
secreto en la CI y en producción? El proyecto decidió que **no hay ningún secreto que
configurar** en la aplicación (la sesión es un token aleatorio en base, no un JWT; ver el
`application.yml.ejemplo`), y el `application.yml` no se versiona. Un *pepper* con valor
por omisión en el código no protege nada; uno obligatorio rompe el arranque de todo
entorno que no lo tenga, y perderlo o rotarlo invalida todos los PIN. Es una dependencia
nueva de infraestructura que merece su propio ADR, no un añadido a este.

Lo que lo hace tolerable hoy:
- Quien filtra la base ya tiene los costos, las ventas y los clientes: el PIN no es lo
  más valioso que se llevó.
- Un PIN solo autoriza a alguien que ya tiene una sesión de la misma empresa y solo dentro
  de la caja; no abre sesión.
- Por la API, el atacante tiene cinco intentos cada quince minutos por administrador.

**Cuándo reabrirlo:** si se decide guardar secretos de servidor en la configuración (para
la facturación electrónica, por ejemplo, ya hay un token), el *pepper* es la primera
mejora barata: HMAC-SHA-256 del PIN con una clave del servidor antes de `Seguridad.derivar`,
con el prefijo del formato almacenado distinguiendo los PIN con y sin *pepper* para que
convivan.

## Consecuencias

- **Riesgo residual: bloqueo del PIN de un administrador de la propia empresa.** Un
  vendedor puede mantener bloqueado el PIN de un administrador de su empresa con cinco
  fallos cada quince minutos. Es un daño acotado (el administrador sigue pudiendo autorizar
  él mismo la operación en la caja: no necesita su PIN) y deja rastro: cada intento queda
  en `auditoria_accesos` a nombre del vendedor. No hay límite por origen; si hace falta,
  es otra decisión.
- La contraseña de un administrador antigua, de menos de ocho caracteres, podría ser de
  dígitos: por eso se compara el PIN nuevo contra ella aunque hoy el mínimo de ocho lo
  haga imposible.
- **Riesgo residual: «distinto de tu contraseña» es un pequeño oráculo.** Con una sesión de
  administrador robada, quien intenta fijar un PIN y recibe «debe ser distinto de tu
  contraseña» sabe que su contraseña es ese número de 4 a 6 dígitos. Solo afecta a
  contraseñas más cortas que el mínimo actual de ocho caracteres, que ya no se pueden
  crear. Al cambiar un PIN existente la comprobación va después de verificar el PIN
  actual; la primera vez no hay PIN que verificar.
- Quien llame a `autorizar` desde una operación nueva debe hacerlo en su fachada, antes de
  su transacción, y consumir el `Autorizacion` con `exigir`. La prueba de que los intentos
  sobreviven a la reversión se repite por `POST /caja/ventas` en la venta.
- La bitácora de accesos mezcla ingresos y PIN; se distinguen por el motivo.
