-- =====================================================================
-- SGIP · Migración V7 · Datos iniciales
--
-- Carga los catálogos mínimos y los parámetros vigentes al momento de
-- la especificación. Todos los valores son modificables desde la
-- aplicación; ninguno está codificado en el sistema.
-- =====================================================================

SET NAMES utf8mb4;

-- --------------------------------------------------------------- roles
INSERT INTO roles (nombre, descripcion) VALUES
  ('ADMINISTRADOR', 'Acceso completo: catálogo, proveedores, precios, usuarios y reportes'),
  ('VENDEDOR',      'Consulta de catálogo, cotización y registro de pedidos'),
  ('CLIENTE',       'Consulta de catálogo y precios de venta');

-- ---------------------------------------------------------- categorías
INSERT INTO categorias (nombre, descripcion) VALUES
  ('Computadores',         'Equipos de escritorio y portátiles'),
  ('Dispositivos móviles', 'Celulares y tabletas'),
  ('Monitores',            'Pantallas y televisores'),
  ('Redes',                'Equipos de conectividad y comunicaciones'),
  ('Servidores',           'Equipos de cómputo empresarial y almacenamiento'),
  ('Periféricos',          'Teclados, ratones, impresoras y accesorios'),
  ('Componentes',          'Partes internas y repuestos');

-- ------------------------------------------- unidad de valor tributario
-- El valor lo fija la DIAN por resolución antes del 1.º de enero.
INSERT INTO uvt_anual (anio, valor, resolucion) VALUES
  (2025, 49799.00, 'Resolución DIAN 000193 de 2024'),
  (2026, 52374.00, 'Resolución DIAN 000238 de 2025');

-- ----------------------------------------------------- reglas de margen
-- Veinte por ciento de contado, que agrupa efectivo y transferencia.
-- Veinticinco por ciento a crédito, que agrupa tarjeta y crédito directo.
-- El diferencial compensa el costo financiero y la comisión de la
-- franquicia. Ámbito general: aplican a todo el catálogo mientras no
-- exista una regla más específica por categoría o proveedor.
INSERT INTO reglas_margen (categoria_id, proveedor_id, forma_pago, factor, vigente_desde) VALUES
  (NULL, NULL, 'CONTADO', 1.2000, '2026-01-01'),
  (NULL, NULL, 'CREDITO', 1.2500, '2026-01-01');

-- -------------------------------------------------- reglas de impuesto
-- Artículo 424 del Estatuto Tributario, numerales 5 y 6: la exclusión
-- opera POR DEBAJO del umbral. El equipo económico no causa impuesto;
-- el que supera el tope lo causa a la tarifa general.
INSERT INTO reglas_iva (categoria_id, tratamiento, umbral_uvt, tarifa, vigente_desde, fundamento) VALUES
  (NULL, 'GRAVADO', NULL, 0.1900, '2026-01-01',
   'Tarifa general del impuesto sobre las ventas'),
  ((SELECT categoria_id FROM categorias WHERE nombre = 'Computadores'),
   'EXCLUIDO_HASTA_UMBRAL', 50.00, 0.1900, '2026-01-01',
   'Estatuto Tributario, artículo 424, numeral 5'),
  ((SELECT categoria_id FROM categorias WHERE nombre = 'Dispositivos móviles'),
   'EXCLUIDO_HASTA_UMBRAL', 22.00, 0.1900, '2026-01-01',
   'Estatuto Tributario, artículo 424, numeral 6');

-- ------------------------------------------------------ regla de envío
-- El umbral se evalúa sobre el subtotal, antes del impuesto, y la
-- comparación es estricta: una compra de novecientos mil más impuesto
-- no alcanza el beneficio aunque su total supere el millón.
-- costo_envio en cero significa que hoy el envío no se cobra cuando la
-- compra no alcanza el umbral; al fijar una tarifa, empieza a cobrarse.
INSERT INTO reglas_envio (umbral_valor, base_calculo, costo_envio, permite_obsequio, vigente_desde) VALUES
  (1000000.00, 'SUBTOTAL', 0.00, 1, '2026-01-01');

