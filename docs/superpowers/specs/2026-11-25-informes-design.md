# Informes (RF-57, RF-58, RF-59, RF-69) — diseño

- **Fecha:** 2026-11-25. Revisión 2 del 2026-11-26, tras la revisión EGM (ver §9).
- **Rama:** `feat/informes`.
- **Decisiones:** el 2026-11-25 el usuario pidió avanzar sin más preguntas. Las decisiones que no salen de los requisitos van marcadas **(decisión del orquestador)**.

## 1. Para qué

El dueño de la tienda necesita saber:

- cuánto vale lo que tiene en bodega;
- qué productos no se venden y cuánta plata tiene quieta en ellos;
- cuánto vende de verdad, descontando devoluciones y descuentos, por producto y por vendedor;
- qué se vendió sin existencia y falta reponer.

Hoy el «Panel» muestra algunos totales; los informes dan el detalle.

## 2. Lo que fijan los requisitos (v3/v4)

- **RF-57 Inventario valorizado.**
  - Valor al costo vigente y al precio base de contado.
  - Totaliza los productos con existencia > 0.
  - Muestra en una **sección aparte** las unidades y el valor de las referencias con saldo negativo.
  - Filtro por categoría. Un filtro sin resultados devuelve encabezados y totales en cero.
- **RF-58 Sin movimiento.**
  - Referencias activas con existencia > 0 y sin salidas por venta en el período.
  - Totaliza su costo inmovilizado.
  - El período por omisión es de 90 días. Una fecha inicial posterior a la final devuelve 400.
- **RF-59 Ventas netas** por período, producto y vendedor.
  - Muestra unidades vendidas, devoluciones (RF-68), descuentos e ingresos netos.
  - **Concilia exactamente** con los movimientos de venta y devolución del kárdex (RF-49).
- **RF-69 Pendientes de reposición.**
  - Las ventas con existencia negativa pendientes de reposición.
  - La parte de descuadres sin conexión no aplica: la venta sin conexión es de la segunda fase.
- **Permisos (Tabla 10).** Solo el administrador de empresa. Vendedor, super administrador y cliente reciben 403.
- **Aislamiento (RF-09).** Cada informe muestra solo los datos de su empresa.
- **Rendimiento (RNF-03).** Menos de 5 s sobre 10 000 referencias.

## 3. Objetivos, no objetivos y antiobjetivos

**Objetivos**
- Menú «Informes» del admin, con una pestaña por informe.
- En cada informe: filtros, cifras grandes arriba (totales) y una tabla paginada.
- **«Descargar en Excel»** *(decisión del orquestador)*. El dueño suele llevar estas cifras a su contador.
- Diseño nuevo: `impeccable`, modo Operate, colores solo de `tokens.css`.
- Los informes también funcionan en modo consulta (RN-15): son lecturas.

**No objetivos**
- RF-60, compras por proveedor (prioridad baja, v2).
- Gráficas *(decisión del orquestador)*. Si se agregan después, se siguen las reglas de la skill `dataviz`.
- Informes programados o enviados por correo.

**Antiobjetivos**
- Cifras que no cuadren con el kárdex o con la plata cobrada.
- Un informe que cargue todo en memoria o tarde más de 5 s.
- Que el vendedor vea costos o utilidades. El costo es solo del admin desde el 2026-10-31.

## 4. Enfoques considerados

1. **Consultas SQL de agregación por informe (`JdbcClient`), paginadas y directas sobre las tablas** ← **elegido**.
   - Los informes leen `productos`, `movimientos_inventario`, `venta_items`, `devolucion_items` y `ventas`.
   - El precio de contado sale de `v_precios_producto`, y solo para las filas de la página.
   - Las vistas `v_inventario_valorizado` y `v_rotacion_inventario` **no** sirven:
     - dejan fuera los productos inactivos;
     - no exponen `categoria_id`;
     - la de rotación tiene los 90 días fijos y usa `CURDATE()`.
2. **Tablas resumen precalculadas.** Se descarta: sin una medición que lo pida, viola «medir antes de optimizar». Queda como plan B si la prueba de 10 000 referencias no cumple.
3. **Calcular en Java.** Se descarta: carga todo en memoria.

## 5. Diseño

### 5.0 Reglas comunes

- **Rutas.** `GET /api/v1/informes/<informe>` y `GET /api/v1/informes/<informe>/excel`. `InformesControlador` lleva en la clase `@PreAuthorize("hasRole('ADMINISTRADOR')")`, como `PanelControlador`.
- **Informes:**
  - `inventario-valorizado`
  - `sin-movimiento`
  - `ventas-netas`
  - `pendientes-reposicion`
