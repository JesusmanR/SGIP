-- =====================================================================
-- SGIP · Migración V32 · El producto lleva versión
-- =====================================================================
--
-- Dos personas con el mismo producto abierto, o una edición abierta
-- mientras se aplica una carga de precios, terminaban así: quien
-- guardaba al final reescribía el producto entero con lo que había leído
-- al principio, y el cambio del otro desaparecía sin aviso.
--
-- La versión permite que el guardado compruebe «sigue siendo el producto
-- que leí» y, si no lo es, se niegue. JPA la incrementa sola en cada
-- UPDATE que hace; el SQL escrito a mano que cambie datos del catálogo
-- (hoy, aplicar los costos de una carga) tiene que sumarla él mismo.
--
-- El movimiento de inventario NO la suma: la existencia ya no la escribe
-- la entidad, de modo que un saldo que cambia no invalida una edición.

ALTER TABLE productos
  ADD COLUMN version INT NOT NULL DEFAULT 0;
