-- =====================================================================
-- SGIP · Migración V5 · Funciones almacenadas
--
-- Ejecutar con el cliente mysql, que interpreta la directiva DELIMITER.
-- Si el servidor tiene activado el registro binario sin
-- log_bin_trust_function_creators, ejecutar antes:
--   SET GLOBAL log_bin_trust_function_creators = 1;
-- =====================================================================

DROP FUNCTION IF EXISTS fn_valor_uvt;
DROP FUNCTION IF EXISTS fn_es_habil;
DROP FUNCTION IF EXISTS fn_sumar_dias_habiles;
DROP FUNCTION IF EXISTS fn_envio_sugerido;

DELIMITER $$

-- ---------------------------------------------------------------------
-- fn_valor_uvt · valor de la unidad de valor tributario en una fecha.
-- Devuelve NULL si el año no está cargado; quien la consuma debe tratar
-- ese caso como producto gravado, que es el tratamiento conservador.
-- ---------------------------------------------------------------------
CREATE FUNCTION fn_valor_uvt(p_fecha DATE)
RETURNS DECIMAL(12,2)
READS SQL DATA
BEGIN
  DECLARE v_valor DECIMAL(12,2);
  SELECT valor INTO v_valor FROM uvt_anual WHERE anio = YEAR(p_fecha);
  RETURN v_valor;
END$$

-- ---------------------------------------------------------------------
-- fn_es_habil · verdadero si la fecha no es sábado, domingo ni figura
-- en el calendario de días no laborables.
-- ---------------------------------------------------------------------
CREATE FUNCTION fn_es_habil(p_fecha DATE)
RETURNS TINYINT(1)
READS SQL DATA
BEGIN
  DECLARE v_festivo INT DEFAULT 0;
  IF DAYOFWEEK(p_fecha) IN (1, 7) THEN
    RETURN 0;
  END IF;
  SELECT COUNT(*) INTO v_festivo FROM calendario_habil WHERE fecha = p_fecha;
  RETURN IF(v_festivo > 0, 0, 1);
END$$

-- ---------------------------------------------------------------------
-- fn_sumar_dias_habiles · fecha resultante de avanzar n días hábiles.
-- La cotización emitida hoy con vigencia de cinco días hábiles vence en
-- fn_sumar_dias_habiles(CURDATE(), 5).
-- El límite de 400 iteraciones evita un bucle infinito si el calendario
-- quedara mal cargado.
-- ---------------------------------------------------------------------
CREATE FUNCTION fn_sumar_dias_habiles(p_fecha DATE, p_dias INT)
RETURNS DATE
READS SQL DATA
BEGIN
  DECLARE v_fecha DATE DEFAULT p_fecha;
  DECLARE v_contados INT DEFAULT 0;
  DECLARE v_vueltas INT DEFAULT 0;

  IF p_dias <= 0 THEN
    RETURN p_fecha;
  END IF;

  WHILE v_contados < p_dias AND v_vueltas < 400 DO
    SET v_fecha = DATE_ADD(v_fecha, INTERVAL 1 DAY);
    SET v_vueltas = v_vueltas + 1;
    IF fn_es_habil(v_fecha) = 1 THEN
      SET v_contados = v_contados + 1;
    END IF;
  END WHILE;

  RETURN v_fecha;
END$$

-- ---------------------------------------------------------------------
-- fn_envio_sugerido · costo de envío que corresponde según la regla
-- vigente. Devuelve cero cuando la compra supera el umbral.
--
-- Es una sugerencia, no una imposición: el vendedor puede regalar el
-- envío por debajo del umbral marcando origen_envio = 'MANUAL' en el
-- documento, con motivo y responsable. Esta función solo calcula lo que
-- la regla dicta por sí sola.
--
-- La comparación es estricta. Con umbral de un millón, una compra de
-- exactamente un millón no alcanza el beneficio.
-- ---------------------------------------------------------------------
CREATE FUNCTION fn_envio_sugerido(
  p_subtotal DECIMAL(14,2),
  p_iva      DECIMAL(14,2),
  p_fecha    DATE)
RETURNS DECIMAL(14,2)
READS SQL DATA
BEGIN
  DECLARE v_umbral DECIMAL(14,2);
  DECLARE v_base   VARCHAR(20);
  DECLARE v_costo  DECIMAL(14,2);
  DECLARE v_valor  DECIMAL(14,2);

  SELECT umbral_valor, base_calculo, costo_envio
    INTO v_umbral, v_base, v_costo
  FROM reglas_envio
  WHERE p_fecha >= vigente_desde
    AND (vigente_hasta IS NULL OR p_fecha <= vigente_hasta)
  ORDER BY vigente_desde DESC, regla_envio_id DESC
  LIMIT 1;

  IF v_umbral IS NULL THEN
    RETURN 0;
  END IF;

  SET v_valor = IF(v_base = 'TOTAL_CON_IVA',
                   COALESCE(p_subtotal, 0) + COALESCE(p_iva, 0),
                   COALESCE(p_subtotal, 0));

  RETURN IF(v_valor > v_umbral, 0, v_costo);
END$$

DELIMITER ;
