-- ---------------------------------------------------------------------
-- V46 · Devoluciones, anulaciones y saldo a favor (RF-68)
--
-- Una venta de la caja se puede devolver en parte o anular entera. Lo que
-- se le devuelve al cliente sale de un turno (efectivo, tarjeta o
-- transferencia hechos por fuera) o se queda como «saldo a favor»: dinero
-- del cliente en la tienda, que gasta en una compra posterior (ADR-0033).
--
--   ventas.estado        Gana DEVUELTA_PARCIAL. ANULADA ya existía (V4). Se
--                        añade al final de la lista para que el cambio se
--                        haga sin reescribir la tabla.
--
--   devoluciones         Una por operación, con su consecutivo D-00000001
--                        (el número lo da `consecutivos`, tipo DEVOLUCION,
--                        que la V44 ya trae). Guarda quién la hizo y quién
--                        la autorizó. `cierre_id` es el turno por el que
--                        salió el dinero y es nulo si la devolución no movió
--                        dinero (la venta contra entrega sin cobrar no
--                        pagó nada); esa regla la sostiene la aplicación.
--                        Lleva la clave de idempotencia de la solicitud
--                        (un doble envío no la duplica; UNIQUE por empresa),
--                        la huella de su carga (la misma clave con otra carga
--                        es un error) y lo que se respondió al registrarla
--                        (el estado en que dejó la venta y lo que quedó por
--                        cobrar), para que el reintento reciba la misma
--                        respuesta aunque la venta haya cambiado después.
--
--   devolucion_items     Qué se devolvió: producto, unidades y valor. Una
--                        venta tiene una sola línea por producto, así que
--                        el producto identifica la línea. La suma de los
--                        valores de una línea, a lo largo de todas sus
--                        devoluciones, es exactamente el total de la línea.
--
--   reembolsos           Cómo se devolvió el dinero, por medio. Con el medio
--                        de sistema SALDO_A_FAVOR el dinero no sale de la
--                        caja: se abona al saldo del cliente.
--
--   saldos_cliente       Cuánto tiene a favor cada cliente. Nunca negativo.
--                        La fila nace con el primer abono.
--
--   movimientos_saldo    Cada abono (de una devolución) y cada uso (en una
--                        venta), para poder explicar el saldo.
--
-- Decisiones del esquema:
--
--   * Toda clave foránea nueva es compuesta con `empresa_id` (V11, V31): una
--     devolución, un reembolso o un saldo de otra empresa no se pueden
--     escribir ni por error. Por eso `devoluciones` lleva su propio
--     UNIQUE (empresa_id, devolucion_id), de donde cuelgan sus hijas.
--   * `creado_en` lo escribe la aplicación con la hora de Bogotá del reloj
--     (ADR-0030): no hay DEFAULT CURRENT_TIMESTAMP.
--   * `movimientos_saldo` dice de dónde viene cada fila según su tipo: un
--     abono viene de una devolución y un uso se hace en una venta.
--   * El pago de una venta con saldo a favor no lleva `cierre_id` (V44): no
--     pasa por la caja. Esa regla depende del tipo del medio y la sostiene
--     la aplicación, como ya decía la V44.
--
-- Requiere: V4, V11, V31, V40, V43 y V44.
-- ---------------------------------------------------------------------

-- 1. ventas
ALTER TABLE ventas
  MODIFY COLUMN estado ENUM('REGISTRADA','ANULADA','DEVUELTA_PARCIAL')
      NOT NULL DEFAULT 'REGISTRADA';

