-- ---------------------------------------------------------------------
-- V30 · Cada rechazo de importación lleva su categoría, no solo su frase
--
-- `motivo_rechazo` guarda la causa escrita para que una persona la lea:
-- «La referencia MT-0031 no existe en el catálogo de esta empresa».
-- Eso es lo que el RF-26 pide y está bien.
--
-- La P-07 necesita además otra cosa: una pastilla por registro —«Sin
-- referencia», «Costo inválido»— que es una categoría, no una frase. Y
-- una categoría no se saca de una frase sin cotejar su texto, que es
-- acoplar la pantalla a la redacción del mensaje: cambiar una coma en
-- el motivo rompería el color de la pastilla, y el fallo aparecería
-- lejos de la causa.
--
-- Tampoco se puede deducir de las columnas que ya hay. Un rechazo por
-- referencia inexistente y uno por código repetido dejan los dos
-- `producto_id` nulo, de modo que mirarlo no distingue.
--
-- Las cinco categorías son exactamente las que el validador decide, y
-- las cuatro últimas son las que el RF-26 enumera.
--
-- Requiere: V29.
-- ---------------------------------------------------------------------

ALTER TABLE detalle_carga_precios
  ADD COLUMN motivo_codigo ENUM('REFERENCIA_VACIA','REFERENCIA_REPETIDA',
                                'REFERENCIA_INEXISTENTE','COSTO_NO_NUMERICO',
                                'COSTO_NO_POSITIVO') NULL AFTER motivo_rechazo;

-- La categoría y la frase van juntas o no van. Una fila aceptada no
-- tiene ninguna de las dos; una rechazada tiene las dos, porque la
-- pastilla sin texto no explica y el texto sin pastilla no se puede
-- pintar.
ALTER TABLE detalle_carga_precios
  ADD CONSTRAINT ck_detalle_motivo_completo CHECK (
    (aceptado = 1 AND motivo_rechazo IS NULL AND motivo_codigo IS NULL)
    OR (aceptado = 0 AND motivo_rechazo IS NOT NULL AND motivo_codigo IS NOT NULL));
