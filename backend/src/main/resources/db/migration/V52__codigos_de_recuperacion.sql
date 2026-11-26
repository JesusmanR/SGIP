-- ---------------------------------------------------------------------
-- V52 · Códigos para recuperar la contraseña por correo (RF-65)
--
-- Quien olvidó su contraseña pide un código de 6 dígitos que le llega al
-- correo y lo confirma con la contraseña nueva. Aquí se guarda ese código
-- ya derivado (nunca en claro), con lo que hace falta para limitarlo:
--
--   expira    vence 15 minutos después de crearse.
--   usado_en  lo marca el que lo consume y el que lo reemplaza por otro
--             más nuevo; un código con usado_en no vale.
--   intentos  confirmaciones fallidas contra este código (tope de 5).
--   origen    la IP de quien lo pidió, solo para la auditoría.
--
-- Todas las horas son la hora de Bogotá que escribe la aplicación con
-- Hoy.ahora(reloj) (ADR-0030); la base no pone ninguna por defecto, de
-- modo que lo que se escribe es lo que después se compara.
--
-- Un usuario no se borra, se desactiva; si alguien lo borra a mano, sus
-- códigos se van con él (ON DELETE CASCADE) y no estorban. La historia
-- que cuenta vive en auditoria_accesos, que no depende de esta tabla.
--
-- No lleva empresa_id: el código pertenece a un usuario y el usuario ya
-- pertenece a una empresa. Ninguna consulta lee esta tabla sin pasar por
-- el usuario que se identificó con su correo.
--
-- El índice compuesto sirve a las tres consultas de cada solicitud y
-- confirmación: el código vigente del usuario (usado_en IS NULL y
-- expira > ahora), el conteo de los creados en los últimos 15 minutos y
-- la invalidación de los anteriores.
--
-- Además lleva un índice de solo usuario_id, declarado ANTES que el
-- compuesto, y no es un descuido. En MySQL 9.7 la clave foránea se ata al
-- primer índice que le sirve; si ese es el compuesto, que tiene columnas
-- que admiten nulo, la base NO comprueba el padre al insertar una fila con
-- usado_en nulo (el caso de todo código nuevo): aceptaba un usuario_id que
-- no existe. Atada al índice de una sola columna, la comprueba. Se
-- verificó en 9.7.2 con la prueba noSeAceptaUnUsuarioInexistente.
--
-- Requiere: V1 (usuarios).
-- ---------------------------------------------------------------------

CREATE TABLE codigos_recuperacion (
  codigo_id    BIGINT           NOT NULL AUTO_INCREMENT,
  usuario_id   INT              NOT NULL,
  codigo_hash  VARCHAR(255)     NOT NULL,
  creado_en    DATETIME         NOT NULL,
  expira       DATETIME         NOT NULL,
  usado_en     DATETIME         NULL,
  intentos     TINYINT UNSIGNED NOT NULL DEFAULT 0,
  origen       VARCHAR(60)      NULL,
  CONSTRAINT pk_codigos_recuperacion PRIMARY KEY (codigo_id),
  CONSTRAINT fk_codigos_recuperacion_usuario FOREIGN KEY (usuario_id)
    REFERENCES usuarios (usuario_id) ON DELETE CASCADE,
  INDEX ix_codigos_recuperacion_fk (usuario_id),
  INDEX ix_codigos_recuperacion_usuario (usuario_id, usado_en, expira)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
