-- =====================================================================
-- SGIP · Migración V38 · El precio se calcula solo para la empresa que pregunta
-- =====================================================================
--
-- Revisión final, I3. ADR-0029.
--
-- ## El problema
--
-- `v_regla_margen` (V36) y `v_regla_iva` (V34) eligen la regla que rige con un
-- ROW_NUMBER sobre todos los productos de la base. Una tabla derivada con una
-- función de ventana no se puede fusionar con la consulta que la usa, y
-- `v_precios_producto` la une con un LEFT JOIN: el filtro de empresa de
-- `ConsultaCatalogo` se queda fuera y el motor materializa las reglas de todas
-- las empresas en cada lectura. Medido con 200 productos en la empresa que se
-- consulta y 10.000 en otras cinco, con dos reglas propias por producto: leer un
-- producto pasaba de 12 ms a unos 780 ms, y el paso mayor del plan de unas mil
-- filas a más de setenta mil. Con este cambio, unos 2 ms. Añadir `empresa_id` al PARTITION BY no cambia nada:
-- el filtro llega por el JOIN, no por un WHERE sobre la vista.
--
-- ## Lo que cambia
--
-- Cada vista recorre `productos` y busca las reglas de cada producto en una
-- tabla derivada LATERAL que lee de ese producto la empresa, la categoría y el
-- proveedor. Sin función de ventana en el nivel de la vista, MySQL la fusiona en
-- `v_precios_producto`, y la búsqueda de reglas se hace solo para los productos
-- que la consulta ya acotó por empresa:
--
--   · reglas_margen, por `uq_reglas_margen_producto`
--     (empresa_id, producto_id = el producto OR producto_id IS NULL): las propias
--     del producto y las generales de su empresa, nunca las de otra.
--   · reglas_iva, por `fk_reglas_iva_producto_emp` con la misma forma.
--
-- No hace falta ningún índice nuevo: los dos existen y el plan los usa.
--
-- ## La precedencia no cambia
--
-- El orden es el mismo de la V36 y de la V34, columna por columna, y el
-- desempate final sigue siendo el identificador de la regla, de modo que la
-- regla elegida es la misma. En `v_regla_margen` el ROW_NUMBER sigue ahí, pero
-- dentro de la LATERAL y particionado solo por la forma de pago: las candidatas
-- ya son las de un producto. En `v_regla_iva`, donde hay una regla por
-- producto, basta ORDER BY … LIMIT 1. La candidata sigue siendo la misma:
-- vigente hoy, del ámbito del producto, y en el IVA con el tipo de impuesto
-- activo (el JOIN interno con `tipos_impuesto` va dentro de la LATERAL, igual
-- que la tabla anidada de la V34).
--
-- Comprobado sobre una copia con seis empresas, 10.500 productos y 21.000
-- reglas de toda clase (propias con porcentaje y con precio, de categoría, de
-- proveedor y de las dos, vencidas, futuras, empates de fecha, un tipo de
-- impuesto desactivado, una empresa sin `empresa_config`, una no responsable y
-- productos sin regla de IVA): las filas de las seis vistas son idénticas antes y
-- después, comparadas con EXCEPT ALL en los dos sentidos.
--
-- ## Las columnas
--
-- Nombres, orden y tipos de `v_precios_producto`, `v_regla_iva` y las demás
-- vistas no cambian. En `v_regla_margen`, `forma_pago` y `forma_calculo` dejan de
-- ser el VARCHAR que producía la unión de la V36 y vuelven a ser el ENUM de la
-- tabla, con los mismos valores; quien la lee (`RepositorioPrecios.reglaVigente`)
-- las recibe como texto igual que antes. `es_propia` se conserva BIGINT.
--
-- ## Lo que no se hace
--
-- `V36` y `V34` no se editan: ya están aplicadas. `v_precios_producto` no se
-- redefine: lee las dos vistas por su nombre y toma la definición nueva sola.
-- Nada se cachea: un precio debe verse en cuanto cambia (ADR-0029).
-- =====================================================================

