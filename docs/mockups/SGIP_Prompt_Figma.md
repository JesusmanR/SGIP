# Prompts para construir la interfaz del SGIP en Figma

Proyecto: Sistema de Gestión de Inventario de Productos — Black Steel Parts
Paleta: Aurora Mist
Autor: Jesús Manuel Durán Muñoz · Ficha 3234206 · SENA

---

## Cómo usar este archivo

No pegues todo de una vez. Un prompt muy largo hace que Figma pierda detalle a
la mitad y devuelva pantallas genéricas. Están separados en seis bloques y el
orden importa: el bloque 1 crea las variables de color, y todo lo demás las
referencia por nombre. Si lo saltas, Figma inventa colores.

Cada bloque se pega en una conversación nueva de **Figma Make**. Si usas
**First Draft** (el generador del panel de Actions), empieza por el bloque 3 en
adelante: First Draft no crea variables, solo marcos.

Después de cada bloque, revisa antes de seguir. Corregir una pantalla es barato;
corregir veinte, no.

---

## Bloque 1 — Variables de color

> Crea una colección de variables de Figma llamada `Aurora Mist` con un único
> modo llamado `Claro`. Añade exactamente estas variables de tipo color, con
> estos nombres y estos valores. No añadas ninguna más y no cambies ningún
> valor.
>
> **Superficie**
> `lienzo` #F4F7F6 · `tarjeta` #FFFFFF · `chrome` #203959 · `chrome-activo` #2C4A66
>
> **Acento**
> `acento` #15707F · `acento-claro` #5EB6C6 · `acento-sutil` #E3EFF1
>
> **Texto**
> `tinta` #16283E · `tinta-2` #416772 · `tinta-3` #5C6866
>
> **Línea**
> `borde` #D5DEDB · `borde-control` #839492
>
> **Neutros de marca**
> `salvia` #96ADA5 · `bruma` #ABB7B3
>
> **Estado**
> `exito` #15624A · `exito-fondo` #E6F0EB
> `aviso` #8A5214 · `aviso-fondo` #F7EEE0
> `peligro` #9C2F28 · `peligro-fondo` #F6E8E7
> `info` #2C5A78 · `info-fondo` #E7EEF3
>
> Crea también variables de número en una colección `Medidas`:
> `radio-tarjeta` 10, `radio-control` 6, `radio-insignia` 999,
> `espacio-1` 4, `espacio-2` 8, `espacio-3` 12, `espacio-4` 16, `espacio-5` 24.
>
> Al terminar, muéstrame la lista de variables creadas para verificarla.

---

## Bloque 2 — Estilos de texto

> Crea estilos de texto con la familia **Inter**. Si no está disponible, usa
> Roboto y dímelo. Nombra cada estilo tal cual:
>
> | Estilo | Tamaño | Grosor | Interlineado | Color |
> |---|---|---|---|---|
> | `Titulo/Pantalla` | 20 | 700 | 28 | tinta |
> | `Titulo/Tarjeta` | 14 | 600 | 20 | tinta |
> | `Cifra/Indicador` | 26 | 700 | 32 | tinta |
> | `Cuerpo/Normal` | 13 | 400 | 20 | tinta |
> | `Cuerpo/Fuerte` | 13 | 600 | 20 | tinta |
> | `Etiqueta/Campo` | 11 | 500 | 16 | tinta-2 |
> | `Etiqueta/Tabla` | 11 | 600 | 16 | tinta-2 |
> | `Menor/Auxiliar` | 11 | 400 | 16 | tinta-3 |
> | `Insignia` | 10 | 600 | 14 | hereda del estado |
>
> Reglas: las cifras de dinero y de cantidad usan **numeración tabular**
> (`font-variant-numeric: tabular-nums`) para que las columnas alineen. Las
> etiquetas van en formato oración, nunca en mayúsculas sostenidas, salvo los
> títulos de grupo de la barra lateral, que van en mayúsculas a 10 px con
> espaciado de letra 0,7.

---

## Bloque 3 — Componentes base