- **Período.**
  - `desde` y `hasta` son fechas `yyyy-MM-dd`, ambas inclusive. Se consulta como el intervalo `[desde 00:00, hasta+1 00:00)` en hora de Bogotá.
  - Errores (400 con `ProblemDetail`):
    - fecha mal escrita: «La fecha no es válida.»;
    - `desde` posterior a `hasta`: «La fecha inicial no puede ser posterior a la final.»;
    - rango de más de **366 días** *(decisión del orquestador)*: «Elige un período de máximo un año.».
  - Una fecha futura se acepta.
- **Paginación.** `pagina` (desde 0) y `tamano` (por omisión 50, máximo 200). Desempate siempre por `producto_id`. Las consultas son constantes por página.
- **Lectura consistente.** La pantalla y el Excel leen los totales y la tabla en una sola transacción `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`, en el hilo de la petición.
- **Hoy.** «Hoy» = `Hoy.de(reloj)`; nunca `CURDATE()`.

### 5.1 Inventario valorizado (RF-57)

- **Filtros.** `categoriaId` (opcional) y `q` (código o nombre).
- **Fuente.**
  - `productos` de la empresa, **activos e inactivos**. Los inactivos con existencia cuentan *(decisión del orquestador: siguen siendo plata en bodega)* y llevan la marca «Inactivo».
  - El valor al costo es `existencia × costo`, con el costo vigente del producto.
  - El precio sale de `v_precios_producto` y queda vacío si no hay regla de precio o si el producto está inactivo.
- **Totales.**
  - **En bodega** (existencia > 0): referencias, unidades, valor al costo, y valor a precio de contado solo de las que tienen precio. Si hay referencias sin precio, se indica «N sin precio».
  - **Vendido sin existencia**, la sección aparte: referencias con existencia < 0, unidades bajo cero (Σ −existencia) y su valor al costo. Es la misma cifra que muestra el Panel.
- **Tabla.**
  - Columnas: código, producto, categoría, existencia, costo, precio, valor al costo y valor a precio.
  - Orden: valor al costo de mayor a menor.
  - Entran las referencias con existencia distinta de 0.
- **Excel.** El archivo se llama `informe-inventario-valorizado-<hoy>.xlsx`.

### 5.2 Sin movimiento (RF-58)

- **Filtros.**
  - `desde` y `hasta`: por omisión `hoy − 90 días` y `hoy`.
  - `categoriaId`, opcional.
- **Qué entra.** Productos **activos** con existencia > 0 sin ningún movimiento `tipo = 'EGRESO'` en el período (`NOT EXISTS`).
  - Hoy, `EGRESO` solo lo escribe la venta.
  - Una prueba falla si aparece un `EGRESO` cuyo `documento` no sea un `ventas.consecutivo`. Así se protege esta regla.
- **Totales.** Referencias y capital inmovilizado (Σ existencia × costo).
- **Tabla.**
  - Columnas: código, producto, existencia, costo y capital.
  - **Última venta:** el `MAX(fecha)` de los `EGRESO` del producto, sin límite de fecha, o «Nunca».
  - Orden: capital de mayor a menor.
- **Excel.** El archivo se llama `informe-sin-movimiento-<desde>-<hasta>.xlsx`.

### 5.3 Ventas netas (RF-59)

- **Filtros.** `desde` y `hasta` (por omisión, del primer día del mes en curso a hoy) y `agrupar = producto | vendedor`.
- **Base de cálculo: todo con IVA incluido** *(decisión del orquestador)*. Así la cifra cuadra peso a peso con la plata cobrada y devuelta. Las devoluciones se guardan con IVA y redondeadas a pesos, así que no se pueden separar exactas sin IVA.
- **Columnas y totales:**

| Columna | Cálculo |
|---|---|
| Unidades vendidas | Σ `venta_items.cantidad` (todo lo vendido cuenta, incluidas las unidades en encargo) |
| Unidades devueltas | Σ `devolucion_items.cantidad` |
| Subtotal | Σ `venta_items.subtotal` |
| Descuentos | Σ `venta_items.descuento`, que incluye el descuento de toda la compra repartido por línea |
| IVA | Σ `venta_items.valor_iva` |
| Vendido | Subtotal − Descuentos + IVA |
| Devoluciones | Σ `devolucion_items.valor` (lo devuelto al cliente, con IVA y neto de descuento) |
| **Ingreso neto** | Vendido − Devoluciones |

- **Qué cuenta en el período.**
  - Las ventas, por `ventas.fecha`, en **cualquier estado**: no se filtra por `ventas.estado`.
    - Una venta devuelta por completo queda `ANULADA`, pero su venta y su devolución siguen en el kárdex.
    - Una venta contra entrega todavía por cobrar también cuenta, porque ya salió del inventario.
  - Las devoluciones, por `devoluciones.creado_en`, en la fecha en que se hicieron. Por eso una fila puede tener un neto negativo.
