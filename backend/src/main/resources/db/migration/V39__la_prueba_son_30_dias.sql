-- ---------------------------------------------------------------------
-- V39 · La prueba son 30 días (RF-04 de la v4, ADR-0031)
--
-- La v4 de los requisitos, decisión del usuario del 2026-11-07, fija la
-- prueba gratuita en 30 días calendario contados desde el día siguiente
-- al alta, con vencimiento a las 23:59:59 de Bogotá del trigésimo día.
-- La V27 la había pasado a un mes de calendario (ADR-0022); esta vuelve a
-- los días con un parámetro nuevo.
--
-- Nada se borra. El parámetro de meses se cierra, igual que la V27 cerró
-- el de días: la tabla guarda histórico con `vigente_desde` y
-- `vigente_hasta` para que una suscripción creada bajo un parámetro se
-- explique con ese parámetro. Las suscripciones existentes no se
-- recalculan.
--
-- El parámetro de días vuelve como una fila nueva, que empieza hoy. La
-- vieja (la de la V22, cerrada por la V27) se queda como estaba.
--
-- Requiere: V22, V26, V27.
-- ---------------------------------------------------------------------

-- 1. Se cierra el de meses con fecha de ayer: así deja de regir hoy.
--
--    GREATEST no sobra. En una base nueva —la de la CI, o la de quien
--    monta el proyecto desde cero— la V27 y esta corren el mismo día, de
--    modo que la fila de meses empieza hoy, y cerrarla ayer violaría
--    ck_parametros_plataforma_rango (vigente_hasta >= vigente_desde). Lo
--    mismo pasa cuando la V27 corrió con la sesión en UTC y esta corre en
--    Bogotá, ya de noche. En ese caso la fila de meses queda vigente solo
--    hoy, junto a la de días; el alta lee únicamente la de días, de modo
--    que no hay dos respuestas para la misma pregunta.
UPDATE parametros_plataforma
   SET vigente_hasta = GREATEST(vigente_desde, CURDATE() - INTERVAL 1 DAY)
 WHERE clave = 'sgip.plataforma.prueba.meses'
   AND vigente_hasta IS NULL;

-- 2. Y entra el de días. La restricción de la V26
--    (ck_parametros_plataforma_dias_prueba) ya lo acota entre 1 y 365.
INSERT INTO parametros_plataforma (clave, valor, tipo_dato, descripcion, vigente_desde)
VALUES ('sgip.plataforma.prueba.dias', '30', 'ENTERO',
        'Días de prueba gratuita, contados desde el día siguiente al alta (RF-04, ADR-0031)',
        CURDATE());

-- =====================================================================
-- Comprobación
-- =====================================================================
--   SELECT valor FROM parametros_plataforma
--    WHERE clave = 'sgip.plataforma.prueba.dias'
--      AND vigente_desde <= CURDATE()
--      AND (vigente_hasta IS NULL OR vigente_hasta >= CURDATE());
--   → 30
--
--   SELECT COUNT(*) FROM parametros_plataforma
--    WHERE clave = 'sgip.plataforma.prueba.meses' AND vigente_hasta IS NULL;
--   → 0
-- =====================================================================
