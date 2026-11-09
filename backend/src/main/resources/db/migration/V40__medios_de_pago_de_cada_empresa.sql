-- ---------------------------------------------------------------------
-- V40 · Medios de pago de cada empresa (RF-43, M17)
--
-- La caja cobra con los medios que la empresa tenga activos. Hasta aquí la
-- tabla `medios_pago` (V10) solo traía las cuatro filas de la empresa 1 y el
-- alta sembraba cuatro más, entre ellas «Crédito», que no es un medio: el
-- crédito es una forma de pago de la venta (`ventas.forma_pago`) y no se
-- cobra en el mostrador. Esta migración deja la tabla como la caja la
-- necesita:
--
--   1. Se retira «Crédito». Las ventas que lo usaran quedan sin medio
--      (`medio_id` es nulo) en lugar de apuntar a una fila que ya no existe.
--      En una base de demostración ninguna lo usa; el UPDATE está para la
--      base que sí lo tenga, porque el MODIFY del paso 2 falla con filas que
--      usan el valor que se quita.
--   2. El ENUM pierde CREDITO y gana SALDO_A_FAVOR, el medio de sistema con
--      que se paga con el saldo del cliente (ADR-0033).
--   3. Toda empresa sin ningún medio recibe Efectivo, Tarjeta y Transferencia,
--      activos. Una empresa que ya tiene alguno no se toca: sus medios son
--      decisión suya.
--   4. Toda empresa recibe «Saldo a favor», inactivo: lo gestiona el sistema
--      y no aparece entre los medios que el cajero elige.
--
-- El orden importa. El 3 va antes que el 4: si «Saldo a favor» entrara
-- primero, ninguna empresa parecería vacía y no se sembraría nada. Y el
-- paso 1 va antes que el 3: la empresa que solo tenía «Crédito» queda vacía
-- y recibe los tres.
--
-- Cada sentencia se puede repetir sin duplicar nada.
--
-- Requiere: V10, V11 (la clave foránea compuesta de `ventas`).
-- ---------------------------------------------------------------------

-- 1. «Crédito» sale, y las ventas que lo usaran se sueltan primero.
UPDATE ventas
   SET medio_id = NULL
 WHERE medio_id IN (SELECT medio_id FROM medios_pago WHERE tipo = 'CREDITO');

DELETE FROM medios_pago WHERE tipo = 'CREDITO';

-- 2. Sin CREDITO, con SALDO_A_FAVOR.
ALTER TABLE medios_pago
  MODIFY COLUMN tipo ENUM('EFECTIVO','TARJETA','TRANSFERENCIA','SALDO_A_FAVOR')
      NOT NULL;

-- 3. Una sola sentencia para los tres: con tres INSERT protegidos por «no
--    tiene ninguno», el primero sembraría «Efectivo» y la empresa dejaría de
--    parecer vacía para los otros dos.
INSERT INTO medios_pago (empresa_id, nombre, tipo, activo)
SELECT e.empresa_id, s.nombre, s.tipo, 1
  FROM empresas e
  JOIN (SELECT 'Efectivo' AS nombre, 'EFECTIVO' AS tipo
        UNION ALL SELECT 'Tarjeta', 'TARJETA'
        UNION ALL SELECT 'Transferencia', 'TRANSFERENCIA') s
 WHERE NOT EXISTS (SELECT 1 FROM medios_pago m WHERE m.empresa_id = e.empresa_id);

-- 4. El nombre es único por empresa. Si una empresa ya había creado por su
--    cuenta un medio llamado «Saldo a favor», se aparta el nombre en lugar
--    de dejar que la migración falle y el backend no arranque.
UPDATE medios_pago
   SET nombre = 'Saldo a favor (propio)'
 WHERE nombre = 'Saldo a favor' AND tipo <> 'SALDO_A_FAVOR';

INSERT INTO medios_pago (empresa_id, nombre, tipo, activo)
SELECT e.empresa_id, 'Saldo a favor', 'SALDO_A_FAVOR', 0
  FROM empresas e
 WHERE NOT EXISTS (SELECT 1 FROM medios_pago m
                    WHERE m.empresa_id = e.empresa_id AND m.tipo = 'SALDO_A_FAVOR');

-- =====================================================================
-- Comprobación
-- =====================================================================
--   SELECT COUNT(*) FROM medios_pago WHERE tipo = 'SALDO_A_FAVOR' AND activo = 0;
--   → una fila por empresa
--
--   SELECT e.empresa_id FROM empresas e
--    WHERE NOT EXISTS (SELECT 1 FROM medios_pago m
--                       WHERE m.empresa_id = e.empresa_id AND m.activo = 1);
--   → ninguna empresa sin un medio activo
-- =====================================================================