-- --------------------------------------------------------- parámetros
INSERT INTO parametros (clave, valor, tipo_dato, descripcion, vigente_desde) VALUES
  ('sgip.uvt.anio',                        '2026',          'ENTERO',   'Año gravable de la UVT vigente', '2026-01-01'),
  ('sgip.iva.tarifa.general',              '0.19',          'DECIMAL',  'Tarifa general del impuesto sobre las ventas', '2026-01-01'),
  ('sgip.precio.alerta.cruce.umbral',      'true',          'BOOLEANO', 'Aviso cuando un recálculo cruza el tope de exclusión', '2026-01-01'),
  ('sgip.envio.umbral',                    '1000000',       'DECIMAL',  'Compra mínima para envío sin costo', '2026-01-01'),
  ('sgip.envio.base',                      'SUBTOTAL',      'TEXTO',    'Base sobre la que se evalúa el umbral de envío', '2026-01-01'),
  ('sgip.envio.obsequio.permitido',        'true',          'BOOLEANO', 'Permite regalar el envío por debajo del umbral', '2026-01-01'),
  ('sgip.envio.obsequio.exige.motivo',     'true',          'BOOLEANO', 'Exige motivo y responsable al regalar el envío', '2026-01-01'),
  ('sgip.cotizacion.vigencia.dias.habiles','5',             'ENTERO',   'Vigencia por defecto de las cotizaciones', '2026-01-01'),
  ('sgip.stock.minimo.defecto',            '5',             'ENTERO',   'Umbral de existencia crítica por defecto', '2026-01-01'),
  ('sgip.seguridad.clave.longitud.minima', '8',             'ENTERO',   'Longitud mínima de contraseña', '2026-01-01'),
  ('sgip.seguridad.sesion.minutos',        '30',            'ENTERO',   'Expiración de sesión por inactividad', '2026-01-01'),
  ('sgip.seguridad.intentos.maximos',      '5',             'ENTERO',   'Intentos fallidos antes del bloqueo', '2026-01-01'),
  ('sgip.log.nivel',                       'INFO',          'TEXTO',    'Nivel de detalle de la bitácora', '2026-01-01');

-- ---------------------------------------------- calendario de festivos
-- Ley 51 de 1983: varias festividades se trasladan al lunes siguiente.
-- Verificar la lista antes de cargar cada año nuevo.
INSERT INTO calendario_habil (fecha, descripcion, tipo) VALUES
  ('2026-01-01', 'Año Nuevo', 'FESTIVO'),
  ('2026-01-12', 'Reyes Magos', 'FESTIVO'),
  ('2026-03-23', 'San José', 'FESTIVO'),
  ('2026-04-02', 'Jueves Santo', 'FESTIVO'),
  ('2026-04-03', 'Viernes Santo', 'FESTIVO'),
  ('2026-05-01', 'Día del Trabajo', 'FESTIVO'),
  ('2026-05-18', 'Ascensión del Señor', 'FESTIVO'),
  ('2026-06-08', 'Corpus Christi', 'FESTIVO'),
  ('2026-06-15', 'Sagrado Corazón', 'FESTIVO'),
  ('2026-06-29', 'San Pedro y San Pablo', 'FESTIVO'),
  ('2026-07-20', 'Independencia de Colombia', 'FESTIVO'),
  ('2026-08-07', 'Batalla de Boyacá', 'FESTIVO'),
  ('2026-08-17', 'Asunción de la Virgen', 'FESTIVO'),
  ('2026-10-12', 'Día de la Raza', 'FESTIVO'),
  ('2026-11-02', 'Todos los Santos', 'FESTIVO'),
  ('2026-11-16', 'Independencia de Cartagena', 'FESTIVO'),
  ('2026-12-08', 'Inmaculada Concepción', 'FESTIVO'),
  ('2026-12-25', 'Navidad', 'FESTIVO'),
  ('2027-01-01', 'Año Nuevo', 'FESTIVO'),
  ('2027-01-11', 'Reyes Magos', 'FESTIVO'),
  ('2027-03-22', 'San José', 'FESTIVO'),
  ('2027-03-25', 'Jueves Santo', 'FESTIVO'),
  ('2027-03-26', 'Viernes Santo', 'FESTIVO'),
  ('2027-05-01', 'Día del Trabajo', 'FESTIVO'),
  ('2027-05-10', 'Ascensión del Señor', 'FESTIVO'),
  ('2027-05-31', 'Corpus Christi', 'FESTIVO'),
  ('2027-06-07', 'Sagrado Corazón', 'FESTIVO'),
  ('2027-07-05', 'San Pedro y San Pablo', 'FESTIVO'),
  ('2027-07-20', 'Independencia de Colombia', 'FESTIVO'),
  ('2027-08-07', 'Batalla de Boyacá', 'FESTIVO'),
  ('2027-08-16', 'Asunción de la Virgen', 'FESTIVO'),
  ('2027-10-18', 'Día de la Raza', 'FESTIVO'),
  ('2027-11-01', 'Todos los Santos', 'FESTIVO'),
  ('2027-11-15', 'Independencia de Cartagena', 'FESTIVO'),
  ('2027-12-08', 'Inmaculada Concepción', 'FESTIVO'),
  ('2027-12-25', 'Navidad', 'FESTIVO');