SET NAMES utf8mb4;

CREATE OR REPLACE VIEW v_regla_margen AS
SELECT
  p.producto_id,
  p.empresa_id,
  c.forma_pago,
  c.factor,
  c.forma_calculo,
  c.regla_id,
  c.precio_fijo,
  c.es_propia
FROM productos p
JOIN LATERAL (
  SELECT
    r.forma_pago,
    r.factor,
    r.forma_calculo,
    r.regla_id,
    r.precio_fijo,
    CAST(r.producto_id IS NOT NULL AS SIGNED) AS es_propia,
    ROW_NUMBER() OVER (
      PARTITION BY r.forma_pago
      ORDER BY (r.producto_id IS NOT NULL) DESC,
               (r.categoria_id IS NOT NULL) + (r.proveedor_id IS NOT NULL) DESC,
               r.vigente_desde DESC,
               r.regla_id DESC
    ) AS prioridad
  FROM reglas_margen r
  WHERE r.empresa_id = p.empresa_id
    AND (r.producto_id = p.producto_id OR r.producto_id IS NULL)
    AND (r.categoria_id IS NULL OR r.categoria_id = p.categoria_id)
    AND (r.proveedor_id IS NULL OR r.proveedor_id = p.proveedor_id)
    AND CURDATE() >= r.vigente_desde
    AND (r.vigente_hasta IS NULL OR CURDATE() <= r.vigente_hasta)
) AS c
WHERE c.prioridad = 1;


CREATE OR REPLACE VIEW v_regla_iva AS
SELECT
  p.producto_id,
  p.empresa_id,
  c.tipo_impuesto_id,
  c.codigo                               AS codigo_impuesto,
  CASE
    WHEN COALESCE(ec.responsable_iva, FALSE) = FALSE THEN 'NO_GRAVADO'
    WHEN c.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
         AND COALESCE(ec.aplica_exclusion_umbral, FALSE) = FALSE THEN 'GRAVADO'
    ELSE c.tratamiento
  END                                    AS tratamiento,
  CASE
    WHEN COALESCE(ec.aplica_exclusion_umbral, FALSE) = FALSE THEN NULL
    ELSE c.umbral_uvt
  END                                    AS umbral_uvt,
  CASE
    WHEN COALESCE(ec.responsable_iva, FALSE) = FALSE
         OR c.regla_iva_id IS NULL THEN 0.0000
    ELSE c.tarifa
  END                                    AS tarifa,
  c.regla_iva_id,
  COALESCE(ec.responsable_iva, FALSE)    AS responsable_iva
FROM productos p
LEFT JOIN empresa_config ec ON ec.empresa_id = p.empresa_id
-- LEFT y ON TRUE: el producto sin regla aplicable sigue saliendo, con la tarifa
-- en cero, como desde la V34.
LEFT JOIN LATERAL (
  SELECT ti.tipo_impuesto_id, ti.codigo, ti.tratamiento, ti.umbral_uvt, ti.tarifa,
         ri.regla_iva_id
  FROM reglas_iva ri
  JOIN tipos_impuesto ti
    ON ti.tipo_impuesto_id = ri.tipo_impuesto_id
   AND ti.activo = TRUE
  WHERE ri.empresa_id = p.empresa_id
    AND (ri.producto_id = p.producto_id
         OR (ri.producto_id IS NULL
             AND (ri.categoria_id IS NULL OR ri.categoria_id = p.categoria_id)))
    AND CURDATE() >= ri.vigente_desde
    AND (ri.vigente_hasta IS NULL OR CURDATE() <= ri.vigente_hasta)
  ORDER BY (ri.producto_id IS NOT NULL) DESC,
           (ri.categoria_id IS NOT NULL) DESC,
           ri.vigente_desde DESC,
           ri.regla_iva_id DESC
  LIMIT 1
) AS c ON TRUE;
