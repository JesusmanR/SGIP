# ADR-0026 · El acento del producto pasa a morado

Estado: aceptada · 2026-11 · reemplaza la paleta del ADR-0009

## Contexto

La paleta Aurora Mist (ADR-0009) usaba un azul verdoso como acento
(`#15707F`). Otros proveedores del mismo mercado usan azul verdoso, y el
producto necesita distinguirse a simple vista. La decisión es del
usuario del producto, tomada con el diseño de Figma delante.

## Decisión

El acento pasa a **morado**. Los nombres de los tokens no cambian, solo
sus valores; `web/src/estilos/tokens.css` sigue siendo la única fuente
de color.

| Token | Antes | Ahora |
|---|---|---|
| `--acento` | `#15707F` | `#6C54DF` (valor del diseño, exacto) |
| `--acento-claro` | `#5EB6C6` | `#9B88F5` (del diseño) |
| `--acento-sutil` | `#E3EFF1` | `#EEEAFF` (del diseño) |
| `--lienzo` | `#F4F7F6` | `#F7F7F9` (del diseño) |
| `--chrome` / `--chrome-activo` | `#203959` / `#2C4A66` | `#2B2250` / `#3D3270` |
| `--tinta`, `--tinta-2`, `--tinta-3` | azul y verde oscuros | `#1E1B2E`, `#4A4270`, `#625C74` |
| `--borde`, `--borde-control` | grises con tinte verde | `#DEDCE6`, `#8A869A` |
| `--salvia`, `--bruma` | neutros verdosos | `#A39DB8`, `#B8B4C6` |

Valores propios (el diseño no los daba): el chrome, las tintas, los
bordes y los neutros se eligieron con el mismo tinte violeta del acento,
para que ningún gris verdoso quede junto al morado. Los colores de
estado no cambian: éxito sigue verde, peligro rojo, aviso ámbar e
información azul, porque su significado no depende de la marca.

Dos tokens nuevos sacan los últimos colores sueltos de `base.css`:
`--sobre-acento` (blanco, el texto sobre acento, chrome y peligro) y
`--velo` (el fondo tras un diálogo). Ningún componente escribe ya un
valor hexadecimal.

### Contraste

`web/src/estilos/tokens.prueba.js` lee `tokens.css` y exige 4,5:1 en
cada par de texto y fondo (blanco sobre acento, chrome y peligro; acento
sobre tarjeta; las tres tintas sobre tarjeta y lienzo; los cuatro pares
de estado) y 3:1 para `--borde-control` sobre la tarjeta (RNF-13).
Además fija `--acento` en `#6C54DF`. Medido: blanco sobre `--acento`
5,29:1. Con esto la prueba manda sobre el valor de partida: si un token
nuevo no llega, se ajusta el valor y se anota aquí.

Un límite conocido: `--acento` sobre `--acento-sutil` da 4,49:1, apenas
por debajo de 4,5. No es un par de texto de la interfaz (el sutil solo
pinta el halo del foco), pero quien lo use como fondo de texto debe
oscurecer el texto.

## Qué sigue vigente del ADR-0009

Todo lo demás: la tipografía Inter, los radios, la malla de ocho, las
alturas de control, las insignias que siempre llevan texto y el
principio de una sola fuente de color.

## Pendiente

Qué pasa con los logos de Black Steel Parts en Aurora (versiones
`Logo-Black-Steel-Parts-Aurora-Mist*`, nacidas con la paleta anterior):
si se redibujan en morado, se conservan como marca de la empresa o se
retiran. Queda abierto; este ADR no toca esos archivos.
