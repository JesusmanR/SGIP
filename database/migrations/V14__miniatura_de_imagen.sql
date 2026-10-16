-- =====================================================================
-- SGIP · Migración V14 · Miniatura de la imagen de producto
-- =====================================================================
--
-- El catálogo muestra la imagen del producto en un recuadro de unos
-- cuarenta píxeles y estaba descargando la imagen completa de 800×800
-- para encogerla con CSS. Con cien productos en pantalla eso son cerca
-- de diez megas de descarga para pintar cien cuadritos, y en el local
-- esa conexión la comparte el punto de venta.
--
-- Pero el motivo principal no es el peso, sino la corrección. La
-- miniatura se pedía por la dirección «la imagen principal del producto
-- 6», que no nombra un archivo sino un papel: cuál es la principal
-- cambia y la dirección se queda igual. Una dirección así no se puede
-- cachear, y cuando por error se cacheó, el navegador siguió mostrando
-- la imagen anterior sin volver a preguntar.
--
-- Con esta columna la miniatura pasa a tener dirección propia, la del
-- identificador de SU imagen, que sí es inmutable: esos bytes no cambian
-- nunca, y cambiar de principal cambia la dirección. El problema deja de
-- poder ocurrir en vez de quedar arreglado.
--
-- Se admite nula porque las imágenes subidas antes de esta migración no
-- la tienen. La aplicación las resuelve generando la miniatura al vuelo
-- a partir de la imagen guardada, de modo que no hace falta un proceso
-- de relleno ni queda ninguna rota.
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE producto_imagenes
  ADD COLUMN miniatura_ruta VARCHAR(255) NULL AFTER ruta;
