-- =====================================================================
-- SGIP · Migración V28 · Las tablas de importación dejan de poder
--                        cruzar empresas
-- =====================================================================
--
-- La V11 metió `empresa_id` en las veinte claves foráneas que podían
-- cruzar comercios. La V24 cerró tres que se le habían quedado fuera, en
-- el camino de las compras, y las encontró justo al ir a construir ese
-- módulo. Esto es lo mismo una vez más, en el camino de la importación
-- de listas de precios (RF-24, RF-26, RF-27), y encontrado del mismo
-- modo: mirando el esquema antes de escribir encima de él.
--
-- Reproducido sobre una base con la cadena V1 a V27 aplicada y los datos
-- de demostración, que tienen tres empresas:
--
--   1. `cargas_precios` no tiene empresa. Una carga entró nombrando un
--      proveedor de una empresa y un usuario de otra. La fila entró.
--
--   2. `detalle_carga_precios.fk_detalle_producto` apunta a
--      `productos(producto_id)` sin empresa. El detalle de esa carga
--      apuntó al producto PC-001 de la empresa 2, con costo 999.999
--      frente a su costo real de 2.000. La fila entró.
--
--   3. `historico_costos.fk_historico_producto`, igual. El histórico de
--      costos de un producto ajeno acepta filas. La fila entró.
--
-- La segunda es la que muerde, y muerde más fuerte que su equivalente de
-- compras. Aplicar esa carga ejecutaría un UPDATE sobre el costo de un
-- producto de otro comercio. Y el costo no es un dato suelto: de él
-- cuelga toda la vista de precios, de modo que un importador de una
-- empresa reescribiría los precios de venta de otra, de contado y a
-- crédito, con su tratamiento tributario recalculado. Sin dejar rastro
-- en el kárdex, porque un cambio de costo no es un movimiento.
--
-- Ninguna se manifiesta hoy: el módulo no está construido y las cuatro
-- tablas están vacías. Por eso se cierran antes y no después. Con datos
-- dentro, añadir una columna obligatoria y rellenarla es una migración
-- que hay que pensar dos veces.
--
-- ## Por qué `empresa_id` repetida y no subir hasta el padre
--
-- `detalle_carga_precios` cuelga de `cargas_precios`, que tras esta
-- migración sí tiene empresa. Se podría llegar allí con un enlace y
-- acotar. Se descarta por lo que la V11 argumentó en su día, la V15
-- repitió para la vista de precios y la V24 volvió a repetir: el
-- aislamiento no debe depender de que cada consulta se acuerde de subir
-- hasta el padre. Con la columna repetida y la clave compuesta, una fila
-- incoherente no entra, venga de la aplicación, de un guion de
-- mantenimiento o de un dedazo en la consola.
--
-- ## Por qué cambian dos acciones referenciales
--
-- `fk_detalle_producto` era ON DELETE SET NULL y `fk_historico_carga`
-- también. MySQL solo admite SET NULL cuando TODAS las columnas de la
-- clave admiten nulo, y `empresa_id` es obligatoria, de modo que las dos
-- pasan a RESTRICT. No se pierde nada: en este sistema los productos no
-- se borran, se desactivan con `productos.activo`, y una carga es un
-- registro histórico que tampoco se borra. Que el motor se niegue es la
-- respuesta correcta si alguien lo intenta.
--
-- La combinación (empresa_id, producto_id) con `producto_id` nulo sigue
-- entrando: MySQL no comprueba una clave foránea compuesta cuando una de
-- sus columnas es nula. Un registro rechazado por referencia inexistente
-- sigue pudiendo quedar anotado sin producto al que apuntar, que es para
-- lo que esa columna admitía nulo.
--
-- Requiere: V11, V22. Aplicar después de V27.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. cargas_precios · el encabezado de cada importación
-- ---------------------------------------------------------------------
ALTER TABLE cargas_precios
  ADD COLUMN empresa_id INT NULL AFTER carga_id;

UPDATE cargas_precios c
  JOIN proveedores p ON p.proveedor_id = c.proveedor_id
   SET c.empresa_id = p.empresa_id
 WHERE c.empresa_id IS NULL;

ALTER TABLE cargas_precios
  MODIFY COLUMN empresa_id INT NOT NULL;

ALTER TABLE cargas_precios
  DROP FOREIGN KEY fk_cargas_proveedor;

