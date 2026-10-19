-- =====================================================================
-- SGIP · Migración V20 · La vista de precios cierra la modalidad a
--                        crédito: total con impuesto y aviso de umbral
-- =====================================================================
--
-- La vista calculaba para las dos formas de pago el precio y la tarifa
-- —precio_contado, precio_credito, tarifa_contado, tarifa_credito— pero
-- el total con impuesto solo para una: total_contado. Quien vende a
-- crédito veía el precio sin impuesto y la tarifa por separado, y tenía
-- que multiplicar de cabeza.
--
-- Eso no es una incomodidad menor, porque en este modelo las dos
-- modalidades no comparten tratamiento tributario. El Art. 424 del
-- Estatuto Tributario excluye del impuesto los computadores personales
-- por debajo de 50 UVT, y el umbral se mira contra el precio de venta,
-- no contra el costo. Como el margen a crédito es mayor que el de
-- contado, hay una franja de costos en la que el mismo producto queda
-- excluido si se vende de contado y gravado si se vende a crédito.
--
-- Verificado sobre la base de demostración, con la UVT de 2026 en
-- 52.374 pesos (50 UVT = 2.618.700) y los márgenes generales de la
-- empresa 1, ambos SOBRE_COSTO, 1,20 contado y 1,25 crédito:
--
--   PC-001, costo 2.100.000
--     contado  2.520.000  ≤ 2.618.700  → tarifa 0,00  → total 2.520.000
--     crédito  2.625.000  > 2.618.700  → tarifa 0,19  → total 3.123.750
--
--   PC-003, costo 2.150.000
--     contado  2.580.000  ≤ 2.618.700  → tarifa 0,00  → total 2.580.000
--     crédito  2.687.500  > 2.618.700  → tarifa 0,19  → total 3.198.125
--
-- En PC-001 la diferencia entre las dos modalidades no es el 5 % del
-- margen: son 603.750 pesos, porque al cruzar el umbral se suma el 19 %.
-- Un vendedor que ofrezca el crédito mirando solo precio_credito se
-- queda corto en más de medio millón, y lo descubre al facturar.
--
-- Se añaden tres columnas:
--
--   total_credito           · el precio a crédito con su impuesto, para
--                             que la cifra que se ofrece sea la que se
--                             factura.
--   cerca_umbral_credito    · el aviso que ya existía para contado, que
--                             se enciende cuando el precio está en el
--                             último 10 % antes del umbral. Avisa de que
--                             un ajuste de costo o de margen cruza.
--   cruza_umbral_en_credito · el caso de arriba, nombrado: excluido de
--                             contado y gravado a crédito. Es la única
--                             de las tres que no se puede deducir
--                             mirando una sola columna, y es la que le
--                             sirve a la pantalla para explicar al
--                             cliente por qué el crédito cuesta más de
--                             lo que el porcentaje de interés sugiere.
--
-- La aritmética del impuesto se repite en lugar de referirse a la
-- columna tarifa_credito porque una vista no puede usar en una columna
-- el alias de otra. Repetir el CASE es lo que ya hacía total_contado.
--
-- La definición de partida es la de la V17.
-- =====================================================================

SET NAMES utf8mb4;

CREATE OR REPLACE VIEW v_precios_producto AS
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
  c.nombre                               AS categoria,
  pr.proveedor_id,
  pr.razon_social                        AS proveedor,
  p.costo,
  p.existencia,
  p.existencia_minima,
  p.estado_stock,
  ROUND(p.costo * mc.factor, 0)          AS precio_contado,
  ROUND(p.costo * mk.factor, 0)          AS precio_credito,
  ri.codigo_impuesto,
  ri.tratamiento                         AS tratamiento_iva,
  ri.umbral_uvt,
  ROUND(ri.umbral_uvt * fn_valor_uvt(CURDATE()), 2) AS umbral_pesos,
  CASE WHEN ri.regla_iva_id IS NULL THEN 1 ELSE 0 END AS tributacion_sin_configurar,
  CASE
    WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND ROUND(p.costo * mc.factor, 0)
             <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
    ELSE ri.tarifa
  END                                    AS tarifa_contado,
  CASE
    WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND ROUND(p.costo * mk.factor, 0)
             <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
    ELSE ri.tarifa
  END                                    AS tarifa_credito,
  ROUND(p.costo * mc.factor, 0) * (1 +
    CASE
      WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
      WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
           AND ROUND(p.costo * mc.factor, 0)
               <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
      ELSE ri.tarifa
    END)                                 AS total_contado,
  ROUND(p.costo * mk.factor, 0) * (1 +
    CASE
      WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
      WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
           AND ROUND(p.costo * mk.factor, 0)
               <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
      ELSE ri.tarifa
    END)                                 AS total_credito,
  CASE
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND ROUND(p.costo * mc.factor, 0)
             BETWEEN ri.umbral_uvt * fn_valor_uvt(CURDATE()) * 0.90
                 AND ri.umbral_uvt * fn_valor_uvt(CURDATE())
    THEN 1 ELSE 0
  END                                    AS cerca_umbral,
  CASE
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND ROUND(p.costo * mk.factor, 0)
             BETWEEN ri.umbral_uvt * fn_valor_uvt(CURDATE()) * 0.90
                 AND ri.umbral_uvt * fn_valor_uvt(CURDATE())
    THEN 1 ELSE 0
  END                                    AS cerca_umbral_credito,
  -- Excluido de contado y gravado a crédito: el salto de tarifa que el
  -- margen provoca por sí solo.
  CASE
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND ROUND(p.costo * mc.factor, 0)
             <= ri.umbral_uvt * fn_valor_uvt(CURDATE())
         AND ROUND(p.costo * mk.factor, 0)
             >  ri.umbral_uvt * fn_valor_uvt(CURDATE())
    THEN 1 ELSE 0
  END                                    AS cruza_umbral_en_credito,
  (SELECT i.ruta FROM producto_imagenes i
    WHERE i.empresa_id  = p.empresa_id
      AND i.producto_id = p.producto_id
      AND i.principal   = TRUE)          AS imagen_principal
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
WHERE p.activo = 1;


-- =====================================================================
-- Comprobación
-- =====================================================================
--   SELECT codigo, precio_contado, total_contado,
--          precio_credito, total_credito, cruza_umbral_en_credito
--     FROM v_precios_producto
--    WHERE empresa_id = 1 AND cruza_umbral_en_credito = 1;
--
-- Debe devolver PC-001 y PC-003 mientras la UVT y los márgenes de la
-- empresa 1 sigan como están. Si la UVT sube lo suficiente, los dos
-- salen de la franja y la consulta queda vacía: eso no es un fallo, es
-- el umbral moviéndose.
-- =====================================================================
