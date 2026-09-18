-- =====================================================================
-- SGIP · Migración V3 · Parametrización comercial y tributaria
--
-- Ninguna de estas reglas se codifica en la aplicación. Todas llevan
-- fecha de vigencia y se conservan al ser reemplazadas, de modo que un
-- documento emitido en el pasado pueda reconstruirse con las
-- condiciones que regían en su fecha de emisión.
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- parametros · configuración general clave-valor con vigencia
-- ---------------------------------------------------------------------
CREATE TABLE parametros (
  parametro_id   INT           NOT NULL AUTO_INCREMENT,
  clave          VARCHAR(80)   NOT NULL,
  valor          VARCHAR(200)  NOT NULL,
  tipo_dato      ENUM('ENTERO','DECIMAL','TEXTO','BOOLEANO','FECHA')
                               NOT NULL DEFAULT 'TEXTO',
  descripcion    VARCHAR(200)  NULL,
  vigente_desde  DATE          NOT NULL,
  vigente_hasta  DATE          NULL,
  CONSTRAINT pk_parametros PRIMARY KEY (parametro_id),
  CONSTRAINT uq_parametros_clave_vigencia UNIQUE (clave, vigente_desde),
  CONSTRAINT ck_parametros_rango CHECK (
    vigente_hasta IS NULL OR vigente_hasta >= vigente_desde),
  INDEX ix_parametros_clave (clave, vigente_desde)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- uvt_anual
-- La unidad de valor tributario se fija por resolución cada año. Los
-- umbrales tributarios se guardan en UVT y se convierten a pesos con
-- esta tabla, según la fecha del documento.
-- ---------------------------------------------------------------------
CREATE TABLE uvt_anual (
  anio        SMALLINT      NOT NULL,
  valor       DECIMAL(12,2) NOT NULL,
  resolucion  VARCHAR(60)   NULL,
  CONSTRAINT pk_uvt_anual PRIMARY KEY (anio),
  CONSTRAINT ck_uvt_valor CHECK (valor > 0),
  CONSTRAINT ck_uvt_anio CHECK (anio BETWEEN 2006 AND 2100)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- reglas_iva
-- El impuesto no es una tarifa única. El artículo 424 del Estatuto
-- Tributario excluye los computadores personales hasta 50 UVT y los
-- dispositivos móviles inteligentes hasta 22 UVT. La exclusión opera
-- POR DEBAJO del umbral: el bien económico no causa impuesto y el que
-- supera el tope sí lo causa a la tarifa general.
--
-- Excluido y exento son tratamientos distintos. El exento se grava a
-- tarifa cero y da derecho a devolución del impuesto pagado en la
-- adquisición; el excluido no causa el impuesto y no otorga ese
-- derecho. Por eso son valores diferentes de la enumeración.
--
-- categoria_id nulo define la regla general del catálogo.
-- ---------------------------------------------------------------------
CREATE TABLE reglas_iva (
  regla_iva_id   INT           NOT NULL AUTO_INCREMENT,
  categoria_id   INT           NULL,
  tratamiento    ENUM('GRAVADO','EXCLUIDO_HASTA_UMBRAL','EXCLUIDO','EXENTO')
                               NOT NULL DEFAULT 'GRAVADO',
  umbral_uvt     DECIMAL(8,2)  NULL,
  tarifa         DECIMAL(5,4)  NOT NULL DEFAULT 0.1900,
  vigente_desde  DATE          NOT NULL,
  vigente_hasta  DATE          NULL,
  fundamento     VARCHAR(160)  NULL,
  CONSTRAINT pk_reglas_iva PRIMARY KEY (regla_iva_id),
  CONSTRAINT fk_reglas_iva_categoria FOREIGN KEY (categoria_id)
    REFERENCES categorias (categoria_id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT ck_reglas_iva_tarifa CHECK (tarifa >= 0 AND tarifa <= 1),
  -- La comparación con NULL devuelve NULL, y una restricción CHECK solo
  -- falla cuando evalúa a FALSE. Sin la verificación explícita de nulidad
  -- se colaría una regla con umbral vacío.
  CONSTRAINT ck_reglas_iva_umbral CHECK (
    tratamiento <> 'EXCLUIDO_HASTA_UMBRAL'
    OR (umbral_uvt IS NOT NULL AND umbral_uvt > 0)),
  CONSTRAINT ck_reglas_iva_rango CHECK (
    vigente_hasta IS NULL OR vigente_hasta >= vigente_desde),
  INDEX ix_reglas_iva_categoria (categoria_id, vigente_desde)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- reglas_margen
-- Ante varias reglas aplicables prevalece la de ámbito más específico:
-- categoría y proveedor, luego categoría, luego proveedor, luego
-- general. Esa precedencia se resuelve en la vista v_regla_margen.
-- ---------------------------------------------------------------------
CREATE TABLE reglas_margen (
  regla_id       INT           NOT NULL AUTO_INCREMENT,
  categoria_id   INT           NULL,
  proveedor_id   INT           NULL,
  forma_pago     ENUM('CONTADO','CREDITO') NOT NULL,
  factor         DECIMAL(6,4)  NOT NULL,
  vigente_desde  DATE          NOT NULL,
  vigente_hasta  DATE          NULL,
  CONSTRAINT pk_reglas_margen PRIMARY KEY (regla_id),
  CONSTRAINT fk_reglas_margen_categoria FOREIGN KEY (categoria_id)
    REFERENCES categorias (categoria_id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_reglas_margen_proveedor FOREIGN KEY (proveedor_id)
    REFERENCES proveedores (proveedor_id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT ck_reglas_margen_factor CHECK (factor > 1),
  CONSTRAINT ck_reglas_margen_rango CHECK (
    vigente_hasta IS NULL OR vigente_hasta >= vigente_desde),
  INDEX ix_reglas_margen_ambito (forma_pago, categoria_id, proveedor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- reglas_envio
-- base_calculo distingue si el umbral se evalúa sobre el subtotal o
-- sobre el total con impuesto incluido. Se evalúa sobre el subtotal:
-- con productos excluidos de impuesto en el catálogo, usar el total
-- haría que dos carritos con la misma mercancía cayeran en lados
-- distintos del umbral solo por el tratamiento tributario.
--
-- La comparación es estricta: una compra igual al umbral no alcanza el
-- beneficio, tiene que superarlo.
--
-- permite_obsequio habilita que el vendedor regale el envío por debajo
-- del umbral. Cuando lo hace queda registrado con motivo y responsable
-- en el documento, porque es un descuento y debe poder auditarse.
-- ---------------------------------------------------------------------
CREATE TABLE reglas_envio (
  regla_envio_id   INT           NOT NULL AUTO_INCREMENT,
  umbral_valor     DECIMAL(14,2) NOT NULL,
  base_calculo     ENUM('SUBTOTAL','TOTAL_CON_IVA') NOT NULL DEFAULT 'SUBTOTAL',
  costo_envio      DECIMAL(14,2) NOT NULL DEFAULT 0,
  permite_obsequio TINYINT(1)    NOT NULL DEFAULT 1,
  vigente_desde    DATE          NOT NULL,
  vigente_hasta    DATE          NULL,
  CONSTRAINT pk_reglas_envio PRIMARY KEY (regla_envio_id),
  CONSTRAINT ck_reglas_envio_umbral CHECK (umbral_valor >= 0),
  CONSTRAINT ck_reglas_envio_costo CHECK (costo_envio >= 0),
  CONSTRAINT ck_reglas_envio_rango CHECK (
    vigente_hasta IS NULL OR vigente_hasta >= vigente_desde)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- calendario_habil
-- La vigencia de las cotizaciones se cuenta en días hábiles. El
-- calendario colombiano traslada varias festividades al lunes siguiente
-- por efecto de la Ley 51 de 1983, de modo que no puede derivarse con
-- una fórmula de fechas fijas.
-- ---------------------------------------------------------------------
CREATE TABLE calendario_habil (
  fecha        DATE         NOT NULL,
  descripcion  VARCHAR(80)  NOT NULL,
  tipo         ENUM('FESTIVO','NO_LABORAL') NOT NULL DEFAULT 'FESTIVO',
  CONSTRAINT pk_calendario_habil PRIMARY KEY (fecha)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
