-- =====================================================================
-- SGIP · Migración V36 · El porcentaje y el precio de venta por producto
-- =====================================================================
--
-- El ADR-0025 retira la prohibición del RF-31: al registrar o editar un
-- producto, la tienda escribe su porcentaje de ganancia o su precio de venta.
-- Ninguno de los dos va en `productos`, y esa es la decisión de fondo: lo que
-- se escribe es una **regla con vigencia** en `reglas_margen`, igual que las
-- de categoría o de proveedor, de modo que un cambio de precio deja historia
-- en lugar de sobrescribir una columna.
--
-- ## Reglas
--
-- `producto_id` acota la regla a un producto y `precio_fijo` dice la cifra
-- cuando lo escrito fue el precio. Una regla dice su precio de una de dos
-- maneras, nunca de las dos ni de ninguna:
--
--   escribió el porcentaje   factor = 1 + %/100     precio_fijo NULL
--   escribió el precio       factor NULL            precio_fijo = la cifra
--
-- De ahí sale lo que se conserva cuando sube el costo: con factor, el precio
-- se recalcula y el porcentaje se queda; con precio fijo, el precio se queda
-- y el margen cambia. No hay ninguna lógica adicional que lo decida.
--
-- `precio_fijo` es DECIMAL(12,0): el precio de venta va en pesos enteros, el
-- mismo redondeo con el que la vista calcula los demás, y no pasa de 12 cifras
-- porque `precio_unitario` de las ventas es DECIMAL(14,2) (V4): un precio más
-- largo se guardaría bien y fallaría el día de cobrarlo.
--
-- ## Precedencia
--
-- Primero la regla del producto. Después, como desde la V12, la más específica:
-- categoría y proveedor, luego una de las dos y luego la general. Entre una de
-- categoría y una de proveedor, que son igual de específicas, decide la fecha.
-- La vigencia vale igual: una regla propia vencida no decide nada y el precio
-- vuelve a la regla que corresponda.
--
-- ## Por qué v_regla_margen es una unión
--
-- El alta exige el precio de contado y lo copia al crédito, de modo que dos
-- reglas por producto son el régimen normal. Con una sola condición
-- `(r.producto_id IS NULL OR r.producto_id = p.producto_id)`, ningún índice
-- sirve y cada regla recorre todos los productos de su empresa: con 10.000
-- productos, una página del catálogo tardaba 30 segundos. Separadas, las
-- propias se buscan por igualdad y las generales siguen multiplicando por su
-- número, que es pequeño. El ROW_NUMBER va sobre la unión, de modo que la
-- precedencia es la misma. Las cifras están en el ADR-0025.
--
-- ## La clave única y la de empresa
--
-- `uq_reglas_margen_producto` es la que sostiene «dos cambios el mismo día
-- corrigen la misma fila». Las reglas que no son de un producto tienen
-- `producto_id` nulo, y dos nulos no chocan en un índice único: ni se
-- restringe nada de lo que ya existía ni hace falta otro índice para la clave
-- foránea, que usa sus dos primeras columnas.
--
-- La clave foránea no lleva ON UPDATE CASCADE, a diferencia de las de
-- categoría y proveedor de la V11, porque MySQL no deja que una columna con
-- acción de actualización entre en un CHECK, y `producto_id` entra. El
-- identificador de un producto no cambia nunca, así que no se pierde nada.
--
-- ## v_precios_producto
--
-- Copia de la definición que dejó la V35, incluida la rama de la UVT en
-- `tributacion_sin_configurar`. El cambio de fondo: `precio_contado` y
-- `precio_credito` se calculan **una sola vez**, en la tabla derivada `b`, y
-- todo lo demás los lee de ahí. La V25 repetía la fórmula en más de diez
-- sitios y añadir `precio_fijo` a cada copia es como se llega a un total
-- calculado con otro precio.
--
-- `imagen_principal` se queda fuera de `b` a propósito: una subconsulta en la
-- lista de selección impide a MySQL fusionar la tabla derivada, y los filtros
-- de `ConsultaCatalogo` dejarían de llegar a los índices de `productos`.
--
-- Nombres y orden de las columnas de antes no cambian; las nuevas van detrás.
-- `base_contado` y `base_credito` son nulas con un precio fijo, porque no se
-- calculó sobre ninguna base y `forma_calculo` no puede decirlo: es NOT NULL.
--
-- ## El margen
--
-- `margen_*` es el real, contra el precio ya redondeado: costo 7 al 20 % da
-- precio 8, que son 14,29 %. `porcentaje_*` es el que declara la regla, 20,00 %
-- en ese caso, y es el que el formulario abre: si abriera el margen real,
-- guardar sin tocar nada reescribiría la regla con otra cifra. Con un precio
-- fijo, `porcentaje_*` es nulo.
--
-- El dividendo se convierte a un DECIMAL con muchos decimales antes de dividir
-- porque MySQL conserva solo cuatro más que el dividendo: con los dos que trae
-- el costo, 33,3349999 se volvería 33,335000 y `ROUND` daría 33,34. La tabla
-- database/muestras/casos_de_precio.csv tiene esos casos. `NULLIF` no es una
-- restricción sino un seguro: `ck_productos_costo` ya exige costo positivo, y
-- si un día no lo hiciera, el margen sería NULL y no una cifra inventada.
--
-- Lo comprueba database/verificar_precios.sh.
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE reglas_margen
  MODIFY COLUMN factor DECIMAL(6,4) NULL,
  ADD COLUMN producto_id INT NULL AFTER proveedor_id,
  ADD COLUMN precio_fijo DECIMAL(12,0) NULL AFTER factor;

