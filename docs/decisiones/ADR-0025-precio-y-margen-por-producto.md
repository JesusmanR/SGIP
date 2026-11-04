# ADR-0025 · El precio de venta y el porcentaje se escriben por producto

Estado: aceptada · 2026 · retira la prohibición del RF-31; enmienda el ADR-0003 y el ADR-0021

## Contexto

El RF-31 prohibía capturar el precio a mano: el precio solo podía salir de
una regla de margen por categoría o proveedor. El ADR-0003 lo sostenía con
una vista, `v_precios_producto`, como único sitio donde se calcula.

En la tienda eso no alcanza. Quien registra un producto piensa en una de dos
cosas: «a este le gano el 20 %» o «este se vende a 1.250». El usuario decidió
que el producto muestre los dos datos y que, al escribir uno, se complete el
otro.

## Decisión

**Se retira la prohibición de capturar el precio.** Sigue vigente lo que el
ADR-0003 protege: el precio que se vende lo resuelve una sola vista.

**Una regla propia del producto es una fila más de `reglas_margen`.** La V36
añade `producto_id` y `precio_fijo`. La fila lleva `factor` (se escribió un
porcentaje) o `precio_fijo` (se escribió la cifra), nunca los dos; lo impone
un `CHECK`. Así la historia, la vigencia y el reparto por empresa son los que
la tabla ya tenía.

**Precedencia:** lo escrito para un producto gana a toda regla general. Entre
las generales todo sigue igual: gana la más específica y, a igualdad, la más
reciente. Las reglas generales no se tocan.

**El margen no es automático.** No se siembra ninguna regla. Sin regla, el
precio es nulo: nunca cero. El catálogo dice «Falta el precio de venta» al
administrador.

**El porcentaje se calcula sobre el costo:** 1.000 al 20 % es 1.200. El precio
se redondea al peso, con la mitad hacia arriba. Un precio escrito da su
porcentaje con dos decimales: 1.250 sobre 1.000 es 25,00 %.

### Lo que se conserva cuando cambia el costo

| Se escribió | Se conserva | Cambia | Ejemplo |
|---|---|---|---|
| El porcentaje | El porcentaje | El precio | 20 %, costo 1.500: precio 1.800 |
| El precio | El precio | El margen | 1.250, costo 1.100: margen 13,64 % |
| Nada (regla general) | La regla | El precio | Se recalcula como siempre |

Se conserva lo que la persona decidió. Quien escribió «le gano el 20 %» quiere
seguir ganando eso; quien escribió «se vende a 1.250» quiere esa cifra en la
etiqueta. Es lo que hace la importación de costos, y su vista previa muestra
lo mismo que aplicará.

### Por qué la vista previa del navegador no decide el precio

`web/src/servicios/precio.js` completa el otro campo mientras se escribe, pero
**solo se envía lo que el usuario escribió**. El precio se calcula una vez, en
la vista, sobre el costo guardado.

Si el navegador enviara las dos cifras, habría dos verdades y un costo cambiado
por otra persona entre abrir y guardar las separaría. Por eso enviar porcentaje
y precio a la vez es un 400, y el catálogo se refresca con lo que devuelve el
servidor.

Para que la vista previa no enseñe una cifra que luego no se vende, usa enteros
exactos y no `Number`: en coma flotante, 1.001,25 al 20 % da 1.201 y la base da
1.202. `database/muestras/casos_de_precio.csv` es una sola tabla, y la leen
`verificar_precios.sh` contra la vista, `CalculoDePrecioTest` contra Java y
`precio.prueba.js` contra el navegador. Si uno se aparta, los tres lo dicen.

### Qué significa cada columna de la vista

- `margen_contado`, `margen_credito`: lo que se gana de verdad, sobre el precio
  ya redondeado. Con costo 7 al 20 % el precio es 8 y el margen 14,29 %.
- `porcentaje_contado`, `porcentaje_credito`: lo declarado en la regla, sobre el
  costo. Es nulo con un precio escrito. Una regla `SOBRE_PRECIO` se traduce a su
  equivalente sobre el costo, de modo que 20 % sobre el precio se lee 25 %.

