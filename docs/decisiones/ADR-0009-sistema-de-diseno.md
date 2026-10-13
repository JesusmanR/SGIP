# ADR-0009 · Sistema de diseño de la interfaz

Estado: aceptada · 2026

## Contexto

La pantalla de acceso se construyó antes de que existiera un sistema de
diseño. Al abordar las doce pantallas restantes hacía falta fijar
paleta, tipografía, retícula y componentes, o cada pantalla habría
resuelto lo mismo de una manera distinta.

## Decisión

Una sola familia tipográfica, Inter, en cuatro pesos. El carácter lo da
la escala y la jerarquía, no la mezcla de fuentes. Las cifras usan
numeración tabular.

La paleta parte de la identidad de Black Steel Parts, pero los colores
de estado se oscurecieron hasta alcanzar la relación de contraste de 4,5
a 1 que exige el RNF-13. Los originales no la cumplían en texto pequeño.

Las tarjetas se delimitan con borde de un pixel, no con sombra. Radio 10
en tarjetas y 6 en controles: la diferencia comunica jerarquía sin
recurrir a la elevación.

Las etiquetas van en oración, no en mayúsculas sostenidas.

## El componente propio

El artículo 424 del Estatuto Tributario excluye del impuesto los
computadores hasta 50 UVT y los dispositivos móviles hasta 22 UVT.
Cruzar ese tope encarece el producto en diecinueve puntos de golpe.

El sistema incorpora un medidor de umbral: una barra que sitúa el precio
respecto del tope, con la zona de exclusión en verde y la gravada en
rojo. Aparece en la ficha de producto, en el registro y en la
importación de precios.

Es el único elemento inventado de la interfaz. El resto son patrones
establecidos, y conviene que lo sean: en una herramienta de uso diario
la originalidad en los controles se paga en confusión.

## Consecuencias

La pantalla de acceso implementada ya no coincide con el sistema. Queda
como tarea del Sprint 2 reimplementarla, y el informe documenta ambas
versiones para que la diferencia sea explícita y no un descuido.

Adoptar Inter añade una dependencia tipográfica. Es de licencia abierta
y se distribuye con la aplicación, de modo que no depende de lo que el
usuario tenga instalado.

Los diseños resuelven el estado normal de cada pantalla. Los estados de
error, carga y vacío están especificados en el documento de mock-ups y
siguen pendientes de diseño. Un diseño que solo resuelve el caso feliz
está a la mitad.

## Nota posterior

Dos cosas cambiaron después de escribir esta decisión.

La figura de la pantalla de acceso implementada **se retiró del
informe**. Conservaba la paleta anterior y ya no representaba la
solución, de modo que documentar «ambas versiones» dejó de tener
sentido: lo que el informe muestra ahora son las dos versiones vigentes
del acceso, la de ordenador y la móvil.

La reimplementación de esa pantalla dejó de ser una tarea de Swing. El
front se construye en React y el módulo de escritorio en Swing no se
migra. Ver ADR-0010.

La paleta Aurora Mist y los tokens derivados están en
`../../web/src/estilos/tokens.css`, que es la única fuente de color del
front. Allí queda registrado, con las luminancias medidas, por qué una
insignia de estado nunca puede ser solo un punto de color.