> Crea una página llamada `Componentes` y construye estos componentes con auto
> layout y propiedades de variante. Usa las variables de `Aurora Mist`; no
> escribas valores hexadecimales sueltos.
>
> **Botón** — alto 32, radio `radio-control`, relleno horizontal 15.
> Variantes en la propiedad `tipo`: `primario` (fondo `acento`, texto blanco),
> `secundario` (fondo `tarjeta`, borde 1 px `borde-control`, texto `tinta`),
> `fantasma` (sin fondo ni borde, texto `tinta-2`).
> Propiedad booleana `deshabilitado`: fondo `bruma`, texto blanco, sin sombra.
>
> **Campo de texto** — alto 32, radio `radio-control`, fondo `tarjeta`,
> borde 1 px `borde-control`. Variantes en `estado`: `normal`, `foco`
> (borde 2 px `acento` y halo `acento-sutil`), `error` (borde 2 px `peligro`),
> `solo-lectura` (fondo `lienzo`, borde `borde`, texto `tinta-3`).
> Incluye una ranura superior para la etiqueta, con estilo `Etiqueta/Campo`, y
> una ranura inferior opcional para el mensaje de error con color `peligro`.
>
> **Selector** — igual que el campo, con un triángulo hacia abajo a la derecha
> en color `tinta-2`.
>
> **Insignia de estado** — alto 20, radio `radio-insignia`, relleno 8
> horizontal, texto con estilo `Insignia`. Variantes en `estado`:
> `exito` (fondo `exito-fondo`, texto `exito`),
> `aviso` (fondo `aviso-fondo`, texto `aviso`),
> `peligro` (fondo `peligro-fondo`, texto `peligro`),
> `info` (fondo `info-fondo`, texto `info`),
> `neutro` (fondo `lienzo`, texto `tinta-2`).
> **La insignia siempre contiene texto.** No crees una variante que sea solo un
> punto o un círculo de color.
>
> **Tarjeta** — fondo `tarjeta`, borde 1 px `borde`, radio `radio-tarjeta`,
> relleno 14. Sin sombra. Ranura para título con estilo `Titulo/Tarjeta`.
>
> **Fila de tabla** — alto 36, separador inferior 1 px `borde`. Variantes en
> `estado`: `normal`, `alterna` (fondo `lienzo`), `seleccionada`
> (fondo `acento-sutil`).
>
> **Encabezado de tabla** — alto 28, texto `Etiqueta/Tabla`, separador inferior
> 1 px `borde`, sin fondo.
>
> **Barra lateral** — ancho 240, fondo `chrome`. Elementos de 36 de alto, texto
> 12 px color `salvia`. El elemento activo lleva fondo `chrome-activo`, texto
> blanco en grosor 600 y una barra vertical de 3 px en `acento-claro` pegada al
> borde izquierdo. Los títulos de grupo van en `bruma`, 10 px, mayúsculas.
>
> **Indicador numérico** — tarjeta con etiqueta arriba en `Etiqueta/Campo`,
> cifra en `Cifra/Indicador` y una línea de variación abajo en `Menor/Auxiliar`.
>
> **Medidor de umbral** — una barra horizontal de 8 px de alto y radio 4, con
> una marca vertical que señala el tope. Variantes en `posicion`:
> `debajo` (relleno `exito`), `cerca` (relleno `aviso`),
> `encima` (relleno `peligro`). Lleva dos etiquetas: el valor del tope y el
> precio actual.

---

## Bloque 4 — Estructura del archivo

> Crea estas páginas en el archivo, en este orden:
>
> 1. `Cubierta` — nombre del proyecto, autor, ficha y la paleta en cinco barras.
> 2. `Fundamentos` — muestras de color con su nombre de variable, escala
>    tipográfica y rejilla de espaciado.
> 3. `Componentes` — la que ya creaste.
> 4. `Escritorio` — marcos de 1440 × 900.
> 5. `Móvil` — marcos de 390 × 844.
> 6. `Flujos` — conexiones entre pantallas para el prototipo.
>
> Nombra cada marco con su identificador y su nombre, así: `P-02 · Panel de
> indicadores`. Todos los marcos llevan auto layout vertical y fondo `lienzo`,
> salvo los de acceso y escáner, que van a sangre.

---

## Bloque 5 — Pantallas de escritorio

