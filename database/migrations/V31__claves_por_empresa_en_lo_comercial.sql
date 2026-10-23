-- =====================================================================
-- SGIP · Migración V31 · Las tablas de lo comercial dejan de poder
--                        cruzar empresas
-- =====================================================================
--
-- Cuarta vez. La V11 metió `empresa_id` en veinte claves foráneas, la
-- V24 cerró las que se le habían quedado fuera en el camino de las
-- compras, la V28 las del camino de la importación, y las tres las
-- encontraron del mismo modo: mirando el esquema antes de escribir
-- encima de él. Esta aparece al ir a construir el panel de indicadores
-- (P-02), que lee existencias y movimientos, y al contar las claves
-- compuestas del esquema salieron cuatro tablas que apuntan a
-- `productos(producto_id)` por la clave simple.
--
-- Reproducido sobre una base con la cadena V1 a V30 aplicada y los datos
-- de demostración, que tienen tres empresas:
--
--   1. `venta_items.fk_venitems_producto`. La venta V-0001, de la
--      empresa 1, aceptó un renglón con el producto PC-001 de la
--      empresa 2. La fila entró, y entró con utilidad 2.498.000:
--      calculada contra el costo de 2.000 de un producto ajeno.
--
--   2. `producto_proveedor.fk_pp_proveedor`. El producto PC-001 de la
--      empresa 2 quedó abastecido por Mayorista Tech SAS, que es
--      proveedor de la empresa 1. La fila entró.
--
--   3. `descuadres_existencia.fk_desc_producto`. Una operación de la
--      cola de sincronización de la empresa 1 anotó un descuadre sobre
--      el producto PC-001 de la empresa 2. La fila entró.
--
--   4. `cotizacion_items.fk_cotitems_producto` es la misma forma exacta
--      que la primera y no se reprodujo aparte: no hay cotizaciones
--      cargadas y la tabla es gemela de `venta_items`.
--
-- Al escribir el centinela que impide una quinta vez (ver abajo) salieron
-- dos más que el conteo de claves compuestas nunca habría encontrado:
-- `facturas_electronicas`, que no tiene `empresa_id` en absoluto, y
-- `auditoria_accesos`, que lo tiene y no lo usa en su clave. Van en los
-- apartados 5 y 6.
--
-- La primera es la que muerde. Un renglón de venta no es un dato
-- suelto: de él sale el movimiento de inventario que descuenta la
-- existencia (RF-49), de modo que vender hacia fuera restaría unidades
-- del inventario de otro comercio, y la utilidad del renglón se calcula
-- contra el costo del producto apuntado, que es el del otro comercio.
-- El criterio de la RF-09 dice que ninguna consulta de una empresa
-- devuelve registros de otra «verificado sobre productos, clientes,
-- ventas, movimientos y reportes»; hoy el esquema no lo sostiene para
-- ventas, y el panel de indicadores es precisamente un lector de
-- existencias y de movimientos.
--
-- La segunda no tiene padre del que heredar la empresa, y por eso es la
-- más expuesta de las cuatro: `cotizacion_items` y `venta_items` al
-- menos cuelgan de una cabecera con `empresa_id`, y una consulta que
-- suba hasta ella queda acotada aunque la clave no obligue.
-- `producto_proveedor` casa dos tablas que son las dos por empresa, sin
-- nada en medio, de modo que no hay ni esa salida.
--
-- Ninguna se manifiesta hoy en la aplicación: ningún repositorio del
-- backend nombra estas cuatro tablas, `cotizacion_items`, `venta_items`
-- y `descuadres_existencia` están vacías y `producto_proveedor` solo
-- tiene las filas de demostración. Por eso se cierran antes y no
-- después, igual que la V28: con datos dentro, añadir una columna
-- obligatoria y rellenarla es una migración que hay que pensar dos
-- veces, y los módulos de cotización y venta son los que vienen.
--
-- ## Por qué `empresa_id` repetida
--
-- El mismo argumento de la V11, repetido por la V15 para la vista de
-- precios, por la V24 y por la V28: el aislamiento no debe depender de
-- que cada consulta se acuerde de subir hasta el padre. Con la columna
-- repetida y la clave compuesta, una fila incoherente no entra, venga
-- de la aplicación, de un guion de mantenimiento o de un dedazo en la
-- consola.
--
-- ## Lo que no cambia
--
-- Ninguna acción referencial cambia de sentido aquí, al contrario que en
-- la V28. Las cuatro tablas usan CASCADE o RESTRICT, y MySQL admite
-- ambas sobre una clave compuesta cuyas columnas son todas
-- obligatorias; lo que no admite es SET NULL, que es lo que obligó a la
-- V28 a convertir dos en RESTRICT. Aquí no hay ninguna SET NULL.
--
-- `cola_sincronizacion` no tenía clave candidata sobre
-- (empresa_id, operacion_id), que es a lo que necesita apuntar
-- `descuadres_existencia`. Se añade con el mismo nombre que la V11 y la
-- V28 dieron a las suyas.
--
-- Requiere: V4, V11. Aplicar después de V30.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. venta_items · la que muerde
-- ---------------------------------------------------------------------
ALTER TABLE venta_items
  ADD COLUMN empresa_id INT NULL AFTER venta_id;

