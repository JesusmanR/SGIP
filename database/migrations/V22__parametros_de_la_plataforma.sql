-- =====================================================================
-- SGIP · Migración V22 · Los parámetros de la plataforma dejan de ser
--                        parámetros de la empresa 1
-- =====================================================================
--
-- La RN-14 dice que cada empresa dispone de un mes gratuito desde su
-- alta y nombra el parámetro que lo gobierna. La V10 lo creó así:
--
--   INSERT INTO parametros (empresa_id, clave, valor, ...)
--   VALUES (1, 'sgip.suscripcion.prueba.dias', '30', 'ENTERO', ...);
--
-- El valor es correcto y el sitio no. `parametros.empresa_id` es NOT
-- NULL, de modo que la duración del periodo de prueba de **todas** las
-- empresas quedó guardada como un ajuste de la empresa 1. Eso tiene tres
-- consecuencias, y ninguna es teórica:
--
--   1. El módulo de plataforma tendría que leer una fila de un comercio
--      para conocer su propia política. La RN-17 dice que el super
--      administrador «no accede a los datos operativos de ningún
--      negocio», y la primera operación que escribiéramos ya lo
--      incumpliría.
--   2. El administrador de la empresa 1 puede cambiar, desde su propia
--      pantalla de parámetros, el periodo de prueba de la competencia.
--   3. Si algún día se da de baja la empresa 1, la plataforma se queda
--      sin su parámetro.
--
-- Es el mismo defecto que la V16 corrigió retirando los dos parámetros
-- de imagen: un ajuste que no puede ser por empresa no se guarda en una
-- tabla por empresa.
--
-- ## Por qué una tabla nueva y no `empresa_id` anulable
--
-- La salida tentadora es admitir nulo en `parametros.empresa_id` y que
-- nulo signifique «plataforma». No sirve: la clave única es
-- (empresa_id, clave, vigente_desde), y tanto MySQL como MariaDB
-- consideran distintos dos nulos en un índice único. La tabla aceptaría
-- dos parámetros de plataforma con la misma clave y la misma vigencia, y
-- entonces «el» valor del periodo de prueba deja de existir: hay dos, y
-- gana el que la consulta ordene primero.
--
-- La alternativa elegida es la que el proyecto ya usa para lo
-- tributario. El ADR-0016 reparte esa materia en dos capas: el catálogo
-- nacional en `tipos_impuesto` y `tipos_retencion`, lo que cada comercio
-- declara en `empresa_config`. Aquí pasa lo mismo con los ajustes: lo
-- que la plataforma decide va en `parametros_plataforma` y lo que cada
-- comercio decide sigue en `parametros`. Dos tablas, cada una con su
-- clave única de verdad y su dueño claro.
--
-- ## El nombre de la clave
--
-- La V10 la llamó `sgip.suscripcion.prueba.dias` y el informe la nombra
-- `sgip.plataforma.prueba.dias`. Se adopta la del informe, que es el
-- documento que la RN-14 cita, y el prefijo además dice el alcance: un
-- parámetro que empieza por `sgip.plataforma.` no es de nadie en
-- particular.
--
-- La fila de la empresa 1 se borra en lugar de cerrarle la vigencia, por
-- el mismo motivo que la V16 argumentó: cerrar la vigencia conserva un
-- dato que nunca debió existir con ese dueño, y la siguiente consulta
-- que busque por clave sin filtrar la vigencia lo volvería a encontrar.
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. La tabla
-- ---------------------------------------------------------------------
CREATE TABLE parametros_plataforma (
  parametro_id  INT          NOT NULL AUTO_INCREMENT,
  clave         VARCHAR(80)  NOT NULL,
  valor         VARCHAR(200) NOT NULL,
  tipo_dato     ENUM('ENTERO','DECIMAL','TEXTO','BOOLEANO','FECHA')
                             NOT NULL DEFAULT 'TEXTO',
  descripcion   VARCHAR(200) NULL,
  vigente_desde DATE         NOT NULL,
  vigente_hasta DATE         NULL,
  CONSTRAINT pk_parametros_plataforma PRIMARY KEY (parametro_id),
  -- Sin empresa en la clave, porque no hay empresa. Esta es la
  -- unicidad que la tabla por empresa no podía ofrecer con un nulo.
  CONSTRAINT uq_parametros_plataforma_clave UNIQUE (clave, vigente_desde),
  CONSTRAINT ck_parametros_plataforma_rango CHECK (
    vigente_hasta IS NULL OR vigente_hasta >= vigente_desde),
  -- El alcance se lee en el nombre. No es decoración: es lo que permite
  -- revisar de un vistazo que nadie metió aquí un ajuste de comercio.
  CONSTRAINT ck_parametros_plataforma_prefijo CHECK (
    clave LIKE 'sgip.plataforma.%'),
  INDEX ix_parametros_plataforma_clave (clave, vigente_desde)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- 2. El periodo de prueba, en su sitio
-- ---------------------------------------------------------------------
INSERT INTO parametros_plataforma
       (clave, valor, tipo_dato, descripcion, vigente_desde)
VALUES ('sgip.plataforma.prueba.dias', '30', 'ENTERO',
        'Días de prueba gratuita desde el alta de una empresa (RN-14)',
        '2026-01-01');


-- ---------------------------------------------------------------------
-- 3. Retirar la fila que estaba en la tabla equivocada
-- ---------------------------------------------------------------------
DELETE FROM parametros WHERE clave = 'sgip.suscripcion.prueba.dias';


-- =====================================================================
-- Comprobación
-- =====================================================================
--   SELECT valor FROM parametros_plataforma
--    WHERE clave = 'sgip.plataforma.prueba.dias'
--      AND vigente_desde <= CURDATE()
--      AND (vigente_hasta IS NULL OR vigente_hasta >= CURDATE());
--   → 30
--
--   SELECT COUNT(*) FROM parametros WHERE clave LIKE '%prueba.dias%';
--   → 0
--
-- Y debe quedar rechazado:
--
--   INSERT INTO parametros_plataforma (clave, valor, vigente_desde)
--   VALUES ('sgip.catalogo.algo', 'x', '2026-01-01');
--     → ck_parametros_plataforma_prefijo
--
--   la misma clave con la misma vigencia dos veces
--     → uq_parametros_plataforma_clave
-- =====================================================================
