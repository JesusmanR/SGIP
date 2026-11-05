# ADR-0028 · El bloqueo de una cuenta caduca y la plataforma puede levantarlo

Estado: aceptada · 2026-11-05

## Contexto

Cinco intentos fallidos bloqueaban la cuenta para siempre. Quien tecleaba mal su
contraseña inicial cinco veces en el primer día, en el ingreso o en la pantalla de
cambio de clave, se quedaba fuera. Si era el único administrador de su empresa, no había
nadie que lo desbloqueara: el administrador de otra empresa no alcanza a un usuario
ajeno (RN-16), el super administrador no tenía ruta, y lo mismo le pasaba al propio
super administrador. La única salida era SQL a mano. Además, quien llegaba a ese punto
leía siempre «El correo o la contraseña no son correctos», aunque ya acertara.

La revisión final de la puesta a punto lo marcó como hallazgo importante (I2).

## Decisión

Es una decisión del orquestador, reversible con un ajuste de configuración.

**El bloqueo caduca.** Tras 5 intentos fallidos, la cuenta se bloquea 15 minutos. La
duración es `sgip.seguridad.minutos-de-bloqueo` (15 por omisión); el número de intentos
sigue siendo `sgip.seguridad.intentos-maximos`. Al vencer el plazo, el siguiente intento
limpia la cuenta (los intentos vuelven a cero) y se evalúa la clave como en cualquier
otro ingreso: acertar entra, errar cuenta como el primer error.

**Quién decide que venció.** La aplicación, con el `Clock` inyectado del proyecto. La
migración `V37` añade `usuarios.bloqueado_en`, que solo guarda el instante que escribe la
aplicación; no tiene valor por omisión y el motor nunca lo calcula ni lo compara. Así
base y aplicación no pueden discrepar sobre «ahora» aunque estén en zonas horarias
distintas, que es la discrepancia que el hallazgo M5 señala para las vigencias (en ese
caso, entre la aplicación y `CURDATE()`).

**Las cuentas ya bloqueadas** antes de la V37 quedan con `bloqueado_en` nulo y se toman
por vencidas: no hay desde cuándo contar, y dejarlas bloqueadas para siempre es lo que
esta decisión corrige. Se desbloquean en su siguiente ingreso.

**El mensaje no revela nada y sí orienta.** El 401 de ingreso es idéntico con la clave
errada, la cuenta bloqueada, la cuenta inactiva o el usuario inexistente, y dice siempre
que, si se equivoca varias veces, la cuenta se bloquea durante N minutos, con N tomado de
la configuración. Decírselo a todos por igual no confirma qué correos existen. La web
muestra el texto del servidor tal cual (`Acceso.jsx`).

**Desbloqueo desde la plataforma.** `POST /api/v1/plataforma/empresas/{empresaId}/usuarios/{usuarioId}/desbloquear`,
solo `SUPER_ADMIN` (la anotación está en la clase, como el resto del controlador). Es un
POST sobre un recurso propio y no un `DELETE`: no se borra nada. Responde 204; 404
`ProblemDetail` si el usuario no es de esa empresa, con el mismo texto que si no
existiera. Queda en `auditoria_plataforma`, la bitácora que ya registra lo que la
plataforma decide sobre una empresa, con la acción nueva `USUARIO_DESBLOQUEO` (V37
extiende el `ENUM` y las dos restricciones de la tabla, que se escribieron cerradas a
propósito). La referencia es el identificador del usuario, no su correo: el correo mide
120 caracteres y la columna, 60. Desbloquear a quien no está bloqueado no cambia nada y
no se anota. El desbloqueo de `/usuarios/{id}/desbloquear`, el del administrador de la
propia empresa, sigue igual.

**Los fallos de `/auth/clave`** (M22) quedan en `auditoria_accesos` con el mismo motivo
que un ingreso fallido (`CLAVE_INCORRECTA`) y el origen de la petición. Ningún mensaje
ni registro lleva contraseñas.

## Consecuencias

- Un atacante que conozca un correo puede mantener esa cuenta bloqueada intentando cada
  15 minutos. Antes la bloqueaba una sola vez, para siempre; ahora el daño dura lo que
  dura la insistencia, y el dueño tiene siempre la salida de esperar. No hay límite por
  origen todavía: si hace falta, es otra decisión.
- Mientras la cuenta está bloqueada, intentar no extiende el plazo: el rechazo ocurre
  antes de mirar la clave.
- No hay recuperar ni restablecer contraseña: esta decisión no lo añade. Quien olvida la
  clave sigue sin salida propia, y es un hallazgo aparte.
- El super administrador bloqueado se desbloquea solo al vencer el plazo; ninguna ruta
  de plataforma lo alcanza, porque el desbloqueo se acota por empresa y él no tiene.
- Revertir es poner `sgip.seguridad.minutos-de-bloqueo` en un valor alto. Un bloqueo
  indefinido requeriría cambiar `Usuario.bloqueoVencido`.