> En la página `Escritorio`, crea marcos de 1440 × 900 para estas pantallas.
> Todas comparten la misma plantilla: barra lateral de 240 a la izquierda, barra
> superior de 56 con título y buscador, y contenido sobre fondo `lienzo` con
> relleno 24. Usa los componentes ya creados; no dibujes controles nuevos.
>
> **V-00 · Menú principal** — rejilla de seis accesos directos en tres columnas
> y una tarjeta de avisos con tabla de tres filas.
>
> **P-02 · Panel de indicadores** — cuatro indicadores numéricos en fila
> (referencias activas, en existencia crítica, agotadas, capital en inventario);
> debajo, dos tarjetas lado a lado: tabla corta de existencias críticas y
> resultado de la última carga de precios; abajo, un gráfico de barras de
> ingresos y egresos de los últimos treinta días.
>
> **P-03 · Catálogo de productos** — fila de filtros (buscador, categoría,
> proveedor, estado), tabla de ocho columnas y paginación al pie.
>
> **P-04 · Ficha de producto** — dos columnas: a la izquierda los datos y la
> descripción; a la derecha una tarjeta de precios con el medidor de umbral,
> el precio de contado y el precio a crédito.
>
> **V-01 · Gestión de productos** — filtros, tabla y formulario de detalle
> debajo, con los botones Nuevo, Guardar, Eliminar, Cancelar y Cerrar alineados
> a la derecha.
>
> **V-02 · Gestión de proveedores** — igual, con un selector de origen de lista
> (Ninguno, Archivo, Servicio web). Cuando el valor es «Servicio web», los
> campos de URL y credencial aparecen habilitados; en los otros dos valores
> aparecen en estado `solo-lectura`. Crea las dos versiones del marco.
>
> **V-03 · Gestión de categorías** — la más simple: filtro, tabla de cuatro
> columnas y formulario de dos campos.
>
> **V-04 · Gestión de clientes** — formulario en dos filas y una sección
> separada para la autorización de tratamiento de datos, con fecha, medio y un
> botón de revocar.
>
> **V-05 · Gestión de usuarios** — tabla con columna de rol e intentos
> fallidos, y una sección de credenciales con los botones Restablecer
> contraseña, Desbloquear cuenta y Cerrar sesiones activas. **No incluyas
> ningún campo que muestre la contraseña ni su hash.**
>
> **P-05 · Registro y edición de producto** — formulario completo con el
> medidor de umbral actualizándose junto al campo de costo.
>
> **P-07 · Importación de lista de precios** — es la pantalla más importante
> del sistema. Tres pasos en un indicador de progreso: cargar archivo, mapear
> columnas, confirmar. En el paso de mapeo, una tabla de dos columnas que
> enfrenta la columna del archivo del proveedor con el campo del SGIP. En el
> paso de confirmación, un resumen con tres cifras —aplicados, rechazados y en
> el umbral— y la lista de referencias que cruzan el tope de exclusión de IVA.
>
> **P-08 · Configuración de reglas de margen** — tabla de reglas con categoría,
> forma de pago, factor y vigencia; formulario para añadir una regla nueva; y
> un aviso que explique que la regla más específica tiene prioridad sobre la
> general.
>
> **P-11 · Ingresos y egresos** — filtros de fecha y tipo de movimiento, tabla
> de movimientos y un formulario lateral para registrar uno nuevo.
>
> **P-12 · Reporte de rotación** — tabla ordenada por días sin movimiento, con
> el capital detenido por referencia y un total al pie.
>
> **P-13 · Emisión y consulta de facturas** — tabla de documentos con insignias
> de estado (emitida, aceptada por la DIAN, rechazada) y panel de detalle.

---

## Bloque 6 — Pantallas móviles

> En la página `Móvil`, crea marcos de 390 × 844. Todo control táctil mide al
> menos 44 de alto. No hay barra lateral: la navegación va en una barra inferior
> de cinco destinos.
>
> **P-01 · Acceso** — logotipo centrado, campos de correo y contraseña, botón
> primario de ancho completo y enlace de recuperación. Crea tres versiones del
> marco: normal, con error de credenciales y con la cuenta bloqueada.
>
> **P-09 · Generación de cotización** — cabecera con el cliente, lista de
> renglones con cantidad y precio, y un pie fijo con subtotal, IVA, envío y
> total. Incluye un interruptor para regalar el envío y una leyenda que muestre
> la fecha de vencimiento calculada en días hábiles.
>
> **P-10 · Lectura de código de barras** — vista de cámara a sangre con un
> marco de enfoque centrado, instrucción breve arriba y botón de captura
> manual abajo. Crea una segunda versión con el resultado encontrado en una
> hoja inferior.
>
> **P-03 móvil · Catálogo** — lista de tarjetas en lugar de tabla, cada una con
> código, nombre, precio e insignia de estado.
>
> **P-04 móvil · Ficha de producto** — igual que la de escritorio pero en una
> sola columna, con el medidor de umbral debajo del precio.

---

## Reglas que debes repetir en cada bloque

Pega este recuadro al final de cualquier prompt de pantallas. Son las reglas
que Figma más tiende a olvidar.

> **Restricciones del sistema**
>
> 1. Ninguna pantalla tiene un campo editable de precio de venta. El precio lo
>    deriva el sistema del costo y de la regla de margen. Si una pantalla
>    necesita mostrarlo, va en estado `solo-lectura`.
> 2. El color `acento` aparece una sola vez por pantalla, en la acción
>    principal. Si una pantalla tiene dos botones primarios, está mal.
> 3. Las insignias de estado siempre llevan texto. El color acompaña, no
>    informa por sí solo: en impresión en blanco y negro los cuatro estados se
>    ven idénticos.
> 4. Los importes van en pesos colombianos con punto como separador de miles y
>    sin decimales.
> 5. Cada pantalla con datos necesita tres versiones: con datos reales, vacía
>    con mensaje orientador, y cargando con esqueletos de contenido en lugar de
>    un indicador giratorio.
> 6. Usa auto layout en todo. Nada posicionado en absoluto salvo la vista de
>    cámara.
> 7. Usa las variables por nombre. Ningún valor hexadecimal escrito a mano.

---

## Si el resultado no convence

Figma tiende a devolver pantallas correctas pero vacías de sustancia. Dos
correcciones que funcionan:

**Dale datos reales.** En lugar de «una tabla de productos», pega las filas:
`PC-001 · Portátil económico · Computadores · 2.100.000 · 10 · Activo`. Con
datos verosímiles el resultado deja de parecer una plantilla.

**Pide una pantalla a la vez.** Si un bloque devuelve ocho marcos mediocres,
vuelve a pedir uno solo, describiendo cada sección. Luego usa ese marco como
referencia: «aplica la misma estructura y densidad de P-02 a P-11».
