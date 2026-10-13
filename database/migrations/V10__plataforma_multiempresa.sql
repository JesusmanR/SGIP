-- ---------------------------------------------------------------------
-- V10. Plataforma multiempresa, abastecimiento, medios de pago y
--      operación sin conexión
--
-- Lleva el esquema del modelo de una sola empresa al de la versión 4 de la
-- especificación. Son cuatro bloques:
--
--   1. La empresa como entidad, con su suscripción, y la columna empresa_id
--      en todo lo que pertenece a un negocio.
--   2. Abastecimiento: varias referencias de proveedor por producto, órdenes
--      de compra y recepciones.
--   3. Comercial: medios de pago configurables y cierre de caja por turno.
--   4. Sincronización: cola de operaciones y descuadres de existencia.
--
-- Ejecutar después de V9. Los datos existentes se asignan a la empresa 1,
-- que corresponde a Black Steel Parts.
-- ---------------------------------------------------------------------

-- =====================================================================
-- 1. LA EMPRESA
-- =====================================================================

CREATE TABLE empresas (
  empresa_id      INT           NOT NULL AUTO_INCREMENT,
  nit             VARCHAR(20)   NOT NULL,
  razon_social    VARCHAR(160)  NOT NULL,
  correo_contacto VARCHAR(120)  NULL,
  telefono        VARCHAR(20)   NULL,
  ciudad          VARCHAR(80)   NULL,
  estado          ENUM('PRUEBA','ACTIVA','DESACTIVADA')
                                NOT NULL DEFAULT 'PRUEBA',
  creada_en       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_empresas PRIMARY KEY (empresa_id),
  CONSTRAINT uq_empresas_nit UNIQUE (nit)
) ENGINE=InnoDB;