-- 2. devoluciones
CREATE TABLE devoluciones (
  devolucion_id  BIGINT       NOT NULL AUTO_INCREMENT,
  empresa_id     INT          NOT NULL,
  consecutivo    VARCHAR(20)  NOT NULL,
  venta_id       BIGINT       NOT NULL,
  motivo         VARCHAR(200) NOT NULL,
  autorizado_por INT          NOT NULL,
  registrado_por INT          NOT NULL,
  cierre_id      BIGINT       NULL,
  creado_en      DATETIME     NOT NULL,
  clave_idempotencia CHAR(36) NOT NULL,
  huella_carga       CHAR(64) NOT NULL,
  estado_resultante  VARCHAR(20)   NOT NULL,
  saldo_por_cobrar   DECIMAL(14,2) NOT NULL,
  CONSTRAINT pk_devoluciones PRIMARY KEY (devolucion_id),
  CONSTRAINT uq_devoluciones_empresa UNIQUE (empresa_id, devolucion_id),
  CONSTRAINT uq_devoluciones_consecutivo UNIQUE (empresa_id, consecutivo),
  CONSTRAINT uq_devoluciones_idempotencia UNIQUE (empresa_id, clave_idempotencia),
  CONSTRAINT fk_devoluciones_venta_emp FOREIGN KEY (empresa_id, venta_id)
    REFERENCES ventas (empresa_id, venta_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_devoluciones_autoriza_emp FOREIGN KEY (empresa_id, autorizado_por)
    REFERENCES usuarios (empresa_id, usuario_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_devoluciones_registra_emp FOREIGN KEY (empresa_id, registrado_por)
    REFERENCES usuarios (empresa_id, usuario_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_devoluciones_cierre_emp FOREIGN KEY (empresa_id, cierre_id)
    REFERENCES cierres_caja (empresa_id, cierre_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_devoluciones_motivo CHECK (CHAR_LENGTH(TRIM(motivo)) > 0),
  CONSTRAINT ck_devoluciones_resultado CHECK (
    estado_resultante IN ('DEVUELTA_PARCIAL','ANULADA') AND saldo_por_cobrar >= 0),
  INDEX ix_devoluciones_venta (empresa_id, venta_id),
  INDEX ix_devoluciones_cierre (empresa_id, cierre_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 3. devolucion_items
CREATE TABLE devolucion_items (
  devolucion_id BIGINT        NOT NULL,
  empresa_id    INT           NOT NULL,
  producto_id   INT           NOT NULL,
  cantidad      INT           NOT NULL,
  valor         DECIMAL(14,2) NOT NULL,
  CONSTRAINT pk_devolucion_items PRIMARY KEY (devolucion_id, producto_id),
  CONSTRAINT fk_devitems_devolucion_emp FOREIGN KEY (empresa_id, devolucion_id)
    REFERENCES devoluciones (empresa_id, devolucion_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_devitems_producto_emp FOREIGN KEY (empresa_id, producto_id)
    REFERENCES productos (empresa_id, producto_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_devitems_importes CHECK (cantidad > 0 AND valor >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 4. reembolsos
CREATE TABLE reembolsos (
  reembolso_id  BIGINT        NOT NULL AUTO_INCREMENT,
  devolucion_id BIGINT        NOT NULL,
  empresa_id    INT           NOT NULL,
  medio_id      INT           NOT NULL,
  monto         DECIMAL(14,2) NOT NULL,
  referencia    VARCHAR(60)   NULL,
  CONSTRAINT pk_reembolsos PRIMARY KEY (reembolso_id),
  CONSTRAINT fk_reembolsos_devolucion_emp FOREIGN KEY (empresa_id, devolucion_id)
    REFERENCES devoluciones (empresa_id, devolucion_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_reembolsos_medio_emp FOREIGN KEY (empresa_id, medio_id)
    REFERENCES medios_pago (empresa_id, medio_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_reembolsos_monto CHECK (monto > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 5. saldos_cliente
CREATE TABLE saldos_cliente (
  empresa_id INT           NOT NULL,
  cliente_id INT           NOT NULL,
  saldo      DECIMAL(14,2) NOT NULL DEFAULT 0,
  CONSTRAINT pk_saldos_cliente PRIMARY KEY (empresa_id, cliente_id),
  CONSTRAINT fk_saldos_cliente_emp FOREIGN KEY (empresa_id, cliente_id)
    REFERENCES clientes (empresa_id, cliente_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_saldos_cliente_saldo CHECK (saldo >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 6. movimientos_saldo
CREATE TABLE movimientos_saldo (
  movimiento_id BIGINT        NOT NULL AUTO_INCREMENT,
  empresa_id    INT           NOT NULL,
  cliente_id    INT           NOT NULL,
  tipo          ENUM('ABONO','USO') NOT NULL,
  monto         DECIMAL(14,2) NOT NULL,
  devolucion_id BIGINT        NULL,
  venta_id      BIGINT        NULL,
  creado_en     DATETIME      NOT NULL,
  CONSTRAINT pk_movimientos_saldo PRIMARY KEY (movimiento_id),
  CONSTRAINT fk_movsaldo_cliente_emp FOREIGN KEY (empresa_id, cliente_id)
    REFERENCES clientes (empresa_id, cliente_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_movsaldo_devolucion_emp FOREIGN KEY (empresa_id, devolucion_id)
    REFERENCES devoluciones (empresa_id, devolucion_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_movsaldo_venta_emp FOREIGN KEY (empresa_id, venta_id)
    REFERENCES ventas (empresa_id, venta_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_movsaldo_monto CHECK (monto > 0),
  CONSTRAINT ck_movsaldo_origen CHECK (
    (tipo = 'ABONO' AND devolucion_id IS NOT NULL AND venta_id IS NULL)
    OR (tipo = 'USO' AND venta_id IS NOT NULL AND devolucion_id IS NULL)),
  INDEX ix_movsaldo_cliente (empresa_id, cliente_id, creado_en)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- =====================================================================
-- Comprobación
-- =====================================================================
--   SELECT COUNT(*) FROM information_schema.referential_constraints
--    WHERE constraint_schema = DATABASE() AND constraint_name LIKE '%\_emp';
--   → 12 más que antes de esta migración
--
--   SELECT COUNT(*) FROM saldos_cliente WHERE saldo < 0;
--   → 0, y la base no deja que sea otra cosa
-- =====================================================================