ALTER TABLE reglas_margen
  ADD CONSTRAINT uq_reglas_margen_producto
      UNIQUE (empresa_id, producto_id, forma_pago, vigente_desde),
  ADD CONSTRAINT fk_reglas_margen_producto_emp
      FOREIGN KEY (empresa_id, producto_id)
      REFERENCES productos (empresa_id, producto_id)
      ON DELETE CASCADE ON UPDATE RESTRICT,
  ADD CONSTRAINT ck_reglas_margen_factor_o_precio CHECK (
    (precio_fijo IS NULL AND factor IS NOT NULL)
    OR (precio_fijo IS NOT NULL AND factor IS NULL AND producto_id IS NOT NULL)),
  ADD CONSTRAINT ck_reglas_margen_precio_fijo CHECK (
    precio_fijo IS NULL OR precio_fijo > 0);


CREATE OR REPLACE VIEW v_regla_margen AS
SELECT producto_id, empresa_id, forma_pago, factor, forma_calculo, regla_id,
       precio_fijo, es_propia
FROM (
  SELECT
    u.*,
    ROW_NUMBER() OVER (
      PARTITION BY u.producto_id, u.forma_pago
      ORDER BY u.es_propia DESC,
               u.especificidad DESC,
               u.vigente_desde DESC,
               u.regla_id DESC
    ) AS prioridad
  FROM (
    SELECT
      p.producto_id,
      p.empresa_id,
      r.forma_pago,
      r.factor,
      r.forma_calculo,
      r.regla_id,
      r.precio_fijo,
      r.vigente_desde,
      1 AS es_propia,
      (r.categoria_id IS NOT NULL) + (r.proveedor_id IS NOT NULL) AS especificidad
    FROM reglas_margen r
    JOIN productos p
      ON p.empresa_id  = r.empresa_id
     AND p.producto_id = r.producto_id
    WHERE (r.categoria_id IS NULL OR r.categoria_id = p.categoria_id)
      AND (r.proveedor_id IS NULL OR r.proveedor_id = p.proveedor_id)
      AND CURDATE() >= r.vigente_desde
      AND (r.vigente_hasta IS NULL OR CURDATE() <= r.vigente_hasta)
    UNION ALL
    SELECT
      p.producto_id,
      p.empresa_id,
      r.forma_pago,
      r.factor,
      r.forma_calculo,
      r.regla_id,
      r.precio_fijo,
      r.vigente_desde,
      0 AS es_propia,
      (r.categoria_id IS NOT NULL) + (r.proveedor_id IS NOT NULL) AS especificidad
    FROM reglas_margen r
    JOIN productos p
      ON p.empresa_id = r.empresa_id
     AND (r.categoria_id IS NULL OR r.categoria_id = p.categoria_id)
     AND (r.proveedor_id IS NULL OR r.proveedor_id = p.proveedor_id)
    WHERE r.producto_id IS NULL
      AND CURDATE() >= r.vigente_desde
      AND (r.vigente_hasta IS NULL OR CURDATE() <= r.vigente_hasta)
  ) AS u
) AS ranking
WHERE prioridad = 1;