- **Vendedor.**
  - Para las ventas es `ventas.usuario_id`, con el nombre de `usuarios`, aunque esté inactivo.
  - Para las devoluciones, el vendedor de la venta original *(decisión del orquestador: el neto de cada vendedor)*.
- **Prueba de que se suman bien los descuentos.** Σ `venta_items.descuento` de una venta = `ventas.descuento`. Si no se cumpliera, el plan lo corrige antes de seguir.
- **Conciliación con el kárdex (prueba obligatoria).** Para el total del período y por producto (no por vendedor: en el kárdex, la devolución lleva a quien la registró):
  - unidades vendidas = Σ cantidades de los `EGRESO` cuyo `documento` es el `consecutivo` de una venta del período;
  - unidades devueltas = Σ de los `DEVOLUCION` cuyo `documento` es el `consecutivo` de una devolución del período.
  - El cruce va **por documento, no por la fecha del kárdex**: la fecha del kárdex la pone la base y la de la venta la aplicación.
  - El escenario de la prueba tiene: dos vendedores, una devolución parcial, una venta devuelta por completo (`ANULADA`), un descuento sobre toda la compra, un encargo que deja la existencia negativa y una venta contra entrega.

### 5.4 Pendientes de reposición (RF-69)

- **Reutiliza** la consulta y las reglas de `RepositorioEncargos.listar`, la que hoy alimenta «Encargos» en la caja, sin duplicarlas. Excluye:
  - ventas `ANULADA`;
  - líneas con el encargo entregado (`encargo_entregado_en`);
  - líneas devueltas por completo.
- **Estados.** Calculados con la existencia **actual**:
  - **«Pendiente»**: existencia < 0. Es el filtro por omisión.
  - **«Ya llegó»**: existencia ≥ 0 y sin entregar.
  - No hay «conciliado en un período»: no se guarda cuándo volvió la existencia a 0 *(decisión del orquestador)*.
- **Tabla.**
  - Columnas: fecha de la venta, número de venta, cliente, producto, unidades del encargo (`venta_items.cantidad_encargo`), entrega estimada, vendedor (`ventas.usuario_id`) y quién autorizó (`ventas.autorizada_por`).
  - Orden: fecha de la venta, la más antigua primero.
- **Totales.**
  - Encargos pendientes, como número de líneas.
  - Unidades por reponer, como Σ `cantidad_encargo` de las líneas pendientes.
  - Aparte, «Unidades bajo cero en bodega» (Σ −existencia), que es la cifra del Panel. Una nota explica que las dos cifras pueden diferir cuando hay recepciones parciales.
- **Excel.** El archivo se llama `informe-pendientes-reposicion-<hoy>.xlsx`.

### 5.5 Excel

- **Escritura.** Se escribe en la respuesta en el hilo de la petición, como `ImportacionControlador.plantilla`. Su clase anidada privada `SalidaDeDescarga` se **extrae** a una clase compartida (`api/Descarga`) y la usan los dos controladores.
- **Recorrido.** Las filas se recorren con cursor: `withFetchSize(Integer.MIN_VALUE)` y `RowCallbackHandler`, como `RepositorioImportacion`. Se usa `SXSSFWorkbook(100)`, con `dispose()` en el `finally`.
- **Contenido.** Una hoja con los filtros usados, los totales y la tabla completa sin paginar.
- **Formato.**
  - Montos con el formato `"$"#,##0`; unidades con `#,##0`.
  - Toda celda de texto lleva `quotePrefixed`, como `GeneradorPlantillaPrecios.Estilos`. Así un nombre que empieza por `=`, `+`, `-` o `@` no se vuelve fórmula.
  - Nombre de archivo ASCII, con el filtro de `GeneradorPlantillaPrecios.nombreDeArchivo`.
- **Web.** `api.js` agrega `api.descargarInforme(informe, filtros)`, que usa la función privada `descargar` con la ruta `/api/v1/informes/<informe>/excel` y la consulta. El respaldo es `informe.xlsx` y el mensaje de error, «No se pudo descargar el informe.».

### 5.6 Rendimiento

- **Prueba con base.** Se siembran 10 000 referencias y unas 20 000 líneas de venta. Cada informe, con su primera página y sus totales, responde en **menos de 5 s**, como la mediana de 5 ejecuciones.
- **Consultas constantes por página,** medidas con el mismo conteo de las pruebas de la caja.
- **Índices.** Se miran con `EXPLAIN` los índices actuales de `movimientos_inventario` después de V10 y V11. Si el `NOT EXISTS` o el `MAX(fecha)` recorren de más, se agrega `(empresa_id, tipo, producto_id, fecha)` en la migración **V53** (reservada para esta rama; V50 a V52 son de otras ramas). Si no hace falta, la V53 no se usa.

