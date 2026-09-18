# ADR-0003 · Los precios de venta se calculan en una vista

Estado: aceptada · 2026 · reemplaza el criterio de la fase previa

## Contexto

Cuando el margen era un factor fijo, el precio de venta podía ser una
columna generada de la tabla `productos`. Al volverse configurable por
categoría, proveedor y forma de pago, el precio pasó a depender de
`reglas_margen` y de `reglas_iva`, que son tablas distintas.

MySQL solo admite columnas generadas que dependan de columnas de la misma
fila.

## Decisión

`productos` almacena únicamente el costo. Los precios de contado y de
crédito, el tratamiento tributario y el total al cliente se resuelven en
la vista `v_precios_producto`, que aplica la precedencia entre reglas.

Siguen siendo columnas generadas `productos.estado_stock`, los subtotales
de las líneas y los totales de cotización y venta, porque cada una
depende solo de su propia fila.

## Consecuencias

El modelo permanece en tercera forma normal. Denormalizar el factor sobre
`productos` habría permitido la columna generada, pero introduce una
dependencia transitiva: el factor depende de la categoría, no de la clave
del producto.

El precio deja de estar materializado, así que las consultas masivas
pagan el costo de resolver la vista. Si el rendimiento se vuelve un
problema con diez mil referencias, la salida es una tabla de
materialización refrescada al aplicar cada lista de precios, no volver a
la columna generada.
