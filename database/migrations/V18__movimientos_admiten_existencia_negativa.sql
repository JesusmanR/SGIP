-- =====================================================================
-- SGIP · Migración V18 · El kárdex admite la existencia negativa que la
--                        V9 autorizó en el producto
-- =====================================================================
--
-- La V9 levantó la prohibición de existencia negativa para que el
-- administrador de empresa pudiera comprometer una venta sin existencia
-- disponible (RF-42, gobernado por la RN-13; la cabecera de la V9 lo cita
-- como RF-27, que era su número en el catálogo de entonces).
--
-- Lo hizo en un solo sitio: la tabla productos. Pero la existencia no se
-- cambia sola. Cada cambio deja su rastro en movimientos_inventario, y
-- esa tabla trae de la V2 dos restricciones que la V9 no tocó:
--
--   CONSTRAINT ck_movimientos_anterior  CHECK (existencia_anterior  >= 0)
--   CONSTRAINT ck_movimientos_posterior CHECK (existencia_posterior >= 0)
--
-- El resultado es un permiso a medias. Comprobado sobre una base armada
-- con las migraciones V1 a V17:
--
--   · UPDATE productos SET existencia = -3 ... entra, y estado_stock
--     queda en AGOTADO, que es lo que la V9 buscaba.
--   · El movimiento que explica ese cambio —EGRESO, anterior 0,
--     posterior -3— es rechazado por ck_movimientos_posterior.
--   · Una segunda venta partiendo de -3 es rechazada además por
--     ck_movimientos_anterior.
--
-- Es decir: el esquema permite dejar la existencia en negativo, pero
-- prohíbe anotar por qué. Una venta sin existencia, implementada contra
-- este esquema, haría las dos cosas en la misma transacción y la
-- transacción entera se abortaría. La venta no quedaría a medias, que es
-- el único consuelo, pero no se podría registrar.
--
-- Hoy no hay nada roto en producción porque todavía nada escribe en
-- movimientos_inventario: el módulo de ventas no está construido. El
-- defecto es latente, y por eso hay que cerrarlo antes de construirlo y
-- no después, cuando el síntoma llegue disfrazado de error de la capa de
-- Java.
--
-- El piso es el mismo que la V9 eligió para productos, -9999, y por el
-- mismo motivo: sigue atajando el error de digitación —nadie vende diez
-- mil unidades que no tiene— sin impedir la operación que el requisito
-- autoriza. Que los tres pisos coincidan importa: si el del kárdex fuera
-- más estrecho que el del producto, habría estados alcanzables en
-- productos que el kárdex no podría explicar, que es exactamente el
-- problema que esta migración cierra.
--
-- Lo que NO se toca:
--
--   · ck_movimientos_cantidad (cantidad <> 0) sigue igual. Un movimiento
--     de cero unidades no es una venta sin existencia: es una fila sin
--     significado.
--   · ck_movimientos_ajuste sigue igual. Un ajuste sin observación sigue
--     siendo inaceptable, y con más razón si deja el saldo en negativo.
--
-- Verificación: database/verificar_aislamiento.sh registra un movimiento
-- que deja el saldo en negativo y comprueba que entra. Esa comprobación
-- falla con las restricciones de la V2 y pasa con las de esta migración.
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE movimientos_inventario
  DROP CHECK ck_movimientos_anterior,
  DROP CHECK ck_movimientos_posterior;

ALTER TABLE movimientos_inventario
  ADD CONSTRAINT ck_movimientos_anterior  CHECK (existencia_anterior  >= -9999),
  ADD CONSTRAINT ck_movimientos_posterior CHECK (existencia_posterior >= -9999);


-- =====================================================================
-- Comprobación
-- =====================================================================
-- Con la migración aplicada, esto entra:
--
--   INSERT INTO movimientos_inventario
--     (empresa_id, producto_id, usuario_id, tipo, cantidad,
--      existencia_anterior, existencia_posterior, observacion)
--   VALUES (1, 1, 1, 'EGRESO', 3, 0, -3, 'venta sin existencia RF-42');
--
-- Y esto sigue sin entrar, que es lo que se quería conservar:
--
--   ... cantidad 0            → ck_movimientos_cantidad
--   ... tipo AJUSTE sin observacion → ck_movimientos_ajuste
--   ... existencia_posterior -10000 → ck_movimientos_posterior
-- =====================================================================
