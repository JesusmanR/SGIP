-- =====================================================================
-- SGIP · Migración V33 · Un código de barras por empresa
-- =====================================================================
--
-- El lector del punto de venta busca el producto por su código de
-- barras. Con dos productos de la misma empresa que lo compartían, la
-- búsqueda devolvía el que el motor encontrara primero: se vendía una
-- referencia y se descontaba la existencia de otra, sin ningún aviso.
--
-- Desde ahora el código de barras es único dentro de cada empresa, igual
-- que el código y el SKU. Dos empresas sí pueden tener el mismo: cada una
-- etiqueta lo suyo. Y varios productos sin código de barras siguen
-- siendo válidos, porque la clave deja pasar tantos NULL como haga falta.
--
-- Los datos que ya existen no pueden impedir la migración, así que se
-- ordenan antes de poner la clave:
--
--   1. Un código vacío o con espacios al borde pasa a NULL o se recorta.
--      La aplicación hace lo mismo al guardar; sin esto, «123 » y «123»
--      se tomarían por distintos aquí y por iguales allí.
--   2. Si varios productos de una empresa repiten un código, lo conserva
--      el de menor producto_id (el más antiguo) y a los demás se les
--      deja NULL.
--
-- Qué se pierde y cómo se recupera: el código que se retira de un
-- producto no se guarda en otra parte. No hay forma de saber cuál de los
-- productos repetidos tenía la etiqueta correcta, y quedarse con el más
-- antiguo es una regla, no un acierto. Quien lo necesite lo vuelve a
-- capturar editando el producto (o leyendo su etiqueta con el lector);
-- hasta entonces ese producto se vende por su código interno. El sistema
-- no inventa un código para ocultar el choque.
--
-- Ambos cambios suben `version`, porque cambian datos del catálogo por
-- fuera de JPA (V32): una edición abierta antes de migrar no debe
-- guardar después con el código viejo y deshacerlo sin avisar.
--
-- La clave nueva sirve también a la búsqueda por (empresa, código de
-- barras), de modo que el índice simple de la V10 sobra y se retira.

UPDATE productos
   SET codigo_barras = NULLIF(TRIM(codigo_barras), ''),
       version       = version + 1
 WHERE codigo_barras IS NOT NULL
   AND (codigo_barras = '' OR codigo_barras <> TRIM(codigo_barras));

-- El grupo se calcula en una tabla derivada porque MySQL no deja
-- modificar una tabla leyéndola en una subconsulta de la misma sentencia
-- (error 1093); con GROUP BY la derivada se materializa y sí se puede.
-- Agrupar y comparar usan la misma intercalación que la clave nueva
-- (utf8mb4_0900_ai_ci), así que «ABC» y «abc» cuentan como repetidos
-- aquí por la misma razón que la clave los rechazaría después.
UPDATE productos p
  JOIN (SELECT empresa_id, codigo_barras, MIN(producto_id) AS conserva
          FROM productos
         WHERE codigo_barras IS NOT NULL
         GROUP BY empresa_id, codigo_barras
        HAVING COUNT(*) > 1) d
    ON d.empresa_id = p.empresa_id
   AND d.codigo_barras = p.codigo_barras
   SET p.codigo_barras = NULL,
       p.version       = p.version + 1
 WHERE p.producto_id <> d.conserva;

ALTER TABLE productos
  DROP INDEX ix_productos_barras,
  ADD CONSTRAINT uq_productos_barras UNIQUE (empresa_id, codigo_barras);
