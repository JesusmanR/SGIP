# ADR-0032 · El marco de la app pasa al diseño nuevo: menú blanco, barra con migas y DM Sans + Manrope

Estado: aceptada · 2026-11-19 · reemplaza la tipografía de la ADR-0009 (Inter) y el menú oscuro (`--chrome`)

## Contexto

El diseño nuevo de Figma cambia el marco de la app (menú lateral, barra superior y
cabecera de página) y la tipografía. Solo se cambia el marco; el contenido de cada
pantalla no se toca.

## Decisión

- **Menú lateral blanco** de 248 px con borde derecho, marca arriba, grupos con título
  en mayúsculas, enlaces de 43 px con radio 10 y perfil abajo (iniciales, nombre, rol y
  «Salir»). En el celular es un cajón que sale desde la izquierda con velo; Escape, el
  botón «Cerrar navegación» o tocar el velo lo cierran y el foco vuelve al botón «Menú».
- **Barra superior** blanca de 70 px, fija, con migas «Espacio de trabajo / sección».
  El contador de la prueba (RF-73) pasa a su derecha.
- **`EncabezadoPagina`**: etiqueta morada, h1 de 28 px y subtítulo. Sin `etiqueta`
  toma el grupo de la sección actual, así las pantallas existentes no cambian de código.
- **Tipografía:** DM Sans para el texto y Manrope para los títulos, autoalojadas con
  `@fontsource` (solo subconjunto latino; DM Sans 400-700, Manrope 700 y 800). No se usa
  Google Fonts: la tienda puede estar sin internet y una petición externa filtra visitas.
- **Sin funciones inventadas:** no se incluyen la búsqueda ⌘K, la campana, el botón
  «Nuevo», la tarjeta de ayuda ni el selector de rol del diseño. El menú sigue saliendo
  de `secciones.js`.
- **Color:** todo en `tokens.css`. Tokens nuevos `--menu-*` y `--borde-suave`. Los grises
  del diseño no cumplían el RNF-13 (#AAA8B3 sobre blanco: 2,3:1) y se oscurecieron a 4,5:1
  o más. `--chrome` se queda solo para el panel de la pantalla de acceso.

## Consecuencias

- Dos dependencias nuevas de fuentes, versionadas y sin servicio externo.
- Un h1 en Manrope 700 (el diseño pide 750, no existe en la fuente estática).
