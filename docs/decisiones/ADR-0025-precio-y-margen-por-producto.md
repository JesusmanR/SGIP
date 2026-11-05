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
datos. Un vínculo obligaría a la vista a resolver una regla a partir de otra. El
crédito sí sigue al contado mientras la casilla esté sin marcar, y es a propósito:
lo hace el formulario, al guardar, delante de quien edita, y queda en la historia
como cualquier otro cambio. Lo que no ocurre es que el crédito cambie solo, sin
que nadie haya abierto el producto.

El formulario marca «Usar otro precio a crédito» si el crédito es otra regla: si
difieren las cifras, o si coinciden por casualidad con distinta clase de regla.
Un 20 % sobre 1.000 y 1.200 escritos a crédito dan la misma cifra y son dos
decisiones: abrirlos como iguales borraría el precio escrito al cambiar el contado.

### Crear un producto en pantalla

`FormularioProducto` con `modo="crear"`, y el botón «Nuevo producto» del catálogo,
solo para el administrador. Son obligatorios los que exige `SolicitudProducto`
(nombre, categoría, proveedor, código y costo) y el contado, aunque nadie lo haya
tocado. La casilla marcada exige también el crédito: sin él, el servidor lo
igualaría al contado y no sería lo que se ve en pantalla. Por eso el alta no manda
`creditoIgualAlContado`, que `SolicitudProducto` no tiene.

La existencia inicial es opcional y solo existe al crear: el backend la convierte
en un movimiento `INGRESO`. Al editar sigue sin haber campo de existencia.

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
- Precio de más de 12 cifras, escrito o resultado de un porcentaje: 400. `precio_fijo`
  es DECIMAL(12,0) porque `precio_unitario` de las ventas es DECIMAL(14,2): un precio
  más largo se guardaría bien y fallaría el día de cobrarlo.

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
  una subida general los va a mover. El alcance se pregunta a `reglas_margen` y no
  a la vista, porque se cuenta al abrir la pantalla.
- Igualar el crédito a un contado propio lee primero esa regla con bloqueo, y solo
  si no hay va a la vista, que es una lectura normal: tras esperar el bloqueo,
  vería la foto de antes y copiaría el contado viejo.

## Consecuencias

- Una empresa puede tener precios que no siguen ninguna regla general. Por eso
  el catálogo anota «precio escrito» junto a la cifra.
- La pantalla de carga masiva cambió una frase que dejó de ser cierta: «Los
  precios de venta ya salen del costo nuevo» no vale para un precio escrito.
- **Cuánto cuesta.** MySQL 9.7 en el equipo de desarrollo, base desechable con datos
  generados para medir, en milisegundos. Sirve para comparar, no como promesa de tiempo.

  Sin reglas propias, 10.000 productos en dos empresas y 10 reglas generales, misma
  sentencia del catálogo, con las columnas nuevas en la V36. Base reconstruida en cada
  una de tres rondas, cinco ejecuciones por ronda, mediana de las tres medianas:

  | Consulta | V35 | V36 | Diferencia |
  |---|---|---|---|
  | Listado, 50 filas | 758 | 816 | +8 % |
  | Búsqueda por texto | 328 | 341 | +4 % |
  | Un producto por identificador | 321 | 328 | +2 % |

  Con dos reglas propias por producto, que es el régimen normal porque el alta las
  crea: la mitad con porcentaje y la mitad con precio escrito. Una empresa con 10.000
  productos más los de demostración y 20.005 reglas. Mediana de tres ejecuciones:

  | Consulta | Una sola condición con `OR` | Unión de la V36 |
  |---|---|---|
  | Listado, 50 filas | 30.395 | 2.127 |
  | Búsqueda por texto | 29.087 | 1.244 |
  | Un producto | 29.375 | 1.190 |
  | `reglaVigente` | 35 | 34 |
  | Vista previa de una carga de 500 líneas, sin ninguna que cruce el umbral | 29.738 | 2.018 |
  | Alcance por la vista | 29.313 | 1.308 |
  | Alcance por `reglas_margen`, el que queda | — | 89 |

  Por eso la vista es una unión: con la condición única, cada regla recorría todos
  los productos de su empresa, y duplicar el catálogo cuadruplicaba el trabajo. Lo
  protege `EscalaDeReglasPropiasTest`, que lee las filas reales del plan y no un reloj.
  Con 1.000 productos y las mismas reglas por producto, el listado pasó de 471 a 183.

  El dinero no cambia: las columnas de `v_precios_producto`, `v_inventario_valorizado`
  y `v_rotacion_inventario` (29.715 líneas) son idénticas a las de la V35 sin reglas
  propias, y con 17.155 reglas propias de toda clase, a las de la condición única.

  Sigue siendo lento: 2 segundos por página a esa escala, y 1 por producto. Las dos
  vistas de reglas se materializan enteras para todas las empresas en cada consulta.
  Esa forma viene de antes de la V36, pero las reglas propias le dan más filas que
  materializar: sin ellas, la misma página tarda 0,8 segundos. Queda pendiente, y no
  se cachea: un precio debe verse en cuanto cambia.
- El `_emp` de CI sube de 46 a 47. `database/aplicar.sh` se queda en 46 porque
  se detiene en la V31: su número es el de la V31, y el de CI es el del esquema
  final.
- Los diagramas de `docs/diagramas/fuente` no se actualizaron.
