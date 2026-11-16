-- ---------------------------------------------------------------------
-- V45 · Domicilio, contra entrega, producto nuevo y encargo entregado
--       (RF-41, RF-42, RF-44, RF-69)
--
-- La venta del mostrador (V44) ya se registra. Esta migración deja lo que
-- le falta para cuatro cosas que se activan dentro de la venta:
--
--   domicilios_venta   Los datos de la entrega a domicilio de una venta
--                      (dirección, ciudad, teléfono y quién recibe) y quién
--                      la entregó y cuándo. Una fila por venta a domicilio.
--                      Los domicilios los hace un tercero: no hay
--                      transportadora, guía ni estados intermedios.
--
--   ventas             `pago_contra_entrega`: la venta a domicilio que se
--                      cobra al entregarla. Nace con `estado_pago =
--                      PENDIENTE` y sin pagos; el cobro posterior los crea
--                      y la pasa a PAGADA. Solo cabe en un domicilio.
--
--   productos          `costo_provisional`: el producto que se creó dentro de
--                      la venta nace con costo 1,00 (la columna exige costo
--                      mayor que cero) y queda marcado para que el
--                      administrador sepa que su costo no es el real.
--
--   venta_items        `encargo_entregado_en` y `encargo_entregado_por`: la
--                      entrega a mano de un encargo. El estado de un encargo
--                      («pendiente» o «llegó») NO se guarda: se calcula al
--                      consultar con la existencia del producto.
--
-- Y siembra, para cada empresa que ya existe, el proveedor «Sin proveedor» y
-- la categoría «Sin categoría» que el producto nuevo necesita: `proveedor_id`
-- y `categoria_id` son obligatorios y `v_precios_producto` hace JOIN interno
-- con ambas tablas. El alta de empresa los siembra para las nuevas
-- (`ServicioPlataforma.darDeAlta`); la venta solo los busca y nunca los crea.
-- La siembra es idempotente: si la empresa ya tiene uno con ese nombre se
-- reutiliza (la comparación es la de la columna: sin distinguir mayúsculas
-- ni tildes, la misma de las claves únicas). El proveedor lleva el NIT
-- reservado «SIN-PROVEEDOR» (`uq_proveedores_nit` es por empresa); si algún
-- proveedor de la empresa ya usa ese NIT con otro nombre no se inserta nada
-- en lugar de fallar la migración, y el producto nuevo de esa empresa
-- responde con un error de datos claro hasta que se corrija a mano.
--
-- Decisiones del esquema:
--
--   * Toda clave foránea nueva es compuesta con `empresa_id` (V11, V31) y su
--     nombre termina en `_emp`: son tres (domicilios_venta → ventas,
--     domicilios_venta → usuarios y venta_items → usuarios).
--   * «Quién y cuándo» van siempre en pareja: o están los dos o no está
--     ninguno. La actualización de la entrega es condicionada a que siga
--     sin entregar (`entregado_en IS NULL`), de modo que dos entregas
--     simultáneas no se pisan y la segunda sabe que llegó tarde.
--   * Un encargo solo se entrega en una línea que lo es
--     (`cantidad_encargo > 0`).
--   * Los textos del domicilio no admiten blancos y caben en la columna:
--     longitudes de las columnas homónimas de `clientes` (V4), salvo
--     `recibe`, que es un nombre.
--   * Tres índices para las listas: lo que falta por despachar, lo que
--     falta por cobrar y los encargos pendientes. Ninguna lista recorre el
--     historial entero de una empresa.
--
-- Requiere: V4, V10, V11, V31 y V44.
-- ---------------------------------------------------------------------

