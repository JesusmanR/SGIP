-- =====================================================================
-- SGIP · Migración V25 · El precio respeta la forma de cálculo de su
--                        regla
-- =====================================================================
--
-- El RF-32 pide que la regla de ganancia se pueda expresar de dos
-- maneras, y da el ejemplo que la define:
--
--   «Un costo de 1 000 con recargo del 20 % da 1 200; el mismo costo con
--    margen del 20 % sobre el precio da 1 250.»
--
-- La V10 preparó la mitad del terreno: añadió
-- `reglas_margen.forma_calculo` con las dos opciones y un comentario que
-- lo explica bien —«un veinte por ciento sobre el costo no es un veinte
-- por ciento sobre el precio de venta»—. Y `v_regla_margen` expone la
-- columna.
--
-- La otra mitad no se hizo. `v_precios_producto` multiplica el costo por
-- el factor y punto, sin mirar la forma de cálculo. Reproducido sobre
-- una base con la cadena V1 a V24 aplicada: con una regla SOBRE_PRECIO
-- del 20 % y un costo de 1 000, la vista devuelve **1 200**, que es la
-- respuesta de la otra base. El dato se guarda y no se usa.
--
-- Es el mismo patrón que la V23 encontró con `motivo`: una columna que
-- entra en una migración, nadie la conecta, y el requisito que la pidió
-- queda incumplido mientras el esquema aparenta cumplirlo.
--
--
-- ## Qué significa `factor`
--
-- Lo que se guarda es **uno más el porcentaje**, para las dos bases.
-- Quien configura la regla escribe «20 %» y la columna queda en 1,2000,
-- da igual la base; lo que cambia es la fórmula que la aplica:
--
--   SOBRE_COSTO   precio = costo × factor
--   SOBRE_PRECIO  precio = costo ÷ (2 − factor)
--
-- Comprobación con el ejemplo del requisito, costo 1 000 y factor 1,2000:
--
--   SOBRE_COSTO   1 000 × 1,20     = 1 200 ✓
--   SOBRE_PRECIO  1 000 ÷ (2−1,20) = 1 000 ÷ 0,80 = 1 250 ✓
--
-- La división sale de despejar el margen sobre el precio de venta: si el
-- margen es m sobre el precio p y el costo es c, entonces p − c = m·p, de
-- donde p = c ÷ (1 − m). Y como el factor guarda 1 + m, resulta
-- 1 − m = 2 − factor.
--
-- Se mantiene esta convención en lugar de guardar el porcentaje a secas
-- porque la restricción `ck_reglas_margen_factor` ya exige `factor > 1` y
-- los datos existentes están escritos así. Cambiar el significado de una
-- columna poblada es la clase de migración que deja dos interpretaciones
-- vivas; aquí la convención sigue siendo una sola y queda escrita.
--
--
-- ## El tope del margen sobre el precio
--
-- Un margen del 100 % sobre el precio de venta no existe: significaría
-- que el precio es infinito, porque el costo tendría que ser cero. En la
-- fórmula se ve solo: con factor 2 el divisor es cero.
--
-- Hasta ahora nada lo impedía, de modo que una regla SOBRE_PRECIO con
-- factor 2 habría producido una división por cero —que MySQL resuelve
-- devolviendo nulo, y un precio nulo recorre el catálogo entero sin que
-- nadie sepa de dónde salió—. `ck_reglas_margen_sobre_precio` lo cierra.
--
-- El recargo sobre el costo no lleva tope: un factor de 3 es un recargo
-- del 200 %, que es caro pero no es imposible.
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. El margen sobre el precio no llega al 100 %
-- ---------------------------------------------------------------------
ALTER TABLE reglas_margen
  ADD CONSTRAINT ck_reglas_margen_sobre_precio CHECK (
    forma_calculo <> 'SOBRE_PRECIO' OR factor < 2);


-- ---------------------------------------------------------------------
-- 2. La vista de precios aplica la fórmula que la regla declara
-- ---------------------------------------------------------------------
-- La definición de partida es la de la V20, con las dos expresiones de
-- precio —contado y crédito— sustituidas por su forma condicionada, y el
-- resto intacto: los filtros de empresa de la V15, los identificadores
-- de la V17 y las columnas de crédito de la V20.
--
-- La forma de cálculo se expone además como columna, porque el RF-32
-- pide que «la interfaz indique cuál de las dos bases está aplicando» y
-- la pantalla no tiene cómo saberlo si no se le dice: dos productos con
-- el mismo precio pueden venir de reglas distintas.

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
  CASE WHEN ri.regla_iva_id IS NULL THEN 1 ELSE 0 END AS tributacion_sin_configurar,
  CASE
    WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND (CASE WHEN mc.forma_calculo = 'SOBRE_PRECIO'
                   THEN ROUND(p.costo / (2 - mc.factor), 0)
                   ELSE ROUND(p.costo * mc.factor, 0) END)
             <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
    ELSE ri.tarifa
  END                                    AS tarifa_contado,
  CASE
    WHEN ri.tratamiento IN ('EXCLUIDO', 'EXENTO', 'NO_GRAVADO') THEN 0
    WHEN ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND (CASE WHEN mk.forma_calculo = 'SOBRE_PRECIO'
                   THEN ROUND(p.costo / (2 - mk.factor), 0)
                   ELSE ROUND(p.costo * mk.factor, 0) END)
             <= ri.umbral_uvt * fn_valor_uvt(CURDATE()) THEN 0
    ELSE ri.tarifa
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
      ELSE ri.tarifa
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
      ELSE ri.tarifa
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


-- =====================================================================
-- Comprobación
-- =====================================================================
-- El ejemplo del RF-32, con un producto de costo 1 000:
--
--   regla CONTADO SOBRE_COSTO  factor 1,2000 → precio_contado 1 200
--   regla CONTADO SOBRE_PRECIO factor 1,2000 → precio_contado 1 250
--
-- Y debe quedar rechazado:
--
--   UPDATE reglas_margen SET forma_calculo='SOBRE_PRECIO', factor=2.0000
--     → ck_reglas_margen_sobre_precio
--
-- El guion database/verificar_precios.sh comprueba las dos bases, el
-- tope, la regla más específica del RN-03 y las tres referencias del
-- RF-34 —justo debajo, justo en y justo encima del umbral—.
-- =====================================================================
