-- =====================================================================
-- SGIP · Datos de demostración
--
-- NO cargar en producción. Sirven para verificar que las reglas de
-- precio, impuesto y vigencia se comportan como especifica el informe.
-- =====================================================================

SET NAMES utf8mb4;

INSERT INTO usuarios (rol_id, nombre, correo, clave_hash) VALUES
  (1, 'Administrador de prueba', 'admin@bspstore.com.co', 'pbkdf2$120000$c2FsZGVwcnVlYmE$aGFzaGRlcHJ1ZWJh');

-- La restricción ck_proveedores_url exige la dirección del servicio en el
-- mismo INSERT cuando el origen es SERVICIO_WEB.
INSERT INTO proveedores (razon_social, nit, contacto, telefono, plazo_dias, origen_lista, url_servicio) VALUES
  ('Mayorista Tech SAS',   '900123456-1', 'Área comercial', '6076000000', 30, 'ARCHIVO',      NULL),
  ('Distribuidora Andina', '900987654-2', 'Ventas',         '6076111111', 15, 'SERVICIO_WEB', 'https://api.ejemplo.com/precios');

-- Referencias elegidas a propósito alrededor de los topes de exclusión.
INSERT INTO productos (categoria_id, proveedor_id, codigo, nombre, costo, existencia, existencia_minima) VALUES
  (1, 1, 'PC-001',  'Portátil económico',            2100000, 10, 3),
  (1, 1, 'PC-002',  'Portátil gama media',           2200000,  8, 3),
  (1, 1, 'PC-003',  'Portátil justo bajo el tope',   2150000,  5, 3),
  (2, 1, 'CEL-001', 'Celular económico',              900000, 20, 5),
  (2, 1, 'CEL-002', 'Celular gama media',            1000000, 15, 5),
  (3, 2, 'MON-001', 'Monitor de 27 pulgadas',        1000000,  2, 5),
  (5, 2, 'SRV-001', 'Servidor de torre',            14000000,  0, 1);

INSERT INTO clientes (razon_social, tipo_documento, nit, correo, ciudad, autorizacion_datos, medio_autorizacion) VALUES
  ('Comercializadora del Oriente SAS', 'NIT', '901222333-4', 'compras@ejemplo.com', 'Bucaramanga', NOW(), 'Formulario web');

-- ---------------------------------------------------------------------
-- Verificaciones esperadas
-- ---------------------------------------------------------------------
-- 1) Umbral de exclusión. PC-001 queda excluido y PC-002 gravado, pese a
--    que sus costos difieren en solo cien mil pesos.
SELECT codigo, costo, precio_contado, tratamiento_iva,
       ROUND(umbral_pesos) AS umbral, tarifa_contado,
       ROUND(total_contado) AS total_al_cliente, cerca_umbral
FROM v_precios_producto
ORDER BY codigo;

-- 2) Estado de existencias derivado por columna generada.
SELECT codigo, existencia, existencia_minima, estado_stock FROM productos ORDER BY codigo;

-- 3) Vigencia en días hábiles. Una cotización emitida el 1 de abril de
--    2026 vence el 10, porque el 2 y el 3 son Jueves y Viernes Santo.
SELECT fn_sumar_dias_habiles('2026-04-01', 5) AS vencimiento;

-- 4) Precedencia de reglas de margen. Al crear una regla de categoría,
--    debe prevalecer sobre la general.
-- INSERT INTO reglas_margen (categoria_id, forma_pago, factor, vigente_desde)
--   VALUES (5, 'CONTADO', 1.1200, '2026-01-01');
-- SELECT codigo, precio_contado FROM v_precios_producto WHERE codigo = 'SRV-001';

-- 5) Regla de envío. El umbral se mide sobre el subtotal y la
--    comparación es estricta. La semilla deja costo_envio en cero, que
--    equivale a no cobrar envío nunca; para ver la regla operando hay
--    que fijar una tarifa real.
UPDATE reglas_envio SET costo_envio = 25000 WHERE regla_envio_id = 1;

SELECT 'subtotal 900.000 + IVA'      AS caso, fn_envio_sugerido( 900000, 171000, '2026-04-01') AS envio, 'cobra' AS esperado
UNION ALL
SELECT 'subtotal 1.000.000 exacto',        fn_envio_sugerido(1000000, 190000, '2026-04-01'), 'cobra'
UNION ALL
SELECT 'subtotal 1.000.001',               fn_envio_sugerido(1000001, 190000, '2026-04-01'), 'gratis'
UNION ALL
SELECT 'subtotal 1.500.000 sin impuesto',  fn_envio_sugerido(1500000,      0, '2026-04-01'), 'gratis';

-- 6) Por qué importa la base. Con el umbral medido sobre el total con
--    impuesto, la compra de novecientos mil pasaría a ser gratuita,
--    porque su total llega a 1.071.000.
UPDATE reglas_envio SET base_calculo = 'TOTAL_CON_IVA' WHERE regla_envio_id = 1;
SELECT 'mismo caso con base TOTAL_CON_IVA' AS caso,
       fn_envio_sugerido(900000, 171000, '2026-04-01') AS envio;
UPDATE reglas_envio SET base_calculo = 'SUBTOTAL', costo_envio = 0 WHERE regla_envio_id = 1;

-- 7) El obsequio manual exige motivo y responsable.
--    El primer INSERT debe ser rechazado; el segundo, aceptado.
-- INSERT INTO cotizaciones (consecutivo,cliente_id,usuario_id,estado,fecha_emision,
--   subtotal,iva,envio,origen_envio)
--   VALUES ('COT-DEMO-1',1,1,'VIGENTE','2026-04-01',800000,152000,0,'MANUAL');
-- INSERT INTO cotizaciones (consecutivo,cliente_id,usuario_id,estado,fecha_emision,
--   subtotal,iva,envio,origen_envio,motivo_envio,envio_autorizado_por)
--   VALUES ('COT-DEMO-2',1,1,'VIGENTE','2026-04-01',800000,152000,0,'MANUAL',
--           'Cliente recurrente, cierre de mes',1);
