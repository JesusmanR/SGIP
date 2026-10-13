-- =====================================================================
-- V11 · El aislamiento entre empresas pasa a las claves foráneas
-- =====================================================================
--
-- La V10 agregó empresa_id a doce tablas, pero dejó las claves foráneas
-- como estaban: de una sola columna. Una clave foránea de una columna
-- solo comprueba que la fila referida EXISTA, no que pertenezca a la
-- misma empresa. El resultado es que el esquema admite esto:
--
--   INSERT INTO productos (empresa_id, categoria_id, ...)
--   VALUES (2, <categoria de la empresa 1>, ...);
--
-- La fila entra sin error. Un producto de un comercio queda colgado de
-- la categoría de otro. Y como la lectura normal filtra por la empresa
-- del producto, el defecto no se ve: la categoría ajena aparece como si
-- fuera propia.
--
-- La corrección es declarar la clave sobre las dos columnas. Para que
-- MySQL lo admita, la tabla referida necesita un índice único sobre
-- (empresa_id, clave primaria), que es lo que crea el primer bloque.
--
-- Esta migración NO cambia datos. Si alguna fila ya cruza empresas, el
-- ALTER falla con error 1452 y no se aplica. Eso es deliberado: es mejor
-- enterarse ahora que arrastrar la inconsistencia.
--
-- Requiere V10 aplicada. Haz una copia antes: MySQL confirma cada
-- sentencia de definición por su cuenta y esto no se deshace con
-- ROLLBACK.
--
--   mysqldump -u root -p sgip > respaldo_antes_de_v11.sql
-- =====================================================================


-- =====================================================================
-- 1. Índices únicos que las claves compuestas necesitan referir
-- =====================================================================
-- Son redundantes en apariencia —la clave primaria ya garantiza la
-- unicidad— pero MySQL exige que las columnas referidas por una clave
-- foránea sean el prefijo de algún índice. Sin esto, cada ALTER del
-- bloque 2 falla con error 1822.

ALTER TABLE categorias
  ADD UNIQUE INDEX uq_categorias_empresa (empresa_id, categoria_id);

ALTER TABLE proveedores
  ADD UNIQUE INDEX uq_proveedores_empresa (empresa_id, proveedor_id);

ALTER TABLE clientes
  ADD UNIQUE INDEX uq_clientes_empresa (empresa_id, cliente_id);

ALTER TABLE productos
  ADD UNIQUE INDEX uq_productos_empresa (empresa_id, producto_id);

ALTER TABLE cotizaciones
  ADD UNIQUE INDEX uq_cotizaciones_empresa (empresa_id, cotizacion_id);

ALTER TABLE medios_pago
  ADD UNIQUE INDEX uq_medios_empresa (empresa_id, medio_id);

ALTER TABLE cierres_caja
  ADD UNIQUE INDEX uq_cierres_empresa (empresa_id, cierre_id);

-- En usuarios, empresa_id admite nulo: el super administrador de la
-- plataforma no pertenece a ninguna empresa. Un índice único tolera
-- varios nulos, de modo que esto no impide crear más de un usuario de
-- plataforma.
ALTER TABLE usuarios
  ADD UNIQUE INDEX uq_usuarios_empresa (empresa_id, usuario_id);


-- =====================================================================
-- 2. Catálogo y comercial: la referencia incluye la empresa
-- =====================================================================
-- Cada bloque suelta la clave de una columna y la vuelve a declarar
-- sobre dos, conservando las mismas acciones de borrado y actualización
-- que tenía. La única excepción está señalada más abajo.
--
-- Las claves nuevas llevan el sufijo _emp. No es adorno: cuando MySQL
-- crea una clave foránea y no encuentra un índice que la respalde, crea
-- uno con el nombre de la restricción. Al soltar la clave, ese índice
-- NO desaparece. Reutilizar el nombre fallaría con error 1061, que es
-- exactamente el defecto que tenía la primera V9. Catorce de estas
-- veinte claves están en esa situación, y cuáles depende del motor, de
-- modo que renombrar es más seguro que llevar la lista.
--
-- Los índices de una columna que quedan no se borran: siguen sirviendo
-- para buscar por esa columna sola, cosa que el índice compuesto
-- (empresa_id, columna) no hace.

ALTER TABLE productos
  DROP FOREIGN KEY fk_productos_categoria;
