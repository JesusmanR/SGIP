-- =====================================================================
-- SGIP · Migración V34 · Los impuestos son opcionales
-- =====================================================================
--
-- Muchos comercios pequeños no están obligados a cobrar impuestos sobre
-- las ventas, y hasta ahora el sistema los trataba como si lo estuvieran.
-- Una empresa recién creada no tiene ninguna regla de IVA (nadie eligió
-- todavía) y `v_regla_iva` solo devolvía filas para productos CON regla.
-- El producto sin regla salía de `v_precios_producto` sin tratamiento,
-- sin tarifa y con el total vacío, y la RN-25 lo leía como «todavía no se
-- puede vender». Para una tienda que no tiene que cobrar impuestos eso es
-- un bloqueo sin motivo.
--
-- Desde esta migración una empresa vende sin haber configurado nada. El
-- ADR-0024 enmienda al ADR-0016 y explica la decisión; esto es lo que
-- cambia en el esquema.
--
--
-- ## v_regla_iva
--
-- Devuelve una fila por producto, también cuando no hay regla (LEFT JOIN),
-- y expone `responsable_iva` para que la vista de precios decida el aviso
-- sin volver a leer `empresa_config`.
--
--   · La empresa que no es responsable de IVA, con o sin regla: NO_GRAVADO,
--     tarifa 0. Es lo que la V12 ya hacía cuando había regla; ahora lo hace
--     también cuando no la hay.
--   · La responsable sin regla aplicable: tratamiento NULL y tarifa 0.
--     `codigo_impuesto`, `tipo_impuesto_id` y `regla_iva_id` son NULL: no
--     hay nada que nombrar. La tarifa es 0 y no NULL a propósito, para que
--     ningún total dependa de un NULL que alguien olvide mirar.
--
-- Una regla cuyo tipo de impuesto la plataforma desactivó se sigue
-- ignorando, y ahora hay que decirlo en el JOIN y no en la condición de una
-- tabla aparte: `LEFT JOIN (reglas_iva JOIN tipos_impuesto …)` es una sola
-- tabla anidada. Con dos LEFT JOIN sueltos, la regla de un producto con
-- tipo inactivo ganaría el orden con el tipo en NULL y taparía la regla
-- general válida; con la tabla anidada no existe como candidata, igual que
-- con el JOIN interno de la V12.
--
-- Las condiciones de vigencia de la regla siguen en el ON. En un WHERE
-- volverían a dejar fuera a los productos sin regla.
--
-- Las columnas que ya había conservan nombre y tipo; `responsable_iva` va
-- al final. El backend no lee esta vista directamente: la lee
-- `v_precios_producto`.
--
--
-- ## v_precios_producto
--
-- Copia de la definición de la V25, con estos únicos cambios:
--
--   1. `tributacion_sin_configurar` vale 1 solo cuando la empresa es
--      responsable de IVA y el producto no tiene regla. Es un aviso: no
--      impide vender. Para quien no es responsable no hay nada que
--      configurar y vale 0.
--   2. Sin regla, la tarifa y los totales no dependen de un NULL: el
--      `ELSE ri.tarifa` de las cuatro columnas que lo usan pasa a
--      `COALESCE(ri.tarifa, 0)`. El total sin regla es igual al precio. La
--      única cosa que sigue dejando un precio en NULL es no tener regla de
--      ganancia, que es otra pregunta y ya era así.
--
-- Nombres y tipos de las columnas no cambian: `ConsultaCatalogo`,
-- `RepositorioPrecios`, `RepositorioImportacion` y `RepositorioPanel` la
-- leen, y el backend arranca con `ddl-auto: validate`.
--
--
-- ## Las líneas de venta y de cotización
--
-- `venta_items.tratamiento_iva` y `cotizacion_items.tratamiento_iva` (V4)
-- eran ENUM('GRAVADO','EXCLUIDO','EXENTO'). La vista da NO_GRAVADO a la
-- empresa que no es responsable de IVA, y esa es justo la que ahora vende
-- sin configurar nada: la primera venta habría fallado al guardar la línea
-- con "Data truncated". Se añade NO_GRAVADO al final de los dos tipos, que
-- en MySQL es un cambio de metadatos que no reescribe la tabla. Se
-- conservan NOT NULL y el valor por omisión GRAVADO. Se comprobó antes que
-- ningún disparador, vista, restricción ni columna generada depende de
-- estas columnas (`subtotal` es cantidad por precio unitario).
--
-- Cuando la vista da tratamiento NULL —empresa responsable sin regla—, el
-- punto de venta no tiene qué copiar: guarda NO_GRAVADO, porque no se
-- cobró impuesto. Ver el ADR-0024.
--
--
-- ## Una empresa sin fila en `empresa_config`
--
-- `v_regla_iva` ya no pierde los productos de una empresa a la que le falta
-- su fila de configuración: el JOIN es LEFT y esa empresa se trata como no
-- responsable y sin exclusión por umbral (COALESCE a FALSE). El alta crea la
-- fila siempre, de modo que esto es una red de seguridad y no un caso
-- esperado.
--
--
-- ## Lo que no cambia
--
-- `tratamiento_iva`, `tarifa_*` y `total_*` de los productos CON regla son
-- los de siempre, y `cerca_umbral*` y `cruza_umbral_en_credito` también:
-- un tratamiento NULL no es EXCLUIDO_HASTA_UMBRAL. Esta migración no toca
-- datos de `productos`, de modo que no sube `version` (V32).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. v_regla_iva devuelve una fila por producto
-- ---------------------------------------------------------------------
-- La definición de partida es la de la V12, con tres cambios: el LEFT JOIN
-- anidado, la tarifa cero cuando no hay regla, y `responsable_iva`.
CREATE OR REPLACE VIEW v_regla_iva AS
SELECT producto_id, empresa_id, tipo_impuesto_id, codigo_impuesto,
       tratamiento, umbral_uvt, tarifa, regla_iva_id, responsable_iva
