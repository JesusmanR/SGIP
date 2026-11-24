-- ---------------------------------------------------------------------
-- V49 · Una fila de una lista de precios puede ser de un producto de otro proveedor
--
-- Una lista de precios es de un proveedor. Si una fila nombra un producto que
-- el catálogo tiene con otro proveedor, esa fila se rechaza y el resto de la
-- lista se aplica. La V30 le puso a cada rechazo su categoría en un ENUM
-- (`detalle_carga_precios.motivo_codigo`); esta causa es la sexta.
--
-- Los cinco valores de antes se copian de la V30 en el mismo orden y el nuevo
-- va al final: el ENUM guarda el índice del valor, y reordenar o intercalar
-- haría que las categorías ya guardadas apuntaran a otra. La columna sigue
-- admitiendo nulo, porque una fila aceptada no tiene categoría. El CHECK de la
-- V30 (`ck_detalle_motivo_completo`) solo exige que la categoría exista cuando
-- hay rechazo y no cita los valores, de modo que no se toca.
--
-- Requiere: V30.
-- ---------------------------------------------------------------------

ALTER TABLE detalle_carga_precios
  MODIFY COLUMN motivo_codigo ENUM('REFERENCIA_VACIA','REFERENCIA_REPETIDA',
                                   'REFERENCIA_INEXISTENTE','COSTO_NO_NUMERICO',
                                   'COSTO_NO_POSITIVO','PRODUCTO_DE_OTRO_PROVEEDOR') NULL;