CREATE TABLE suscripciones (
  suscripcion_id INT          NOT NULL AUTO_INCREMENT,
  empresa_id     INT          NOT NULL,
  plan           VARCHAR(40)  NOT NULL DEFAULT 'BASICO',
  inicio_prueba  DATE         NOT NULL,
  fin_prueba     DATE         NOT NULL,
  vigente_hasta  DATE         NULL,
  estado         ENUM('PRUEBA','AL_DIA','VENCIDA')
                              NOT NULL DEFAULT 'PRUEBA',
  CONSTRAINT pk_suscripciones PRIMARY KEY (suscripcion_id),
  CONSTRAINT uq_suscripciones_empresa UNIQUE (empresa_id),
  CONSTRAINT fk_suscripciones_empresa FOREIGN KEY (empresa_id)
    REFERENCES empresas (empresa_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT ck_suscripciones_prueba CHECK (fin_prueba >= inicio_prueba)
) ENGINE=InnoDB;

-- La empresa que ya existía: todo lo registrado hasta ahora es suyo.
INSERT INTO empresas (empresa_id, nit, razon_social, ciudad, estado)
  VALUES (1, '900000000-0', 'Black Steel Parts', 'Bucaramanga', 'ACTIVA');

INSERT INTO suscripciones (empresa_id, plan, inicio_prueba, fin_prueba, vigente_hasta, estado)
  VALUES (1, 'OPERADOR', CURRENT_DATE, CURRENT_DATE, NULL, 'AL_DIA');

-- --------------------------------------------- roles con ámbito --------
-- El super administrador pertenece a la plataforma y no a ninguna empresa.
ALTER TABLE roles
  ADD COLUMN ambito ENUM('PLATAFORMA','EMPRESA') NOT NULL DEFAULT 'EMPRESA'
      AFTER nombre;

UPDATE roles SET ambito = 'PLATAFORMA' WHERE nombre = 'SUPER_ADMIN';

UPDATE roles
   SET descripcion = 'Operador de la plataforma: alta, activación y suscripción de empresas, y datos tributarios nacionales'
 WHERE nombre = 'SUPER_ADMIN';

-- --------------------------------------------- empresa_id --------------
-- Se añade nula, se rellena con la empresa 1 y solo entonces se exige.
-- Hacerlo en un paso fallaría sobre las filas ya existentes.
ALTER TABLE usuarios      ADD COLUMN empresa_id INT NULL AFTER usuario_id;
ALTER TABLE categorias    ADD COLUMN empresa_id INT NULL AFTER categoria_id;
ALTER TABLE proveedores   ADD COLUMN empresa_id INT NULL AFTER proveedor_id;
ALTER TABLE productos     ADD COLUMN empresa_id INT NULL AFTER producto_id;
ALTER TABLE clientes      ADD COLUMN empresa_id INT NULL AFTER cliente_id;
ALTER TABLE cotizaciones  ADD COLUMN empresa_id INT NULL AFTER cotizacion_id;
ALTER TABLE ventas        ADD COLUMN empresa_id INT NULL AFTER venta_id;
ALTER TABLE movimientos_inventario ADD COLUMN empresa_id INT NULL AFTER movimiento_id;
ALTER TABLE parametros    ADD COLUMN empresa_id INT NULL AFTER parametro_id;
ALTER TABLE reglas_margen ADD COLUMN empresa_id INT NULL AFTER regla_id;
ALTER TABLE reglas_envio  ADD COLUMN empresa_id INT NULL AFTER regla_envio_id;
ALTER TABLE auditoria_accesos ADD COLUMN empresa_id INT NULL AFTER acceso_id;

UPDATE usuarios      SET empresa_id = 1;
UPDATE categorias    SET empresa_id = 1;
UPDATE proveedores   SET empresa_id = 1;
UPDATE productos     SET empresa_id = 1;
UPDATE clientes      SET empresa_id = 1;
UPDATE cotizaciones  SET empresa_id = 1;
UPDATE ventas        SET empresa_id = 1;
UPDATE movimientos_inventario SET empresa_id = 1;
UPDATE parametros    SET empresa_id = 1;
UPDATE reglas_margen SET empresa_id = 1;
UPDATE reglas_envio  SET empresa_id = 1;

-- El usuario de plataforma no pertenece a ninguna empresa.
UPDATE usuarios u
   JOIN roles r ON r.rol_id = u.rol_id
   SET u.empresa_id = NULL
 WHERE r.ambito = 'PLATAFORMA';

-- Donde la pertenencia es obligatoria, se exige.
ALTER TABLE categorias    MODIFY empresa_id INT NOT NULL;
ALTER TABLE proveedores   MODIFY empresa_id INT NOT NULL;
ALTER TABLE productos     MODIFY empresa_id INT NOT NULL;
ALTER TABLE clientes      MODIFY empresa_id INT NOT NULL;
ALTER TABLE cotizaciones  MODIFY empresa_id INT NOT NULL;
ALTER TABLE ventas        MODIFY empresa_id INT NOT NULL;
ALTER TABLE movimientos_inventario MODIFY empresa_id INT NOT NULL;
ALTER TABLE parametros    MODIFY empresa_id INT NOT NULL;
ALTER TABLE reglas_margen MODIFY empresa_id INT NOT NULL;
ALTER TABLE reglas_envio  MODIFY empresa_id INT NOT NULL;

ALTER TABLE usuarios      ADD CONSTRAINT fk_usuarios_empresa      FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id);
ALTER TABLE categorias    ADD CONSTRAINT fk_categorias_empresa    FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id);
ALTER TABLE proveedores   ADD CONSTRAINT fk_proveedores_empresa   FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id);
ALTER TABLE productos     ADD CONSTRAINT fk_productos_empresa     FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id);
ALTER TABLE clientes      ADD CONSTRAINT fk_clientes_empresa      FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id);
ALTER TABLE cotizaciones  ADD CONSTRAINT fk_cotizaciones_empresa  FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id);
ALTER TABLE ventas        ADD CONSTRAINT fk_ventas_empresa        FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id);
ALTER TABLE movimientos_inventario ADD CONSTRAINT fk_movimientos_empresa FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id);
ALTER TABLE parametros    ADD CONSTRAINT fk_parametros_empresa    FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id);
ALTER TABLE reglas_margen ADD CONSTRAINT fk_reglas_margen_empresa FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id);
ALTER TABLE reglas_envio  ADD CONSTRAINT fk_reglas_envio_empresa  FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id);
ALTER TABLE auditoria_accesos ADD CONSTRAINT fk_auditoria_empresa FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id);

-- ------------------------------- unicidad dentro de cada empresa -------
-- Dos tiendas distintas pueden tener el mismo código de producto, la misma
-- categoría o el mismo cliente. La unicidad global dejaría de tener sentido
-- en cuanto entrara la segunda empresa.
ALTER TABLE productos  DROP INDEX uq_productos_codigo;
ALTER TABLE categorias DROP INDEX uq_categorias_nombre;
ALTER TABLE proveedores DROP INDEX uq_proveedores_nit;
ALTER TABLE clientes   DROP INDEX uq_clientes_documento;
ALTER TABLE parametros DROP INDEX uq_parametros_clave_vigencia;