UPDATE venta_items i
  JOIN ventas v ON v.venta_id = i.venta_id
   SET i.empresa_id = v.empresa_id
 WHERE i.empresa_id IS NULL;

ALTER TABLE venta_items
  MODIFY COLUMN empresa_id INT NOT NULL;

ALTER TABLE venta_items
  DROP FOREIGN KEY fk_venitems_venta;

ALTER TABLE venta_items
  ADD CONSTRAINT fk_venitems_venta_emp
      FOREIGN KEY (empresa_id, venta_id)
      REFERENCES ventas (empresa_id, venta_id)
      ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE venta_items
  DROP FOREIGN KEY fk_venitems_producto;

ALTER TABLE venta_items
  ADD CONSTRAINT fk_venitems_producto_emp
      FOREIGN KEY (empresa_id, producto_id)
      REFERENCES productos (empresa_id, producto_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;


-- ---------------------------------------------------------------------
-- 2. cotizacion_items · la gemela de la anterior
-- ---------------------------------------------------------------------
ALTER TABLE cotizacion_items
  ADD COLUMN empresa_id INT NULL AFTER cotizacion_id;

UPDATE cotizacion_items i
  JOIN cotizaciones c ON c.cotizacion_id = i.cotizacion_id
   SET i.empresa_id = c.empresa_id
 WHERE i.empresa_id IS NULL;

ALTER TABLE cotizacion_items
  MODIFY COLUMN empresa_id INT NOT NULL;

ALTER TABLE cotizacion_items
  DROP FOREIGN KEY fk_cotitems_cotizacion;

ALTER TABLE cotizacion_items
  ADD CONSTRAINT fk_cotitems_cotizacion_emp
      FOREIGN KEY (empresa_id, cotizacion_id)
      REFERENCES cotizaciones (empresa_id, cotizacion_id)
      ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE cotizacion_items
  DROP FOREIGN KEY fk_cotitems_producto;

ALTER TABLE cotizacion_items
  ADD CONSTRAINT fk_cotitems_producto_emp
      FOREIGN KEY (empresa_id, producto_id)
      REFERENCES productos (empresa_id, producto_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;


-- ---------------------------------------------------------------------
-- 3. producto_proveedor · la que no tiene padre
-- ---------------------------------------------------------------------
-- El relleno sale del producto y no del proveedor a propósito. Si
-- alguna fila ya casaba dos empresas, el producto decide de quién es la
-- fila y la clave hacia el proveedor la rechaza al crearse. Esa negativa
-- es la respuesta correcta: la migración se detiene y la fila
-- incoherente queda a la vista, en lugar de entrar como válida.
ALTER TABLE producto_proveedor
  ADD COLUMN empresa_id INT NULL AFTER producto_proveedor_id;

UPDATE producto_proveedor pp
  JOIN productos p ON p.producto_id = pp.producto_id
   SET pp.empresa_id = p.empresa_id
 WHERE pp.empresa_id IS NULL;

ALTER TABLE producto_proveedor
  MODIFY COLUMN empresa_id INT NOT NULL;

ALTER TABLE producto_proveedor
  DROP FOREIGN KEY fk_pp_producto;

ALTER TABLE producto_proveedor
  ADD CONSTRAINT fk_pp_producto_emp
      FOREIGN KEY (empresa_id, producto_id)
      REFERENCES productos (empresa_id, producto_id)
      ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE producto_proveedor
  DROP FOREIGN KEY fk_pp_proveedor;

ALTER TABLE producto_proveedor
  ADD CONSTRAINT fk_pp_proveedor_emp
      FOREIGN KEY (empresa_id, proveedor_id)
      REFERENCES proveedores (empresa_id, proveedor_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;


-- ---------------------------------------------------------------------
-- 4. descuadres_existencia · lo que la cola no pudo aplicar
-- ---------------------------------------------------------------------
-- Primero la clave candidata que le falta al padre.
ALTER TABLE cola_sincronizacion
  ADD CONSTRAINT uq_cola_empresa UNIQUE (empresa_id, operacion_id);

ALTER TABLE descuadres_existencia
  ADD COLUMN empresa_id INT NULL AFTER operacion_id;

UPDATE descuadres_existencia d
  JOIN cola_sincronizacion c ON c.operacion_id = d.operacion_id
   SET d.empresa_id = c.empresa_id
 WHERE d.empresa_id IS NULL;

ALTER TABLE descuadres_existencia
  MODIFY COLUMN empresa_id INT NOT NULL;

ALTER TABLE descuadres_existencia
  DROP FOREIGN KEY fk_desc_operacion;

ALTER TABLE descuadres_existencia
  ADD CONSTRAINT fk_desc_operacion_emp
      FOREIGN KEY (empresa_id, operacion_id)
      REFERENCES cola_sincronizacion (empresa_id, operacion_id)
      ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE descuadres_existencia
  DROP FOREIGN KEY fk_desc_producto;

ALTER TABLE descuadres_existencia
  ADD CONSTRAINT fk_desc_producto_emp
      FOREIGN KEY (empresa_id, producto_id)
      REFERENCES productos (empresa_id, producto_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;


-- ---------------------------------------------------------------------
-- 5. facturas_electronicas · la que no cruza pero debe llevar la columna
-- ---------------------------------------------------------------------
-- Esta no puede cruzar empresas por sí sola: solo referencia la venta, y
-- la venta determina la empresa sin ambigüedad. Lleva la columna por lo
-- mismo que la V28 argumentó para `mapeos_proveedor`: la RN-16 exige
-- filtrar por empresa en toda lectura, y una tabla sin `empresa_id`
-- obliga a que esa consulta sea la excepción que se acuerde de subir
-- por la venta. Las excepciones son lo que después se olvida, y la
-- consulta en cuestión es la de la RF-47, «consultar el estado de
-- validación de cada factura emitida», que es un listado por empresa.
ALTER TABLE facturas_electronicas
  ADD COLUMN empresa_id INT NULL AFTER factura_id;

UPDATE facturas_electronicas f
  JOIN ventas v ON v.venta_id = f.venta_id
   SET f.empresa_id = v.empresa_id
 WHERE f.empresa_id IS NULL;

ALTER TABLE facturas_electronicas
  MODIFY COLUMN empresa_id INT NOT NULL;

ALTER TABLE facturas_electronicas
  DROP FOREIGN KEY fk_facturas_venta;

ALTER TABLE facturas_electronicas
  ADD CONSTRAINT fk_facturas_venta_emp
      FOREIGN KEY (empresa_id, venta_id)
      REFERENCES ventas (empresa_id, venta_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;


-- ---------------------------------------------------------------------
-- 6. auditoria_accesos · la que sí podía mentir
-- ---------------------------------------------------------------------
-- Tiene `empresa_id` propio y una clave de una sola columna hacia
-- `usuarios`, de modo que una fila puede declarar la empresa 1 para un
-- usuario de la empresa 2. No es una fuga de datos: es un registro de
-- auditoría falso, que es peor de lo que parece, porque la auditoría es
-- justo lo que se consulta cuando hay que reconstruir qué pasó.
--
-- Las dos columnas admiten nulo y eso se conserva: un intento de acceso
-- fallido con un correo que no existe no tiene usuario ni empresa.
-- MySQL no comprueba una clave compuesta cuando una de sus columnas es
-- nula, de modo que esas filas siguen entrando, y el acceso correcto
-- del super administrador también: su `usuarios.empresa_id` es nulo
-- (ADR-0020).
--
-- La acción pasa de SET NULL a RESTRICT. No es una pérdida:
-- `ServicioUsuarios` dice en su propio comentario que desactivar una
-- cuenta «no es borrarla: la auditoría de accesos» la necesita, y
-- ningún repositorio ejecuta un DELETE sobre `usuarios`. Ese SET NULL
-- era inalcanzable desde la aplicación, y además anulaba `usuario_id`
-- dejando `empresa_id` en pie; sobre la clave compuesta anularía las
-- dos y el registro perdería a qué empresa fue el acceso. RESTRICT
-- conserva el registro y niega el borrado manual, que es la respuesta
-- correcta si alguien lo intenta.
ALTER TABLE auditoria_accesos
  DROP FOREIGN KEY fk_auditoria_usuario;

ALTER TABLE auditoria_accesos
  ADD CONSTRAINT fk_auditoria_usuario_emp
      FOREIGN KEY (empresa_id, usuario_id)
      REFERENCES usuarios (empresa_id, usuario_id)
      ON DELETE RESTRICT ON UPDATE CASCADE;


-- ---------------------------------------------------------------------
-- Las dos que quedan por fuera, y por qué
-- ---------------------------------------------------------------------
-- Tras esta migración, dos claves siguen apuntando a una tabla con
-- `empresa_id` sin incluirlo, y las dos son correctas así. El centinela
-- de `aplicar.sh` las nombra para que una tercera no pueda colarse
-- detrás de ellas:
--
--   · `sesiones.fk_sesiones_usuario`. La tabla no tiene `empresa_id`
--     propio, de modo que no hay par que pueda discrepar: la empresa de
--     una sesión es la de su usuario y se llega a ella por un solo
--     enlace.
--
--   · `auditoria_plataforma.fk_auditoria_plataforma_usuario`. Aquí
--     `empresa_id` no es la empresa del actor sino la empresa
--     AFECTADA, y el actor es el super administrador, que no pertenece
--     a ninguna (ADR-0020). La V21 ya lo documenta, y admite nulo
--     porque un cambio de UVT (unidad de valor tributario) no afecta a
--     una empresa sino a todas. Una clave compuesta aquí no estaría
--     cerrando un agujero: estaría afirmando algo falso.