CREATE OR REPLACE VIEW v_precios_producto AS
SELECT
  b.producto_id,
  b.empresa_id,
  b.codigo,
  b.sku,
  b.nombre,
  b.presentacion,
  b.descripcion,
  b.codigo_barras,
  b.categoria_id,
  b.categoria,
  b.proveedor_id,
  b.proveedor,
  b.costo,
  b.existencia,
  b.existencia_minima,
  b.estado_stock,
  b.precio_contado,
  b.precio_credito,
  b.base_contado,
  b.base_credito,
  b.factor_contado,
  b.factor_credito,
  b.codigo_impuesto,
  b.tratamiento                          AS tratamiento_iva,
  b.umbral_uvt,
  ROUND(b.umbral_uvt * fn_valor_uvt(CURDATE()), 2) AS umbral_pesos,
  CASE
    WHEN b.responsable_iva = TRUE AND b.regla_iva_id IS NULL THEN 1
    WHEN b.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND fn_valor_uvt(CURDATE()) IS NULL THEN 1
    ELSE 0
  END                                    AS tributacion_sin_configurar,
  CASE
    WHEN b.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
    WHEN b.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND b.precio_contado <= b.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
    ELSE COALESCE(b.tarifa, 0)
  END                                    AS tarifa_contado,
  CASE
    WHEN b.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
    WHEN b.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND b.precio_credito <= b.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
    ELSE COALESCE(b.tarifa, 0)
  END                                    AS tarifa_credito,
  b.precio_contado * (1 +
    CASE
      WHEN b.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
      WHEN b.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
           AND b.precio_contado <= b.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
      ELSE COALESCE(b.tarifa, 0)
    END)                                 AS total_contado,
  b.precio_credito * (1 +
    CASE
      WHEN b.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
      WHEN b.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
           AND b.precio_credito <= b.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
      ELSE COALESCE(b.tarifa, 0)
    END)                                 AS total_credito,
  CASE
    WHEN b.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND b.precio_contado
             BETWEEN b.umbral_uvt * fn_valor_uvt(CURDATE()) * 0.90
                 AND b.umbral_uvt * fn_valor_uvt(CURDATE())
    THEN 1 ELSE 0
  END                                    AS cerca_umbral,
  CASE
    WHEN b.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND b.precio_credito
             BETWEEN b.umbral_uvt * fn_valor_uvt(CURDATE()) * 0.90
                 AND b.umbral_uvt * fn_valor_uvt(CURDATE())
    THEN 1 ELSE 0
  END                                    AS cerca_umbral_credito,
  CASE
    WHEN b.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND b.precio_contado <= b.umbral_uvt * fn_valor_uvt(CURDATE())
         AND b.precio_credito >  b.umbral_uvt * fn_valor_uvt(CURDATE())
    THEN 1 ELSE 0
  END                                    AS cruza_umbral_en_credito,
  (SELECT i.ruta FROM producto_imagenes i
    WHERE i.empresa_id  = b.empresa_id
      AND i.producto_id = b.producto_id
      AND i.principal   = TRUE)          AS imagen_principal,
  ROUND(CAST((b.precio_contado - b.costo) * 100 AS DECIMAL(34,14)) / NULLIF(b.costo, 0), 2)
                                         AS margen_contado,
  ROUND(CAST((b.precio_credito - b.costo) * 100 AS DECIMAL(34,14)) / NULLIF(b.costo, 0), 2)
                                         AS margen_credito,
  b.precio_fijo_contado,
  b.precio_fijo_credito,
  CASE WHEN b.base_contado = 'SOBRE_PRECIO'
       THEN ROUND(CAST((b.factor_contado - 1) * 100 AS DECIMAL(34,14)) / (2 - b.factor_contado), 2)
       ELSE ROUND((b.factor_contado - 1) * 100, 2)
  END                                    AS porcentaje_contado,
  CASE WHEN b.base_credito = 'SOBRE_PRECIO'
       THEN ROUND(CAST((b.factor_credito - 1) * 100 AS DECIMAL(34,14)) / (2 - b.factor_credito), 2)
       ELSE ROUND((b.factor_credito - 1) * 100, 2)
  END                                    AS porcentaje_credito,
  b.regla_propia_contado,
  b.regla_propia_credito
