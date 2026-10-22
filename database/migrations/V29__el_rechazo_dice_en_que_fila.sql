-- ---------------------------------------------------------------------
-- V29 · Un rechazo de importación dice en qué fila del archivo estaba
--
-- El RF-26 pide listar los registros rechazados «con su causa», y
-- `detalle_carga_precios` guardaba la causa pero no el sitio. Con cien
-- filas aún se encuentra a ojo; con veinte mil —que es el tope que el
-- RF-24 admite— decirle a alguien que «hay doce referencias
-- inexistentes» sin decirle dónde es no decirle nada.
--
-- La referencia de origen no sirve para eso. Un rechazo por código
-- repetido tiene dos filas con la misma referencia, y una fila con la
-- celda de referencia vacía no tiene ninguna.
--
-- Admite nulo porque el RF-29 —consultar la lista desde el servicio web
-- del proveedor, versión 2— no tiene filas que numerar. El origen de la
-- carga dice cuál de los dos casos es.
--
-- Requiere: V28.
-- ---------------------------------------------------------------------

ALTER TABLE detalle_carga_precios
  ADD COLUMN numero_fila INT NULL AFTER empresa_id;

-- La fila 1 de una hoja es el encabezado, de modo que un dato siempre
-- está en la 2 o más adelante. Un cero o un negativo aquí serían un
-- error de quien escribe, no un dato del archivo.
ALTER TABLE detalle_carga_precios
  ADD CONSTRAINT ck_detalle_numero_fila CHECK (
    numero_fila IS NULL OR numero_fila >= 2);
