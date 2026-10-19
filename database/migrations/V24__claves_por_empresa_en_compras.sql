-- =====================================================================
-- SGIP · Migración V24 · Las tablas de compras dejan de poder cruzar
--                        empresas
-- =====================================================================
--
-- La V11 recorrió el esquema metiendo `empresa_id` en las veinte claves
-- foráneas que podían cruzar comercios. Tres se le quedaron fuera, todas
-- en el camino de las compras, y se descubren justo al ir a construir
-- ese módulo (RF-22, RF-23).
--
-- Reproducidas sobre una base con la cadena V1 a V23 aplicada y dos
-- empresas cargadas:
--
--   1. `orden_compra_items.fk_oci_producto` apunta a
--      `productos(producto_id)` sin empresa. Una orden de la empresa 1
--      acepta una línea que apunta a un producto de la empresa 5. La
--      fila entró.
--
--   2. `recepciones.fk_rec_usuario` apunta a `usuarios(usuario_id)` sin
--      empresa. Una recepción de una orden de la empresa 1 quedó firmada
--      por un usuario de la empresa 5. La fila entró.
--
--   3. `movimientos_inventario.fk_mov_recepcion` apunta a
--      `recepciones(recepcion_id)` sin empresa, de modo que un
--      movimiento de un comercio puede colgar de la recepción de otro.
--
-- La primera es la que muerde. Recibir esa orden registraría un INGRESO
-- sobre el producto de la empresa 5: una compra de un comercio subiendo
-- la existencia de otro, con su movimiento de kárdex y todo. No es una
-- fuga de lectura, es una escritura en el inventario ajeno.
--
-- Ninguna de las tres se manifiesta hoy porque el módulo de compras no
-- está construido y las tres tablas están vacías. Por eso se cierran
-- antes y no después: con datos dentro, añadir una columna obligatoria y
-- rellenarla es una migración que hay que pensar dos veces.
--
-- ## Por qué `empresa_id` y no filtrar por el padre
--
-- `orden_compra_items` y `recepciones` cuelgan de `ordenes_compra`, que
-- sí tiene empresa. Se podría llegar a ella con un enlace y acotar allí.
-- Se descarta por lo mismo que la V11 argumentó en su día y la V15
-- repitió para la vista de precios: el aislamiento no debe depender de
-- que cada consulta se acuerde de subir hasta el padre. Con la columna
-- repetida y la clave compuesta, una fila incoherente no entra, venga de
-- la aplicación, de un guion de mantenimiento o de un dedazo en la
-- consola.
--
-- La columna se añade admitiendo nulo, se rellena desde la orden y
-- después se vuelve obligatoria. Hoy no hay filas que rellenar, pero una
-- base de desarrollo con pruebas a medias sí las tendría, y una
-- migración que falla a mitad es peor que una que tarda tres pasos.
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. Las órdenes pueden ser referenciadas por (empresa, orden)
-- ---------------------------------------------------------------------
-- Sin este índice, las claves compuestas de los dos apartados
-- siguientes no se pueden crear: una clave foránea exige un índice
-- único en el destino con exactamente esas columnas.
ALTER TABLE ordenes_compra
  ADD UNIQUE INDEX uq_ordenes_compra_empresa (empresa_id, orden_id);


-- ---------------------------------------------------------------------
-- 2. orden_compra_items
-- ---------------------------------------------------------------------
ALTER TABLE orden_compra_items
  ADD COLUMN empresa_id INT NULL AFTER orden_id;

UPDATE orden_compra_items i
  JOIN ordenes_compra o ON o.orden_id = i.orden_id
   SET i.empresa_id = o.empresa_id;

ALTER TABLE orden_compra_items
  MODIFY COLUMN empresa_id INT NOT NULL;

ALTER TABLE orden_compra_items
  ADD UNIQUE INDEX uq_oci_empresa (empresa_id, item_id);

ALTER TABLE orden_compra_items
  DROP FOREIGN KEY fk_oci_orden;
ALTER TABLE orden_compra_items
  ADD CONSTRAINT fk_oci_orden_emp
      FOREIGN KEY (empresa_id, orden_id)
      REFERENCES ordenes_compra (empresa_id, orden_id)
      ON DELETE CASCADE ON UPDATE CASCADE;

-- La línea de una orden solo puede apuntar a un producto de la misma
-- empresa. Es la que habría dejado que una compra subiera la existencia
-- de un comercio ajeno.
ALTER TABLE orden_compra_items
  DROP FOREIGN KEY fk_oci_producto;
ALTER TABLE orden_compra_items
  ADD CONSTRAINT fk_oci_producto_emp
      FOREIGN KEY (empresa_id, producto_id)
      REFERENCES productos (empresa_id, producto_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;


-- ---------------------------------------------------------------------
-- 3. recepciones
-- ---------------------------------------------------------------------
ALTER TABLE recepciones
  ADD COLUMN empresa_id INT NULL AFTER orden_id;

UPDATE recepciones r
  JOIN ordenes_compra o ON o.orden_id = r.orden_id
   SET r.empresa_id = o.empresa_id;

ALTER TABLE recepciones
  MODIFY COLUMN empresa_id INT NOT NULL;

ALTER TABLE recepciones
  ADD UNIQUE INDEX uq_recepciones_empresa (empresa_id, recepcion_id);

ALTER TABLE recepciones
  DROP FOREIGN KEY fk_rec_orden;
ALTER TABLE recepciones
  ADD CONSTRAINT fk_rec_orden_emp
      FOREIGN KEY (empresa_id, orden_id)
      REFERENCES ordenes_compra (empresa_id, orden_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;

-- Quien firma una recepción tiene que ser de la empresa que compra.
ALTER TABLE recepciones
  DROP FOREIGN KEY fk_rec_usuario;
ALTER TABLE recepciones
  ADD CONSTRAINT fk_rec_usuario_emp
      FOREIGN KEY (empresa_id, usuario_id)
      REFERENCES usuarios (empresa_id, usuario_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;


-- ---------------------------------------------------------------------
-- 4. El movimiento cuelga de una recepción de su propia empresa
-- ---------------------------------------------------------------------
-- `recepcion_id` admite nulo —un ingreso manual o un ajuste no vienen de
-- ninguna recepción— y eso sigue valiendo: una clave foránea compuesta
-- con un componente nulo no se comprueba, que es el comportamiento que
-- aquí se quiere.
ALTER TABLE movimientos_inventario
  DROP FOREIGN KEY fk_mov_recepcion;
ALTER TABLE movimientos_inventario
  ADD CONSTRAINT fk_mov_recepcion_emp
      FOREIGN KEY (empresa_id, recepcion_id)
      REFERENCES recepciones (empresa_id, recepcion_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;


-- =====================================================================
-- Comprobación
-- =====================================================================
-- Las tres que antes entraban deben quedar rechazadas con el error 1452:
--
--   · una línea de orden con un producto de otra empresa
--       → fk_oci_producto_emp
--   · una recepción firmada por un usuario de otra empresa
--       → fk_rec_usuario_emp
--   · un movimiento colgado de una recepción de otra empresa
--       → fk_mov_recepcion_emp
--
-- Y debe seguir entrando:
--
--   · una orden con sus líneas y su recepción, todo de la misma empresa
--   · un movimiento sin recepción (recepcion_id nulo)
--
-- El guion database/verificar_aislamiento.sh comprueba las cinco. El
-- conteo de claves por empresa que vigilan aplicar.sh y la integración
-- continua pasa de 24 a 29: cinco nuevas, las cuatro de los apartados 2
-- y 3 más la del 4.
-- =====================================================================
