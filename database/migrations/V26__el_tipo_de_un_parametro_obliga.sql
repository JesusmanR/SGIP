-- ---------------------------------------------------------------------
-- V26 · El tipo declarado de un parámetro obliga a su valor
--
-- `parametros` y `parametros_plataforma` guardan el valor como texto y
-- declaran aparte qué tipo es: ENTERO, DECIMAL, BOOLEANO, FECHA o TEXTO.
-- Esa declaración no obligaba a nada. Una fila con tipo_dato = 'ENTERO'
-- y valor = 'treinta' entraba sin una queja —comprobado sobre MySQL 8
-- antes de escribir esto— y el tipo quedaba como un comentario.
--
-- No es inocuo. `RepositorioPlataforma.diasDePrueba()` lee el valor y le
-- aplica `Integer.parseInt`, y es la consulta que decide cuántos días de
-- prueba recibe cada empresa que se da de alta. Una fila así no rompe al
-- escribirse: rompe después, en el alta de la siguiente empresa, lejos
-- de quien la escribió y sin que el mensaje mencione el parámetro.
--
-- Esta migración hace que el tipo mande. Es la misma idea de la V25 con
-- `forma_calculo`: una columna que declara cómo hay que interpretar otra
-- y a la que nadie hacía caso.
--
-- Comprobado antes de añadirla que ninguna de las diecisiete filas
-- cargadas la incumple, de modo que el ALTER no falla sobre una base
-- existente.
--
-- Requiere: V22. Aplicar después de V25.
-- ---------------------------------------------------------------------

-- TEXTO queda fuera a propósito: es el tipo que no promete nada, y
-- cualquier cadena lo cumple. Cada rama se escribe como implicación
-- —«o no es de este tipo, o lo respeta»— porque MySQL exige que la
-- expresión del CHECK sea booleana y un CASE con comparaciones dentro
-- lo rechaza con el error 3812. La V23 ya tropezó con eso.
ALTER TABLE parametros
  ADD CONSTRAINT ck_parametros_valor_segun_tipo CHECK (
    (tipo_dato <> 'ENTERO'   OR valor REGEXP '^-?[0-9]+$') AND
    (tipo_dato <> 'DECIMAL'  OR valor REGEXP '^-?[0-9]+(\\.[0-9]+)?$') AND
    (tipo_dato <> 'BOOLEANO' OR valor IN ('true', 'false', '1', '0')) AND
    (tipo_dato <> 'FECHA'    OR valor REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'));

ALTER TABLE parametros_plataforma
  ADD CONSTRAINT ck_parametros_plataforma_valor_segun_tipo CHECK (
    (tipo_dato <> 'ENTERO'   OR valor REGEXP '^-?[0-9]+$') AND
    (tipo_dato <> 'DECIMAL'  OR valor REGEXP '^-?[0-9]+(\\.[0-9]+)?$') AND
    (tipo_dato <> 'BOOLEANO' OR valor IN ('true', 'false', '1', '0')) AND
    (tipo_dato <> 'FECHA'    OR valor REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'));

-- El periodo de prueba, además, tiene que ser un número de días con
-- sentido. La restricción de arriba impide 'treinta'; esta impide -5 y
-- 100000, que son enteros perfectamente válidos y periodos de prueba
-- que no lo son.
ALTER TABLE parametros_plataforma
  ADD CONSTRAINT ck_parametros_plataforma_dias_prueba CHECK (
    clave <> 'sgip.plataforma.prueba.dias'
    OR (valor REGEXP '^[0-9]{1,3}$' AND CAST(valor AS UNSIGNED) BETWEEN 1 AND 365));
