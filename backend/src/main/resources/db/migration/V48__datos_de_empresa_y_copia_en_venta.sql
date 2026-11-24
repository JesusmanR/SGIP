-- ---------------------------------------------------------------------
-- V48 · Los datos de la empresa, su identidad con tipo, y la copia en la venta
--
-- Tres cosas que van juntas porque el recibo las lee juntas:
--
--   1. `empresas` gana lo que el administrador edita (nombre comercial y
--      dirección) y el tipo del documento con que se identifica la empresa.
--   2. `ventas` gana la copia de esos datos al momento de la venta, para que
--      reimprimir un recibo viejo no muestre datos que la empresa cambió
--      después. Aquí solo se crean las columnas; las llena el registro de
--      la venta (la tarea siguiente).
--   3. `auditoria_plataforma` aprende el cambio de identidad de una empresa.
--
-- ## empresas
--
-- `tipo_documento` es NIT o CC: la empresa puede ser una persona natural
-- que se identifica con su cédula. Las empresas que ya existen son NIT,
-- que es lo que dice el valor por omisión. El número vive donde siempre
-- estuvo, en `nit` (VARCHAR(20) UNIQUE), y NO se toca ninguna fila: las
-- semillas con su dígito de verificación (900000000-0) se quedan como están,
-- y para compararlas con un número nuevo se les descarta el sufijo desde el
-- guion. Solo se normaliza el valor que entra en un cambio (puntos y
-- espacios fuera, de 5 a 15 dígitos; un guion se rechaza). Nunca se pide ni
-- se valida el dígito de verificación.
--
-- `nombre_comercial` y `direccion` son nulos hasta que el administrador
-- los llene. Las dos columnas de contacto que ya existían (`telefono`,
-- `ciudad`, `correo_contacto`) no cambian.
--
-- ## ventas
--
-- Cinco columnas nulas. La copia está ausente si y solo si `empresa_nit`
-- es nulo: una venta anterior a esta migración queda con la copia vacía y
-- su recibo usa los datos actuales de la empresa (límite aceptado, por
-- escrito en el diseño). Ninguna se rellena aquí: inventar «los datos del
-- día de la venta» de una venta vieja sería mentir sobre lo que se imprimió.
--
-- Agregar columnas nulas al final no reescribe la tabla (ALGORITHM=INSTANT).
--
-- ## auditoria_plataforma
--
-- La acción nueva, CAMBIO_IDENTIDAD_EMPRESA, recae sobre una empresa y es
-- un cambio (tiene valor anterior), igual que activar o desactivar. Se
-- toca lo mismo que en la V37, y por lo mismo: el ENUM y las dos
-- restricciones que se escribieron «cerradas» a propósito (V21). Un CHECK
-- no se puede cambiar en el sitio, así que se quitan y se vuelven a poner,
-- y se hace antes de tocar el ENUM porque el motor no deja modificar una
-- columna que una restricción comprueba.
--
-- Se listan las ocho acciones que ya existían (las del ENUM de la V37) más
-- la nueva, al final.
--
-- `campo` es nueva y nula: dice qué campo cambió. El cambio de identidad
-- deja UNA fila por cada campo que cambia (tipo_documento, nit y
-- razon_social) y, sin esta columna, las tres filas serían indistinguibles
-- salvo por la forma de los valores. Las acciones que ya existían la dejan
-- nula.
--
-- Requiere: V10 (empresas), V21 y V37 (auditoria_plataforma), V44 (ventas).
-- ---------------------------------------------------------------------

ALTER TABLE empresas
  ADD COLUMN nombre_comercial VARCHAR(150) NULL,
  ADD COLUMN direccion        VARCHAR(200) NULL,
  ADD COLUMN tipo_documento   ENUM('NIT','CC') NOT NULL DEFAULT 'NIT';

ALTER TABLE ventas
  ADD COLUMN empresa_nombre         VARCHAR(160)    NULL,
  ADD COLUMN empresa_tipo_documento ENUM('NIT','CC') NULL,
  ADD COLUMN empresa_nit            VARCHAR(20)     NULL,
  ADD COLUMN empresa_direccion      VARCHAR(200)    NULL,
  ADD COLUMN empresa_telefono       VARCHAR(20)     NULL;

ALTER TABLE auditoria_plataforma
  DROP CHECK ck_auditoria_plataforma_cambio,
  DROP CHECK ck_auditoria_plataforma_ambito;

ALTER TABLE auditoria_plataforma
  MODIFY accion ENUM('EMPRESA_ALTA',
                     'EMPRESA_ACTIVACION',
                     'EMPRESA_DESACTIVACION',
                     'UVT_ALTA',
                     'UVT_CAMBIO',
                     'IMPUESTO_ALTA',
                     'IMPUESTO_CAMBIO',
                     'USUARIO_DESBLOQUEO',
                     'CAMBIO_IDENTIDAD_EMPRESA') NOT NULL,
  ADD COLUMN campo VARCHAR(40) NULL AFTER entidad;

ALTER TABLE auditoria_plataforma
  ADD CONSTRAINT ck_auditoria_plataforma_cambio CHECK (
    accion NOT IN ('EMPRESA_ACTIVACION','EMPRESA_DESACTIVACION',
                   'UVT_CAMBIO','IMPUESTO_CAMBIO','USUARIO_DESBLOQUEO',
                   'CAMBIO_IDENTIDAD_EMPRESA')
    OR valor_anterior IS NOT NULL),
  ADD CONSTRAINT ck_auditoria_plataforma_ambito CHECK (
    (accion IN ('EMPRESA_ALTA','EMPRESA_ACTIVACION','EMPRESA_DESACTIVACION',
                'USUARIO_DESBLOQUEO','CAMBIO_IDENTIDAD_EMPRESA')
     AND empresa_id IS NOT NULL)
    OR (accion IN ('UVT_ALTA','UVT_CAMBIO','IMPUESTO_ALTA','IMPUESTO_CAMBIO')
     AND empresa_id IS NULL));
