# ADR-0029 · El precio se calcula solo para la empresa que pregunta

Estado: aceptada · 2026-11-07

## Contexto

La revisión final de la puesta a punto marcó un hallazgo importante (I3): leer un
producto tardaba más de un segundo, y el tiempo crecía con toda la base y no con la
tienda. El lector de código de barras de la caja hace una lectura por cada producto que
pasa, así que eso bloqueaba la caja. ADR-0025 ya lo había anotado: 2 s por página y 1 s
por producto con 10.000 productos.

La causa es la forma de dos vistas. `v_regla_margen` (V36) y `v_regla_iva` (V34) eligen
la regla vigente con un `ROW_NUMBER()` sobre todos los productos de la base. Una tabla
derivada con función de ventana no se puede fusionar con la consulta que la usa.
Además, `v_precios_producto` la une con un `LEFT JOIN`. Por eso el filtro de empresa de
`ConsultaCatalogo` no llega dentro, y el motor materializa en cada lectura las reglas de
todas las empresas.

## Qué se midió

Se usó un MySQL 9.7.2 desechable en un portátil, con la cadena V1–V37. La empresa
consultada tenía 200 productos con dos reglas propias cada uno, reglas generales, de
categoría, de proveedor y de impuesto. Las otras cinco empresas tenían 2.000 productos
cada una, con la misma forma y los mismos códigos de barras. En total eran unas 10.500
filas de productos y 21.000 de reglas. Cada cifra es la mediana de cinco ejecuciones de
la sentencia de `ConsultaCatalogo`, en milisegundos.

| Lectura | V37, solo la empresa | V37, con 10.000 en otras | V38, con 10.000 en otras |
|---|---|---|---|
| Un producto por identificador | 35 | 1.300–2.400 | 3–7 |
| Un producto por código de barras | 35 | 1.350–2.400 | 3–6 |
| Página de 50 | 67–96 | 1.400–2.500 | 70–107 |

Planes con `EXPLAIN ANALYZE`:

- **Antes:** tres `Materialize` sobre toda la base, de 49.798, 49.798 y 21.432 filas.
  Son las reglas de margen de contado, las de crédito y las de IVA, de todas las
  empresas.
- **Después:** las dos vistas se fusionan en `v_precios_producto`. Por cada producto ya
  acotado por empresa hay una `Materialize (invalidate on row from p)` de unas 5 filas,
  que se obtienen con `Index lookup on r using uq_reglas_margen_producto (empresa_id =
  p.empresa_id, producto_id = p.producto_id or NULL)`. El IVA usa el mismo acceso, por
  `fk_reglas_iva_producto_emp`.

En la prueba con base, el paso mayor del plan para la empresa consultada pasaba de 1.047
a 71.449 filas al añadir las otras empresas. Con V38 no cambia.

Añadir `empresa_id` al `PARTITION BY` se probó primero y no sirve: el filtro llega por el
`JOIN` y no por un `WHERE` sobre la vista, así que el motor no lo empuja.

## Decisión

La migración `V38` redefine las dos vistas de reglas. `V34` y `V36` no se tocan, porque
ya están aplicadas. `v_precios_producto` tampoco se toca: lee las dos vistas por su nombre.

- Cada vista recorre `productos` y busca las reglas del producto en una tabla derivada
  `LATERAL`, con la empresa, la categoría y el proveedor de esa fila. Sin función de
  ventana en el nivel de la vista, MySQL la fusiona, y la búsqueda se hace solo para los
  productos que la consulta ya acotó.
- La precedencia no cambia: es el mismo orden y el mismo desempate por identificador de
  regla. En el margen, el `ROW_NUMBER` queda dentro de la `LATERAL`, particionado por
  forma de pago. En el IVA basta `ORDER BY … LIMIT 1`.
- No hacen falta índices nuevos: los dos que usa el plan ya existían.

Se eligió esta vía y no consultas parametrizadas en Java porque la vista es el único
sitio donde se decide un precio (ADR-0003, V36). Importación, panel, inventario valorizado
y rotación la leen igual, y con V38 todos ganan sin cambiar código.

### El resultado es idéntico

- **Datos.** En la copia de 10.500 productos se compararon las seis vistas antes y
  después con `EXCEPT ALL` en los dos sentidos, y salieron 0 filas distintas. La copia
  incluía propias con porcentaje y con precio, de categoría, de proveedor y de ambos,
  reglas vencidas, futuras, empates de fecha, un tipo de impuesto desactivado, una empresa
  sin `empresa_config`, una no responsable, una sin exclusión por umbral, productos
  inactivos y 900 productos sin regla de IVA.
- **Columnas.** Nombres, orden y tipos son iguales en todas las vistas, salvo dos
  columnas de `v_regla_margen`: `forma_pago` y `forma_calculo` son ahora el `ENUM` de la
  tabla, con los mismos valores, en lugar del `VARCHAR` que producía la unión de la V36.
  Su único lector, `RepositorioPrecios.reglaVigente`, las recibe como texto igual que
  antes.

## Presupuesto como prueba (manual §6)

`PreciosPorEmpresaConBaseTest` (`conBase`) carga ese volumen una vez y comprueba tres cosas:

1. El paso mayor del plan para un producto, un código de barras y una página no crece más
   de 1,5 veces al añadir las otras empresas. Esta comprobación no depende de la máquina y
   es la que da el rojo sin V38.
2. Cada lectura de `ConsultaCatalogo` es una sola sentencia.
3. Hay un tiempo máximo, como mediana de cinco:
   - 250 ms por producto, unas cuarenta veces lo medido después y varias veces menos que
     lo de antes;
   - 1.000 ms por página.

   Son cifras holgadas para un ejecutor de la CI lento. No prometen el tiempo de la caja:
   marcan lo que no debe volver a pasar.

## Qué no se cachea, y por qué

No se cachea ni el precio ni el producto leído. Un precio debe verse en cuanto cambia: una
regla nueva, un costo importado o el cambio de día que activa o vence una vigencia. Una
caché tendría que invalidarse con cada una de esas escrituras y con el reloj. Con lecturas
de unos milisegundos, no hay un dato que la justifique.

## Lo que queda

La página todavía paga por todos los productos **de su propia empresa**:

- `v_precios_producto` se materializa entera antes de `ORDER BY nombre LIMIT 50`, porque
  la subconsulta de `imagen_principal` en su lista de selección impide fusionarla.
- Calcula `fn_valor_uvt(CURDATE())` unas quince veces por fila.

Con una empresa de 10.000 productos, la página tardó 5,5 s con V38, frente a 7,9 s con
V37 en la misma base. No empeora, pero sigue siendo lento. Leer un producto sí es
constante (3–7 ms). Arreglarlo pide redefinir `v_precios_producto`: la UVT calculada una
vez y la imagen con un `LEFT JOIN`, para que el `LIMIT` actúe antes de buscar reglas.
Queda para cuando una tienda tenga un catálogo así. No bloquea la caja.
