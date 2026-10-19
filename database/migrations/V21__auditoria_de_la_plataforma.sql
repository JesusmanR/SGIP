-- =====================================================================
-- SGIP · Migración V21 · Registro de lo que la plataforma decide
-- =====================================================================
--
-- Dos requisitos del módulo de plataforma piden lo mismo con las mismas
-- palabras, y el esquema no lo tenía:
--
--   RF-02 · «El cambio queda registrado con responsable y fecha»
--           (activación de una empresa)
--   RF-05 · «... y queda registrada con responsable y fecha»
--           (datos tributarios de alcance nacional)
--
-- Hoy `empresas.estado` es una columna sin historia: al pasar de
-- DESACTIVADA a ACTIVA, el valor anterior desaparece y con él quién lo
-- cambió. `uvt_anual` y `tipos_impuesto` guardan vigencia —de modo que
-- el valor viejo sí sobrevive— pero tampoco guardan autoría.
--
-- Son tres tablas que necesitan lo mismo, y las decisiones son pocas y
-- caras: dar de alta un comercio, suspenderlo, cambiar la tarifa que
-- aplica a todos. Eso se registra en una bitácora, no en columnas
-- repetidas tres veces.
--
-- Por qué una tabla nueva y no `auditoria_accesos`: esa registra
-- intentos de ingreso, con correo y motivo de rechazo. Son dos preguntas
-- distintas —«quién intentó entrar» y «quién cambió qué»— y juntarlas
-- obliga a cada consulta a filtrar la mitad de las filas que no le
-- interesan.
--
-- Es un registro **inmutable**, como `movimientos_inventario`: se
-- inserta y no se actualiza ni se borra. El esquema no puede obligarlo
-- sin disparadores, de modo que queda dicho aquí y la capa de Java no
-- expone ninguna operación que lo modifique.
--
-- `empresa_id` admite nulo a propósito: un cambio de UVT (unidad de
-- valor tributario) o de tarifa no pertenece a ninguna empresa, le
-- aplica a todas. Lo que no admite nulo es `usuario_id`: un cambio sin
-- responsable es precisamente lo que el requisito prohíbe. La clave
-- foránea usa ON DELETE RESTRICT por el mismo motivo, al contrario que
-- `auditoria_accesos`, que pone el usuario en nulo al borrarlo: allí la
-- fila sigue valiendo sin él porque conserva el correo, aquí la fila
-- sin responsable no vale para nada.
--
-- La clave foránea de `empresa_id` rompe la costumbre del repositorio y
-- usa ON UPDATE RESTRICT en lugar de CASCADE. No es un descuido: el
-- motor prohíbe las acciones referenciales sobre una columna que
-- aparece en un CHECK, y con razón —una acción en cascada cambiaría el
-- valor sin volver a evaluar la restricción—. Se intentó primero con
-- CASCADE y el CREATE TABLE fue rechazado:
--
--   ERROR 1901: Function or expression 'empresa_id' cannot be used in
--               the CHECK clause of `ck_auditoria_plataforma_ambito`
--
-- Entre las dos, la que vale es el CHECK: `empresa_id` es una clave
-- subrogada autoincremental que nunca cambia de valor, de modo que la
-- cascada no protege de nada, mientras que sin el CHECK sí se pueden
-- insertar filas que nadie puede interpretar. `usuario_id` conserva
-- CASCADE porque ninguna restricción lo menciona.
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE auditoria_plataforma (
  auditoria_id   BIGINT        NOT NULL AUTO_INCREMENT,
  usuario_id     INT           NOT NULL,
  empresa_id     INT           NULL,
  accion         ENUM('EMPRESA_ALTA',
                      'EMPRESA_ACTIVACION',
                      'EMPRESA_DESACTIVACION',
                      'UVT_ALTA',
                      'UVT_CAMBIO',
                      'IMPUESTO_ALTA',
                      'IMPUESTO_CAMBIO') NOT NULL,
  -- La tabla y la fila sobre las que se decidió. La referencia es la
  -- clave natural y no el identificador: el año para la UVT, el código
  -- para el tipo de impuesto, el NIT para la empresa. Un número
  -- autoincremental no le dice nada a quien lee la bitácora un año
  -- después.
  entidad        VARCHAR(40)   NOT NULL,
  referencia     VARCHAR(60)   NOT NULL,
  valor_anterior VARCHAR(200)  NULL,
  valor_nuevo    VARCHAR(200)  NULL,
  motivo         VARCHAR(200)  NULL,
  fecha          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_auditoria_plataforma PRIMARY KEY (auditoria_id),
  CONSTRAINT fk_auditoria_plataforma_usuario FOREIGN KEY (usuario_id)
    REFERENCES usuarios (usuario_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  -- ON UPDATE RESTRICT, no CASCADE: ver la nota de la cabecera. La
  -- columna aparece en ck_auditoria_plataforma_ambito y el motor no
  -- admite acciones referenciales sobre una columna comprobada.
  CONSTRAINT fk_auditoria_plataforma_empresa FOREIGN KEY (empresa_id)
    REFERENCES empresas (empresa_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  -- Un alta no tiene valor anterior y un cambio sí. Lo segundo se puede
  -- exigir; lo primero no hace falta prohibirlo.
  CONSTRAINT ck_auditoria_plataforma_cambio CHECK (
    accion NOT IN ('EMPRESA_ACTIVACION','EMPRESA_DESACTIVACION',
                   'UVT_CAMBIO','IMPUESTO_CAMBIO')
    OR valor_anterior IS NOT NULL),
  -- Lo que le pasa a una empresa se registra contra esa empresa. Lo
  -- nacional no tiene empresa. Sin esta comprobación, una activación sin
  -- empresa_id quedaría como una fila que nadie puede interpretar.
  --
  -- Las dos listas se escriben completas en lugar de usar un LIKE sobre
  -- el prefijo. No es verbosidad: así la restricción falla cerrado. Quien
  -- añada mañana una acción al ENUM y olvide clasificarla aquí no podrá
  -- insertar ninguna fila con ella, y el fallo aparece al primer intento
  -- en lugar de convertirse en filas de ámbito indefinido.
  CONSTRAINT ck_auditoria_plataforma_ambito CHECK (
    (accion IN ('EMPRESA_ALTA','EMPRESA_ACTIVACION','EMPRESA_DESACTIVACION')
     AND empresa_id IS NOT NULL)
    OR (accion IN ('UVT_ALTA','UVT_CAMBIO','IMPUESTO_ALTA','IMPUESTO_CAMBIO')
     AND empresa_id IS NULL)),
  INDEX ix_auditoria_plataforma_empresa (empresa_id, fecha),
  INDEX ix_auditoria_plataforma_fecha (fecha),
  INDEX ix_auditoria_plataforma_entidad (entidad, referencia, fecha)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- =====================================================================
-- Comprobación
-- =====================================================================
-- Debe entrar (una desactivación con su valor anterior y su empresa):
--
--   INSERT INTO auditoria_plataforma
--          (usuario_id, empresa_id, accion, entidad, referencia,
--           valor_anterior, valor_nuevo, motivo)
--   VALUES (1, 1, 'EMPRESA_DESACTIVACION', 'empresas', '900123456-1',
--           'ACTIVA', 'DESACTIVADA', 'suscripción vencida');
--
-- Y debe quedar rechazado:
--
--   ... 'EMPRESA_ACTIVACION' sin valor_anterior
--         → ck_auditoria_plataforma_cambio
--   ... 'EMPRESA_ACTIVACION' con empresa_id nulo
--         → ck_auditoria_plataforma_ambito
--   ... 'UVT_CAMBIO' con empresa_id
--         → ck_auditoria_plataforma_ambito
-- =====================================================================
