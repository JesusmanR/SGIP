-- ---------------------------------------------------------------------
-- V50 · Cotizaciones listas para vender (RF-37, RF-39, RF-40)
--
-- Las tablas `cotizaciones` y `cotizacion_items` nacieron en la V4 para
-- una cotización que nadie había escrito todavía. Esta migración las deja
-- listas para la cotización de verdad.
--
--   cotizacion_items   Sus dos importes (`subtotal` y `valor_iva`) eran
--                      columnas generadas: el IVA salía de
--                      `ROUND(cantidad * precio * tarifa, 2)` dentro de la
--                      base y no se podía escribir. La venta ya calcula el
--                      IVA de cada línea en la aplicación (V44 §3) y la
--                      cotización tiene que dar exactamente la misma cifra
--                      que la venta en que se convierte, así que pasan a
--                      columnas normales que escribe ese mismo cálculo.
--
--   consecutivos       Gana el tipo `COTIZACION`: el número de cada
--                      cotización lo da la misma tabla que numera ventas y
--                      devoluciones (`COT-` y ocho cifras).
--
--   cotizaciones       Gana quién anuló, cuándo y por qué. Las tres cosas
--                      van juntas o no va ninguna, y solo en una cotización
--                      anulada (`ck_cotizaciones_anulacion`). Gana también
--                      el índice por el que se listan y se vencen:
--                      empresa, estado y fecha de vencimiento.
--
-- Decisiones del esquema:
--
--   * Los importes de las líneas que ya existían se conservan: se copian
--     de las columnas generadas antes de quitarlas, como en la V44. Hoy no
--     hay cotizaciones en ninguna base de producción, pero la copia no
--     cuesta nada y evita perder datos de demostración.
--   * El ENUM de `consecutivos.tipo` guarda el índice del valor, así que
--     se re-listan los tres de la V44 en su orden y el nuevo va al final
--     (patrón de la V49).
--   * `anulada_por` es una clave compuesta con `empresa_id`, igual que
--     `fk_cotizaciones_usuario_emp` de la V11: quien anula tiene que ser de
--     la misma empresa. Sin acción referencial (RESTRICT), porque MySQL no
--     deja que una columna con ON UPDATE CASCADE entre en un CHECK.
--   * `ix_cotizaciones_estado` de la V4 queda como está; el índice nuevo
--     empieza por la empresa, que es por donde se consulta siempre.
--
-- Nada de esto toca una vista: ninguna vista, función ni guion lee los
-- importes generados de `cotizacion_items`.
--
-- Requiere: V4, V11, V31, V44.
-- ---------------------------------------------------------------------

-- 1. cotizacion_items: los importes dejan de ser generados
ALTER TABLE cotizacion_items
  ADD COLUMN subtotal_nuevo  DECIMAL(14,2) NULL,
  ADD COLUMN valor_iva_nuevo DECIMAL(14,2) NULL;

UPDATE cotizacion_items
   SET subtotal_nuevo  = subtotal,
       valor_iva_nuevo = valor_iva;

ALTER TABLE cotizacion_items
  DROP COLUMN subtotal,
  DROP COLUMN valor_iva;

ALTER TABLE cotizacion_items
  CHANGE COLUMN subtotal_nuevo  subtotal  DECIMAL(14,2) NOT NULL,
  CHANGE COLUMN valor_iva_nuevo valor_iva DECIMAL(14,2) NOT NULL,
  ADD CONSTRAINT ck_cotitems_importes CHECK (subtotal >= 0 AND valor_iva >= 0);

-- 2. consecutivos: el tipo COTIZACION
ALTER TABLE consecutivos
  MODIFY COLUMN tipo ENUM('VENTA','DEVOLUCION','PRODUCTO_RAPIDO','COTIZACION') NOT NULL;

-- 3. cotizaciones: la anulación y el índice por estado
ALTER TABLE cotizaciones
  ADD COLUMN anulada_por      INT          NULL,
  ADD COLUMN anulada_en       DATETIME     NULL,
  ADD COLUMN motivo_anulacion VARCHAR(200) NULL,
  ADD CONSTRAINT fk_cotizaciones_anulada_emp FOREIGN KEY (empresa_id, anulada_por)
    REFERENCES usuarios (empresa_id, usuario_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT ck_cotizaciones_anulacion CHECK (
    (estado = 'ANULADA') = (anulada_por IS NOT NULL AND anulada_en IS NOT NULL AND motivo_anulacion IS NOT NULL)
    AND (estado = 'ANULADA' OR (anulada_por IS NULL AND anulada_en IS NULL AND motivo_anulacion IS NULL))),
  ADD INDEX ix_cotizaciones_empresa_estado (empresa_id, estado, fecha_vencimiento);