ALTER TABLE productos
  ADD CONSTRAINT fk_productos_categoria_emp
      FOREIGN KEY (empresa_id, categoria_id)
      REFERENCES categorias (empresa_id, categoria_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE productos
  DROP FOREIGN KEY fk_productos_proveedor;
ALTER TABLE productos
  ADD CONSTRAINT fk_productos_proveedor_emp
      FOREIGN KEY (empresa_id, proveedor_id)
      REFERENCES proveedores (empresa_id, proveedor_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;

-- En reglas_margen las dos columnas admiten nulo: una regla puede
-- aplicar a toda la empresa, a una categoría o a un proveedor. Cuando la
-- columna es nula, InnoDB no comprueba la clave, que es el
-- comportamiento buscado.
ALTER TABLE reglas_margen
  DROP FOREIGN KEY fk_reglas_margen_categoria;
ALTER TABLE reglas_margen
  ADD CONSTRAINT fk_reglas_margen_categoria_emp
      FOREIGN KEY (empresa_id, categoria_id)
      REFERENCES categorias (empresa_id, categoria_id)
      ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE reglas_margen
  DROP FOREIGN KEY fk_reglas_margen_proveedor;
ALTER TABLE reglas_margen
  ADD CONSTRAINT fk_reglas_margen_proveedor_emp
      FOREIGN KEY (empresa_id, proveedor_id)
      REFERENCES proveedores (empresa_id, proveedor_id)
      ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE ordenes_compra
  DROP FOREIGN KEY fk_oc_proveedor;
ALTER TABLE ordenes_compra
  ADD CONSTRAINT fk_oc_proveedor_emp
      FOREIGN KEY (empresa_id, proveedor_id)
      REFERENCES proveedores (empresa_id, proveedor_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE movimientos_inventario
  DROP FOREIGN KEY fk_movimientos_producto;
ALTER TABLE movimientos_inventario
  ADD CONSTRAINT fk_movimientos_producto_emp
      FOREIGN KEY (empresa_id, producto_id)
      REFERENCES productos (empresa_id, producto_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE cotizaciones
  DROP FOREIGN KEY fk_cotizaciones_cliente;
ALTER TABLE cotizaciones
  ADD CONSTRAINT fk_cotizaciones_cliente_emp
      FOREIGN KEY (empresa_id, cliente_id)
      REFERENCES clientes (empresa_id, cliente_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE ventas
  DROP FOREIGN KEY fk_ventas_cliente;
ALTER TABLE ventas
  ADD CONSTRAINT fk_ventas_cliente_emp
      FOREIGN KEY (empresa_id, cliente_id)
      REFERENCES clientes (empresa_id, cliente_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE ventas
  DROP FOREIGN KEY fk_ventas_medio;
ALTER TABLE ventas
  ADD CONSTRAINT fk_ventas_medio_emp
      FOREIGN KEY (empresa_id, medio_id)
      REFERENCES medios_pago (empresa_id, medio_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ventas
  DROP FOREIGN KEY fk_ventas_cierre;
ALTER TABLE ventas
  ADD CONSTRAINT fk_ventas_cierre_emp
      FOREIGN KEY (empresa_id, cierre_id)
      REFERENCES cierres_caja (empresa_id, cierre_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

-- ── La excepción ────────────────────────────────────────────────────
-- fk_ventas_cotizacion tenía ON DELETE SET NULL. Una clave compuesta no
-- puede usar SET NULL aquí: MySQL anularía las DOS columnas, y
-- ventas.empresa_id es obligatoria. El ALTER fallaría con error 1830.
--
-- Pasa a RESTRICT. En la práctica no cambia nada, porque en este esquema
-- nada se elimina: las cotizaciones se anulan cambiando su estado. Y si
-- alguien intentara borrar una cotización con venta asociada, RESTRICT
-- lo impide en lugar de dejar la venta sin su origen, que para la
-- trazabilidad es mejor.
ALTER TABLE ventas
  DROP FOREIGN KEY fk_ventas_cotizacion;
ALTER TABLE ventas
  ADD CONSTRAINT fk_ventas_cotizacion_emp
      FOREIGN KEY (empresa_id, cotizacion_id)
      REFERENCES cotizaciones (empresa_id, cotizacion_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;


-- =====================================================================
-- 3. El autor de una operación pertenece a la empresa de la operación
-- =====================================================================
-- Estas claves ganan un efecto adicional al aislamiento: como
-- usuarios.empresa_id es nulo para el super administrador de la
-- plataforma, y la empresa_id de estas tablas es obligatoria, la clave
-- compuesta deja de admitir que un usuario de plataforma figure como
-- autor de una venta, un movimiento o un cierre de caja.
--
-- Eso es la regla de negocio: BSP crea, activa y desactiva empresas,
-- pero no opera dentro de ellas. Hasta ahora era una convención; aquí
-- queda en el esquema.
--
-- auditoria_accesos queda fuera a propósito: su empresa_id admite nulo
-- porque tiene que registrar los accesos del super administrador.
--
-- Todas estas claves usan ON UPDATE RESTRICT, aunque tres de ellas
-- venían con CASCADE. No es una preferencia: MySQL rechaza la clave con
-- error 1005 (errno 150) cuando la columna hija es obligatoria y la del
-- padre admite nulo, porque un CASCADE podría tener que escribir nulo en
-- una columna que no lo acepta. Como usuarios.empresa_id admite nulo
-- para el super administrador, CASCADE queda descartado aquí.
--
-- La consecuencia práctica es nula: usuario_id es una clave artificial
-- que no cambia nunca, y un usuario no se traslada de empresa. Si alguna
-- vez hiciera falta trasladarlo, hay que mover sus filas a mano, que es
-- lo correcto: sus ventas pertenecen a la empresa donde las hizo.

ALTER TABLE ventas
  DROP FOREIGN KEY fk_ventas_usuario;
ALTER TABLE ventas
  ADD CONSTRAINT fk_ventas_usuario_emp
      FOREIGN KEY (empresa_id, usuario_id)
      REFERENCES usuarios (empresa_id, usuario_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ventas
  DROP FOREIGN KEY fk_ventas_autoriza;
ALTER TABLE ventas
  ADD CONSTRAINT fk_ventas_autoriza_emp
      FOREIGN KEY (empresa_id, autorizada_por)
      REFERENCES usuarios (empresa_id, usuario_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ventas
  DROP FOREIGN KEY fk_ventas_envio_autoriza;
ALTER TABLE ventas
  ADD CONSTRAINT fk_ventas_envio_autoriza_emp
      FOREIGN KEY (empresa_id, envio_autorizado_por)
      REFERENCES usuarios (empresa_id, usuario_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE cotizaciones
  DROP FOREIGN KEY fk_cotizaciones_usuario;
ALTER TABLE cotizaciones
  ADD CONSTRAINT fk_cotizaciones_usuario_emp
      FOREIGN KEY (empresa_id, usuario_id)
      REFERENCES usuarios (empresa_id, usuario_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE cotizaciones
  DROP FOREIGN KEY fk_cotizaciones_envio_autoriza;
ALTER TABLE cotizaciones
  ADD CONSTRAINT fk_cotizaciones_envio_autoriza_emp
      FOREIGN KEY (empresa_id, envio_autorizado_por)
      REFERENCES usuarios (empresa_id, usuario_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE movimientos_inventario
  DROP FOREIGN KEY fk_movimientos_usuario;
ALTER TABLE movimientos_inventario
  ADD CONSTRAINT fk_movimientos_usuario_emp
      FOREIGN KEY (empresa_id, usuario_id)
      REFERENCES usuarios (empresa_id, usuario_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE ordenes_compra
  DROP FOREIGN KEY fk_oc_usuario;
ALTER TABLE ordenes_compra
  ADD CONSTRAINT fk_oc_usuario_emp
      FOREIGN KEY (empresa_id, usuario_id)
      REFERENCES usuarios (empresa_id, usuario_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE cierres_caja
  DROP FOREIGN KEY fk_cierre_usuario;
ALTER TABLE cierres_caja
  ADD CONSTRAINT fk_cierre_usuario_emp
      FOREIGN KEY (empresa_id, usuario_id)
      REFERENCES usuarios (empresa_id, usuario_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE cola_sincronizacion
  DROP FOREIGN KEY fk_cola_usuario;
ALTER TABLE cola_sincronizacion
  ADD CONSTRAINT fk_cola_usuario_emp
      FOREIGN KEY (empresa_id, usuario_id)
      REFERENCES usuarios (empresa_id, usuario_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;


-- =====================================================================
-- Comprobación
-- =====================================================================
-- Lo siguiente debe fallar con error 1452. Si entra, la migración no
-- quedó aplicada:
--
--   INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo,
--                          sku, nombre, costo, existencia,
--                          existencia_minima, activo)
--   SELECT 2,
--          (SELECT categoria_id FROM categorias  WHERE empresa_id = 1 LIMIT 1),
--          (SELECT proveedor_id FROM proveedores WHERE empresa_id = 1 LIMIT 1),
--          'FUGA-001','SKU-FUGA','Producto cruzado',1000,1,1,TRUE;
--
-- Y esto debe seguir funcionando: un producto con la categoría y el
-- proveedor de su propia empresa.
