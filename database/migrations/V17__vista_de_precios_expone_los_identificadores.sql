-- =====================================================================
-- SGIP · Migración V17 · La vista de precios expone los identificadores
--                        de categoría y proveedor
-- =====================================================================
--
-- La vista exponía el nombre de la categoría y la razón social del
-- proveedor, que es lo que la pantalla muestra, pero no sus
-- identificadores. Eso dejaba fuera dos cosas que el RF-16 y el RF-15
-- necesitan.
--
-- La primera son los filtros del catálogo. Filtrar por nombre obliga a
-- comparar cadenas, que es frágil —acentos, mayúsculas, un cambio de
-- nombre— y además no distingue dos categorías homónimas de empresas
-- distintas si alguna consulta olvidara acotar. Con el identificador el
-- filtro es exacto.
--
-- La segunda es la pantalla de edición. Para preseleccionar la
-- categoría y el proveedor actuales en sus listas desplegables hace
-- falta el identificador: con el nombre habría que buscarlo de vuelta,
-- y dos categorías que se llamen parecido volverían ambigua esa
-- búsqueda.
--
-- La definición es la de la V15 con dos columnas más. Se conservan los
-- filtros de empresa que aquella añadió, en la subconsulta de imagen y
-- en los cuatro enlaces.
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
  CASE
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND ROUND(p.costo * mc.factor, 0)
             BETWEEN ri.umbral_uvt * fn_valor_uvt(CURDATE()) * 0.90
                 AND ri.umbral_uvt * fn_valor_uvt(CURDATE())
    THEN 1 ELSE 0
  END                                    AS cerca_umbral,
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