ALTER TABLE productos   ADD CONSTRAINT uq_productos_codigo    UNIQUE (empresa_id, codigo);
ALTER TABLE categorias  ADD CONSTRAINT uq_categorias_nombre   UNIQUE (empresa_id, nombre);
ALTER TABLE proveedores ADD CONSTRAINT uq_proveedores_nit     UNIQUE (empresa_id, nit);
ALTER TABLE clientes    ADD CONSTRAINT uq_clientes_documento  UNIQUE (empresa_id, tipo_documento, nit);
ALTER TABLE parametros  ADD CONSTRAINT uq_parametros_clave    UNIQUE (empresa_id, clave, vigente_desde);

-- =====================================================================
-- 2. CATÁLOGO Y ABASTECIMIENTO
-- =====================================================================

-- La presentación forma parte de la identidad del producto: «Arroz Diana
-- 500 g» y «Arroz Diana 1 kg» son dos referencias distintas. Por eso el
-- inventario sigue contando unidades enteras.
ALTER TABLE productos
  ADD COLUMN sku           VARCHAR(40) NULL AFTER codigo,
  ADD COLUMN presentacion  VARCHAR(60) NULL AFTER nombre,
  ADD COLUMN codigo_barras VARCHAR(60) NULL AFTER sku;

UPDATE productos SET sku = codigo WHERE sku IS NULL;

ALTER TABLE productos MODIFY sku VARCHAR(40) NOT NULL;
ALTER TABLE productos ADD CONSTRAINT uq_productos_sku UNIQUE (empresa_id, sku);
CREATE INDEX ix_productos_barras ON productos (codigo_barras);

