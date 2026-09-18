-- =====================================================================
-- SGIP · Migración V6 · Vistas de consulta
--
-- Los precios de venta se resuelven aquí y no como columnas generadas
-- de la tabla productos, porque dependen de reglas_margen y de
-- reglas_iva, que son tablas distintas. MySQL solo admite columnas
-- generadas que dependan de columnas de la misma fila.
-- =====================================================================

-- ---------------------------------------------------------------------
-- v_regla_margen · resuelve la precedencia entre reglas de margen.
-- Prevalece la de ámbito más específico: categoría y proveedor, luego
-- categoría, luego proveedor, luego general. A igual especificidad,
-- la de vigencia más reciente.
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_regla_margen AS
SELECT producto_id, forma_pago, factor, regla_id
FROM (
  SELECT
    p.producto_id,
    r.forma_pago,
    r.factor,
    r.regla_id,
    ROW_NUMBER() OVER (
      PARTITION BY p.producto_id, r.forma_pago
      ORDER BY (r.categoria_id IS NOT NULL) + (r.proveedor_id IS NOT NULL) DESC,
               r.vigente_desde DESC,
               r.regla_id DESC
    ) AS prioridad
  FROM productos p
  JOIN reglas_margen r
    ON (r.categoria_id IS NULL OR r.categoria_id = p.categoria_id)
   AND (r.proveedor_id IS NULL OR r.proveedor_id = p.proveedor_id)
   AND CURDATE() >= r.vigente_desde
   AND (r.vigente_hasta IS NULL OR CURDATE() <= r.vigente_hasta)
) AS ranking
WHERE prioridad = 1;

-- ---------------------------------------------------------------------
-- v_regla_iva · resuelve la regla tributaria aplicable a cada producto.
-- La regla con categoría prevalece sobre la regla general.
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_regla_iva AS
SELECT producto_id, tratamiento, umbral_uvt, tarifa, regla_iva_id
FROM (
  SELECT
    p.producto_id,
    ri.tratamiento,
    ri.umbral_uvt,
    ri.tarifa,
    ri.regla_iva_id,
    ROW_NUMBER() OVER (
      PARTITION BY p.producto_id
      ORDER BY (ri.categoria_id IS NOT NULL) DESC,
               ri.vigente_desde DESC,
               ri.regla_iva_id DESC
    ) AS prioridad
  FROM productos p
  JOIN reglas_iva ri
    ON (ri.categoria_id IS NULL OR ri.categoria_id = p.categoria_id)
   AND CURDATE() >= ri.vigente_desde
   AND (ri.vigente_hasta IS NULL OR CURDATE() <= ri.vigente_hasta)
) AS ranking
WHERE prioridad = 1;

-- ---------------------------------------------------------------------
-- v_precios_producto · precio de venta y tratamiento tributario
-- vigentes de cada referencia activa.
--
-- cerca_umbral marca las referencias cuyo precio queda dentro del diez
-- por ciento inferior al tope de exclusión. Son las candidatas a que la
-- empresa ajuste el margen para no cruzarlo, porque superar el umbral
-- añade diecinueve puntos al precio final de un golpe.
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_precios_producto AS
SELECT
  p.producto_id,
  p.codigo,
  p.nombre,
  c.nombre                               AS categoria,
  pr.razon_social                        AS proveedor,
  p.costo,
  p.existencia,
  p.existencia_minima,
  p.estado_stock,
  ROUND(p.costo * mc.factor, 0)          AS precio_contado,
  ROUND(p.costo * mk.factor, 0)          AS precio_credito,
  ri.tratamiento                         AS tratamiento_iva,
  ri.umbral_uvt,
  ROUND(ri.umbral_uvt * fn_valor_uvt(CURDATE()), 2) AS umbral_pesos,
  CASE
    WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO') THEN 0
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND ROUND(p.costo * mc.factor, 0)
             <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
    ELSE ri.tarifa
  END                                    AS tarifa_contado,
  CASE
    WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO') THEN 0
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND ROUND(p.costo * mk.factor, 0)
             <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
    ELSE ri.tarifa
  END                                    AS tarifa_credito,
  ROUND(p.costo * mc.factor, 0) * (1 +
    CASE
      WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO') THEN 0
      WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
           AND ROUND(p.costo * mc.factor, 0)
               <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
      ELSE ri.tarifa
    END)                                 AS total_contado,
  CASE
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND ROUND(p.costo * mc.factor, 0)
             BETWEEN ri.umbral_uvt * fn_valor_uvt(CURDATE()) * 0.90
                 AND ri.umbral_uvt * fn_valor_uvt(CURDATE())
    THEN 1 ELSE 0
  END                                    AS cerca_umbral
