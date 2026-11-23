-- ---------------------------------------------------------------------
-- V44 · Registrar una venta en el mostrador (RF-41, RF-42, RF-44, RF-36)
--
-- Las tablas `ventas` y `venta_items` nacieron en la V4 para una venta que
-- nadie había escrito todavía. Esta migración las deja listas para la venta
-- de verdad y crea lo que le falta.
--
--   consecutivos   El siguiente número de cada documento, por empresa y
--                  tipo. La fila de una empresa nace con su primer uso
--                  (`INSERT IGNORE` y luego `SELECT ... FOR UPDATE`); la
--                  clave `(empresa_id, tipo)` impide dos filas.
--
--   pagos_venta    Lo que se cobró de una venta, con su medio, su turno y
--                  quién lo recibió. Una venta puede tener varios pagos
--                  (cobro mixto). `cierre_id` es nulo solo con el pago de
--                  saldo a favor, que no pasa por la caja; esa regla
--                  depende del tipo del medio y la aplicación la sostiene.
--
--   ventas         Gana la clave de idempotencia (un doble envío de la
--                  misma venta no la duplica) y la huella de su carga (la
--                  misma clave con otra carga es un error); el descuento
--                  sobre toda la compra tal como se pidió, y el tipo de
--                  entrega. Pierde `medio_id`: el medio va en cada pago.
--
--   venta_items    Sus tres importes (`subtotal`, `valor_iva`, `utilidad`)
--                  eran columnas generadas que ignoraban el descuento y no
--                  se podían escribir: un 10 % de descuento dejaba el IVA
--                  guardado sobre el valor sin descontar. Pasan a columnas
--                  normales que escribe el cálculo de la venta, y la línea
--                  gana su `descuento` y los datos del encargo.
--
--   movimientos_inventario   Gana `autorizado_por`: quién autorizó un
--                  egreso que deja la existencia en negativo.
--
-- Decisiones del esquema:
--
--   * `utilidad` no lleva CHECK de no negativo: un descuento autorizado
--     puede dejar la línea por debajo del costo, y eso es una venta
--     legítima, no un error de datos.
--   * El descuento sobre toda la compra se guarda como lo pidió quien
--     vende (`descuento_tipo` y `descuento_valor`, completos o vacíos);
--     lo que de verdad se descontó está en `descuento` de la venta y de
--     cada línea.
--   * `ventas.total` sigue siendo generada (`subtotal - descuento + iva +
--     envio`) y ahora coincide con el total que calcula la aplicación.
--   * Toda clave foránea nueva es compuesta con `empresa_id` (V11, V31).
--
-- Los importes de las líneas que ya existían se conservan: se copian de
-- las columnas generadas antes de quitarlas. `ventas` y `venta_items` no
-- tenían filas en ninguna base de producción, pero la copia no cuesta nada
-- y evita perder datos de demostración o de un entorno intermedio.
--
-- Nada de esto toca una vista: ninguna vista, función ni guion lee
-- `ventas.medio_id` ni los importes generados de `venta_items`.
--
-- Requiere: V4, V9, V10, V11, V12, V31, V34 y V43.
-- ---------------------------------------------------------------------

