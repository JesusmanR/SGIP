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

## Nota posterior

La decisión se mantiene: los precios se resuelven en `v_precios_producto`
y no como columnas generadas.

La vista cambió dos veces desde entonces. Ahora expone `empresa_id`
—sin él la aplicación no podía filtrarla por empresa, que es lo que
exige la RN-16— y resuelve los interruptores tributarios de la empresa,
de modo que ninguna consulta posterior tenga que acordarse de ellos. Ver
ADR-0016.

También expone `tributacion_sin_configurar`: una empresa sin
configuración tributaria no tiene regla aplicable y su precio al cliente
no se puede calcular. Antes la vista habría devuelto nulo sin explicar
por qué.

## Nota posterior · las dos bases de cálculo

La vista aplicaba siempre `costo × factor`, y `reglas_margen` guardaba
desde la V3 una columna `forma_calculo` con dos valores posibles. Nadie
la leía: un margen configurado «sobre el precio de venta» se calculaba
como un recargo sobre el costo. Con 20 % y costo 1.000 el precio salía
1.200 en vez de 1.250.

La V25 lo corrige. La vista distingue las dos bases y una restricción
impide el único factor sin sentido: con 2 el divisor es cero, de modo que
un margen del 100 % sobre el precio exigiría costo cero.

No cambia esta decisión —el precio se sigue resolviendo en la vista—,
pero sí la refuerza: el motivo de que el defecto durara veintidós
migraciones es que nada comparaba lo que la regla declaraba con lo que la
vista calculaba. Ahora `database/verificar_precios.sh` lo compara, con
las cifras del propio requisito.

La vista expone además `base_contado` y `base_credito`, que es lo que el
RF-32 pide que la interfaz indique. Dos productos con el mismo precio
pueden venir de reglas distintas y la pantalla no tiene cómo saberlo
mirando el número.