FROM (
  SELECT
    p.producto_id,
    p.empresa_id,
    p.codigo,
    p.sku,
    p.nombre,
    p.presentacion,
    p.descripcion,
    p.codigo_barras,
    c.categoria_id,
    c.nombre                             AS categoria,
    pr.proveedor_id,
    pr.razon_social                      AS proveedor,
    p.costo,
    p.existencia,
    p.existencia_minima,
    p.estado_stock,
    -- Los dos únicos sitios donde se decide un precio. Sin ninguna regla,
    -- ninguna rama tiene con qué calcular y el resultado es NULL, nunca cero.
    CASE WHEN mc.precio_fijo IS NOT NULL THEN mc.precio_fijo
         WHEN mc.forma_calculo = 'SOBRE_PRECIO'
         THEN ROUND(p.costo / (2 - mc.factor), 0)
         ELSE ROUND(p.costo * mc.factor, 0)
    END                                  AS precio_contado,
    CASE WHEN mk.precio_fijo IS NOT NULL THEN mk.precio_fijo
         WHEN mk.forma_calculo = 'SOBRE_PRECIO'
         THEN ROUND(p.costo / (2 - mk.factor), 0)
         ELSE ROUND(p.costo * mk.factor, 0)
    END                                  AS precio_credito,
    CASE WHEN mc.precio_fijo IS NULL THEN mc.forma_calculo END AS base_contado,
    CASE WHEN mk.precio_fijo IS NULL THEN mk.forma_calculo END AS base_credito,
    mc.factor                            AS factor_contado,
    mk.factor                            AS factor_credito,
    mc.precio_fijo                       AS precio_fijo_contado,
    mk.precio_fijo                       AS precio_fijo_credito,
    COALESCE(mc.es_propia, 0)            AS regla_propia_contado,
    COALESCE(mk.es_propia, 0)            AS regla_propia_credito,
    ri.codigo_impuesto,
    ri.tratamiento,
    ri.umbral_uvt,
    ri.responsable_iva,
    ri.regla_iva_id,
    ri.tarifa
  FROM productos p
  JOIN categorias  c  ON c.categoria_id  = p.categoria_id
                     AND c.empresa_id    = p.empresa_id
  JOIN proveedores pr ON pr.proveedor_id = p.proveedor_id
                     AND pr.empresa_id   = p.empresa_id
  LEFT JOIN v_regla_margen mc ON mc.producto_id = p.producto_id
                             AND mc.empresa_id  = p.empresa_id
                             AND mc.forma_pago  = 'CONTADO'
  LEFT JOIN v_regla_margen mk ON mk.producto_id = p.producto_id
                             AND mk.empresa_id  = p.empresa_id
                             AND mk.forma_pago  = 'CREDITO'
  LEFT JOIN v_regla_iva    ri ON ri.producto_id = p.producto_id
                             AND ri.empresa_id  = p.empresa_id
  WHERE p.activo = 1
) AS b;
