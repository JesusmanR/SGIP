-- =====================================================================
-- SGIP · Migración V13 · Logo de la empresa
-- =====================================================================
--
-- Las imágenes de producto ya tenían su tabla desde la V12. La empresa no
-- tenía dónde guardar su logo, y lo necesita para encabezar el catálogo
-- exportable (RF-64) y las facturas.
--
-- Se guarda la RUTA y no los bytes. Un logo en la base de datos se
-- arrastra en cada copia de seguridad, en cada réplica y en cada consulta
-- que por descuido haga SELECT *, y a cambio no gana nada: no se busca
-- por su contenido ni se cruza con otras tablas.
--
-- La ruta es relativa a la raíz de almacenamiento que configura la
-- aplicación. Guardarla absoluta ataría las filas a la carpeta de un
-- servidor concreto, y mover la instalación obligaría a reescribir datos.
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE empresas
  ADD COLUMN logo_ruta VARCHAR(255) NULL AFTER razon_social;
