-- =====================================================================
-- SGIP · Migración V23 · El kárdex se explica solo
-- =====================================================================
--
-- El RF-49 pide «registrar todo cambio de existencia con su valor
-- anterior y posterior, tipo de movimiento, motivo y usuario
-- responsable», y acepta la implementación con esta prueba:
--
--   «Para cualquier referencia, la suma algebraica de sus movimientos
--    iguala su existencia actual, verificado sobre la totalidad del
--    catálogo de prueba. Un ajuste sin motivo devuelve 400.»
--
-- Esa frase se puede cumplir de dos maneras. Una es escribir con cuidado
-- en la capa de Java y comprobarlo después con un guion. La otra es que
-- el esquema no deje escribir una fila incoherente, y entonces la suma
-- cuadra porque no puede no cuadrar. Esta migración hace la segunda, y
-- de paso cierra tres cosas que la tabla tenía sin decidir.
--
-- Se puede hacer hoy y no se podrá mañana: `movimientos_inventario` está
-- vacía. Nada escribe en ella porque el módulo de inventario es
-- justamente el que se va a construir ahora. Apretar las restricciones
-- de una tabla con datos obliga a limpiar los datos primero; apretarlas
-- antes del primer INSERT no cuesta nada.
--
--
-- ## 1. El signo de `cantidad`
--
-- La V2 dejó `cantidad INT` con una sola restricción, `cantidad <> 0`,
-- que no dice si un egreso se escribe 3 o −3. Dos convenciones posibles,
-- y la tabla aceptaba las dos a la vez: bastaba con que dos personas
-- escribieran código en semanas distintas para que la suma algebraica
-- del RF-49 dejara de significar nada.
--
-- Se adopta **magnitud positiva, con la dirección en `tipo`**, y no por
-- gusto: `v_rotacion_inventario` ya suma `cantidad` de los movimientos
-- de tipo EGRESO esperando unidades vendidas. Con signo negativo esa
-- vista reportaría ventas en negativo, de modo que la convención ya
-- estaba elegida de hecho, solo que sin escribir. Aquí queda escrita.
--
-- `ck_movimientos_cantidad` pasa de `<> 0` a `> 0`.
--
--
-- ## 2. La coherencia entre la cantidad y el salto
--
-- La tabla guarda tres cifras que tienen que contar la misma historia:
-- `existencia_anterior`, `existencia_posterior` y `cantidad`. Nada
-- impedía escribir «salí de 10, quedé en 4, moví 2 unidades», que es una
-- fila que miente y que rompe el RF-49 sin que nadie se entere hasta que
-- alguien suma.
--
-- `ck_movimientos_coherencia` exige que el salto sea exactamente la
-- cantidad, en la dirección que el tipo indica:
--
--   INGRESO, DEVOLUCION → posterior = anterior + cantidad
--   EGRESO              → posterior = anterior − cantidad
--   AJUSTE              → |posterior − anterior| = cantidad
--
-- Escritas como tres implicaciones unidas por AND —«no es de este tipo
-- O cuadra»— y no como un CASE: MySQL exige que la expresión de un CHECK
-- sea booleana y rechaza el CASE, que devuelve el 1 o el 0 de la
-- comparación. MariaDB lo acepta, de modo que es una diferencia que solo
-- aparece contra el motor de verdad. Está anotada en database/README.md
-- junto a las otras dos.
--
-- DEVOLUCION suma porque es la devolución de un cliente, que vuelve a la
-- bodega. Devolver mercancía a un proveedor saca unidades y se registra
-- como EGRESO o, si corrige un conteo, como AJUSTE.
--
-- AJUSTE es el único que admite las dos direcciones, y tiene que
-- admitirlas: un conteo físico puede encontrar más o menos de lo que
-- decía el sistema. Por eso se compara el valor absoluto.
--
-- Con esto, `cantidad` queda redundante frente al salto. Es deliberado:
-- en un registro inmutable, el dato repetido y comprobado es lo que
-- permite detectar una fila mal escrita en lugar de heredarla. Y deja la
-- prueba del RF-49 reducida a una tautología, que es donde conviene
-- tener las invariantes.
--
--
-- ## 3. `motivo` y `observacion`, una sola columna
--
-- La V2 creó `observacion` y la V10 añadió `motivo` en el mismo ALTER
-- que `recepcion_id`, cuyo comentario explica solo `recepcion_id`. La
-- segunda columna entró sin motivo declarado y quedaron dos campos de
-- texto libre para la misma cosa.
--
-- Eso tiene una consecuencia concreta: `ck_movimientos_ajuste` exige
-- `observacion IS NOT NULL` para los ajustes, mientras que el RF-49
-- habla de «motivo» y su criterio dice «un ajuste sin motivo devuelve
-- 400». La restricción guarda la columna que el requisito no nombra, de
-- modo que un ajuste con `motivo` lleno y `observacion` vacía —que es lo
-- que escribiría cualquiera leyendo el requisito— sería rechazado, y uno
-- con `observacion` llena y `motivo` vacío pasaría sin motivo.
--
-- Se consolida en `motivo`, que es la palabra del requisito, y se retira
-- `observacion` de esta tabla. No se retira de `recepciones`, donde no
-- es lo mismo: allí es una nota sobre la entrega —«llegó en dos cajas,
-- una abierta»— y no la razón de un movimiento.
--
-- Se borra la columna en lugar de dejarla sin usar porque una columna
-- que nadie llena es una invitación a que alguien la llene, y entonces
-- la mitad del motivo de cada movimiento está en un sitio y la otra
-- mitad en otro. La tabla está vacía: no se pierde ningún dato.
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. El ajuste exige motivo, no observación
-- ---------------------------------------------------------------------
-- Primero se mueve la restricción y después se borra la columna: al
-- revés, el DROP COLUMN fallaría por estar la columna comprometida en un
-- CHECK vigente.
ALTER TABLE movimientos_inventario
  DROP CHECK ck_movimientos_ajuste;

