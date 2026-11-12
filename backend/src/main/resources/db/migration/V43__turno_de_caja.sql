-- ---------------------------------------------------------------------
-- V43 · El turno de caja (RF-45)
--
-- `cierres_caja` nació en la V10 (y la V11 le puso sus claves por empresa)
-- sin que ningún código la usara. Un turno es una fila de esa tabla: se
-- abre con `apertura` y se cierra al llenar `cierre`. Esta migración le
-- añade dos cosas.
--
--   base_efectivo   El efectivo con que el cajero abre el turno, en pesos
--                   enteros (RNF-16). Cero si abre sin base. No cambia
--                   `esperado`: ese lo calcula y lo guarda el cierre, sin
--                   cambio de esquema.
--
--   abierto_de      Quién tiene el turno abierto: su `usuario_id` mientras
--                   `cierre` es nulo y nulo desde que se cierra. Con el
--                   índice único `(empresa_id, abierto_de)` el motor
--                   impide que un cajero tenga dos turnos abiertos aunque
--                   dos aperturas lleguen a la vez, algo que una lectura
--                   previa de la aplicación no puede garantizar. Un
--                   índice único admite varios nulos, así que los turnos
--                   cerrados no estorban y se conservan todos.
--
--                   La columna es generada: nadie la escribe. La
--                   aplicación abre insertando y cierra llenando `cierre`.
--
-- Una sola restricción de verificación, para que una base negativa no la
-- admita el motor aunque la aplicación se equivoque.
--
-- `cierres_caja` no tenía filas en ninguna base (nada la escribía), de
-- modo que el índice único no puede fallar al crearse.
--
-- Requiere: V10 (cierres_caja) y V11 (claves por empresa).
-- ---------------------------------------------------------------------

ALTER TABLE cierres_caja
  ADD COLUMN base_efectivo DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER cierre,
  ADD COLUMN abierto_de INT
      GENERATED ALWAYS AS (IF(cierre IS NULL, usuario_id, NULL)) STORED,
  ADD CONSTRAINT ck_cierre_base CHECK (base_efectivo >= 0),
  ADD UNIQUE INDEX uq_cierres_abierto (empresa_id, abierto_de);
