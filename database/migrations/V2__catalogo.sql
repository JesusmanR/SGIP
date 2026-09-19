-- =====================================================================
-- SGIP · Migración V2 · Catálogo, abastecimiento e inventario
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- categorias
-- Normalizada como tabla para poder asociarle reglas de margen y de
-- impuesto mediante clave foránea.
-- ---------------------------------------------------------------------
CREATE TABLE categorias (
  categoria_id  INT           NOT NULL AUTO_INCREMENT,
  nombre        VARCHAR(80)   NOT NULL,
  descripcion   VARCHAR(200)  NULL,
  activa        BOOL          NOT NULL DEFAULT 1,
  CONSTRAINT pk_categorias PRIMARY KEY (categoria_id),
  CONSTRAINT uq_categorias_nombre UNIQUE (nombre)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- proveedores
-- credencial_servicio guarda el secreto cifrado por la aplicación.
-- La base de datos nunca recibe la credencial en claro.
-- ---------------------------------------------------------------------
CREATE TABLE proveedores (
  proveedor_id         INT           NOT NULL AUTO_INCREMENT,
  razon_social         VARCHAR(160)  NOT NULL,
  nit                  VARCHAR(20)   NOT NULL,
  contacto             VARCHAR(120)  NULL,
  correo               VARCHAR(120)  NULL,
  telefono             VARCHAR(20)   NULL,
  plazo_dias           INT           NOT NULL DEFAULT 0,
  origen_lista         ENUM('NINGUNO','ARCHIVO','SERVICIO_WEB')
                                     NOT NULL DEFAULT 'NINGUNO',
  url_servicio         VARCHAR(255)  NULL,
  credencial_servicio  VARBINARY(512) NULL,
  activo               BOOL           NOT NULL DEFAULT 1,
  creado_en            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_proveedores PRIMARY KEY (proveedor_id),
  CONSTRAINT uq_proveedores_nit UNIQUE (nit),
  CONSTRAINT ck_proveedores_plazo CHECK (plazo_dias >= 0),
  CONSTRAINT ck_proveedores_url CHECK (
    origen_lista <> 'SERVICIO_WEB' OR url_servicio IS NOT NULL),
  INDEX ix_proveedores_activo (activo)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- productos
-- estado_stock es columna generada: depende únicamente de otras columnas
-- de la misma fila, condición que MySQL exige para las columnas
-- generadas. Los precios de venta NO pueden serlo, porque dependen de
-- reglas_margen, que es otra tabla; se resuelven en la vista
-- v_precios_producto de la migración V5.
-- ---------------------------------------------------------------------
CREATE TABLE productos (
  producto_id        INT           NOT NULL AUTO_INCREMENT,
  categoria_id       INT           NOT NULL,
  proveedor_id       INT           NOT NULL,
  codigo             VARCHAR(60)   NOT NULL,
  nombre             VARCHAR(160)  NOT NULL,
  descripcion        VARCHAR(400)  NULL,
  costo              DECIMAL(14,2) NOT NULL,
  existencia         INT           NOT NULL DEFAULT 0,
  existencia_minima  INT           NOT NULL DEFAULT 5,
  estado_stock       ENUM('ACTIVO','CRITICO','AGOTADO')
                     GENERATED ALWAYS AS (
                       CASE
                         WHEN existencia = 0 THEN 'AGOTADO'
                         WHEN existencia <= existencia_minima THEN 'CRITICO'
                         ELSE 'ACTIVO'
                       END
                     ) STORED,
  activo             BOOL          NOT NULL DEFAULT 1,
  creado_en          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  actualizado_en     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                   ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT pk_productos PRIMARY KEY (producto_id),
  CONSTRAINT uq_productos_codigo UNIQUE (codigo),
  CONSTRAINT fk_productos_categoria FOREIGN KEY (categoria_id)
    REFERENCES categorias (categoria_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_productos_proveedor FOREIGN KEY (proveedor_id)
    REFERENCES proveedores (proveedor_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT ck_productos_costo CHECK (costo > 0),
  CONSTRAINT ck_productos_existencia CHECK (existencia >= 0),
  CONSTRAINT ck_productos_minima CHECK (existencia_minima >= 0),
  INDEX ix_productos_categoria (categoria_id),
  INDEX ix_productos_proveedor (proveedor_id),
  INDEX ix_productos_estado (estado_stock, activo),
  INDEX ix_productos_nombre (nombre)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- mapeos_proveedor
-- Correspondencia entre las columnas del archivo del proveedor y los
-- campos del sistema. Es lo que permite absorber formatos heterogéneos
-- sin escribir código por proveedor.
-- ---------------------------------------------------------------------
CREATE TABLE mapeos_proveedor (
  mapeo_id         INT           NOT NULL AUTO_INCREMENT,
  proveedor_id     INT           NOT NULL,
  campo_sistema    VARCHAR(60)   NOT NULL,
  columna_origen   VARCHAR(60)   NOT NULL,
  obligatorio      BOOL          NOT NULL DEFAULT 0,
  fila_encabezado  INT           NOT NULL DEFAULT 1,
  hoja             VARCHAR(60)   NULL,
  CONSTRAINT pk_mapeos_proveedor PRIMARY KEY (mapeo_id),
  CONSTRAINT uq_mapeos_campo UNIQUE (proveedor_id, campo_sistema),
  CONSTRAINT fk_mapeos_proveedor FOREIGN KEY (proveedor_id)
    REFERENCES proveedores (proveedor_id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT ck_mapeos_fila CHECK (fila_encabezado >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- cargas_precios · encabezado de cada importación
-- ---------------------------------------------------------------------
CREATE TABLE cargas_precios (
  carga_id              BIGINT        NOT NULL AUTO_INCREMENT,
  proveedor_id          INT           NOT NULL,
  usuario_id            INT           NOT NULL,
  origen                ENUM('ARCHIVO','SERVICIO_WEB') NOT NULL,
  nombre_archivo        VARCHAR(200)  NULL,
  estado                ENUM('CARGADA','VALIDADA','APLICADA','RECHAZADA')
                                      NOT NULL DEFAULT 'CARGADA',
  fecha                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  fecha_aplicacion      DATETIME      NULL,
  registros_leidos      INT           NOT NULL DEFAULT 0,
  registros_aceptados   INT           NOT NULL DEFAULT 0,
  registros_rechazados  INT           NOT NULL DEFAULT 0,
  CONSTRAINT pk_cargas_precios PRIMARY KEY (carga_id),
  CONSTRAINT fk_cargas_proveedor FOREIGN KEY (proveedor_id)
    REFERENCES proveedores (proveedor_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_cargas_usuario FOREIGN KEY (usuario_id)
    REFERENCES usuarios (usuario_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT ck_cargas_totales CHECK (
    registros_leidos >= registros_aceptados + registros_rechazados),
  CONSTRAINT ck_cargas_aplicacion CHECK (
    estado <> 'APLICADA' OR fecha_aplicacion IS NOT NULL),
  INDEX ix_cargas_proveedor_fecha (proveedor_id, fecha),
  INDEX ix_cargas_estado (estado)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- detalle_carga_precios
-- producto_id admite nulo: un registro rechazado por referencia
-- inexistente no tiene producto al cual apuntar y aun así debe quedar
-- registrado con su motivo.
-- ---------------------------------------------------------------------
CREATE TABLE detalle_carga_precios (
  detalle_id          BIGINT        NOT NULL AUTO_INCREMENT,
  carga_id            BIGINT        NOT NULL,
  producto_id         INT           NULL,
  referencia_origen   VARCHAR(80)   NOT NULL,
  descripcion_origen  VARCHAR(200)  NULL,
  costo               DECIMAL(14,2) NULL,
  costo_anterior      DECIMAL(14,2) NULL,
  aceptado            BOOL          NOT NULL DEFAULT 0,
  motivo_rechazo      VARCHAR(200)  NULL,
  CONSTRAINT pk_detalle_carga PRIMARY KEY (detalle_id),
  CONSTRAINT fk_detalle_carga FOREIGN KEY (carga_id)
    REFERENCES cargas_precios (carga_id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_detalle_producto FOREIGN KEY (producto_id)
    REFERENCES productos (producto_id) ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT ck_detalle_costo CHECK (costo IS NULL OR costo > 0),
  CONSTRAINT ck_detalle_rechazo CHECK (
    aceptado = 1 OR motivo_rechazo IS NOT NULL),
  INDEX ix_detalle_carga_aceptado (carga_id, aceptado),
  INDEX ix_detalle_referencia (referencia_origen)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- historico_costos
-- El costo no se sobrescribe: se cierra el periodo vigente y se abre
-- uno nuevo. Permite reconstruir el costo de cualquier fecha pasada.
-- ---------------------------------------------------------------------
CREATE TABLE historico_costos (
  historico_id   BIGINT        NOT NULL AUTO_INCREMENT,
  producto_id    INT           NOT NULL,
  costo          DECIMAL(14,2) NOT NULL,
  vigente_desde  DATE          NOT NULL,
  vigente_hasta  DATE          NULL,
  carga_id       BIGINT        NULL,
  CONSTRAINT pk_historico_costos PRIMARY KEY (historico_id),
  CONSTRAINT uq_historico_vigencia UNIQUE (producto_id, vigente_desde),
  CONSTRAINT fk_historico_producto FOREIGN KEY (producto_id)
    REFERENCES productos (producto_id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_historico_carga FOREIGN KEY (carga_id)
    REFERENCES cargas_precios (carga_id) ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT ck_historico_costo CHECK (costo > 0),
  CONSTRAINT ck_historico_rango CHECK (
    vigente_hasta IS NULL OR vigente_hasta >= vigente_desde),
  INDEX ix_historico_producto (producto_id, vigente_desde)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- movimientos_inventario
-- Registro inmutable. Conserva existencia anterior y posterior, de modo
-- que la existencia actual pueda reconstruirse sumando movimientos.
-- ---------------------------------------------------------------------
CREATE TABLE movimientos_inventario (
  movimiento_id         BIGINT        NOT NULL AUTO_INCREMENT,
  producto_id           INT           NOT NULL,
  usuario_id            INT           NOT NULL,
  tipo                  ENUM('INGRESO','EGRESO','DEVOLUCION','AJUSTE') NOT NULL,
  cantidad              INT           NOT NULL,
  existencia_anterior   INT           NOT NULL,
  existencia_posterior  INT           NOT NULL,
  documento             VARCHAR(60)   NULL,
  observacion           VARCHAR(200)  NULL,
  fecha                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_movimientos PRIMARY KEY (movimiento_id),
  CONSTRAINT fk_movimientos_producto FOREIGN KEY (producto_id)
    REFERENCES productos (producto_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_movimientos_usuario FOREIGN KEY (usuario_id)
    REFERENCES usuarios (usuario_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT ck_movimientos_cantidad CHECK (cantidad <> 0),
  CONSTRAINT ck_movimientos_anterior CHECK (existencia_anterior >= 0),
  CONSTRAINT ck_movimientos_posterior CHECK (existencia_posterior >= 0),
  CONSTRAINT ck_movimientos_ajuste CHECK (
    tipo <> 'AJUSTE' OR observacion IS NOT NULL),
  INDEX ix_movimientos_producto_fecha (producto_id, fecha),
  INDEX ix_movimientos_tipo_fecha (tipo, fecha)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
