-- =====================================================================
-- SGIP · Migración V16 · Retirar los parámetros de imagen
-- =====================================================================
--
-- La V12 sembró tres parámetros para el catálogo de imágenes. Dos de
-- ellos no los lee nadie y no pueden llegar a leerlos nunca:
--
--   sgip.catalogo.imagen.bytes.maximo = 5242880   (5 MB)
--   sgip.catalogo.imagenes.maximo     = 5
--
-- El primero además contradice al código, que exige 10 MB desde que el
-- ADR-0017 fijó un tope único. Un parámetro que dice una cosa y un
-- sistema que hace otra es peor que no tener parámetro: el que lo lea
-- tomará decisiones sobre un número falso.
--
-- La pregunta de fondo no es cuál de los dos números vale, sino si ese
-- tope puede vivir en esta tabla. No puede, y el motivo no es de
-- preferencia:
--
--   · `parametros` lleva empresa_id, de modo que su valor es por
--     empresa. El tope de subida no lo aplica la aplicación sino el
--     contenedor, mediante un MultipartConfigElement que se construye
--     una vez al arrancar y rige para toda petición de toda empresa.
--     Un valor por empresa no puede gobernar algo que no distingue
--     empresas.
--
--   · Lo mismo vale para el máximo de imágenes por producto: quien lo
--     hace cumplir es la restricción ck_producto_imagenes_orden, que es
--     del esquema y por lo tanto global. Cambiar el parámetro a ocho no
--     dejaría subir ocho; dejaría la tabla diciendo ocho mientras el
--     motor sigue rechazando la sexta.
--
-- Los dos se retiran, por lo tanto, en lugar de corregir su valor. El
-- tope de bytes vive en AlmacenArchivos.BYTES_MAXIMOS, de donde se
-- derivan los límites de multipart, y el de imágenes por producto en la
-- restricción del esquema, que es la que lo hace cumplir.
--
-- Sobre la convención de vigencia. El Anexo A del informe establece que
-- los parámetros no se sobrescriben: se conservan con fecha de vigencia
-- para que un documento emitido en el pasado pueda reconstruirse con
-- los valores de su momento. Aquí se borran y no se cierra su vigencia,
-- a propósito. Esa convención protege valores que gobernaron algo, y
-- estos dos no gobernaron nada: ninguna línea los leyó jamás. Cerrarles
-- la vigencia dejaría en la tabla un tope de 5 MB que parecería haber
-- estado en vigor hasta hoy, que es exactamente la lectura falsa que
-- esta migración viene a evitar.
--
-- Se conserva el tercero, sgip.inventario.importacion.filas, porque el
-- módulo de importación no está construido y, cuando lo esté, nada
-- impide que cada empresa comprometa un volumen distinto.
-- =====================================================================

SET NAMES utf8mb4;

DELETE FROM parametros
 WHERE clave IN ('sgip.catalogo.imagen.bytes.maximo',
                 'sgip.catalogo.imagenes.maximo');
