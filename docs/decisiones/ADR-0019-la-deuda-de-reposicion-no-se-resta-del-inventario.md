# ADR-0019 · La deuda de reposición se declara aparte, no se resta del inventario

Estado: aceptada · 2026 · revisa una consecuencia de ADR-0014

## Contexto

ADR-0014 decidió que la existencia negativa **es** la deuda de
reposición, y lo hizo bien: con el negativo, la existencia disponible es
la existencia y no hay dos cifras que puedan desalinearse.

Entre sus consecuencias anotó esta:

> **La valorización de inventario puede dar negativo** para una
> referencia. `v_inventario_valorizado` lo va a mostrar, y está bien que
> lo muestre: es pasivo, no error.

La primera mitad de esa frase es cierta y la segunda se quedó corta. Al
revisar el esquema completo salió el motivo. `v_inventario_valorizado` no
se lee fila por fila: se lee sumada, porque la pregunta que responde es
cuánto vale la bodega. Y la columna `valor_al_costo` era la misma para
las dos cosas, de modo que una referencia en negativo no aparecía como
pasivo al lado del activo: lo **restaba**.

Medido sobre la base de demostración, con PC-001 en −2 y costo de
2.100.000:

| | |
|---|---|
| `valor_al_costo` de PC-001 | −4.200.000 |
| total de la bodega como lo calculaba la V12 | 59.150.000 |
| total con la existencia acotada a cero | 63.350.000 |

Son 4,2 millones de diferencia por una sola referencia, y en la
dirección que peor se detecta: el tablero no mostraba un error, mostraba
una cifra plausible y más baja. Nadie la va a cuestionar. Lo mismo
ocurría con `capital_retenido` en `v_rotacion_inventario`.

Que ADR-0014 lo hubiera previsto no lo volvía correcto. Preverlo fue
acertado; llamarlo «pasivo, no error» supuso que alguien leería la fila,
cuando lo que se lee es la suma.

## Decisión

**Una columna que se suma para responder «cuánto vale la bodega» no
puede cargar también la respuesta a «cuánto debo».** La suma de una
columna mezclada no responde ninguna de las dos preguntas.

Así que se separan, sin perder nada:

- `valor_al_costo`, `valor_a_precio_venta`, `utilidad_potencial` y
  `capital_retenido` acotan la existencia a cero antes de multiplicar:
  `GREATEST(existencia, 0) * …`. Valorizan **lo que hay**. No existe
  media bodega negativa; una existencia de −2 significa que hay cero
  unidades y se deben dos.
- `unidades_pendientes` y `valor_pendiente_al_costo` nombran **lo que se
  debe**, en columnas propias, en las dos vistas.
- La columna `existencia` se deja intacta, con su signo. Es el dato real
  y el tablero necesita poder mostrar −2.

Lo que ADR-0014 quería —que el pasivo sea visible— se cumple mejor así
que antes: ahora tiene nombre. Antes estaba visible solo para quien
mirara una fila concreta, e invisible para quien mirara el total, que es
casi todo el mundo.

Migración `V19__la_valorizacion_no_resta_lo_que_se_debe.sql`.

## Por qué no un signo en el informe

La alternativa era dejar la aritmética como estaba y arreglarlo al
presentar: que el tablero sumara solo las filas positivas. Se descartó
porque reparte la misma regla entre cada pantalla, cada informe
exportable y cada consulta que alguien escriba después. La vista es el
único sitio donde la regla se escribe una vez. Es el mismo argumento de
ADR-0003 para tener los precios en la vista y no en el código.

## Consecuencias

**La suma de `valor_al_costo` ya no cuadra con `SUM(existencia * costo)`.**
Es deliberado y es la diferencia entre el activo y el neto. Quien
necesite el neto lo tiene: `valor_al_costo - valor_pendiente_al_costo`.

**Hay una cifra nueva que alguien debe mirar.** ADR-0014 ya advirtió que
el mecanismo no cobra la deuda por sí solo. `valor_pendiente_al_costo`
pone precio a esa advertencia: no dice cuántas referencias se deben,
dice cuánto cuesta reponerlas, que es la cifra con la que se decide una
compra.

**La utilidad potencial de una referencia en negativo es cero, no
negativa.** No se pierde utilidad por deber unidades; se pierde la
oportunidad de ganarla, y eso ya lo dice `unidades_pendientes`.
