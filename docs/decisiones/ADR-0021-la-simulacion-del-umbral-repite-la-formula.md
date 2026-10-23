# ADR-0021 · La simulación del umbral repite la fórmula de la vista, y una prueba la sostiene

Estado: aceptada · 2026 · desarrolla ADR-0003 y ADR-0004

## Contexto

El RF-35 pide avisar de qué referencias cambiarían de tratamiento
tributario al aplicar una lista de precios. Se decidió avisar **antes**
de aplicarla y no después, porque avisar después cumple la letra del
requisito y no sirve para nada: quien se entera cuando el precio ya
cambió no tiene nada que decidir.

Eso tiene una consecuencia incómoda. ADR-0003 estableció que el precio
se calcula en `v_precios_producto` y en ningún otro sitio, y esa regla
es lo que hace cierto el RF-31 —el precio no se captura a mano— y lo que
hace que el RF-30 surta efecto sin desplegar. Pero la simulación tiene
que calcular el precio de una regla **que todavía no existe**, y una
regla que no está en la tabla no entra en la vista.

De modo que la simulación no puede salir de la vista. Tiene que repetir
su fórmula.

## Decisión

Se repite, a sabiendas, en `RepositorioPrecios.simularCruces`. Y se
acepta la duplicación a cambio de una comprobación que la ataja, en
`database/verificar_precios.sh`:

> Aplicada con el factor y la base que la vista declara para cada
> referencia, la fórmula duplicada tiene que devolver **el mismo precio**
> que la vista, en todas.

Compara precio contra precio, no clasificaciones, y por eso delata
cualquier desviación: un redondeo distinto, un orden de operaciones
distinto, una rama de la base de cálculo mal escrita. El factor y la
base se leen de la vista referencia por referencia, de modo que la
comprobación sigue valiendo cuando un producto esté gobernado por una
regla de categoría o de proveedor y no por la general.

**La comprobación que parecía bastar, y no bastaba.** La primera versión
de este guardián era otra: simular con el factor que ya rige tiene que
encontrar cero cruces, porque nada cambia. Es una igualdad elegante y no
caduca, pero probada a propósito con la fórmula desviada un 0,1 % **pasó
en verde**. Solo mira la franja pegada al umbral, y en esa franja no caía
ninguna referencia de la base de demostración. Se conserva en el guion,
porque prueba el aviso de extremo a extremo, pero no es lo que sostiene
la duplicación.

Las dos se ejecutan con las **dos** bases de cálculo, no solo con la que
rija ese día. Una rama sin guardián es exactamente lo que permitió que la
vista ignorara `forma_calculo` desde la V3 hasta la V25.

## Consecuencias

La fórmula vive en dos sitios y hay que cambiarla en dos sitios. La
comprobación no lo impide: lo delata. Quien cambie la vista y no la
simulación tendrá la entrega en rojo con el motivo escrito, que es la
diferencia entre una duplicación vigilada y una duplicación a secas.

De lo anterior se sigue una regla para quien añada guardianes parecidos:
hay que verlos fallar. Un guardián que nunca se ha visto en rojo no es un
guardián, es una línea que da tranquilidad. El de aquí se comprobó
desviando la fórmula un 0,1 % y confirmando que señala nueve referencias
—y fue así como se descubrió que el guardián anterior no señalaba
ninguna.

Cuando exista el módulo de importación de listas de precios, reutiliza
esta misma simulación en lugar de escribir la tercera copia.

## Nota posterior · el módulo de importación llegó, y el encargo no se
## podía cumplir tal como estaba escrito

El párrafo de arriba daba por hecho que la importación necesitaría la
misma simulación. No es así, y se ve al intentarlo: `simularCruces`
calcula el precio de una **regla** que todavía no existe sobre el costo
actual, y la importación necesita el de un **costo** que todavía no se
ha aplicado sobre la regla actual. Son la misma fórmula con distinto
hueco; la consulta no se puede compartir.

Lo que sí se comparte es la fórmula, que es lo que se habría escrito por
tercera vez. Vive en `RepositorioPrecios.PRECIO`, con huecos para el
costo, el factor y la forma de cálculo, y la usan las dos consultas.

Al bajarla a una constante salió algo que no se veía: **dentro de
`simularCruces` la fórmula ya estaba escrita tres veces** —en la columna
del precio nuevo, en la del tratamiento nuevo y en el filtro—, de modo
que la extracción mejora también la consulta que ya existía. El recuento
honesto de copias no era dos, era cuatro.

Siguen siendo dos definiciones: la de la vista y la del backend. El
guardián que las compara no cambia.

La alternativa descartada era materializar la regla propuesta en una
tabla temporal y leer la vista contra ella. Habría evitado la
duplicación, pero exige escribir en la base para responder una pregunta
de solo lectura, y deja la puerta abierta a que una simulación
interrumpida deje reglas a medias en una tabla que nadie mira.

## Nota posterior · los cinco verificadores no eran independientes

Esta decisión se apoya en que una comprobación que no se ha visto
fallar no demuestra nada. Hay una variante del mismo problema que no
estaba contemplada: una comprobación que falla por algo que no es su
asunto.

`verificar_tributacion.sh` creaba el producto `SKU-TRIB` con existencia
40 y ningún movimiento, y no lo retiraba al terminar.
`verificar_kardex.sh` comprueba exactamente eso —que no haya
referencias con existencia sin kárdex que la sustente— de modo que
pasaba si se ejecutaba antes y fallaba si se ejecutaba después, con un
descuadre que no era del esquema sino del guion anterior. Integración
continua los ejecuta en el orden que pasa. Estaba verde por el orden.

El arreglo es que cada guion deshaga lo que crea, y la comprobación del
arreglo es ejecutar la batería en el orden de integración continua, en
el inverso, y dos veces seguidas sin reconstruir la base. Los tres
pases tienen que dar lo mismo; si no lo dan, el resultado de cualquiera
de ellos no significa nada.

De paso quedó a la vista otra suposición: una base recién construida
con datos de demostración tiene **cero** ventas. Una comprobación que
buscaba una venta existente para probar la facturación estaba midiendo
el rastro de un guion anterior, no el esquema. Las comprobaciones
nuevas crean lo que necesitan.
