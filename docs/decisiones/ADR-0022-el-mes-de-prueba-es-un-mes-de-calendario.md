# ADR-0022 · El mes de prueba es un mes de calendario, no treinta días

Estado: aceptada · 2026 · resuelve una incoherencia de la RN-14

## Contexto

Los requisitos dicen tres cosas sobre el periodo de prueba, y una no
encaja con las otras dos:

| Dónde | Qué dice |
|---|---|
| RN-14, prosa | «Cada empresa dispone de **un mes** gratuito desde su alta» |
| RF-04, criterio de aceptación | «Una empresa creada hoy queda en prueba **hasta el mismo día del mes siguiente**» |
| RN-14, columna de parámetro | `sgip.plataforma.prueba.dias` · **30 días** |

El código seguía la tercera: `hoy.plusDays(diasDePrueba())`.

Un mes y treinta días solo coinciden en abril, junio, septiembre y
noviembre. Medido sobre un año completo, de las 365 fechas de alta
posibles **240 daban un vencimiento distinto del que el criterio pide**:

```
alta 2026-01-01  →  hoy+30d = 2026-01-31   criterio = 2026-02-01
alta 2026-01-31  →  hoy+30d = 2026-03-02   criterio = 2026-02-28
alta 2026-03-01  →  hoy+30d = 2026-03-31   criterio = 2026-04-01
```

La prueba que cubría el alta no lo detectó porque estaba escrita desde
el código y no desde el requisito: fijaba el reloj el 17 de octubre y
afirmaba `HOY.plusDays(30)`, que es lo que el código hacía. El criterio
pedía el 17 de noviembre y la prueba daba por bueno el 16.

## Decisión

Manda el criterio de aceptación del RF-04. Es el único de los tres
enunciados que no admite dos lecturas, y es lo que se verifica.

El periodo se mide en meses: el parámetro pasa a ser
`sgip.plataforma.prueba.meses` con valor 1, y el alta calcula
`hoy.plusMonths(...)`. Cuando el día no existe en el mes siguiente
—el 31 de enero— `plusMonths` recorta al último que sí existe, que es la
única lectura posible de «el mismo día» en ese caso.

El parámetro viejo no se borra: se le cierra la vigencia. La tabla
guarda histórico con `vigente_desde` y `vigente_hasta` precisamente para
que se pueda contar qué regía antes, y una suscripción creada bajo el
parámetro de días se explica con el parámetro de días.

## Consecuencias

**El informe queda desactualizado en un punto.** La columna de parámetro
de la RN-14 dice «sgip.plataforma.prueba.dias · 30 días» y ya no es así.
Es una entrada para el control de cambios de la próxima versión, no algo
que se corrija en silencio sobre una versión ya entregada.

**La configurabilidad no se pierde, cambia de unidad.** Un plan de tres
meses de prueba se expresa ahora con el parámetro en 3, y sigue cayendo
en el mismo día del mes.

**Queda pendiente el RF-04 entero.** Lo que esta decisión arregla es el
cálculo. Administrar el periodo desde la aplicación —que es lo que el
RF-04 pide y hoy solo se puede hacer con una sentencia SQL— es trabajo
de la v1.1, que es la prioridad que el propio requisito declara.

## La lección, que vale más que la corrección

Una prueba escrita desde el código confirma el código. Esta llevaba
desde el sprint de plataforma afirmando una fecha equivocada con toda
seguridad, y pasaba en verde en cada entrega.

La única forma de que hubiera fallado es que alguien la escribiera
leyendo el criterio del requisito y no la implementación. Las fechas de
frontera que ahora cubre —el 1 de enero, el 31 de enero, el 1 de marzo—
no se le ocurren a quien parte del código, porque desde el código todas
las fechas se ven iguales.
