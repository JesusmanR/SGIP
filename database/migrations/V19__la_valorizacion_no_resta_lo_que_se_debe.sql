-- =====================================================================
-- SGIP · Migración V19 · La valorización no resta lo que se debe reponer
-- =====================================================================
--
-- Desde la V9 un producto puede quedar con existencia negativa: es una
-- venta comprometida sin existencia disponible (RF-42). Las dos vistas
-- que valorizan el inventario se escribieron antes de esa posibilidad y
-- multiplican la existencia tal cual:
--
--   v_inventario_valorizado:  v.existencia * v.costo  AS valor_al_costo
--   v_rotacion_inventario:    p.existencia * p.costo  AS capital_retenido
--
-- Con existencia negativa eso no da un número pequeño: da un número con
-- el signo invertido, y como el tablero suma la columna para obtener el
-- valor de la bodega, cada referencia en negativo RESTA del valor de las
-- demás. Medido sobre la base de demostración con PC-001 en -2:
--
--   valor_al_costo de PC-001 ......................  -4.200.000
--   total de la bodega tal como está ..............  59.150.000
--   total con la existencia acotada a cero ........  63.350.000
--
-- Son 4,2 millones de diferencia por una sola referencia, y en la
-- dirección que peor se detecta: el tablero no muestra un error, muestra
-- una cifra plausible y más baja. Nadie la va a cuestionar.
--
-- La corrección tiene dos mitades, porque acotar a cero sin más
-- escondería la otra cara del dato.
--
-- Primera mitad: lo que se valoriza es lo que hay. No existe media
-- bodega negativa; una existencia de -2 significa que hay cero unidades
-- y se deben dos. GREATEST(existencia, 0) deja la valorización contando
-- unidades físicas, que es la pregunta que la vista responde.
--
-- Segunda mitad: lo que se debe no desaparece del tablero. Si solo se
-- acotara a cero, el compromiso de reponer dos unidades de un producto
-- de dos millones se volvería invisible justo en la pantalla donde
-- alguien decide qué comprar. Por eso se añaden dos columnas que lo
-- nombran aparte, sin mezclarlo con el valor del inventario:
--
--   unidades_pendientes      · cuántas unidades se deben (0 si no se debe)
--   valor_pendiente_al_costo · cuánto cuesta reponerlas
--
-- Así la suma de valor_al_costo es el valor de la bodega y la suma de
-- valor_pendiente_al_costo es la obligación pendiente. Dos preguntas
-- distintas en dos columnas distintas, en lugar de una columna que
-- intentaba responder las dos y no respondía ninguna.
--
-- La columna existencia se deja intacta, con su signo. Es el dato real y
-- el tablero necesita poder mostrar -2; lo que se corrige es la
-- aritmética que lo convierte en pesos.
--
-- De paso, los enlaces de v_rotacion_inventario repiten el filtro de
-- empresa, como la V15 estableció para la vista de precios. El enlace a
-- categorias y la subconsulta de movimientos cruzaban solo por
-- identificador. Hoy eso no filtra mal, porque categoria_id y
-- producto_id siguen siendo claves primarias globales, pero depende de
-- que sigan siéndolo: la V11 añadió las claves compuestas por empresa
-- precisamente para que ningún cruce dependa de ese detalle.
--
-- La definición de partida es la de la V12, que fue la última que
-- recreó ambas vistas.
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- v_inventario_valorizado
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_inventario_valorizado AS
SELECT
  v.producto_id,
  v.empresa_id,
  v.codigo,
  v.nombre,
  v.presentacion,
  v.categoria,
  v.existencia,
  v.costo,
  v.precio_contado,
  GREATEST(v.existencia, 0) * v.costo            AS valor_al_costo,
  GREATEST(v.existencia, 0) * v.precio_contado   AS valor_a_precio_venta,
  GREATEST(v.existencia, 0) * (v.precio_contado - v.costo)
                                                 AS utilidad_potencial,
  GREATEST(-v.existencia, 0)                     AS unidades_pendientes,
  GREATEST(-v.existencia, 0) * v.costo           AS valor_pendiente_al_costo
FROM v_precios_producto v;

-- ---------------------------------------------------------------------
-- v_rotacion_inventario
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_rotacion_inventario AS
SELECT
  p.producto_id,
  p.empresa_id,
  p.codigo,
  p.nombre,
  p.presentacion,
  c.nombre     AS categoria,
  p.existencia,
  p.costo,
  GREATEST(p.existencia, 0) * p.costo  AS capital_retenido,
  GREATEST(-p.existencia, 0)           AS unidades_pendientes,
  GREATEST(-p.existencia, 0) * p.costo AS valor_pendiente_al_costo,
  COALESCE(s.unidades_vendidas, 0)     AS unidades_vendidas_90d,
  DATEDIFF(CURDATE(), COALESCE(s.ultima_salida, p.creado_en)) AS dias_sin_salida
FROM productos p
JOIN categorias c ON c.categoria_id = p.categoria_id
                 AND c.empresa_id   = p.empresa_id
LEFT JOIN (
  SELECT
    m.empresa_id,
    m.producto_id,
    SUM(m.cantidad)  AS unidades_vendidas,
    MAX(m.fecha)     AS ultima_salida
  FROM movimientos_inventario m
  WHERE m.tipo = 'EGRESO'
    AND m.fecha >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
  GROUP BY m.empresa_id, m.producto_id
) AS s ON s.producto_id = p.producto_id
      AND s.empresa_id  = p.empresa_id
WHERE p.activo = 1;


-- =====================================================================
-- Comprobación
-- =====================================================================
-- Con un producto en existencia negativa:
--
--   SELECT SUM(valor_al_costo), SUM(valor_pendiente_al_costo)
--     FROM v_inventario_valorizado WHERE empresa_id = 1;
--
-- La primera suma no debe bajar cuando una referencia pasa a negativo;
-- la segunda debe subir. Antes de esta migración la primera bajaba y la
-- segunda no existía.
-- =====================================================================
