-- ---------------------------------------------------------------------
-- V41 · El «Consumidor final» de cada empresa (RF-66)
--
-- La caja vende muchas veces sin pedir datos al comprador, y la venta exige un
-- cliente. Esta migración deja a cada empresa con su «Consumidor final»: un
-- cliente genérico, con el documento que la DIAN define para eso (CC
-- 222222222222), que el sistema gestiona y nadie edita.
--
--   1. `clientes` gana `consumidor_final`, falso por omisión, y una restricción
--      que ata la marca al documento genérico. Con la clave única
--      `(empresa_id, tipo_documento, nit)` de la V10 eso garantiza, sin más
--      índices, que una empresa tiene como máximo un consumidor final: la marca
--      solo cabe en la fila CC 222222222222, y esa fila no se repite.
--   2. Si la empresa ya tenía un cliente con ese documento, se MARCA en lugar de
--      insertar otro (el insertar chocaría con la clave única), se le deja el
--      nombre «Consumidor final» y se activa. Lo demás de ese cliente (correo,
--      teléfono...) se conserva.
--   3. Toda empresa que todavía no tenga consumidor final recibe uno, con la
--      ciudad de la empresa. `empresas.ciudad` puede ser nula, y `clientes.ciudad`
--      también.
--
-- El orden importa. El paso 2 va antes que el 3: si se insertara primero, la
-- empresa con el cliente genérico propio recibiría un duplicado y la migración
-- fallaría contra la clave única.
--
-- El consumidor final no tiene autorización de datos: no es una persona. La
-- restricción `ck_clientes_autorizacion` lo permite con `autorizacion_datos` nulo.
--
-- Requiere: V4, V10 (la clave única por empresa) y V11.
-- ---------------------------------------------------------------------

-- 1. La marca, atada al documento genérico.
ALTER TABLE clientes
  ADD COLUMN consumidor_final BOOL NOT NULL DEFAULT FALSE AFTER activo,
  ADD CONSTRAINT ck_clientes_consumidor_final CHECK (
    NOT consumidor_final OR (tipo_documento = 'CC' AND nit = '222222222222'));

-- 2. El cliente que ya tenía el documento genérico pasa a ser el consumidor final.
UPDATE clientes
   SET consumidor_final = TRUE,
       razon_social = 'Consumidor final',
       activo = TRUE
 WHERE tipo_documento = 'CC' AND nit = '222222222222';

-- 3. Y la empresa que no tenía ninguno recibe el suyo.
INSERT INTO clientes (empresa_id, razon_social, tipo_documento, nit, ciudad, activo, consumidor_final)
SELECT e.empresa_id, 'Consumidor final', 'CC', '222222222222', e.ciudad, TRUE, TRUE
  FROM empresas e
 WHERE NOT EXISTS (SELECT 1 FROM clientes c
                    WHERE c.empresa_id = e.empresa_id AND c.consumidor_final = TRUE);

-- =====================================================================
-- Comprobación
-- =====================================================================
--   SELECT e.empresa_id FROM empresas e
--    WHERE (SELECT COUNT(*) FROM clientes c
--            WHERE c.empresa_id = e.empresa_id AND c.consumidor_final = 1) <> 1;
--   → ninguna empresa sin su consumidor final, ni con dos
-- =====================================================================
