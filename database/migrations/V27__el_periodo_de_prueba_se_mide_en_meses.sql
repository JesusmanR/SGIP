-- ---------------------------------------------------------------------
-- V27 · El periodo de prueba se mide en meses, no en días
--
-- El RF-04 pide, con estas palabras, que «una empresa creada hoy queda
-- en prueba hasta el mismo día del mes siguiente». La RN-14 dice «un mes
-- gratuito». El parámetro que se cargó, en cambio, era
-- `sgip.plataforma.prueba.dias` con valor 30, y el alta calculaba
-- `hoy + 30 días`.
--
-- No es lo mismo, y no por poco: solo coinciden en los cuatro meses que
-- tienen treinta días. Medido sobre un año completo, de las 365 fechas
-- de alta posibles 240 daban una fecha de vencimiento distinta de la que
-- el criterio pide.
--
--   alta 2026-01-01  →  hoy+30d = 2026-01-31   criterio = 2026-02-01
--   alta 2026-01-31  →  hoy+30d = 2026-03-02   criterio = 2026-02-28
--   alta 2026-03-01  →  hoy+30d = 2026-03-31   criterio = 2026-04-01
--
-- La incoherencia venía de los propios requisitos: la RN-14 dice «un
-- mes» en la prosa y «30 días» en la columna del parámetro. Manda el
-- criterio de aceptación, que es lo que se verifica y lo único de los
-- tres que no admite dos lecturas.
--
-- El parámetro viejo no se borra: se le cierra la vigencia. La tabla
-- guarda histórico con `vigente_desde` y `vigente_hasta` justamente para
-- que se pueda contar qué regía antes, y una suscripción creada la
-- semana pasada se explica con el parámetro de la semana pasada.
--
-- Requiere: V22, V26.
-- ---------------------------------------------------------------------

-- 1. Se cierra el de días, con fecha de ayer: así deja de regir hoy.
UPDATE parametros_plataforma
   SET vigente_hasta = CURDATE() - INTERVAL 1 DAY
 WHERE clave = 'sgip.plataforma.prueba.dias'
   AND vigente_hasta IS NULL;

-- 2. Y entra el de meses.
INSERT INTO parametros_plataforma (clave, valor, tipo_dato, descripcion, vigente_desde)
VALUES ('sgip.plataforma.prueba.meses', '1', 'ENTERO',
        'Meses de prueba gratuita de una empresa nueva (RN-14, RF-04)',
        CURDATE());

-- 3. La restricción de la V26 acotaba el parámetro de días entre 1 y
--    365. Como ese parámetro ya no rige, la que importa es la del nuevo:
--    entre 1 y 24 meses. La vieja se conserva, porque la fila vieja sigue
--    en la tabla y tiene que seguir siendo válida.
ALTER TABLE parametros_plataforma
  ADD CONSTRAINT ck_parametros_plataforma_meses_prueba CHECK (
    clave <> 'sgip.plataforma.prueba.meses'
    OR (valor REGEXP '^[0-9]{1,2}$' AND CAST(valor AS UNSIGNED) BETWEEN 1 AND 24));