ALTER TABLE cargas_precios
  ADD CONSTRAINT fk_cargas_proveedor_emp
      FOREIGN KEY (empresa_id, proveedor_id)
      REFERENCES proveedores (empresa_id, proveedor_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE cargas_precios
  DROP FOREIGN KEY fk_cargas_usuario;

-- Quien firma la carga tiene que ser de la misma empresa que el
-- proveedor. Es la pareja que entró en la reproducción de arriba.
ALTER TABLE cargas_precios
  ADD CONSTRAINT fk_cargas_usuario_emp
      FOREIGN KEY (empresa_id, usuario_id)
      REFERENCES usuarios (empresa_id, usuario_id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

-- Las hijas necesitan a quién apuntar con la pareja completa.
ALTER TABLE cargas_precios
  ADD CONSTRAINT uq_cargas_empresa UNIQUE (empresa_id, carga_id);


-- ---------------------------------------------------------------------
-- 2. detalle_carga_precios · la que muerde
-- ---------------------------------------------------------------------
ALTER TABLE detalle_carga_precios
  ADD COLUMN empresa_id INT NULL AFTER carga_id;

UPDATE detalle_carga_precios d
  JOIN cargas_precios c ON c.carga_id = d.carga_id
   SET d.empresa_id = c.empresa_id
 WHERE d.empresa_id IS NULL;

ALTER TABLE detalle_carga_precios
  MODIFY COLUMN empresa_id INT NOT NULL;

ALTER TABLE detalle_carga_precios
  DROP FOREIGN KEY fk_detalle_carga;

ALTER TABLE detalle_carga_precios
  ADD CONSTRAINT fk_detalle_carga_emp
      FOREIGN KEY (empresa_id, carga_id)
      REFERENCES cargas_precios (empresa_id, carga_id)
      ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE detalle_carga_precios
  DROP FOREIGN KEY fk_detalle_producto;

ALTER TABLE detalle_carga_precios
  ADD CONSTRAINT fk_detalle_producto_emp
      FOREIGN KEY (empresa_id, producto_id)
      REFERENCES productos (empresa_id, producto_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;


-- ---------------------------------------------------------------------
-- 3. historico_costos · de dónde sale el costo de una fecha pasada
-- ---------------------------------------------------------------------
ALTER TABLE historico_costos
  ADD COLUMN empresa_id INT NULL AFTER historico_id;

UPDATE historico_costos h
  JOIN productos p ON p.producto_id = h.producto_id
   SET h.empresa_id = p.empresa_id
 WHERE h.empresa_id IS NULL;

ALTER TABLE historico_costos
  MODIFY COLUMN empresa_id INT NOT NULL;

ALTER TABLE historico_costos
  DROP FOREIGN KEY fk_historico_producto;

ALTER TABLE historico_costos
  ADD CONSTRAINT fk_historico_producto_emp
      FOREIGN KEY (empresa_id, producto_id)
      REFERENCES productos (empresa_id, producto_id)
      ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE historico_costos
  DROP FOREIGN KEY fk_historico_carga;

ALTER TABLE historico_costos
  ADD CONSTRAINT fk_historico_carga_emp
      FOREIGN KEY (empresa_id, carga_id)
      REFERENCES cargas_precios (empresa_id, carga_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;


-- ---------------------------------------------------------------------
-- 4. mapeos_proveedor · la correspondencia de columnas por proveedor
-- ---------------------------------------------------------------------
-- Esta no cruza empresas por sí sola, porque solo referencia al
-- proveedor. Lleva la columna igualmente: la RN-16 exige filtrar por
-- empresa en toda lectura, y una tabla sin `empresa_id` obliga a que esa
-- consulta sea la excepción que se acuerde de subir por el proveedor.
-- Las excepciones son lo que después se olvida.
ALTER TABLE mapeos_proveedor
  ADD COLUMN empresa_id INT NULL AFTER mapeo_id;

UPDATE mapeos_proveedor m
  JOIN proveedores p ON p.proveedor_id = m.proveedor_id
   SET m.empresa_id = p.empresa_id
 WHERE m.empresa_id IS NULL;

ALTER TABLE mapeos_proveedor
  MODIFY COLUMN empresa_id INT NOT NULL;

ALTER TABLE mapeos_proveedor
  DROP FOREIGN KEY fk_mapeos_proveedor;

ALTER TABLE mapeos_proveedor
  ADD CONSTRAINT fk_mapeos_proveedor_emp
      FOREIGN KEY (empresa_id, proveedor_id)
      REFERENCES proveedores (empresa_id, proveedor_id)
      ON DELETE CASCADE ON UPDATE CASCADE;
