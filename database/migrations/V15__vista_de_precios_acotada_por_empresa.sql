-- =====================================================================
-- SGIP · Migración V15 · La vista de precios se acota por empresa
-- =====================================================================
--
-- La vista v_precios_producto resolvía la imagen principal con una
-- subconsulta que no repetía el filtro de empresa:
--
--   (SELECT i.ruta FROM producto_imagenes i
--     WHERE i.producto_id = p.producto_id AND i.principal = TRUE)
--
-- Hoy esa subconsulta no puede devolver la imagen de otro comercio,
-- porque producto_id es único en toda la tabla y la clave foránea
-- compuesta de la V11 obliga a que la fila de imagen pertenezca a la
-- misma empresa que su producto. Es decir: la vista era correcta, pero
-- no por lo que decía, sino por una propiedad que vive en otra parte
-- del esquema y que nada obliga a conservar.
--
-- Ese es el defecto. Una consulta que depende de una invariante que no
-- enuncia se rompe en silencio el día que la invariante cambia, y el
-- cambio que la rompería es justamente hacia donde este esquema ha
-- venido moviéndose: si algún día producto_id pasara a ser único por
-- empresa en lugar de global —como ya ocurrió con las claves foráneas
-- en la V11 y con las restricciones de unicidad en la V10— la
-- subconsulta empezaría a devolver la imagen de otra empresa sin que
-- ninguna restricción se queje y sin que ninguna prueba falle.
--
-- La corrección es repetir el filtro. No solo en la subconsulta de la
-- imagen: también en los cuatro enlaces de la vista, que dependían de
-- la misma propiedad no enunciada. El costo es escribir empresa_id
-- cinco veces más; la ganancia es que la vista pasa a ser correcta por
-- lo que dice y no por lo que alguien recuerde del resto del esquema.
--
-- La migración no cambia ningún dato ni ninguna tabla: solo redefine la
-- vista. Sobre datos consistentes devuelve exactamente lo mismo que
-- antes, de modo que es segura de aplicar sobre una base en uso. La
-- comprobación que lo demuestra está en verificar_aislamiento.sh, y
-- falla si alguien vuelve a quitar el filtro.
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
  c.nombre                               AS categoria,
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
  -- El filtro de empresa es lo que esta migración añade. Sin él, la
  -- subconsulta solo acierta mientras producto_id siga siendo único en
  -- toda la tabla.
  (SELECT i.ruta FROM producto_imagenes i
    WHERE i.empresa_id  = p.empresa_id
      AND i.producto_id = p.producto_id
      AND i.principal   = TRUE)          AS imagen_principal
FROM productos p
-- Los cuatro enlaces repiten empresa_id por el mismo motivo: ninguno
-- debe depender de que la clave ajena sea única fuera de su empresa.
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