-- 1. consecutivos
CREATE TABLE consecutivos (
  empresa_id INT    NOT NULL,
  tipo       ENUM('VENTA','DEVOLUCION','PRODUCTO_RAPIDO') NOT NULL,
  siguiente  BIGINT NOT NULL DEFAULT 1,
  CONSTRAINT pk_consecutivos PRIMARY KEY (empresa_id, tipo),
  CONSTRAINT fk_consecutivos_empresa FOREIGN KEY (empresa_id)
    REFERENCES empresas (empresa_id),
  CONSTRAINT ck_consecutivos_siguiente CHECK (siguiente >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 2. ventas: el medio se va, la idempotencia y el descuento de la compra llegan
ALTER TABLE ventas
  DROP FOREIGN KEY fk_ventas_medio_emp;

ALTER TABLE ventas
  DROP INDEX fk_ventas_medio_emp,
  DROP INDEX fk_ventas_medio;

ALTER TABLE ventas
  DROP COLUMN medio_id;

ALTER TABLE ventas
  ADD COLUMN clave_idempotencia CHAR(36)  NULL,
  ADD COLUMN huella_carga       CHAR(64)  NULL,
  ADD COLUMN descuento_tipo     ENUM('PORCENTAJE','PESOS') NULL,
  ADD COLUMN descuento_valor    DECIMAL(14,2) NULL,
  ADD COLUMN tipo_entrega       ENUM('MOSTRADOR','DOMICILIO') NOT NULL DEFAULT 'MOSTRADOR',
  ADD CONSTRAINT uq_ventas_idempotencia UNIQUE (empresa_id, clave_idempotencia),
  ADD CONSTRAINT ck_ventas_descuento_total CHECK (
    (descuento_tipo IS NULL AND descuento_valor IS NULL)
    OR (descuento_tipo IS NOT NULL AND descuento_valor IS NOT NULL
        AND descuento_valor >= 0
        AND (descuento_tipo <> 'PORCENTAJE' OR descuento_valor <= 100)));

-- 3. venta_items: los importes dejan de ser generados
ALTER TABLE venta_items
  ADD COLUMN subtotal_nuevo  DECIMAL(14,2) NULL,
  ADD COLUMN valor_iva_nuevo DECIMAL(14,2) NULL,
  ADD COLUMN utilidad_nueva  DECIMAL(14,2) NULL;

UPDATE venta_items
   SET subtotal_nuevo  = subtotal,
       valor_iva_nuevo = valor_iva,
       utilidad_nueva  = utilidad;

ALTER TABLE venta_items
  DROP COLUMN subtotal,
  DROP COLUMN valor_iva,
  DROP COLUMN utilidad;

ALTER TABLE venta_items
  CHANGE COLUMN subtotal_nuevo  subtotal  DECIMAL(14,2) NOT NULL,
  CHANGE COLUMN valor_iva_nuevo valor_iva DECIMAL(14,2) NOT NULL,
  CHANGE COLUMN utilidad_nueva  utilidad  DECIMAL(14,2) NOT NULL,
  ADD COLUMN descuento        DECIMAL(14,2) NOT NULL DEFAULT 0,
  ADD COLUMN cantidad_encargo INT           NOT NULL DEFAULT 0,
  ADD COLUMN entrega_estimada DATE          NULL,
  ADD CONSTRAINT ck_venitems_importes CHECK (
    subtotal >= 0 AND valor_iva >= 0 AND descuento >= 0 AND descuento <= subtotal),
  ADD CONSTRAINT ck_venitems_encargo CHECK (
    cantidad_encargo >= 0 AND cantidad_encargo <= cantidad),
  ADD CONSTRAINT ck_venitems_entrega CHECK (
    cantidad_encargo = 0 OR entrega_estimada IS NOT NULL);

-- 4. el kárdex guarda quién autorizó
ALTER TABLE movimientos_inventario
  ADD COLUMN autorizado_por INT NULL,
  ADD CONSTRAINT fk_movimientos_autorizado_emp FOREIGN KEY (empresa_id, autorizado_por)
    REFERENCES usuarios (empresa_id, usuario_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

-- 5. pagos_venta
CREATE TABLE pagos_venta (
  pago_id       BIGINT        NOT NULL AUTO_INCREMENT,
  empresa_id    INT           NOT NULL,
  venta_id      BIGINT        NOT NULL,
  medio_id      INT           NOT NULL,
  monto         DECIMAL(14,2) NOT NULL,
  referencia    VARCHAR(60)   NULL,
  recibido      DECIMAL(14,2) NULL,
  cierre_id     BIGINT        NULL,
  registrado_por INT          NOT NULL,
  registrado_en DATETIME      NOT NULL,
  CONSTRAINT pk_pagos_venta PRIMARY KEY (pago_id),
  CONSTRAINT fk_pagos_venta_emp FOREIGN KEY (empresa_id, venta_id)
    REFERENCES ventas (empresa_id, venta_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_pagos_medio_emp FOREIGN KEY (empresa_id, medio_id)
    REFERENCES medios_pago (empresa_id, medio_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_pagos_cierre_emp FOREIGN KEY (empresa_id, cierre_id)
    REFERENCES cierres_caja (empresa_id, cierre_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_pagos_usuario_emp FOREIGN KEY (empresa_id, registrado_por)
    REFERENCES usuarios (empresa_id, usuario_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_pagos_monto CHECK (monto > 0),
  CONSTRAINT ck_pagos_recibido CHECK (recibido IS NULL OR recibido >= monto),
  INDEX ix_pagos_cierre (empresa_id, cierre_id, medio_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
