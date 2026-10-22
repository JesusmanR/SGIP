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

La alternativa descartada era materializar la regla propuesta en una
tabla temporal y leer la vista contra ella. Habría evitado la
duplicación, pero exige escribir en la base para responder una pregunta
de solo lectura, y deja la puerta abierta a que una simulación
interrumpida deje reglas a medias en una tabla que nadie mira.
