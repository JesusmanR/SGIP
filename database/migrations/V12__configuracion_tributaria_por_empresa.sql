-- =====================================================================
-- V12 · Configuración tributaria y comercial por empresa
-- =====================================================================
--
-- Esta migración hace dos cosas distintas y conviene no confundirlas.
--
-- La primera es corregir cuatro defectos de aislamiento que la V10 dejó
-- pendientes. Los cuatro se reprodujeron en una base real antes de
-- escribir esto; no son revisión de código, son comportamiento medido:
--
--   1. reglas_iva nunca recibió empresa_id. Su regla general —la fila
--      con categoria_id nulo— era una sola para toda la plataforma. Al
--      cambiar la empresa 1 su regla general a EXCLUIDO, el producto
--      «Arroz Diana 500 g» de la empresa 2 pasó de gravado al 19 % a
--      excluido. Un comercio le cambió el impuesto a otro.
--
--   2. v_regla_margen no filtra por empresa. reglas_margen sí tiene
--      empresa_id, pero el join de la vista no lo usa, de modo que la
--      regla general de la empresa 1 fijaba el precio de los productos
--      de la empresa 2. El arroz de una tienda se valoraba con el
--      margen de un distribuidor de tecnología.
--
--   3. Ninguna de las seis vistas expone empresa_id. La aplicación no
--      puede filtrarlas por empresa, que es justo lo que exige la
--      RN-16.
--
--   4. ventas.consecutivo y cotizaciones.consecutivo eran únicos
--      globales. La segunda empresa que numerara su primera venta como
--      V-0001 chocaba con la primera.
--
-- La segunda cosa es la configuración que pidió el negocio: que cada
-- empresa decida si cobra impuesto, cuál, si practica retenciones y si
-- maneja envíos; que los datos legales los mantenga la plataforma; y
-- que el producto tenga imágenes y la cotización vigencia editable.
--
-- Requiere V11 aplicada. Haz una copia antes: MySQL confirma cada
-- sentencia de definición por su cuenta y esto no se deshace con
-- ROLLBACK.
--
--   mysqldump -u root -p sgip > respaldo_antes_de_v12.sql
-- =====================================================================


-- =====================================================================
-- 1. Correcciones de aislamiento
-- =====================================================================

-- ── 1.1 El consecutivo es único por empresa, no en la plataforma ─────
-- Cada comercio numera sus documentos desde uno. Un único global obliga
-- a que los consecutivos de todos los comercios no se repitan entre sí,
-- que no es ninguna regla de negocio: es un descuido.
ALTER TABLE ventas
  DROP INDEX uq_ventas_consecutivo;
ALTER TABLE ventas
  ADD CONSTRAINT uq_ventas_consecutivo UNIQUE (empresa_id, consecutivo);

ALTER TABLE cotizaciones
  DROP INDEX uq_cotizaciones_consecutivo;
ALTER TABLE cotizaciones
  ADD CONSTRAINT uq_cotizaciones_consecutivo UNIQUE (empresa_id, consecutivo);

-- ── 1.2 ventas necesita su único por empresa ─────────────────────────
-- La V11 lo creó para categorías, proveedores, clientes, productos,
-- cotizaciones, medios de pago, cierres y usuarios, pero no para
-- ventas, que no era padre de nada. Ahora lo es: venta_retenciones
-- cuelga de ella con clave compuesta.
ALTER TABLE ventas
  ADD UNIQUE INDEX uq_ventas_empresa (empresa_id, venta_id);

-- ── 1.3 reglas_iva gana empresa_id ───────────────────────────────────
-- Mismo procedimiento que usó la V10: se añade nula, se rellena y solo
-- entonces se exige. Las tres filas sembradas en la V7 son de la
-- empresa 1, que es Black Steel Parts.
ALTER TABLE reglas_iva
  ADD COLUMN empresa_id INT NULL AFTER regla_iva_id;

UPDATE reglas_iva SET empresa_id = 1;

ALTER TABLE reglas_iva
  MODIFY empresa_id INT NOT NULL;

ALTER TABLE reglas_iva
  ADD CONSTRAINT fk_reglas_iva_empresa FOREIGN KEY (empresa_id)
    REFERENCES empresas (empresa_id) ON UPDATE CASCADE ON DELETE RESTRICT;

