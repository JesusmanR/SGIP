-- ---------------------------------------------------------------------
-- V9. Cuarto rol y venta sin existencia disponible
--
-- Dos cambios de la versión 3 de la especificación obligan a tocar el
-- esquema:
--
--   1. El rol SUPER_ADMIN se separa de ADMINISTRADOR. El primero configura
--      la aplicación y administra accesos; el segundo opera el negocio.
--
--   2. El requisito RF-27 permite al administrador registrar una venta sin
--      existencia disponible. El esquema actual lo impide por dos vías
--      distintas, y ambas hay que levantarlas.
--
-- Ejecutar después de V8. Idempotente en lo que MySQL permite serlo.
-- ---------------------------------------------------------------------

-- ------------------------------------------------- 1. el cuarto rol ---
INSERT INTO roles (nombre, descripcion)
SELECT 'SUPER_ADMIN',
       'Configuración de la aplicación, parámetros tributarios y comerciales, usuarios, roles y accesos'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE nombre = 'SUPER_ADMIN');

-- La frontera entre los dos roles de administración se vuelve explícita.
UPDATE roles
   SET descripcion = 'Operación del negocio: catálogo, proveedores, precios, inventario, ventas y descuentos'
 WHERE nombre = 'ADMINISTRADOR';

-- ------------------------------ 2. existencia negativa autorizada -----
-- La restricción impedía que la existencia bajara de cero. Como el RF-27
-- la permite de forma deliberada y trazable, se sustituye por un piso
-- razonable que sigue atajando el error de digitación: nadie vende diez
-- mil unidades que no tiene.
ALTER TABLE productos
  DROP CHECK ck_productos_existencia;

ALTER TABLE productos
  ADD CONSTRAINT ck_productos_existencia CHECK (existencia >= -9999);

-- La columna generada clasificaba por igualdad con cero, de modo que una
-- existencia negativa habría quedado marcada como CRITICO en lugar de
-- AGOTADO. Se corrige la comparación.
-- El índice sobrevive al borrado de la columna generada: MySQL lo conserva
-- sobre las columnas restantes en lugar de eliminarlo. Hay que retirarlo
-- antes, o el CREATE INDEX posterior choca con un nombre duplicado y deja la
-- migración a medias.
ALTER TABLE productos
  DROP INDEX ix_productos_estado;

ALTER TABLE productos
  DROP COLUMN estado_stock;

ALTER TABLE productos
  ADD COLUMN estado_stock ENUM('ACTIVO','CRITICO','AGOTADO')
      GENERATED ALWAYS AS (
        CASE
          WHEN existencia <= 0                 THEN 'AGOTADO'
          WHEN existencia <= existencia_minima THEN 'CRITICO'
          ELSE 'ACTIVO'
        END
      ) STORED
      AFTER existencia_minima;

CREATE INDEX ix_productos_estado ON productos (estado_stock, activo);

-- ------------------------- 3. huella de la venta sin existencia -------
-- Quién la autorizó y si sigue pendiente de reposición. Se sigue el mismo
-- patrón que ya usa el envío obsequiado: la condición vive en la base y no
-- depende de que la aplicación se acuerde de validarla.
ALTER TABLE ventas
  ADD COLUMN sin_existencia BOOL NOT NULL DEFAULT 0 AFTER estado,
  ADD COLUMN autorizada_por INT  NULL          AFTER sin_existencia,
  ADD COLUMN motivo_sin_existencia VARCHAR(200) NULL AFTER autorizada_por,
  ADD COLUMN reposicion_pendiente BOOL NOT NULL DEFAULT 0 AFTER motivo_sin_existencia;

ALTER TABLE ventas
  ADD CONSTRAINT fk_ventas_autoriza FOREIGN KEY (autorizada_por)
      REFERENCES usuarios (usuario_id);

ALTER TABLE ventas
  ADD CONSTRAINT ck_ventas_sin_existencia CHECK (
    sin_existencia = 0
    OR (autorizada_por IS NOT NULL AND motivo_sin_existencia IS NOT NULL));

CREATE INDEX ix_ventas_pendientes ON ventas (reposicion_pendiente, fecha);

-- ----------------------------------- 4. parámetros de la regla --------
INSERT INTO parametros (clave, valor, tipo_dato, descripcion, vigente_desde) VALUES
  ('sgip.venta.sin.existencia.permitida', 'true',          'BOOLEANO',
   'Permite comprometer una venta sin existencia disponible', '2026-01-01'),
  ('sgip.venta.sin.existencia.rol',       'ADMINISTRADOR', 'TEXTO',
   'Único rol autorizado para comprometer una venta sin existencia', '2026-01-01')
ON DUPLICATE KEY UPDATE valor = VALUES(valor);

-- ---------------------------------------------------------------------
-- Verificaciones esperadas
-- ---------------------------------------------------------------------
-- 1) Los cuatro roles.
-- SELECT nombre, descripcion FROM roles ORDER BY rol_id;

-- 2) La existencia negativa se admite y se clasifica como AGOTADO.
-- UPDATE productos SET existencia = -2 WHERE codigo = 'SRV-001';
-- SELECT codigo, existencia, estado_stock FROM productos WHERE codigo = 'SRV-001';

-- 3) Una venta marcada sin existencia exige responsable y motivo. La
--    siguiente debe fallar con el error 3819.
-- INSERT INTO ventas (consecutivo, cliente_id, usuario_id, forma_pago, sin_existencia)
--   VALUES ('PRUEBA-001', 1, 1, 'CONTADO', 1);