CREATE TABLE producto_proveedor (
  producto_proveedor_id INT          NOT NULL AUTO_INCREMENT,
  producto_id           INT          NOT NULL,
  proveedor_id          INT          NOT NULL,
  referencia            VARCHAR(60)  NOT NULL,
  costo                 DECIMAL(14,2) NOT NULL,
  plazo_dias            INT          NOT NULL DEFAULT 0,
  preferente            BOOL         NOT NULL DEFAULT 0,
  CONSTRAINT pk_producto_proveedor PRIMARY KEY (producto_proveedor_id),
  CONSTRAINT uq_producto_proveedor UNIQUE (producto_id, proveedor_id),
  CONSTRAINT fk_pp_producto  FOREIGN KEY (producto_id)
    REFERENCES productos (producto_id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_pp_proveedor FOREIGN KEY (proveedor_id)
    REFERENCES proveedores (proveedor_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT ck_pp_costo CHECK (costo > 0),
  CONSTRAINT ck_pp_plazo CHECK (plazo_dias >= 0)
) ENGINE=InnoDB;

-- La relación que ya existía se conserva como la referencia preferente.
INSERT INTO producto_proveedor (producto_id, proveedor_id, referencia, costo, preferente)
  SELECT producto_id, proveedor_id, codigo, costo, 1 FROM productos;

CREATE TABLE ordenes_compra (
  orden_id     BIGINT       NOT NULL AUTO_INCREMENT,
  empresa_id   INT          NOT NULL,
  proveedor_id INT          NOT NULL,
  usuario_id   INT          NOT NULL,
  consecutivo  VARCHAR(20)  NOT NULL,
  fecha        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  estado       ENUM('ABIERTA','PARCIAL','RECIBIDA','ANULADA')
                            NOT NULL DEFAULT 'ABIERTA',
  observacion  VARCHAR(200) NULL,
  CONSTRAINT pk_ordenes_compra PRIMARY KEY (orden_id),
  CONSTRAINT uq_ordenes_consecutivo UNIQUE (empresa_id, consecutivo),
  CONSTRAINT fk_oc_empresa    FOREIGN KEY (empresa_id)   REFERENCES empresas (empresa_id),
  CONSTRAINT fk_oc_proveedor  FOREIGN KEY (proveedor_id) REFERENCES proveedores (proveedor_id),
  CONSTRAINT fk_oc_usuario    FOREIGN KEY (usuario_id)   REFERENCES usuarios (usuario_id),
  INDEX ix_ordenes_estado (empresa_id, estado, fecha)
) ENGINE=InnoDB;

CREATE TABLE orden_compra_items (
  item_id           BIGINT        NOT NULL AUTO_INCREMENT,
  orden_id          BIGINT        NOT NULL,
  producto_id       INT           NOT NULL,
  cantidad          INT           NOT NULL,
  costo_unitario    DECIMAL(14,2) NOT NULL,
  cantidad_recibida INT           NOT NULL DEFAULT 0,
  CONSTRAINT pk_orden_compra_items PRIMARY KEY (item_id),
  CONSTRAINT uq_oci_orden_producto UNIQUE (orden_id, producto_id),
  CONSTRAINT fk_oci_orden    FOREIGN KEY (orden_id)
    REFERENCES ordenes_compra (orden_id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_oci_producto FOREIGN KEY (producto_id) REFERENCES productos (producto_id),
  CONSTRAINT ck_oci_cantidad CHECK (cantidad > 0),
  CONSTRAINT ck_oci_costo    CHECK (costo_unitario > 0),
  -- Una recepción no puede superar lo pedido: la regla vive aquí y no
  -- depende de que la aplicación se acuerde de comprobarla.
  CONSTRAINT ck_oci_recibida CHECK (cantidad_recibida >= 0
                                AND cantidad_recibida <= cantidad)
) ENGINE=InnoDB;

CREATE TABLE recepciones (
  recepcion_id BIGINT       NOT NULL AUTO_INCREMENT,
  orden_id     BIGINT       NOT NULL,
  usuario_id   INT          NOT NULL,
  fecha        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  observacion  VARCHAR(200) NULL,
  CONSTRAINT pk_recepciones PRIMARY KEY (recepcion_id),
  CONSTRAINT fk_rec_orden   FOREIGN KEY (orden_id)   REFERENCES ordenes_compra (orden_id),
  CONSTRAINT fk_rec_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios (usuario_id),
  INDEX ix_recepciones_orden (orden_id, fecha)
) ENGINE=InnoDB;

-- El movimiento de inventario queda ligado a la recepción que lo originó.
ALTER TABLE movimientos_inventario
  ADD COLUMN recepcion_id BIGINT NULL AFTER producto_id,
  ADD COLUMN motivo VARCHAR(200) NULL;

ALTER TABLE movimientos_inventario
  ADD CONSTRAINT fk_mov_recepcion FOREIGN KEY (recepcion_id)
    REFERENCES recepciones (recepcion_id);

-- =====================================================================
-- 3. COMERCIAL
-- =====================================================================

CREATE TABLE medios_pago (
  medio_id   INT         NOT NULL AUTO_INCREMENT,
  empresa_id INT         NOT NULL,
  nombre     VARCHAR(60) NOT NULL,
  tipo       ENUM('EFECTIVO','TARJETA','TRANSFERENCIA','CREDITO')
                         NOT NULL,
  activo     BOOL        NOT NULL DEFAULT 1,
  CONSTRAINT pk_medios_pago PRIMARY KEY (medio_id),
  CONSTRAINT uq_medios_pago UNIQUE (empresa_id, nombre),
  CONSTRAINT fk_medios_empresa FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id)
) ENGINE=InnoDB;

INSERT INTO medios_pago (empresa_id, nombre, tipo) VALUES
  (1, 'Efectivo',      'EFECTIVO'),
  (1, 'Transferencia', 'TRANSFERENCIA'),
  (1, 'Tarjeta',       'TARJETA'),
  (1, 'Crédito',       'CREDITO');

CREATE TABLE cierres_caja (
  cierre_id  BIGINT        NOT NULL AUTO_INCREMENT,
  empresa_id INT           NOT NULL,
  usuario_id INT           NOT NULL,
  apertura   DATETIME      NOT NULL,
  cierre     DATETIME      NULL,
  esperado   DECIMAL(14,2) NOT NULL DEFAULT 0,
  contado    DECIMAL(14,2) NULL,
  diferencia DECIMAL(14,2) GENERATED ALWAYS AS (contado - esperado) STORED,
  observacion VARCHAR(200) NULL,
  CONSTRAINT pk_cierres_caja PRIMARY KEY (cierre_id),
  CONSTRAINT fk_cierre_empresa FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id),
  CONSTRAINT fk_cierre_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios (usuario_id),
  CONSTRAINT ck_cierre_fechas CHECK (cierre IS NULL OR cierre >= apertura),
  INDEX ix_cierres_turno (empresa_id, usuario_id, apertura)
) ENGINE=InnoDB;

ALTER TABLE ventas
  ADD COLUMN medio_id    INT NULL AFTER forma_pago,
  ADD COLUMN estado_pago ENUM('PENDIENTE','PAGADA','ANULADA')
                         NOT NULL DEFAULT 'PAGADA' AFTER medio_id,
  ADD COLUMN cierre_id   BIGINT NULL AFTER estado_pago;

ALTER TABLE ventas
  ADD CONSTRAINT fk_ventas_medio  FOREIGN KEY (medio_id)  REFERENCES medios_pago (medio_id),
  ADD CONSTRAINT fk_ventas_cierre FOREIGN KEY (cierre_id) REFERENCES cierres_caja (cierre_id);

-- ------------------------------- forma de cálculo del margen -----------
-- Un veinte por ciento sobre el costo no es un veinte por ciento sobre el
-- precio de venta. La regla declara cuál de las dos cosas significa.
ALTER TABLE reglas_margen
  ADD COLUMN forma_calculo ENUM('SOBRE_COSTO','SOBRE_PRECIO')
      NOT NULL DEFAULT 'SOBRE_COSTO' AFTER forma_pago;

-- =====================================================================
-- 4. SINCRONIZACIÓN
-- =====================================================================

CREATE TABLE cola_sincronizacion (
  operacion_id          BIGINT   NOT NULL AUTO_INCREMENT,
  empresa_id            INT      NOT NULL,
  usuario_id            INT      NOT NULL,
  clave_idempotencia    CHAR(36) NOT NULL,
  tipo                  ENUM('VENTA','CIERRE','AJUSTE') NOT NULL,
  carga                 JSON     NOT NULL,
  registrada_dispositivo DATETIME NOT NULL,
  recibida_servidor     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  estado                ENUM('PENDIENTE','APLICADA','RECHAZADA')
                                 NOT NULL DEFAULT 'PENDIENTE',
  detalle_rechazo       VARCHAR(200) NULL,
  CONSTRAINT pk_cola_sincronizacion PRIMARY KEY (operacion_id),
  -- La clave la genera el dispositivo. Un reenvío de la misma operación
  -- choca aquí en lugar de duplicar la venta.
  CONSTRAINT uq_cola_idempotencia UNIQUE (clave_idempotencia),
  CONSTRAINT fk_cola_empresa FOREIGN KEY (empresa_id) REFERENCES empresas (empresa_id),
  CONSTRAINT fk_cola_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios (usuario_id),
  INDEX ix_cola_estado (empresa_id, estado, recibida_servidor)
) ENGINE=InnoDB;

CREATE TABLE descuadres_existencia (
  descuadre_id BIGINT NOT NULL AUTO_INCREMENT,
  operacion_id BIGINT NOT NULL,
  producto_id  INT    NOT NULL,
  solicitada   INT    NOT NULL,
  disponible   INT    NOT NULL,
  resultante   INT    NOT NULL,
  resuelto     BOOL   NOT NULL DEFAULT 0,
  CONSTRAINT pk_descuadres PRIMARY KEY (descuadre_id),
  CONSTRAINT fk_desc_operacion FOREIGN KEY (operacion_id)
    REFERENCES cola_sincronizacion (operacion_id) ON DELETE CASCADE,
  CONSTRAINT fk_desc_producto  FOREIGN KEY (producto_id) REFERENCES productos (producto_id),
  CONSTRAINT ck_desc_cantidad  CHECK (solicitada > 0),
  INDEX ix_descuadres_pendientes (resuelto, descuadre_id)
) ENGINE=InnoDB;

-- ---------------------------------------- parámetros de la plataforma --
INSERT INTO parametros (empresa_id, clave, valor, tipo_dato, descripcion, vigente_desde) VALUES
  (1, 'sgip.suscripcion.prueba.dias', '30', 'ENTERO',
   'Duración del periodo de prueba de una empresa nueva', '2026-01-01'),
  (1, 'sgip.sincronizacion.cola.maxima', '500', 'ENTERO',
   'Operaciones pendientes antes de bloquear la venta sin conexión', '2026-01-01')
ON DUPLICATE KEY UPDATE valor = VALUES(valor);

-- ---------------------------------------------------------------------
-- Verificaciones esperadas
-- ---------------------------------------------------------------------
-- 1) El super administrador no pertenece a ninguna empresa.
-- SELECT u.correo, r.nombre, r.ambito, u.empresa_id
--   FROM usuarios u JOIN roles r ON r.rol_id = u.rol_id;

-- 2) Dos empresas pueden usar el mismo código de producto.
-- INSERT INTO empresas (nit, razon_social) VALUES ('901111111-1', 'Tienda de prueba');
-- INSERT INTO categorias (empresa_id, nombre) VALUES (2, 'Abarrotes');
-- INSERT INTO productos (empresa_id, categoria_id, proveedor_id, codigo, sku, nombre, costo)
--   VALUES (2, LAST_INSERT_ID(), 1, 'PC-001', 'PC-001', 'Otro producto', 1000);

-- 3) Una recepción no puede superar lo pedido: debe fallar con error 4025.
-- UPDATE orden_compra_items SET cantidad_recibida = cantidad + 1;

-- 4) La misma operación enviada dos veces choca con la clave de
--    idempotencia: debe fallar con error 1062.