-- 1. domicilios_venta
CREATE TABLE domicilios_venta (
  venta_id      BIGINT       NOT NULL,
  empresa_id    INT          NOT NULL,
  direccion     VARCHAR(200) NOT NULL,
  ciudad        VARCHAR(80)  NOT NULL,
  telefono      VARCHAR(20)  NOT NULL,
  recibe        VARCHAR(120) NOT NULL,
  entregado_en  DATETIME     NULL,
  entregado_por INT          NULL,
  CONSTRAINT pk_domicilios_venta PRIMARY KEY (venta_id),
  CONSTRAINT fk_domicilios_venta_emp FOREIGN KEY (empresa_id, venta_id)
    REFERENCES ventas (empresa_id, venta_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_domicilios_entregado_emp FOREIGN KEY (empresa_id, entregado_por)
    REFERENCES usuarios (empresa_id, usuario_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_domicilios_texto CHECK (
    CHAR_LENGTH(TRIM(direccion)) > 0 AND CHAR_LENGTH(TRIM(ciudad)) > 0
    AND CHAR_LENGTH(TRIM(telefono)) > 0 AND CHAR_LENGTH(TRIM(recibe)) > 0),
  CONSTRAINT ck_domicilios_entrega CHECK (
    (entregado_en IS NULL AND entregado_por IS NULL)
    OR (entregado_en IS NOT NULL AND entregado_por IS NOT NULL)),
  INDEX ix_domicilios_pendientes (empresa_id, entregado_en)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 2. ventas: el pago contra entrega, y su lista de por cobrar
ALTER TABLE ventas
  ADD COLUMN pago_contra_entrega BOOL NOT NULL DEFAULT FALSE,
  ADD CONSTRAINT ck_ventas_contra_entrega CHECK (
    NOT pago_contra_entrega OR tipo_entrega = 'DOMICILIO'),
  ADD INDEX ix_ventas_por_cobrar (empresa_id, estado_pago, fecha);

-- 3. productos: el costo que no es el real
ALTER TABLE productos
  ADD COLUMN costo_provisional BOOL NOT NULL DEFAULT FALSE;

-- 4. venta_items: la entrega a mano de un encargo, y su lista de pendientes
ALTER TABLE venta_items
  ADD COLUMN encargo_entregado_en  DATETIME NULL,
  ADD COLUMN encargo_entregado_por INT      NULL,
  ADD CONSTRAINT fk_venitems_entregado_emp FOREIGN KEY (empresa_id, encargo_entregado_por)
    REFERENCES usuarios (empresa_id, usuario_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT ck_venitems_encargo_entregado CHECK (
    ((encargo_entregado_en IS NULL AND encargo_entregado_por IS NULL)
     OR (encargo_entregado_en IS NOT NULL AND encargo_entregado_por IS NOT NULL))
    AND (encargo_entregado_en IS NULL OR cantidad_encargo > 0)),
  ADD INDEX ix_venitems_encargos (empresa_id, cantidad_encargo, encargo_entregado_en);

-- 5. «Sin proveedor» y «Sin categoría» para las empresas que ya existen
INSERT INTO proveedores (empresa_id, razon_social, nit)
SELECT e.empresa_id, 'Sin proveedor', 'SIN-PROVEEDOR'
  FROM empresas e
 WHERE NOT EXISTS (SELECT 1 FROM proveedores p
                    WHERE p.empresa_id = e.empresa_id
                      AND (p.razon_social = 'Sin proveedor' OR p.nit = 'SIN-PROVEEDOR'));

INSERT INTO categorias (empresa_id, nombre)
SELECT e.empresa_id, 'Sin categoría'
  FROM empresas e
 WHERE NOT EXISTS (SELECT 1 FROM categorias c
                    WHERE c.empresa_id = e.empresa_id AND c.nombre = 'Sin categoría');

-- =====================================================================
-- Comprobación
-- =====================================================================
--   SELECT e.empresa_id FROM empresas e
--    WHERE (SELECT COUNT(*) FROM proveedores p
--            WHERE p.empresa_id = e.empresa_id AND p.razon_social = 'Sin proveedor') <> 1
--       OR (SELECT COUNT(*) FROM categorias c
--            WHERE c.empresa_id = e.empresa_id AND c.nombre = 'Sin categoría') <> 1;
--   → ninguna empresa sin su proveedor y su categoría para productos nuevos, ni con dos
-- =====================================================================
