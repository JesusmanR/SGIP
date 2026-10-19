# Informe de inicio del proyecto — versiones

Las cinco versiones entregadas, en la carpeta y bajo control de
versiones, para que el seguimiento no dependa de rescatarlas del
historial de git.

| Versión | Estado que declara | Páginas | Archivo |
|---|---|---|---|
| 1.0 | En desarrollo — Fase 1 completada | 25 | `SGIP_Informe_Inicio_Proyecto_v1.docx` |
| 2.0 | Fase de inicio — planificación y análisis | 73 | `SGIP_Informe_Inicio_Proyecto_v2.docx` |
| 3.0 | Fase de inicio — planificación y análisis | 98 | `SGIP_Informe_Inicio_Proyecto_v3.docx` |
| 4.0 | Fase de inicio — replanteamiento como plataforma multiempresa | 103 | `SGIP_Informe_Inicio_Proyecto_v4.docx` |
| 5.0 | En ejecución — tres iteraciones cerradas al 16 de octubre de 2026 | 120 | `SGIP_Informe_Inicio_Proyecto_v5.docx` |

## Qué cambió en cada una

El detalle no se repite aquí: la **Tabla 2, «Control de cambios»**, del
informe v5 lo recoge cambio a cambio, con la situación anterior y la
resolución de cada uno. Son treinta y nueve entradas repartidas entre
las versiones 3.0, 4.0 y 5.0.

En titulares:

- **2.0 → 3.0** · Saneamiento del catálogo de requisitos: numeración sin
  saltos, separación entre requisitos funcionales y reglas de negocio,
  criterios de aceptación medibles, jerarquía de prioridades que
  distingue el producto mínimo viable. Entra la venta sin existencia
  (RF-42) y el cuarto rol. Se documenta la paleta Aurora Mist.
- **3.0 → 4.0** · El replanteamiento de fondo: el producto deja de ser
  un sistema para Black Steel Parts y pasa a ser una plataforma
  multiempresa que BSP opera como servicio. De ahí salen el aislamiento
  por empresa, la suscripción con periodo de prueba, la operación sin
  conexión, el punto de pago y la separación entre orden de compra y
  recepción.
- **4.0 → 5.0** · El informe deja de describir un proyecto planificado y
  da cuenta del ejecutado: se añade el capítulo de estado de ejecución,
  se corrigen el marco tecnológico y los conteos de requisitos y
  entidades contra lo que el repositorio tiene de verdad, y se reevalúan
  los riesgos con la ejecución a la vista.

La 1.0 es anterior a esa tabla y corresponde al primer informe entregado.

## Sobre el tamaño de los archivos

Los cinco están **aligerados**: las imágenes se guardaron a 1400 px de
ancho como máximo y con paleta de 256 colores. Las cinco versiones pasan
de 30 MB a 12 MB.

No se tocó ni una palabra. Se comprobó, archivo por archivo, que el
número de párrafos, el número de tablas y el texto completo —cabeceras
de tabla y celdas incluidas— son idénticos a los originales, y que los
cinco siguen abriendo y maquetando con el mismo número de páginas.

El porqué: los mockups venían a 2400 px de ancho y una página de este
documento usa unos 16 cm, que a 150 puntos por pulgada son unos 945 px.
Se conserva el doble de lo necesario para impresión. Son interfaces de
color plano, de modo que la paleta reducida no produce bandeado; se
verificó comparando un recorte antes y después.

Si en algún momento hace falta una versión con las imágenes a resolución
original, está en el historial de git: el v5 sin aligerar es el que
quedó en el commit `77faf06` y anteriores.

## Anexos y material de apoyo

| Archivo | Contenido |
|---|---|
| `SGIP_Anexo_E_Casos_de_Uso.docx` | Casos de uso detallados |
| `SGIP_Mockups.pdf` | Las trece pantallas |
| `SGIP_Sistema_Color_Aurora.pdf` | El sistema de color Aurora Mist |
| `figuras/` | Las figuras F01 a F04 y sus fuentes `.dot` |