-- La clave hacia categorías pasa a compuesta, como hizo la V11 con las
-- demás. El sufijo _emp evita chocar con el índice que deja la clave
-- anterior al soltarla.
ALTER TABLE reglas_iva
  DROP FOREIGN KEY fk_reglas_iva_categoria;
ALTER TABLE reglas_iva
  ADD CONSTRAINT fk_reglas_iva_categoria_emp
      FOREIGN KEY (empresa_id, categoria_id)
      REFERENCES categorias (empresa_id, categoria_id)
      ON DELETE CASCADE ON UPDATE CASCADE;


-- =====================================================================
-- 2. Catálogos legales de plataforma
-- =====================================================================
-- Estas dos tablas NO llevan empresa_id, y es deliberado. Guardan ley
-- publicada, no configuración: el valor que una empresa elige es cuál
-- de estas filas le aplica, no cuánto vale la tarifa.
--
-- El riesgo que esto evita es concreto: si cada administrador
-- transcribiera la tarifa, cincuenta comercios escribirían el mismo
-- número y un dedazo en uno produciría impuesto mal calculado en todas
-- sus ventas.

CREATE TABLE tipos_impuesto (
  tipo_impuesto_id INT          NOT NULL AUTO_INCREMENT,
  codigo           VARCHAR(30)  NOT NULL,
  nombre           VARCHAR(80)  NOT NULL,
  tratamiento      ENUM('GRAVADO','EXCLUIDO_HASTA_UMBRAL','EXCLUIDO',
                        'EXENTO','NO_GRAVADO') NOT NULL,
  tarifa           DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
  umbral_uvt       DECIMAL(8,2) NULL,
  fundamento       VARCHAR(160) NULL,
  vigente_desde    DATE         NOT NULL,
  vigente_hasta    DATE         NULL,
  activo           BOOL         NOT NULL DEFAULT TRUE,
  CONSTRAINT pk_tipos_impuesto PRIMARY KEY (tipo_impuesto_id),
  CONSTRAINT uq_tipos_impuesto_codigo UNIQUE (codigo),
  CONSTRAINT ck_tipos_impuesto_tarifa CHECK (tarifa >= 0 AND tarifa <= 1),
  -- La comparación con NULL devuelve NULL, y un CHECK solo falla cuando
  -- evalúa a FALSE: sin la verificación explícita de nulidad se colaría
  -- un tipo de umbral sin umbral.
  CONSTRAINT ck_tipos_impuesto_umbral CHECK (
    tratamiento <> 'EXCLUIDO_HASTA_UMBRAL'
    OR (umbral_uvt IS NOT NULL AND umbral_uvt > 0)),
  CONSTRAINT ck_tipos_impuesto_rango CHECK (
    vigente_hasta IS NULL OR vigente_hasta >= vigente_desde),
  INDEX ix_tipos_impuesto_vigencia (vigente_desde, vigente_hasta)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE tipos_retencion (
  tipo_retencion_id INT          NOT NULL AUTO_INCREMENT,
  codigo            VARCHAR(30)  NOT NULL,
  nombre            VARCHAR(100) NOT NULL,
  clase             ENUM('RENTA','IVA','ICA') NOT NULL,
  -- Seis decimales porque el ICA se expresa por mil: 4,14 por mil es
  -- 0,00414, que con cinco decimales ya pierde precisión.
  tarifa            DECIMAL(8,6) NOT NULL,
  base_minima_uvt   DECIMAL(8,2) NULL,
  -- La retención de IVA se calcula sobre el impuesto, no sobre el
  -- subtotal. Distinguirlo aquí evita que el cálculo lo decida el
  -- código a partir del nombre.
  aplica_sobre      ENUM('SUBTOTAL','IVA') NOT NULL DEFAULT 'SUBTOTAL',
  fundamento        VARCHAR(160) NULL,
  vigente_desde     DATE         NOT NULL,
  vigente_hasta     DATE         NULL,
  activo            BOOL         NOT NULL DEFAULT TRUE,
  CONSTRAINT pk_tipos_retencion PRIMARY KEY (tipo_retencion_id),
  CONSTRAINT uq_tipos_retencion_codigo UNIQUE (codigo),
  CONSTRAINT ck_tipos_retencion_tarifa CHECK (tarifa >= 0 AND tarifa <= 1),
  CONSTRAINT ck_tipos_retencion_rango CHECK (
    vigente_hasta IS NULL OR vigente_hasta >= vigente_desde)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- =====================================================================
-- 3. La configuración de cada empresa
-- =====================================================================
-- Una fila por empresa. La clave primaria ES empresa_id: no hay dos
-- configuraciones de la misma empresa, y eso lo garantiza el esquema en
-- lugar de la aplicación.
--
-- Todos los interruptores arrancan en falso y la vigencia de cotización
-- en cinco días. Arrancar en falso no es timidez: es que nadie eligió
-- todavía, y la RN-25 impide vender hasta que alguien elija.

CREATE TABLE empresa_config (
  empresa_id               INT          NOT NULL,
  responsable_iva          BOOL         NOT NULL DEFAULT FALSE,
  aplica_exclusion_umbral  BOOL         NOT NULL DEFAULT FALSE,
  practica_retenciones     BOOL         NOT NULL DEFAULT FALSE,
  tarifa_ica               DECIMAL(8,6) NULL,
  maneja_envios            BOOL         NOT NULL DEFAULT FALSE,
  cotizacion_vigencia_dias INT          NOT NULL DEFAULT 5,
  configuracion_completa   BOOL         NOT NULL DEFAULT FALSE,
  actualizado_en           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT pk_empresa_config PRIMARY KEY (empresa_id),
  CONSTRAINT fk_empresa_config_empresa FOREIGN KEY (empresa_id)
    REFERENCES empresas (empresa_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT ck_empresa_config_ica CHECK (
    tarifa_ica IS NULL OR (tarifa_ica >= 0 AND tarifa_ica <= 1)),
  CONSTRAINT ck_empresa_config_vigencia CHECK (
    cotizacion_vigencia_dias > 0 AND cotizacion_vigencia_dias <= 365),
  -- El ICA solo tiene sentido si la empresa practica retenciones.
  CONSTRAINT ck_empresa_config_ica_coherente CHECK (
    tarifa_ica IS NULL OR practica_retenciones = TRUE)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- =====================================================================
-- 4. reglas_iva pasa a referir el catálogo
-- =====================================================================
-- Antes guardaba el tratamiento, el umbral y la tarifa en sus propias
-- columnas. Ahora apunta a un tipo del catálogo, y gana producto_id
-- para que el tratamiento heredado de la categoría pueda anularse en un
-- producto suelto, como pide la RN-22.
--
-- La precedencia queda producto, luego categoría, luego la regla
-- general de la empresa: la misma forma que ya usa v_regla_margen.

ALTER TABLE reglas_iva
  ADD COLUMN producto_id      INT NULL AFTER categoria_id,
  ADD COLUMN tipo_impuesto_id INT NULL AFTER producto_id;

-- Las tres reglas sembradas se traducen a tipos del catálogo más abajo,
-- en el bloque de siembra, antes de exigir la columna.

ALTER TABLE reglas_iva
  ADD CONSTRAINT fk_reglas_iva_producto_emp
      FOREIGN KEY (empresa_id, producto_id)
      REFERENCES productos (empresa_id, producto_id)
      ON DELETE CASCADE ON UPDATE CASCADE;

-- La clave hacia el catálogo NO se crea aquí: se crea en el bloque 8.4,
-- después de rellenar la columna y de exigirla. El orden importa y MySQL
-- lo hace cumplir, aunque MariaDB no: con la clave ya puesta, el
-- MODIFY posterior falla con error 1832, «Cannot change column
-- 'tipo_impuesto_id': used in a foreign key constraint». Es el mismo
-- orden que ya usaba la V10 para empresa_id.

ALTER TABLE reglas_iva
  ADD INDEX ix_reglas_iva_producto (producto_id, vigente_desde);

-- La restricción que impide que una regla apunte a la vez a un producto
-- y a una categoría NO está en esta migración: va aparte, en
-- extras/restriccion_ambito_regla_iva.sql, y ese archivo explica por
-- qué quedó fuera.


-- =====================================================================
-- 5. Imágenes de producto
-- =====================================================================
-- La ruta, no el archivo. Guardar imágenes dentro de la base hincha los
-- respaldos y obliga a sacarlas por la aplicación para mostrarlas.

CREATE TABLE producto_imagenes (
  imagen_id   INT          NOT NULL AUTO_INCREMENT,
  empresa_id  INT          NOT NULL,
  producto_id INT          NOT NULL,
  ruta        VARCHAR(255) NOT NULL,
  orden       TINYINT      NOT NULL DEFAULT 1,
  principal   BOOL         NOT NULL DEFAULT FALSE,
  bytes       INT          NULL,
  creado_en   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- El truco que hace cumplir «una sola principal por producto» sin
  -- disparador: la columna vale producto_id cuando la imagen es la
  -- principal y nulo cuando no. Un índice único admite varios nulos,
  -- de modo que las secundarias no chocan entre sí, pero sí choca la
  -- segunda principal del mismo producto.
  principal_unica INT GENERATED ALWAYS AS (
    CASE WHEN principal THEN producto_id END) STORED,
  CONSTRAINT pk_producto_imagenes PRIMARY KEY (imagen_id),
  CONSTRAINT uq_producto_imagenes_principal UNIQUE (principal_unica),
  CONSTRAINT uq_producto_imagenes_orden UNIQUE (producto_id, orden),
  -- RESTRICT y no CASCADE, por dos razones que coinciden.
  --
  -- La técnica: MySQL prohíbe que una clave foránea con acción en
  -- cascada recaiga sobre la columna base de una columna generada
  -- almacenada, y producto_id es la base de principal_unica. Con
  -- CASCADE la tabla no se crea (error 1901 en MariaDB, restricción
  -- equivalente en MySQL).
  --
  -- El negocio: en este esquema nada se elimina. Los productos se
  -- desactivan, de modo que la cascada nunca se habría disparado. Lo
  -- que RESTRICT añade es que, si alguien intenta borrar un producto
  -- con imágenes, el borrado falla en lugar de arrastrarlas en
  -- silencio.
  CONSTRAINT fk_producto_imagenes_producto_emp
    FOREIGN KEY (empresa_id, producto_id)
    REFERENCES productos (empresa_id, producto_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  -- El RF-63 admite hasta cinco imágenes. El tope vive aquí para que no
  -- dependa de que la aplicación lo recuerde.
  CONSTRAINT ck_producto_imagenes_orden CHECK (orden >= 1 AND orden <= 5),
  CONSTRAINT ck_producto_imagenes_bytes CHECK (
    bytes IS NULL OR (bytes > 0 AND bytes <= 5242880))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- =====================================================================
-- 6. Retención en la venta
-- =====================================================================
-- Dirección de la retención: es la que le PRACTICAN al comercio. Una
-- tienda que le vende a una empresa cobra menos, porque el comprador
-- retiene y consigna a la DIAN. Por eso la venta gana el neto a
-- recibir, que es lo que efectivamente ingresa a la caja.
--
-- La retención que el comercio practicaría a sus proveedores ocurre en
-- la compra, no aquí, y no está en esta migración.

ALTER TABLE ventas
  ADD COLUMN retencion_total DECIMAL(14,2) NOT NULL DEFAULT 0.00 AFTER total,
  ADD COLUMN autorizacion_autoconcedida BOOL NOT NULL DEFAULT FALSE
      AFTER motivo_sin_existencia;

-- Columna generada sobre otra columna generada: MySQL lo admite porque
-- `total` se define antes. Es preferible a calcularlo en la aplicación,
-- donde dos sitios distintos acabarían restando de formas distintas.
ALTER TABLE ventas
  ADD COLUMN neto_a_recibir DECIMAL(14,2)
      GENERATED ALWAYS AS (total - retencion_total) STORED
      AFTER retencion_total;

ALTER TABLE ventas
  ADD CONSTRAINT ck_ventas_retencion CHECK (retencion_total >= 0);

CREATE TABLE venta_retenciones (
  retencion_id      BIGINT       NOT NULL AUTO_INCREMENT,
  empresa_id        INT          NOT NULL,
  -- BIGINT, no INT: ventas.venta_id es BIGINT, y una clave foránea con
  -- tipos distintos falla con errno 150, «foreign key constraint is
  -- incorrectly formed», que no dice cuál es el problema.
  venta_id          BIGINT       NOT NULL,
  tipo_retencion_id INT          NOT NULL,
  base              DECIMAL(14,2) NOT NULL,
  -- La tarifa se congela en la fila, como los precios en las líneas de
  -- venta. Si mañana cambia por decreto, el documento emitido conserva
  -- la que se le aplicó.
  tarifa            DECIMAL(8,6) NOT NULL,
  valor             DECIMAL(14,2) NOT NULL,
  aprobada_por      INT          NOT NULL,
  aprobada_en       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_venta_retenciones PRIMARY KEY (retencion_id),
  -- Una retención de cada clase por venta: no se retiene dos veces por
  -- el mismo concepto.
  CONSTRAINT uq_venta_retenciones UNIQUE (venta_id, tipo_retencion_id),
  CONSTRAINT fk_venta_retenciones_venta_emp
    FOREIGN KEY (empresa_id, venta_id)
    REFERENCES ventas (empresa_id, venta_id)
    ON DELETE CASCADE ON UPDATE CASCADE,
  -- Quien aprueba pertenece a la empresa de la venta. La clave
  -- compuesta lo hace cumplir, igual que en la V11.
  CONSTRAINT fk_venta_retenciones_aprueba
    FOREIGN KEY (empresa_id, aprobada_por)
    REFERENCES usuarios (empresa_id, usuario_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_venta_retenciones_tipo
    FOREIGN KEY (tipo_retencion_id)
    REFERENCES tipos_retencion (tipo_retencion_id)
    ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT ck_venta_retenciones_valores CHECK (
    base >= 0 AND valor >= 0 AND tarifa >= 0 AND tarifa <= 1),
  INDEX ix_venta_retenciones_venta (venta_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- =====================================================================
-- 7. Vigencia editable de la cotización
-- =====================================================================
-- El valor se congela en el documento. Si el administrador cambia
-- después el valor por omisión de la empresa, las cotizaciones ya
-- emitidas conservan el suyo.

ALTER TABLE cotizaciones
  ADD COLUMN vigencia_dias INT NOT NULL DEFAULT 5 AFTER fecha_emision;

-- El nombre lleva _dias porque ck_cotizaciones_vigencia ya existe desde
-- la V4: comprueba que la fecha de vencimiento no sea anterior a la de
-- emisión. Reutilizar el nombre falla con error 1826.
ALTER TABLE cotizaciones
  ADD CONSTRAINT ck_cotizaciones_vigencia_dias CHECK (
    vigencia_dias > 0 AND vigencia_dias <= 365);


-- =====================================================================
-- 8. Siembra
-- =====================================================================

-- ── 8.1 Catálogo de tipos de impuesto ────────────────────────────────
-- La lista que ve el administrador en el desplegable. Corta a propósito:
-- la va a leer un tendero, no un contador.
--
-- Los dos tipos de exclusión por umbral vienen del artículo 424 del
-- Estatuto Tributario, numerales 5 y 6, y solo le sirven a quien vende
-- tecnología. Una panadería no los va a escoger nunca, y por eso la
-- empresa declara aparte si aplica la exclusión.
INSERT INTO tipos_impuesto
  (codigo, nombre, tratamiento, tarifa, umbral_uvt, fundamento, vigente_desde) VALUES
  ('IVA_19',          'IVA tarifa general (19 %)',        'GRAVADO',                0.1900, NULL,  'Estatuto Tributario, art. 468',            '2026-01-01'),
  ('IVA_5',           'IVA tarifa reducida (5 %)',        'GRAVADO',                0.0500, NULL,  'Estatuto Tributario, art. 468-1 y 468-3',  '2026-01-01'),
  ('EXENTO',          'Exento (tarifa cero)',             'EXENTO',                 0.0000, NULL,  'Estatuto Tributario, art. 477',            '2026-01-01'),
  ('EXCLUIDO',        'Excluido del impuesto',            'EXCLUIDO',               0.0000, NULL,  'Estatuto Tributario, art. 424',            '2026-01-01'),
  ('NO_GRAVADO',      'No responsable del impuesto',      'NO_GRAVADO',             0.0000, NULL,  'Estatuto Tributario, art. 437 parágrafo 3','2026-01-01'),
  ('EXCL_UMBRAL_PC',  'Computadores, excluidos hasta 50 UVT','EXCLUIDO_HASTA_UMBRAL',0.1900, 50.00, 'Estatuto Tributario, art. 424 num. 5',    '2026-01-01'),
  ('EXCL_UMBRAL_MOV', 'Móviles, excluidos hasta 22 UVT',  'EXCLUIDO_HASTA_UMBRAL',  0.1900, 22.00, 'Estatuto Tributario, art. 424 num. 6',     '2026-01-01');

-- ── 8.2 Catálogo de tipos de retención ───────────────────────────────
-- ATENCIÓN: las tarifas de retención en la fuente varían por concepto y
-- cambian por decreto. Las dos filas siguientes son la ESTRUCTURA, con
-- los valores en cero y marcadas como inactivas a propósito: hay que
-- cargarlas desde la tabla de retención vigente de la DIAN antes de
-- activarlas. Sembrar cifras de memoria en una tabla tributaria es
-- exactamente la clase de error que nadie nota hasta la declaración.
--
-- El ICA no entra aquí: es municipal, varía por actividad económica, y
-- su tarifa la carga el administrador en empresa_config.tarifa_ica.
INSERT INTO tipos_retencion
  (codigo, nombre, clase, tarifa, base_minima_uvt, aplica_sobre, fundamento, vigente_desde, activo) VALUES
  ('RETEFUENTE_COMPRAS', 'Retención en la fuente, compras generales', 'RENTA', 0.000000, NULL, 'SUBTOTAL',
   'Pendiente de cargar desde la tabla de retención vigente de la DIAN', '2026-01-01', FALSE),
  ('RETEIVA',            'Retención de IVA',                          'IVA',   0.000000, NULL, 'IVA',
   'Pendiente de cargar desde la tabla de retención vigente de la DIAN', '2026-01-01', FALSE);

-- ── 8.3 Configuración de las empresas existentes ─────────────────────
-- Black Steel Parts es responsable de IVA y vende tecnología, de modo
-- que aplica la exclusión por umbral. Su configuración queda completa
-- porque ya tiene reglas de margen y medios de pago sembrados.
INSERT INTO empresa_config
  (empresa_id, responsable_iva, aplica_exclusion_umbral, practica_retenciones,
   maneja_envios, cotizacion_vigencia_dias, configuracion_completa)
VALUES (1, TRUE, TRUE, FALSE, TRUE, 5, TRUE);

-- Cualquier otra empresa que ya exista arranca sin configurar, que es
-- lo correcto: nadie eligió por ella.
--
-- Sin subconsulta sobre empresa_config: leer la misma tabla en la que se
-- inserta es terreno donde MySQL y MariaDB no se comportan igual, y aquí
-- no hace falta. La tabla acaba de crearse y solo tiene la fila de la
-- empresa 1, que la condición ya excluye.
INSERT INTO empresa_config (empresa_id)
SELECT e.empresa_id FROM empresas e
 WHERE e.empresa_id <> 1;

-- ── 8.4 Las reglas sembradas apuntan ahora a un tipo ─────────────────
UPDATE reglas_iva ri
   JOIN tipos_impuesto ti ON ti.codigo = 'IVA_19'
   SET ri.tipo_impuesto_id = ti.tipo_impuesto_id
 WHERE ri.categoria_id IS NULL;

UPDATE reglas_iva ri
   JOIN tipos_impuesto ti ON ti.codigo = 'EXCL_UMBRAL_PC'
   SET ri.tipo_impuesto_id = ti.tipo_impuesto_id
 WHERE ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL' AND ri.umbral_uvt = 50.00;

UPDATE reglas_iva ri
   JOIN tipos_impuesto ti ON ti.codigo = 'EXCL_UMBRAL_MOV'
   SET ri.tipo_impuesto_id = ti.tipo_impuesto_id
 WHERE ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL' AND ri.umbral_uvt = 22.00;

-- Cualquier regla que no haya encontrado su tipo cae al general, para
-- que la columna pueda exigirse sin dejar filas huérfanas.
UPDATE reglas_iva ri
   JOIN tipos_impuesto ti ON ti.codigo = 'IVA_19'
   SET ri.tipo_impuesto_id = ti.tipo_impuesto_id
 WHERE ri.tipo_impuesto_id IS NULL;

ALTER TABLE reglas_iva
  MODIFY tipo_impuesto_id INT NOT NULL;

-- Ahora sí la clave foránea. Es de una sola columna porque el catálogo
-- es de plataforma: no tiene empresa a la que pertenecer.
ALTER TABLE reglas_iva
  ADD CONSTRAINT fk_reglas_iva_tipo
      FOREIGN KEY (tipo_impuesto_id)
      REFERENCES tipos_impuesto (tipo_impuesto_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;

-- Las columnas viejas salen una vez traducidas: dejarlas sería tener
-- dos fuentes del mismo dato, que es como empiezan a divergir.
ALTER TABLE reglas_iva
  DROP CONSTRAINT ck_reglas_iva_umbral;
ALTER TABLE reglas_iva
  DROP CONSTRAINT ck_reglas_iva_tarifa;
ALTER TABLE reglas_iva
  DROP COLUMN tratamiento,
  DROP COLUMN umbral_uvt,
  DROP COLUMN tarifa;


-- =====================================================================
-- 9. Vistas reconstruidas
-- =====================================================================
-- Las seis exponen empresa_id. Sin él la aplicación no puede aplicar el
-- filtro único por empresa que exige la RN-16, y tiene que volver a las
-- tablas, que es justamente lo que las vistas existían para evitar.

-- ── 9.1 v_regla_margen ───────────────────────────────────────────────
-- El join gana la condición de empresa. Sin ella, la regla general de
-- un comercio fijaba el precio de los productos de otro.
CREATE OR REPLACE VIEW v_regla_margen AS
SELECT producto_id, empresa_id, forma_pago, factor, forma_calculo, regla_id
FROM (
  SELECT
    p.producto_id,
    p.empresa_id,
    r.forma_pago,
    r.factor,
    r.forma_calculo,
    r.regla_id,
    ROW_NUMBER() OVER (
      PARTITION BY p.producto_id, r.forma_pago
      ORDER BY (r.categoria_id IS NOT NULL) + (r.proveedor_id IS NOT NULL) DESC,
               r.vigente_desde DESC,
               r.regla_id DESC
    ) AS prioridad
  FROM productos p
  JOIN reglas_margen r
    ON r.empresa_id = p.empresa_id
   AND (r.categoria_id IS NULL OR r.categoria_id = p.categoria_id)
   AND (r.proveedor_id IS NULL OR r.proveedor_id = p.proveedor_id)
   AND CURDATE() >= r.vigente_desde
   AND (r.vigente_hasta IS NULL OR CURDATE() <= r.vigente_hasta)
) AS ranking
WHERE prioridad = 1;

-- ── 9.2 v_regla_iva ──────────────────────────────────────────────────
-- Tres cosas nuevas: filtra por empresa, resuelve el tratamiento desde
-- el catálogo, y respeta lo que la empresa declaró.
--
-- La empresa que no es responsable de IVA no causa el impuesto, sea cual
-- sea el tipo asignado a la categoría. Y la exclusión por umbral solo
-- opera si la empresa la aplica: para quien no la aplica, ese tipo se
-- comporta como gravado a su tarifa. Resolverlo aquí evita que cada
-- consulta tenga que acordarse de los dos interruptores.
CREATE OR REPLACE VIEW v_regla_iva AS
SELECT producto_id, empresa_id, tipo_impuesto_id, codigo_impuesto,
       tratamiento, umbral_uvt, tarifa, regla_iva_id
FROM (
  SELECT
    p.producto_id,
    p.empresa_id,
    ti.tipo_impuesto_id,
    ti.codigo AS codigo_impuesto,
    CASE
      WHEN ec.responsable_iva = FALSE THEN 'NO_GRAVADO'
      WHEN ti.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'
           AND ec.aplica_exclusion_umbral = FALSE THEN 'GRAVADO'
      ELSE ti.tratamiento
    END AS tratamiento,
    CASE
      WHEN ec.aplica_exclusion_umbral = FALSE THEN NULL
      ELSE ti.umbral_uvt
    END AS umbral_uvt,
    CASE
      WHEN ec.responsable_iva = FALSE THEN 0.0000
      ELSE ti.tarifa
    END AS tarifa,
    ri.regla_iva_id,
    ROW_NUMBER() OVER (
      PARTITION BY p.producto_id
      ORDER BY (ri.producto_id IS NOT NULL) DESC,
               (ri.categoria_id IS NOT NULL) DESC,
               ri.vigente_desde DESC,
               ri.regla_iva_id DESC
    ) AS prioridad
  FROM productos p
  JOIN empresa_config ec ON ec.empresa_id = p.empresa_id
  JOIN reglas_iva ri
    ON ri.empresa_id = p.empresa_id
   AND (ri.producto_id = p.producto_id
        OR (ri.producto_id IS NULL
            AND (ri.categoria_id IS NULL OR ri.categoria_id = p.categoria_id)))
   AND CURDATE() >= ri.vigente_desde
   AND (ri.vigente_hasta IS NULL OR CURDATE() <= ri.vigente_hasta)
  JOIN tipos_impuesto ti
    ON ti.tipo_impuesto_id = ri.tipo_impuesto_id
   AND ti.activo = TRUE
) AS ranking
WHERE prioridad = 1;

-- ── 9.3 v_precios_producto ───────────────────────────────────────────
-- Gana empresa_id y una bandera nueva: tributacion_sin_configurar.
--
-- Una empresa sin configuración tributaria no tiene regla aplicable, de
-- modo que el precio al cliente no se puede calcular. Antes la vista
-- habría devuelto nulo sin explicar por qué. La bandera lo dice, y es
-- lo que la pantalla necesita para indicar qué falta en lugar de
-- mostrar un total vacío.
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
  (SELECT i.ruta FROM producto_imagenes i
    WHERE i.producto_id = p.producto_id AND i.principal = TRUE) AS imagen_principal
FROM productos p
JOIN categorias  c  ON c.categoria_id  = p.categoria_id
JOIN proveedores pr ON pr.proveedor_id = p.proveedor_id
LEFT JOIN v_regla_margen mc ON mc.producto_id = p.producto_id
                           AND mc.forma_pago  = 'CONTADO'
LEFT JOIN v_regla_margen mk ON mk.producto_id = p.producto_id
                           AND mk.forma_pago  = 'CREDITO'
LEFT JOIN v_regla_iva    ri ON ri.producto_id = p.producto_id
WHERE p.activo = 1;

-- ── 9.4 Las tres restantes ───────────────────────────────────────────
CREATE OR REPLACE VIEW v_existencias_criticas AS
SELECT
  p.producto_id,
  p.empresa_id,
  p.codigo,
  p.nombre,
  p.presentacion,
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
  v.existencia * v.costo            AS valor_al_costo,
  v.existencia * v.precio_contado   AS valor_a_precio_venta,
  v.existencia * (v.precio_contado - v.costo) AS utilidad_potencial
FROM v_precios_producto v;

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


-- =====================================================================
-- 10. Parámetros nuevos
-- =====================================================================
INSERT INTO parametros (empresa_id, clave, valor, tipo_dato, descripcion, vigente_desde) VALUES
  (1, 'sgip.catalogo.imagenes.maximo',     '5',       'ENTERO',
   'Imágenes por producto que admite el RF-63',                        '2026-01-01'),
  (1, 'sgip.catalogo.imagen.bytes.maximo', '5242880', 'ENTERO',
   'Tamaño máximo de una imagen de producto, en bytes (RF-63)',        '2026-01-01'),
  (1, 'sgip.inventario.importacion.filas', '500',     'ENTERO',
   'Filas por archivo que el RF-65 se compromete a procesar en un minuto', '2026-01-01');


-- =====================================================================
-- Comprobación
-- =====================================================================
-- El script database/verificar_aislamiento.sh cubre lo anterior. Para
-- esta migración, lo que debe cumplirse:
--
--   · Una empresa sin configurar no obtiene regla de IVA, y sus
--     productos salen con tributacion_sin_configurar = 1.
--   · Cambiar la regla general de una empresa no altera el tratamiento
--     de los productos de otra.
--   · Cambiar la regla de margen general de una empresa no altera el
--     precio de los productos de otra.
--   · Con responsable_iva en falso, la tarifa es cero cualquiera sea el
--     tipo asignado.
--   · Dos empresas pueden emitir ambas su venta V-0001.
--   · Un producto admite una sola imagen principal.
--   · El neto a recibir es el total menos la retención.
