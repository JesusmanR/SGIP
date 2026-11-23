-- ---------------------------------------------------------------------
-- V42 · El PIN con el que el administrador autoriza en la caja (ADR-0032)
--
-- Un vendedor no puede dar un descuento, vender sin existencia, crear un
-- producto ni devolver sin el visto bueno de un administrador. El
-- administrador lo da en la misma pantalla, con un PIN personal de 4 a 6
-- dígitos distinto de su contraseña. Esta migración guarda ese PIN y los
-- contadores que lo protegen, en `usuarios`.
--
--   pin_hash                  El PIN derivado con el mismo algoritmo que las
--                             contraseñas (ADR-0002), nunca el PIN. Nulo
--                             mientras el administrador no lo haya fijado.
--
--   pin_intentos              Los fallos seguidos CONTRA el PIN de este
--   pin_bloqueado_en          administrador, y desde cuándo está bloqueado
--                             si llegó al máximo. Se cuentan por
--                             administrador: los fallos contra uno no
--                             bloquean a otro.
--
--   pin_errados_emitidos      Los fallos seguidos que ESTE usuario comete al
--   pin_emision_bloqueada_en  apuntar a un administrador que no vale (de otra
--                             empresa, que no es administrador, inactivo o
--                             sin PIN), y desde cuándo está bloqueado. Es lo
--                             que impide que quien tantea bloquee el PIN de
--                             un administrador ajeno: el fallo se le cuenta a
--                             él, no al objetivo.
--
-- Como `bloqueado_en` de la V37, los dos instantes los escribe la aplicación
-- con su reloj y los compara con ese mismo reloj; ninguna lleva valor por
-- omisión ni la calcula el motor. El plazo y el máximo de intentos son los de
-- la cuenta (`sgip.seguridad.minutos-de-bloqueo` y `intentos-maximos`,
-- ADR-0028).
--
-- Dos restricciones, para que lo que la aplicación no debería hacer tampoco
-- lo admita el motor:
--   ck_usuarios_pin_intentos  los dos contadores van de 0 a 50, como
--                             `ck_usuarios_intentos` de la V1.
--   ck_usuarios_pin_bloqueo   un PIN bloqueado es un PIN que existe.
--
-- Los usuarios que ya existen quedan sin PIN y con los contadores en cero:
-- cada administrador fija el suyo desde su perfil.
--
-- Requiere: V1 (usuarios).
-- ---------------------------------------------------------------------

ALTER TABLE usuarios
  ADD COLUMN pin_hash                 VARCHAR(255)     NULL,
  ADD COLUMN pin_intentos             TINYINT UNSIGNED NOT NULL DEFAULT 0,
  ADD COLUMN pin_bloqueado_en         DATETIME         NULL,
  ADD COLUMN pin_errados_emitidos     TINYINT UNSIGNED NOT NULL DEFAULT 0,
  ADD COLUMN pin_emision_bloqueada_en DATETIME         NULL,
  ADD CONSTRAINT ck_usuarios_pin_intentos
    CHECK (pin_intentos BETWEEN 0 AND 50 AND pin_errados_emitidos BETWEEN 0 AND 50),
  ADD CONSTRAINT ck_usuarios_pin_bloqueo
    CHECK (pin_bloqueado_en IS NULL OR pin_hash IS NOT NULL);