ALTER TABLE movimientos_inventario
  ADD CONSTRAINT ck_movimientos_ajuste CHECK (
    tipo <> 'AJUSTE' OR motivo IS NOT NULL);

ALTER TABLE movimientos_inventario
  DROP COLUMN observacion;


-- ---------------------------------------------------------------------
-- 2. La cantidad es una magnitud
-- ---------------------------------------------------------------------
ALTER TABLE movimientos_inventario
  DROP CHECK ck_movimientos_cantidad;

ALTER TABLE movimientos_inventario
  ADD CONSTRAINT ck_movimientos_cantidad CHECK (cantidad > 0);


-- ---------------------------------------------------------------------
-- 3. Las tres cifras cuentan la misma historia
-- ---------------------------------------------------------------------
-- Tres implicaciones unidas por AND, y no un CASE.
--
-- La primera versión usaba `CASE tipo WHEN ... THEN <comparación> END`,
-- que MariaDB acepta y MySQL rechaza:
--
--   ERROR 3812: An expression of non-boolean type specified to a check
--               constraint 'ck_movimientos_coherencia'
--
-- MySQL exige que la expresión de un CHECK sea de tipo booleano, y el
-- CASE devuelve el 1 o el 0 de la comparación, que para su sistema de
-- tipos es un entero. La forma «no es de este tipo O cuadra» es booleana
-- de arriba abajo, dice lo mismo, y además es la que ya usaba
-- ck_movimientos_ajuste unas líneas más arriba.
ALTER TABLE movimientos_inventario
  ADD CONSTRAINT ck_movimientos_coherencia CHECK (
        (tipo NOT IN ('INGRESO', 'DEVOLUCION')
         OR existencia_posterior = existencia_anterior + cantidad)
    AND (tipo <> 'EGRESO'
         OR existencia_posterior = existencia_anterior - cantidad)
    AND (tipo <> 'AJUSTE'
         OR ABS(existencia_posterior - existencia_anterior) = cantidad));