El formulario abre con el porcentaje declarado y no con el margen real. Si
abriera con 14,29, guardar sin cambiar nada movería el precio.

`base_contado` y `base_credito` son nulas con un precio escrito: no se calculó
sobre nada. Las dos columnas de margen nacen de `NULLIF(costo, 0)`, de modo que
ningún cálculo divide por cero; además, `ck_productos_costo` ya impide el costo
cero.

### El crédito

En el alta, `contado` es obligatorio y `credito` opcional: sin él, el crédito
queda igual al contado. En la edición nada es obligatorio.

| `contado` | `credito` | `creditoIgualAlContado` | Qué pasa con el crédito |
|---|---|---|---|
| — | — | — o `false` | Nada cambia |
| Sí | — | — o `true` | Copia el contado |
| Sí | — | `false` | No se mueve |
| — | — | `true` | Copia la regla de contado vigente, aunque sea heredada; sin ella, 400 |
| — o sí | Sí | — o `false` | Toma lo escrito |
| — o sí | Sí | `true` | 400: se contradice |

«Igual al contado» **no se guarda como vínculo**: son dos reglas con los mismos
datos. Un vínculo obligaría a la vista a resolver una regla a partir de otra, y
un cambio futuro del contado arrastraría un crédito que nadie volvió a mirar. El
formulario marca «Usar otro precio a crédito» si las dos cifras difieren.

**Desviación consciente de lo pedido.** El requisito decía `contado` obligatorio
en alta y edición. En edición no lo es: mandar el contado aunque nadie lo haya
escrito sacaría al producto de la regla de su categoría al corregirle el nombre,
y eso contradice «solo se envía lo que el usuario escribió».

### Lo que se rechaza y lo que solo se advierte

- Precio por debajo del costo: se permite, con advertencia. A veces se liquida.
- Porcentaje cero o negativo: 400. Ese caso se hace escribiendo el precio, que
  deja claro que es una cifra decidida y no un cálculo.
- Porcentaje que redondea a cero pesos, precio con centavos, porcentaje con más
  de dos decimales o por encima de 9.899,99 %: 400. Ninguno se redondea en
  silencio.

### La historia

Cambiar el precio o el porcentaje cierra la regla vigente del producto ayer y
abre otra desde hoy. Dos cambios el mismo día corrigen la misma fila, porque
`uq_reglas_margen_producto` solo admite una por día, y dos filas de un día no
dirían cuál mandó. Guardar lo mismo no escribe nada.

### Lo que no se toca

- `ProductoParaVenta` sigue siendo una lista explícita, sin margen. Quien vende
  no ve lo que se gana.
- Una regla propia no toca la fila de `productos`, de modo que no sube `@Version`.
  Dos administradores que cambian el precio del mismo producto a la vez se
  ordenan con un `FOR UPDATE` sobre el producto: la última escritura gana, y la
  historia conserva la anterior si fue de otro día.
- La simulación y el alcance de una regla general no cuentan los productos con
  regla propia: esa regla no los alcanza, y decir lo contrario haría creer que
  una subida general los va a mover.

## Consecuencias

- Una empresa puede tener precios que no siguen ninguna regla general. Por eso
  el catálogo anota «precio escrito» junto a la cifra.
- La pantalla de carga masiva cambió una frase que dejó de ser cierta: «Los
  precios de venta ya salen del costo nuevo» no vale para un precio escrito.
- La vista gana una condición y una clave de orden. Con 10.000 productos cuesta
  entre un 5 y un 8 % más: unos 100 ms en el listado completo y unos 40 ms al
  buscar uno. El dinero que devuelve es idéntico al de la V35 cuando no hay
  reglas propias, comparado columna a columna. La lentitud de fondo, unos 600 ms
  por producto a esa escala, viene de antes: la vista de reglas se materializa
  para todas las empresas. Queda pendiente, y no se cachea: un precio debe
  verse en cuanto cambia.
- El `_emp` de CI sube de 46 a 47. `database/aplicar.sh` se queda en 46 porque
  se detiene en la V31: su número es el de la V31, y el de CI es el del esquema
  final.
- Los diagramas de `docs/diagramas/fuente` no se actualizaron.