### 5.7 Pantalla

```
Informes
[Inventario valorizado] [Sin movimiento] [Ventas netas] [Pendientes de reposición]

Desde [01/11/2026]  Hasta [26/11/2026]  Agrupar por [Producto ▾]   [Descargar en Excel]

 Vendido (con IVA)   Devoluciones   Ingreso neto    Descuentos    IVA incluido
 $21.920.000         $95.000        $21.825.000     $310.000      $3.499.000

 Producto          Vendidas  Devueltas  Subtotal     Desc.     IVA       Devol.    Neto
 Disco corte 7"      120        2      $1.440.000   $12.000   $271.320  $28.560   $1.670.760
 …                                                          [‹ Anterior] [Siguiente ›]
```

- La pestaña y los filtros quedan en la URL.
- Los estados vacíos son claros, por ejemplo: «No hubo ventas entre el 1 y el 26 de noviembre.».
- Un error 400 se muestra junto a los filtros.

## 6. Pruebas que fijan el comportamiento

- **Permisos.**
  - El admin recibe 200. Vendedor, super administrador y cliente reciben 403.
  - Los datos de otra empresa no aparecen.
  - En modo consulta, el admin recibe 200.
- **RF-57.**
  - Los totales en bodega excluyen la existencia negativa.
  - La sección negativa tiene sus propias unidades y su valor.
  - Un producto inactivo con existencia cuenta y lleva su marca.
  - Un producto sin regla de precio suma al costo y aparece en «N sin precio».
  - Una categoría vacía da ceros y encabezados.
- **RF-58.**
  - Un producto vendido hace 100 días aparece con «Última venta».
  - Uno vendido hace 10 días no aparece. Uno con existencia 0 no aparece.
  - Uno que nunca se vendió aparece con «Nunca».
  - Las fechas invertidas dan 400. El período por omisión es de 90 días.
- **RF-59.**
  - La conciliación del §5.3.
  - La suma de los descuentos por línea es igual a la cabecera.
  - La devolución parcial resta al vendedor de la venta.
  - Una devolución de un período posterior da un neto negativo en su fila.
  - Más de 366 días da 400.
- **RF-69.**
  - Un encargo deja su línea como pendiente.
  - Una recepción que lleva la existencia a 0 la pasa a «Ya llegó».
  - Una entregada o devuelta no aparece.
- **Paginación.**
  - El tamaño máximo es 200.
  - El desempate es estable.
- **Excel.**
  - Abre con POI y trae los mismos totales que la pantalla.
  - Con 250 filas, el SXSSF vacía a disco.
  - Un nombre que empieza por `=` queda como texto.
- **Rendimiento.**
  - Menos de 5 s con 10 000 referencias.
  - Consultas constantes por página.
- **Web.**
  - Las cuatro pestañas tienen filtros en la URL.
  - La descarga funciona.
  - Los estados vacíos se ven.
  - El error 400 se muestra.

## 7. Lo que queda al constructor

- Los nombres de clases y de DTO, siguiendo `RepositorioPanel` y `PanelControlador`.
- El formato de las cifras con los helpers de formato existentes en la web.

## 8. Riesgos

- **Rendimiento.** Si `v_precios_producto` resulta lenta incluso para una página, se calcula el precio solo de los ids de la página con la misma fórmula.
- **Las dos cifras de faltantes** pueden diferir (§5.4). La pantalla lo explica en una nota.

## 9. Hallazgos de la revisión (2026-11-26) y cómo se resolvieron

| Hallazgo | Resolución |
|---|---|
| B1 `ANULADA` frente al kárdex | No se filtra por estado; se cuentan las líneas (§5.3) |
| B2 bases mezcladas en el neto | Todo con IVA (§5.3) |
| B3 las vistas no sirven | Consultas directas (§4, §5.1, §5.2) |
| B4 RF-69 ignoraba los encargos | Reutiliza `RepositorioEncargos` y quita «conciliado en el período» |
| B5 `api.descargar` privado | `api.descargarInforme` |
| S1 prefijo de rutas | `/api/v1/informes` |
| S2 Excel | `Descarga` compartida, `REPEATABLE_READ`, `quotePrefixed`, formato y nombre |
| S3 conciliación por vendedor | Solo total y por producto |
| S4 bordes del período | Intervalo semiabierto y cruce por documento |
| S5 reconocer la venta en el kárdex | `EGRESO` + documento, con una prueba que protege la regla |
| S6 rendimiento | Consultas directas, precio solo de la página e índice en V53 si `EXPLAIN` lo pide |
| S7 reglas de ventas netas | Vendedor, netos negativos y encargo |
| M1–M5 | Mediana de 5; mensajes 400; paginación y Excel grande; modo consulta; escenario completo |