-- ---------------------------------------------------------------------
-- 4. Los saldos iniciales quedan explicados
-- ---------------------------------------------------------------------
-- La prueba del RF-49 se ejecuta «sobre la totalidad del catálogo de
-- prueba», y sobre ese catálogo no cuadraría: la V8 cargó productos con
-- existencia mediante un INSERT directo, sin ningún movimiento que la
-- explique. Sumar los movimientos de PC-001 da cero y su existencia es
-- doce.
--
-- No es un defecto de la prueba, es el requisito señalando una fila sin
-- respaldo. «Registrar todo cambio de existencia» incluye el primero: la
-- existencia inicial es un cambio desde cero, y si no está registrada,
-- el kárdex no puede reconstruir el saldo —que es literalmente para lo
-- que la V2 dijo que existía la tabla: «conserva existencia anterior y
-- posterior, de modo que la existencia actual pueda reconstruirse
-- sumando movimientos».
--
-- Se registra un INGRESO de saldo inicial por cada producto con
-- existencia positiva, a nombre del primer administrador de su empresa,
-- que es quien cargó el catálogo. Los productos en cero no llevan
-- ninguno: su suma ya es cero y un movimiento de cantidad cero está
-- prohibido con razón.
--
-- La fecha es la de creación del producto y no la de hoy, para que el
-- movimiento no aparezca como una compra reciente en los informes de
-- rotación.
INSERT INTO movimientos_inventario
       (empresa_id, producto_id, usuario_id, tipo, cantidad,
        existencia_anterior, existencia_posterior, documento, motivo, fecha)
SELECT p.empresa_id,
       p.producto_id,
       (SELECT u.usuario_id FROM usuarios u
         WHERE u.empresa_id = p.empresa_id
         ORDER BY u.usuario_id
         LIMIT 1),
       'INGRESO',
       p.existencia,
       0,
       p.existencia,
       'SALDO-INICIAL',
       'Saldo inicial del catálogo, registrado por la migración V23 (RF-49)',
       p.creado_en
  FROM productos p
 WHERE p.existencia > 0
   AND EXISTS (SELECT 1 FROM usuarios u WHERE u.empresa_id = p.empresa_id)
   AND NOT EXISTS (SELECT 1 FROM movimientos_inventario m
                    WHERE m.empresa_id  = p.empresa_id
                      AND m.producto_id = p.producto_id);


-- =====================================================================
-- Comprobación
-- =====================================================================
-- Deben entrar:
--
--   INGRESO    cantidad 5, de 10 a 15
--   EGRESO     cantidad 3, de  0 a −3   (la venta sin existencia del RF-42)
--   AJUSTE     cantidad 2, de 10 a 12, con motivo
--   AJUSTE     cantidad 2, de 10 a  8, con motivo
--
-- Y deben quedar rechazados:
--
--   cantidad 0 o negativa          → ck_movimientos_cantidad
--   INGRESO cantidad 5, de 10 a 14 → ck_movimientos_coherencia
--   EGRESO  cantidad 3, de 10 a 13 → ck_movimientos_coherencia
--   AJUSTE  sin motivo             → ck_movimientos_ajuste
--   posterior por debajo de −9999  → ck_movimientos_posterior (V18)
--
-- Con estas restricciones, la prueba del RF-49 —que la suma algebraica
-- de los movimientos de una referencia iguale su existencia— se cumple
-- por construcción siempre que la cadena de movimientos no tenga huecos,
-- que es lo único que le queda por garantizar a la capa de Java:
--
--   SELECT p.producto_id,
--          p.existencia,
--          COALESCE(SUM(CASE m.tipo
--                         WHEN 'EGRESO' THEN -m.cantidad
--                         WHEN 'AJUSTE' THEN m.existencia_posterior
--                                            - m.existencia_anterior
--                         ELSE m.cantidad
--                       END), 0) AS suma
--     FROM productos p
--     LEFT JOIN movimientos_inventario m
--            ON m.empresa_id = p.empresa_id
--           AND m.producto_id = p.producto_id
--    GROUP BY p.producto_id, p.existencia
--   HAVING p.existencia <> suma;
--
-- Debe devolver cero filas. El guion verificar_kardex.sh lo ejecuta.
-- =====================================================================
