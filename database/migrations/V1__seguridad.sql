-- =====================================================================
-- SGIP — Sistema de Gestión de Inventario de Productos
-- Migración V1 · Dominio de seguridad
-- Motor: MySQL 8.0.16 o superior (requerido por las restricciones CHECK)
-- =====================================================================

SET NAMES utf8mb4;
SET time_zone = '-05:00';

-- ---------------------------------------------------------------------
-- roles
-- Catálogo de perfiles. Se normaliza como tabla y no como enumeración
-- para que la empresa pueda añadir perfiles sin recompilar el sistema.
-- ---------------------------------------------------------------------
CREATE TABLE roles (
  rol_id       INT           NOT NULL AUTO_INCREMENT,
  nombre       VARCHAR(30)   NOT NULL,
  descripcion  VARCHAR(120)  NULL,
  activo       BOOL          NOT NULL DEFAULT 1,
  CONSTRAINT pk_roles PRIMARY KEY (rol_id),
  CONSTRAINT uq_roles_nombre UNIQUE (nombre)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- usuarios
-- clave_hash almacena el resultado de la derivación PBKDF2 en el formato
-- pbkdf2$iteraciones$sal$hash. Nunca se almacena la contraseña en claro.
-- ---------------------------------------------------------------------
CREATE TABLE usuarios (
  usuario_id         INT           NOT NULL AUTO_INCREMENT,
  rol_id             INT           NOT NULL,
  nombre             VARCHAR(120)  NOT NULL,
  correo             VARCHAR(120)  NOT NULL,
  clave_hash         VARCHAR(255)  NOT NULL,
  activo             BOOL             NOT NULL DEFAULT 1,
  bloqueado          BOOL             NOT NULL DEFAULT 0,
  intentos_fallidos  TINYINT UNSIGNED NOT NULL DEFAULT 0,
  cambio_clave_req   BOOL             NOT NULL DEFAULT 1,
  creado_en          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  actualizado_en     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                   ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT pk_usuarios PRIMARY KEY (usuario_id),
  CONSTRAINT uq_usuarios_correo UNIQUE (correo),
  CONSTRAINT fk_usuarios_rol FOREIGN KEY (rol_id)
    REFERENCES roles (rol_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT ck_usuarios_correo CHECK (correo LIKE '%_@_%.__%'),
  CONSTRAINT ck_usuarios_intentos CHECK (intentos_fallidos BETWEEN 0 AND 50),
  INDEX ix_usuarios_rol (rol_id),
  INDEX ix_usuarios_activo (activo, bloqueado)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- sesiones
-- Se almacena el hash del token, no el token. Si la base de datos se
-- filtra, los tokens vigentes no quedan expuestos.
-- ---------------------------------------------------------------------
CREATE TABLE sesiones (
  sesion_id         BIGINT        NOT NULL AUTO_INCREMENT,
  usuario_id        INT           NOT NULL,
  token_hash        CHAR(64)      NOT NULL,
  inicio            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ultima_actividad  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expira            DATETIME      NOT NULL,
  dispositivo       VARCHAR(120)  NULL,
  origen            VARCHAR(60)   NULL,
  cerrada           BOOL          NOT NULL DEFAULT 0,
  CONSTRAINT pk_sesiones PRIMARY KEY (sesion_id),
  CONSTRAINT uq_sesiones_token UNIQUE (token_hash),
  CONSTRAINT fk_sesiones_usuario FOREIGN KEY (usuario_id)
    REFERENCES usuarios (usuario_id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT ck_sesiones_vigencia CHECK (expira > inicio),
  INDEX ix_sesiones_usuario (usuario_id, cerrada),
  INDEX ix_sesiones_expira (expira)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- auditoria_accesos
-- Conserva el correo utilizado aunque no corresponda a un usuario
-- registrado: ese es justamente el caso que interesa auditar.
-- ---------------------------------------------------------------------
CREATE TABLE auditoria_accesos (
  acceso_id   BIGINT        NOT NULL AUTO_INCREMENT,
  usuario_id  INT           NULL,
  correo      VARCHAR(120)  NOT NULL,
  exitoso     BOOL          NOT NULL,
  motivo      VARCHAR(60)   NULL,
  origen      VARCHAR(60)   NULL,
  fecha       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_auditoria_accesos PRIMARY KEY (acceso_id),
  CONSTRAINT fk_auditoria_usuario FOREIGN KEY (usuario_id)
    REFERENCES usuarios (usuario_id) ON UPDATE CASCADE ON DELETE SET NULL,
  INDEX ix_auditoria_correo_fecha (correo, fecha),
  INDEX ix_auditoria_fecha (fecha)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