FROM (
  SELECT
    p.producto_id,
    p.empresa_id,
    ti.tipo_impuesto_id,
    ti.codigo AS codigo_impuesto,
    CASE
      WHEN COALESCE(ec.responsable_iva, FALSE) = FALSE THEN 'NO_GRAVADO'
      WHEN ti.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
           AND COALESCE(ec.aplica_exclusion_umbral, FALSE) = FALSE THEN 'GRAVADO'
      ELSE ti.tratamiento
    END AS tratamiento,
    CASE
      WHEN COALESCE(ec.aplica_exclusion_umbral, FALSE) = FALSE THEN NULL
      ELSE ti.umbral_uvt
    END AS umbral_uvt,
    CASE
      WHEN COALESCE(ec.responsable_iva, FALSE) = FALSE
           OR ri.regla_iva_id IS NULL THEN 0.0000
      ELSE ti.tarifa
    END AS tarifa,
    ri.regla_iva_id,
    COALESCE(ec.responsable_iva, FALSE) AS responsable_iva,
    ROW_NUMBER() OVER (
      PARTITION BY p.producto_id
      ORDER BY (ri.producto_id IS NOT NULL) DESC,
               (ri.categoria_id IS NOT NULL) DESC,
               ri.vigente_desde DESC,
               ri.regla_iva_id DESC
    ) AS prioridad
  FROM productos p
  LEFT JOIN empresa_config ec ON ec.empresa_id = p.empresa_id
  LEFT JOIN (reglas_iva ri
             JOIN tipos_impuesto ti
               ON ti.tipo_impuesto_id = ri.tipo_impuesto_id
              AND ti.activo = TRUE)
    ON ri.empresa_id = p.empresa_id
   AND (ri.producto_id = p.producto_id
        OR (ri.producto_id IS NULL
            AND (ri.categoria_id IS NULL OR ri.categoria_id = p.categoria_id)))
   AND CURDATE() >= ri.vigente_desde
   AND (ri.vigente_hasta IS NULL OR CURDATE() <= ri.vigente_hasta)
) AS ranking
WHERE prioridad = 1;


-- ---------------------------------------------------------------------
-- 2. v_precios_producto: el aviso solo para el responsable, y el total
--    nunca depende de un NULL
-- ---------------------------------------------------------------------
-- La definición de partida es la de la V25 (ninguna de la V26 a la V33 la
-- redefine). Los cambios están enumerados arriba.

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
  CASE WHEN ri.responsable_iva = TRUE AND ri.regla_iva_id IS NULL
       THEN 1 ELSE 0 END                 AS tributacion_sin_configurar,
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


-- ---------------------------------------------------------------------
-- 3. Las líneas de venta y de cotización admiten NO_GRAVADO
-- ---------------------------------------------------------------------
-- El valor nuevo va al final: los existentes conservan su posición.
ALTER TABLE venta_items
  MODIFY tratamiento_iva ENUM('GRAVADO','EXCLUIDO','EXENTO','NO_GRAVADO')
         NOT NULL DEFAULT 'GRAVADO';

ALTER TABLE cotizacion_items
  MODIFY tratamiento_iva ENUM('GRAVADO','EXCLUIDO','EXENTO','NO_GRAVADO')
         NOT NULL DEFAULT 'GRAVADO';


-- =====================================================================
-- Comprobación
-- =====================================================================
-- Con un producto de costo 1 000 y una regla de ganancia de 1,5 (precio
-- 1 500):
--
--   empresa          regla            tratamiento  sin_configurar  total
--   no responsable   ninguna          NO_GRAVADO   0               1 500
--   responsable      ninguna          NULL         1               1 500
--   responsable      general IVA_19   GRAVADO      0               1 785
--
-- Las comprueba el guion database/verificar_puesta_a_punto.sh, junto con
-- la regla de un producto sobre la general, el tipo inactivo que no tapa a
-- la general y la regla vencida. verificar_tributacion.sh comprueba el
-- primer caso con una empresa recién creada.
-- =====================================================================