FROM productos p
JOIN categorias  c  ON c.categoria_id  = p.categoria_id
JOIN proveedores pr ON pr.proveedor_id = p.proveedor_id
LEFT JOIN v_regla_margen mc ON mc.producto_id = p.producto_id
                           AND mc.forma_pago  = 'CONTADO'
LEFT JOIN v_regla_margen mk ON mk.producto_id = p.producto_id
                           AND mk.forma_pago  = 'CREDITO'
LEFT JOIN v_regla_iva    ri ON ri.producto_id = p.producto_id
WHERE p.activo = 1;

-- ---------------------------------------------------------------------
-- v_existencias_criticas
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_existencias_criticas AS
SELECT
  p.producto_id,
  p.codigo,
  p.nombre,
  c.nombre         AS categoria,
  pr.razon_social  AS proveedor,
  pr.telefono      AS contacto_proveedor,
  p.existencia,
  p.existencia_minima,
  p.estado_stock,
  p.costo,
  (SELECT MAX(m.fecha) FROM movimientos_inventario m
    WHERE m.producto_id = p.producto_id AND m.tipo = 'INGRESO') AS ultimo_ingreso
FROM productos p
JOIN categorias  c  ON c.categoria_id  = p.categoria_id
JOIN proveedores pr ON pr.proveedor_id = p.proveedor_id
WHERE p.activo = 1
  AND p.estado_stock IN ('CRITICO', 'AGOTADO');

-- ---------------------------------------------------------------------
-- v_inventario_valorizado · existencias al costo y al precio de venta
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_inventario_valorizado AS
SELECT
  v.producto_id,
  v.codigo,
  v.nombre,
  v.categoria,
  v.existencia,
  v.costo,
  v.precio_contado,
  v.existencia * v.costo            AS valor_al_costo,
  v.existencia * v.precio_contado   AS valor_a_precio_venta,
  v.existencia * (v.precio_contado - v.costo) AS utilidad_potencial
FROM v_precios_producto v;

-- ---------------------------------------------------------------------
-- v_rotacion_inventario · capital retenido en referencias sin salida
-- durante los últimos noventa días.
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_rotacion_inventario AS
SELECT
  p.producto_id,
  p.codigo,
  p.nombre,
  c.nombre     AS categoria,
  p.existencia,
  p.costo,
  p.existencia * p.costo AS capital_retenido,
  COALESCE(s.unidades_vendidas, 0) AS unidades_vendidas_90d,
  DATEDIFF(CURDATE(), COALESCE(s.ultima_salida, p.creado_en)) AS dias_sin_salida
FROM productos p
JOIN categorias c ON c.categoria_id = p.categoria_id
LEFT JOIN (
  SELECT
    m.producto_id,
    SUM(m.cantidad)  AS unidades_vendidas,
    MAX(m.fecha)     AS ultima_salida
  FROM movimientos_inventario m
  WHERE m.tipo = 'EGRESO'
    AND m.fecha >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
  GROUP BY m.producto_id
) AS s ON s.producto_id = p.producto_id
WHERE p.activo = 1;
