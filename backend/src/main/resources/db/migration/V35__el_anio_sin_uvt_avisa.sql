-- =====================================================================
-- SGIP · Migración V35 · Un año sin UVT avisa y no impide vender
-- =====================================================================
--
-- La UVT es opcional. Solo cambia el cálculo de una empresa: la que es
-- responsable de IVA y activó `aplica_exclusion_umbral`, porque es la única
-- cuyos productos pueden salir `EXCLUIDO_HASTA_UMBRAL` (la V12 trata ese
-- tipo como gravado para quien no activó la exclusión). A las demás, que
-- falte la UVT de un año no les cambia nada.
--
-- Sin la UVT del año en curso, `fn_valor_uvt(CURDATE())` devuelve NULL, la
-- comparación contra el umbral no se puede demostrar y la vista cobra la
-- tarifa: es el tratamiento conservador de la V5, y esta migración NO lo
-- cambia. Tampoco impide vender.
--
-- Lo que sí cambia es el aviso: `tributacion_sin_configurar` (V34) valía 1
-- solo para la responsable con un producto sin regla; ahora vale 1 también
-- para el producto que sale `EXCLUIDO_HASTA_UMBRAL` mientras falte la UVT
-- del año en curso. Es la misma clase de aviso —«falta un dato para que el
-- impuesto se calcule como la empresa espera»— y, como aquel, no bloquea.
--
-- ## v_precios_producto
--
-- Copia de la definición que dejó la V34, con un único cambio: la rama
-- nueva del CASE de `tributacion_sin_configurar`. Nombres, tipos y orden
-- de las columnas no cambian (el backend arranca con `ddl-auto: validate`)
-- y `v_regla_iva` no se toca.
--
-- Lo comprueba database/verificar_puesta_a_punto.sh: con la fila de la UVT
-- del año en curso borrada dentro de una transacción que se deshace, solo
-- el excluido hasta el umbral marca 1; tras el ROLLBACK, todos marcan 0.
-- =====================================================================

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
  CASE WHEN mc.forma_calculo = 'SOBRE_PRECIO'
       THEN ROUND(p.costo / (2 - mc.factor), 0)
       ELSE ROUND(p.costo * mc.factor, 0)
  END                                    AS precio_contado,
  CASE WHEN mk.forma_calculo = 'SOBRE_PRECIO'
       THEN ROUND(p.costo / (2 - mk.factor), 0)
       ELSE ROUND(p.costo * mk.factor, 0)
  END                                    AS precio_credito,
  mc.forma_calculo                       AS base_contado,
  mk.forma_calculo                       AS base_credito,
  mc.factor                              AS factor_contado,
  mk.factor                              AS factor_credito,
  ri.codigo_impuesto,
  ri.tratamiento                         AS tratamiento_iva,
  ri.umbral_uvt,
  ROUND(ri.umbral_uvt * fn_valor_uvt(CURDATE()), 2) AS umbral_pesos,
  CASE
    WHEN ri.responsable_iva = TRUE AND ri.regla_iva_id IS NULL THEN 1
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND fn_valor_uvt(CURDATE()) IS NULL THEN 1
    ELSE 0
  END                                    AS tributacion_sin_configurar,
  CASE
    WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND (CASE WHEN mc.forma_calculo = 'SOBRE_PRECIO'
                   THEN ROUND(p.costo / (2 - mc.factor), 0)
                   ELSE ROUND(p.costo * mc.factor, 0) END)
             <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
    ELSE COALESCE(ri.tarifa, 0)
  END                                    AS tarifa_contado,
  CASE
    WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND (CASE WHEN mk.forma_calculo = 'SOBRE_PRECIO'
                   THEN ROUND(p.costo / (2 - mk.factor), 0)
                   ELSE ROUND(p.costo * mk.factor, 0) END)
             <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
    ELSE COALESCE(ri.tarifa, 0)
  END                                    AS tarifa_credito,
  (CASE WHEN mc.forma_calculo = 'SOBRE_PRECIO'
        THEN ROUND(p.costo / (2 - mc.factor), 0)
        ELSE ROUND(p.costo * mc.factor, 0) END) * (1 +
    CASE
      WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
      WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
           AND (CASE WHEN mc.forma_calculo = 'SOBRE_PRECIO'
                     THEN ROUND(p.costo / (2 - mc.factor), 0)
                     ELSE ROUND(p.costo * mc.factor, 0) END)
               <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
      ELSE COALESCE(ri.tarifa, 0)
    END)                                 AS total_contado,
  (CASE WHEN mk.forma_calculo = 'SOBRE_PRECIO'
        THEN ROUND(p.costo / (2 - mk.factor), 0)
        ELSE ROUND(p.costo * mk.factor, 0) END) * (1 +
    CASE
      WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
      WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
           AND (CASE WHEN mk.forma_calculo = 'SOBRE_PRECIO'
                     THEN ROUND(p.costo / (2 - mk.factor), 0)
                     ELSE ROUND(p.costo * mk.factor, 0) END)
               <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
      ELSE COALESCE(ri.tarifa, 0)
    END)                                 AS total_credito,
  CASE
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND (CASE WHEN mc.forma_calculo = 'SOBRE_PRECIO'
                   THEN ROUND(p.costo / (2 - mc.factor), 0)
                   ELSE ROUND(p.costo * mc.factor, 0) END)
             BETWEEN ri.umbral_uvt * fn_valor_uvt(CURDATE()) * 0.90
                 AND ri.umbral_uvt * fn_valor_uvt(CURDATE())
    THEN 1 ELSE 0
  END                                    AS cerca_umbral,
  CASE
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND (CASE WHEN mk.forma_calculo = 'SOBRE_PRECIO'
                   THEN ROUND(p.costo / (2 - mk.factor), 0)
                   ELSE ROUND(p.costo * mk.factor, 0) END)
             BETWEEN ri.umbral_uvt * fn_valor_uvt(CURDATE()) * 0.90
                 AND ri.umbral_uvt * fn_valor_uvt(CURDATE())
    THEN 1 ELSE 0
  END                                    AS cerca_umbral_credito,
  CASE
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND (CASE WHEN mc.forma_calculo = 'SOBRE_PRECIO'
                   THEN ROUND(p.costo / (2 - mc.factor), 0)
                   ELSE ROUND(p.costo * mc.factor, 0) END)
             <= ri.umbral_uvt * fn_valor_uvt(CURDATE())
         AND (CASE WHEN mk.forma_calculo = 'SOBRE_PRECIO'
                   THEN ROUND(p.costo / (2 - mk.factor), 0)
                   ELSE ROUND(p.costo * mk.factor, 0) END)
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
