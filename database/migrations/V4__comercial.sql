-- =====================================================================
-- SGIP · Migración V4 · Dominio comercial y facturación electrónica
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- clientes
-- Separada de usuarios: un cliente no necesariamente accede al sistema
-- y un usuario no necesariamente es cliente. Unirlas dejaría la mayoría
-- de las columnas en nulo.
-- autorizacion_datos registra el consentimiento exigido por la Ley 1581
-- de 2012 para el tratamiento de datos personales.
-- ---------------------------------------------------------------------
CREATE TABLE clientes (
  cliente_id          INT           NOT NULL AUTO_INCREMENT,
  razon_social        VARCHAR(160)  NOT NULL,
  tipo_documento      ENUM('NIT','CC','CE','PASAPORTE') NOT NULL DEFAULT 'NIT',
  nit                 VARCHAR(20)   NOT NULL,
  correo              VARCHAR(120)  NULL,
  telefono            VARCHAR(20)   NULL,
  direccion           VARCHAR(200)  NULL,
  ciudad              VARCHAR(80)   NULL,
  autorizacion_datos  DATETIME      NULL,
  medio_autorizacion  VARCHAR(60)   NULL,
  activo              BOOL                              NOT NULL DEFAULT 1,
  creado_en           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_clientes PRIMARY KEY (cliente_id),
  CONSTRAINT uq_clientes_documento UNIQUE (tipo_documento, nit),
  CONSTRAINT ck_clientes_autorizacion CHECK (
    autorizacion_datos IS NULL OR medio_autorizacion IS NOT NULL),
  INDEX ix_clientes_razon (razon_social)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- cotizaciones
-- total es columna generada porque depende solo de columnas de su
-- propia fila. Los importes se recalculan desde las líneas por la
-- aplicación al agregar o retirar ítems.
-- ---------------------------------------------------------------------
CREATE TABLE cotizaciones (
  cotizacion_id      BIGINT        NOT NULL AUTO_INCREMENT,
  consecutivo        VARCHAR(20)   NOT NULL,
  cliente_id         INT           NOT NULL,
  usuario_id         INT           NOT NULL,
  forma_pago         ENUM('CONTADO','CREDITO') NOT NULL DEFAULT 'CONTADO',
  fecha_emision      DATE          NULL,
  fecha_vencimiento  DATE          NULL,
  estado             ENUM('BORRADOR','VIGENTE','VENCIDA','CONVERTIDA','ANULADA')
                                   NOT NULL DEFAULT 'BORRADOR',
  subtotal           DECIMAL(14,2) NOT NULL DEFAULT 0,
  descuento          DECIMAL(14,2) NOT NULL DEFAULT 0,
  iva                DECIMAL(14,2) NOT NULL DEFAULT 0,
  envio              DECIMAL(14,2) NOT NULL DEFAULT 0,
  origen_envio       ENUM('REGLA','MANUAL') NOT NULL DEFAULT 'REGLA',
  motivo_envio       VARCHAR(200)  NULL,
  envio_autorizado_por INT         NULL,
  total              DECIMAL(14,2)
                     GENERATED ALWAYS AS (subtotal - descuento + iva + envio) STORED,
  observaciones      VARCHAR(400)  NULL,
  creado_en          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_cotizaciones PRIMARY KEY (cotizacion_id),
  CONSTRAINT uq_cotizaciones_consecutivo UNIQUE (consecutivo),
  CONSTRAINT fk_cotizaciones_cliente FOREIGN KEY (cliente_id)
    REFERENCES clientes (cliente_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_cotizaciones_usuario FOREIGN KEY (usuario_id)
    REFERENCES usuarios (usuario_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  -- Sin acción referencial: MySQL no admite que una columna sujeta a
  -- ON UPDATE CASCADE participe además en una restricción CHECK.
  CONSTRAINT fk_cotizaciones_envio_autoriza FOREIGN KEY (envio_autorizado_por)
    REFERENCES usuarios (usuario_id),
  -- Un envío decidido a mano es un descuento: exige motivo y responsable.
  CONSTRAINT ck_cotizaciones_envio_manual CHECK (
    origen_envio <> 'MANUAL'
    OR (motivo_envio IS NOT NULL AND envio_autorizado_por IS NOT NULL)),
  CONSTRAINT ck_cotizaciones_importes CHECK (
    subtotal >= 0 AND descuento >= 0 AND iva >= 0 AND envio >= 0),
  CONSTRAINT ck_cotizaciones_descuento CHECK (descuento <= subtotal),
  CONSTRAINT ck_cotizaciones_vigencia CHECK (
    fecha_vencimiento IS NULL OR fecha_emision IS NULL
    OR fecha_vencimiento >= fecha_emision),
  CONSTRAINT ck_cotizaciones_emision CHECK (
    estado = 'BORRADOR' OR fecha_emision IS NOT NULL),
  INDEX ix_cotizaciones_cliente (cliente_id),
  INDEX ix_cotizaciones_estado (estado, fecha_vencimiento)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- cotizacion_items
-- precio_unitario y tarifa_iva se almacenan aunque puedan derivarse del
-- producto: son dato histórico, no dato derivado. Si el costo cambia
-- mañana, la oferta emitida hoy debe conservar lo que se ofreció.
-- ---------------------------------------------------------------------
CREATE TABLE cotizacion_items (
  item_id         BIGINT        NOT NULL AUTO_INCREMENT,
  cotizacion_id   BIGINT        NOT NULL,
  producto_id     INT           NOT NULL,
  cantidad        INT           NOT NULL,
  precio_unitario DECIMAL(14,2) NOT NULL,
  tarifa_iva      DECIMAL(5,4)  NOT NULL DEFAULT 0,
  tratamiento_iva ENUM('GRAVADO','EXCLUIDO','EXENTO') NOT NULL DEFAULT 'GRAVADO',
  subtotal        DECIMAL(14,2)
                  GENERATED ALWAYS AS (cantidad * precio_unitario) STORED,
  valor_iva       DECIMAL(14,2)
                  GENERATED ALWAYS AS (
                    ROUND(cantidad * precio_unitario * tarifa_iva, 2)) STORED,
  CONSTRAINT pk_cotizacion_items PRIMARY KEY (item_id),
  CONSTRAINT uq_cotizacion_items UNIQUE (cotizacion_id, producto_id),
  CONSTRAINT fk_cotitems_cotizacion FOREIGN KEY (cotizacion_id)
    REFERENCES cotizaciones (cotizacion_id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_cotitems_producto FOREIGN KEY (producto_id)
    REFERENCES productos (producto_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT ck_cotitems_cantidad CHECK (cantidad > 0),
  CONSTRAINT ck_cotitems_precio CHECK (precio_unitario > 0),
  CONSTRAINT ck_cotitems_tarifa CHECK (tarifa_iva >= 0 AND tarifa_iva <= 1),
  INDEX ix_cotitems_producto (producto_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- ventas
-- cotizacion_id es única: una cotización se convierte en una sola venta.
-- ---------------------------------------------------------------------
CREATE TABLE ventas (
  venta_id       BIGINT        NOT NULL AUTO_INCREMENT,
  consecutivo    VARCHAR(20)   NOT NULL,
  cliente_id     INT           NOT NULL,
  usuario_id     INT           NOT NULL,
  cotizacion_id  BIGINT        NULL,
  fecha          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  forma_pago     ENUM('CONTADO','CREDITO') NOT NULL,
  plazo_dias     INT           NOT NULL DEFAULT 0,
  subtotal       DECIMAL(14,2) NOT NULL DEFAULT 0,
  descuento      DECIMAL(14,2) NOT NULL DEFAULT 0,
  iva            DECIMAL(14,2) NOT NULL DEFAULT 0,
  envio          DECIMAL(14,2) NOT NULL DEFAULT 0,
  origen_envio   ENUM('REGLA','MANUAL') NOT NULL DEFAULT 'REGLA',
  motivo_envio   VARCHAR(200)  NULL,
  envio_autorizado_por INT     NULL,
  total          DECIMAL(14,2)
                 GENERATED ALWAYS AS (subtotal - descuento + iva + envio) STORED,
  estado         ENUM('REGISTRADA','ANULADA') NOT NULL DEFAULT 'REGISTRADA',
  CONSTRAINT pk_ventas PRIMARY KEY (venta_id),
  CONSTRAINT uq_ventas_consecutivo UNIQUE (consecutivo),
  CONSTRAINT uq_ventas_cotizacion UNIQUE (cotizacion_id),
  CONSTRAINT fk_ventas_cliente FOREIGN KEY (cliente_id)
    REFERENCES clientes (cliente_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_ventas_usuario FOREIGN KEY (usuario_id)
    REFERENCES usuarios (usuario_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_ventas_cotizacion FOREIGN KEY (cotizacion_id)
    REFERENCES cotizaciones (cotizacion_id) ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_ventas_envio_autoriza FOREIGN KEY (envio_autorizado_por)
    REFERENCES usuarios (usuario_id),
  CONSTRAINT ck_ventas_envio_manual CHECK (
    origen_envio <> 'MANUAL'
    OR (motivo_envio IS NOT NULL AND envio_autorizado_por IS NOT NULL)),
  CONSTRAINT ck_ventas_importes CHECK (
    subtotal >= 0 AND descuento >= 0 AND iva >= 0 AND envio >= 0),
  CONSTRAINT ck_ventas_plazo CHECK (
    plazo_dias >= 0 AND (forma_pago = 'CREDITO' OR plazo_dias = 0)),
  INDEX ix_ventas_cliente (cliente_id),
  INDEX ix_ventas_fecha (fecha)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- venta_items
-- ---------------------------------------------------------------------
CREATE TABLE venta_items (
  item_id         BIGINT        NOT NULL AUTO_INCREMENT,
  venta_id        BIGINT        NOT NULL,
  producto_id     INT           NOT NULL,
  cantidad        INT           NOT NULL,
  precio_unitario DECIMAL(14,2) NOT NULL,
  costo_unitario  DECIMAL(14,2) NOT NULL,
  tarifa_iva      DECIMAL(5,4)  NOT NULL DEFAULT 0,
  tratamiento_iva ENUM('GRAVADO','EXCLUIDO','EXENTO') NOT NULL DEFAULT 'GRAVADO',
  subtotal        DECIMAL(14,2)
                  GENERATED ALWAYS AS (cantidad * precio_unitario) STORED,
  valor_iva       DECIMAL(14,2)
                  GENERATED ALWAYS AS (
                    ROUND(cantidad * precio_unitario * tarifa_iva, 2)) STORED,
  utilidad        DECIMAL(14,2)
                  GENERATED ALWAYS AS (
                    cantidad * (precio_unitario - costo_unitario)) STORED,
  CONSTRAINT pk_venta_items PRIMARY KEY (item_id),
  CONSTRAINT uq_venta_items UNIQUE (venta_id, producto_id),
  CONSTRAINT fk_venitems_venta FOREIGN KEY (venta_id)
    REFERENCES ventas (venta_id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_venitems_producto FOREIGN KEY (producto_id)
    REFERENCES productos (producto_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT ck_venitems_cantidad CHECK (cantidad > 0),
  CONSTRAINT ck_venitems_precio CHECK (precio_unitario > 0),
  CONSTRAINT ck_venitems_costo CHECK (costo_unitario > 0),
  CONSTRAINT ck_venitems_tarifa CHECK (tarifa_iva >= 0 AND tarifa_iva <= 1),
  INDEX ix_venitems_producto (producto_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- facturas_electronicas
-- El código único lo devuelve la autoridad tributaria tras validar.
-- Una factura rechazada conserva el motivo y no revierte la venta.
-- ---------------------------------------------------------------------
CREATE TABLE facturas_electronicas (
  factura_id       BIGINT        NOT NULL AUTO_INCREMENT,
  venta_id         BIGINT        NOT NULL,
  numero           VARCHAR(20)   NULL,
  cufe             VARCHAR(96)   NULL,
  estado           ENUM('GENERADA','ENVIADA','VALIDADA','RECHAZADA')
                                 NOT NULL DEFAULT 'GENERADA',
  fecha_generacion DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  fecha_envio      DATETIME      NULL,
  fecha_validacion DATETIME      NULL,
  intentos         TINYINT UNSIGNED NOT NULL DEFAULT 0,
  respuesta        TEXT          NULL,
  CONSTRAINT pk_facturas PRIMARY KEY (factura_id),
  CONSTRAINT uq_facturas_venta UNIQUE (venta_id),
  CONSTRAINT uq_facturas_cufe UNIQUE (cufe),
  CONSTRAINT fk_facturas_venta FOREIGN KEY (venta_id)
    REFERENCES ventas (venta_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT ck_facturas_validada CHECK (
    estado <> 'VALIDADA' OR (cufe IS NOT NULL AND fecha_validacion IS NOT NULL)),
  CONSTRAINT ck_facturas_rechazada CHECK (
    estado <> 'RECHAZADA' OR respuesta IS NOT NULL),
  INDEX ix_facturas_estado (estado)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
