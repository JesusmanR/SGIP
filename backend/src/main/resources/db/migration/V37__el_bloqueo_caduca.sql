-- =====================================================================
-- SGIP · Migración V37 · El bloqueo de una cuenta caduca
-- =====================================================================
--
-- Cinco intentos fallidos bloqueaban la cuenta para siempre. Quien tecleaba
-- mal su contraseña inicial cinco veces en su primer día se quedaba fuera, y
-- si era el único administrador de su empresa no había nadie que lo
-- desbloqueara: la única salida era SQL a mano (ADR-0028).
--
-- ## usuarios.bloqueado_en
--
-- El instante en que la cuenta se bloqueó. NO lleva valor por omisión ni lo
-- rellena el motor: lo escribe la aplicación con su propio reloj y lo compara
-- con ese mismo reloj, de modo que base y aplicación no pueden discrepar sobre
-- «ahora» aunque estén en zonas horarias distintas. La columna solo guarda el
-- instante; quién decide si el bloqueo venció es la aplicación.
--
-- Las cuentas que ya estaban bloqueadas quedan con `bloqueado_en` nulo. No hay
-- desde cuándo contar, y la aplicación las toma por vencidas: se desbloquean en
-- el siguiente ingreso. Es lo que esta migración viene a corregir, no una
-- omisión. Rellenarlas aquí con NOW() mezclaría el reloj del motor con el de la
-- aplicación, justo lo que se evita arriba.
--
-- ## auditoria_plataforma
--
-- El super administrador puede desbloquear a un usuario de cualquier empresa
-- (ADR-0028), y la bitácora de la plataforma es el sitio de lo que la
-- plataforma decide sobre una empresa. La acción nueva, USUARIO_DESBLOQUEO,
-- recae sobre una empresa y es un cambio (tiene valor anterior), igual que
-- activar o desactivar una empresa.
--
-- Hay que tocar tres cosas, y las tres son necesarias: el ENUM, y las dos
-- restricciones, que se escribieron «cerradas» a propósito (V21): una acción
-- que no aparece en sus listas no entra. Un CHECK no se puede cambiar en el
-- sitio, así que se quitan y se vuelven a poner, y se hace antes de tocar el
-- ENUM porque el motor no deja modificar una columna que una restricción
-- comprueba.
-- =====================================================================

ALTER TABLE usuarios
  ADD COLUMN bloqueado_en DATETIME NULL AFTER bloqueado;

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
                     'USUARIO_DESBLOQUEO') NOT NULL;

ALTER TABLE auditoria_plataforma
  ADD CONSTRAINT ck_auditoria_plataforma_cambio CHECK (
    accion NOT IN ('EMPRESA_ACTIVACION','EMPRESA_DESACTIVACION',
                   'UVT_CAMBIO','IMPUESTO_CAMBIO','USUARIO_DESBLOQUEO')
    OR valor_anterior IS NOT NULL),
  ADD CONSTRAINT ck_auditoria_plataforma_ambito CHECK (
    (accion IN ('EMPRESA_ALTA','EMPRESA_ACTIVACION','EMPRESA_DESACTIVACION',
                'USUARIO_DESBLOQUEO')
     AND empresa_id IS NOT NULL)
    OR (accion IN ('UVT_ALTA','UVT_CAMBIO','IMPUESTO_ALTA','IMPUESTO_CAMBIO')
     AND empresa_id IS NULL));
