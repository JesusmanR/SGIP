# Informes (RF-57, RF-58, RF-59, RF-69) — diseño

Fecha: 2026-11-25. Rama: `feat/informes`.
El 2026-11-25 el usuario pidió avanzar sin más preguntas. Por eso las decisiones que no salen de los requisitos van marcadas **(decisión del orquestador)**.

## 1. Para qué

El dueño de la tienda necesita saber tres cosas:

- cuánto vale lo que tiene en bodega;
- qué productos no se venden y cuánta plata tiene quieta en ellos;
- cuánto vende de verdad (descontando devoluciones y descuentos), por producto y por vendedor.

También necesita la lista de lo que se vendió sin existencia y falta reponer.
Hoy el «Panel» muestra solo algunos totales. Los informes dan el detalle.

## 2. Lo que fijan los requisitos (v3/v4)

- **RF-57 Inventario valorizado.**
  - Valor al costo vigente y al precio base de contado.
  - Totaliza solo los productos con existencia > 0.
  - Muestra en una **sección aparte** las unidades y el valor de las referencias con saldo negativo.
  - Filtro por categoría.
  - Un filtro sin resultados devuelve encabezados y totales en cero.
- **RF-58 Sin movimiento.**
  - Referencias activas con existencia > 0 y sin salidas por venta en el período.
  - Totaliza su costo inmovilizado.
  - Período por omisión: 90 días calendario. Una fecha inicial posterior a la final devuelve 400.
- **RF-59 Ventas netas** por período, producto y vendedor.
  - Discrimina unidades vendidas, devoluciones (RF-68), descuentos e ingresos netos.
  - **Concilia exactamente** con los movimientos de venta y devolución del kárdex (RF-49) en el mismo período.
- **RF-69 Pendientes de reposición.**
  - Referencias con existencia < 0, con fecha, venta origen, unidades faltantes y responsable.
  - Pasan a «Conciliado» cuando la existencia vuelve a 0 o más.
  - La parte de descuadres sin conexión no aplica: la venta sin conexión es de la segunda fase.
- **Permisos (Tabla 10).** Solo el administrador de empresa. Vendedor, super administrador y cliente reciben 403.
- **Aislamiento (RF-09).** Cada informe ve solo su empresa.
- **Rendimiento (RNF-03).** p95 < 5 s sobre 10 000 referencias.

## 3. Objetivos, no objetivos y antiobjetivos

**Objetivos**
- Menú «Informes» del admin, con una pestaña por informe.
- Cada informe tiene filtros, cifras grandes arriba (totales) y una tabla paginada.
- Botón **«Descargar en Excel»** *(decisión del orquestador)*. Se reutiliza la técnica de la plantilla de precios (SXSSF con memoria acotada), y el dueño suele llevar estas cifras a su contador.
- Pantallas con el diseño nuevo (`impeccable`, modo Operate, colores solo de `tokens.css`).

**No objetivos**
- RF-60, compras por proveedor (prioridad baja, v2).
- Gráficas *(decisión del orquestador)*. Las cifras y tablas responden las preguntas. Si se agregan después, se siguen las reglas de la skill `dataviz`.
- Informes programados o enviados por correo.

**Antiobjetivos**
- Cifras que no cuadren con el kárdex.
- Un informe que cargue toda la tabla en memoria o tarde más de 5 s.
- Que el vendedor vea costos o utilidades. El costo es solo del admin, decisión del 2026-10-31.

## 4. Enfoques considerados

1. **Consultas SQL de agregación por informe (`JdbcClient`), paginadas, sobre las vistas existentes** ← **elegido**.
   - `v_inventario_valorizado` y `v_rotacion_inventario` ya existen.
   - Es el patrón de `RepositorioPanel`.
   - Mantiene las consultas constantes por página.
2. **Tablas resumen precalculadas al vender.** Se descarta por ahora. Sin una medición que lo pida, se viola «medir antes de optimizar». Queda como plan B si la prueba de 10 000 referencias no da los 5 s.
3. **Calcular en Java a partir de los registros.** Se descarta: carga todo en memoria.

## 5. Diseño

Rutas bajo `/api/informes/…`, solo `ADMINISTRADOR`. Las fechas son días en hora de Bogotá (ADR-0030).

**Período** *(decisión del orquestador)*
- Se envía como `desde` y `hasta`, ambos inclusive.
- Si `desde` es posterior a `hasta`, devuelve 400.
- El rango máximo es de **366 días**; uno mayor devuelve 400 con «Elige un período de máximo un año.». Así se acota el costo de la consulta.

### 5.1 Inventario valorizado (RF-57)

- **Filtros.** Categoría (opcional) y texto (código o nombre).
- **Totales.**
  - Sección principal: unidades, valor al costo, valor a precio de contado y utilidad potencial, sobre existencia > 0.
  - Sección aparte «Vendido sin existencia»: unidades pendientes y su valor al costo.
- **Tabla.** Código, producto, categoría, existencia, costo, precio, valor al costo y valor a precio. Ordena por valor al costo de mayor a menor.
- **Fuente.** `v_inventario_valorizado`.
- **Productos inactivos con existencia: cuentan** *(decisión del orquestador)*. Aunque ya no se vendan, siguen siendo plata en bodega. En la tabla llevan la marca «Inactivo».

### 5.2 Sin movimiento (RF-58)

- **Filtros.** `desde` y `hasta`; por omisión los últimos 90 días hasta hoy. Categoría opcional.
- **Qué cuenta.**
  - Productos activos con existencia > 0.
  - Que no tienen ningún movimiento `EGRESO` de venta en el período. La salida de venta se reconoce por el documento de venta en el kárdex; el plan fija la columna exacta.
- **Totales.** Referencias y capital inmovilizado (existencia × costo vigente).
- **Tabla.**
  - Código, producto, existencia, costo y capital inmovilizado.
  - **Última venta**: fecha de la última venta, aunque sea anterior al período, o «Nunca».
  - Ordena por capital de mayor a menor.

### 5.3 Ventas netas (RF-59)

- **Filtros.** `desde` y `hasta` (por omisión el mes en curso) y agrupar por **Producto** o **Vendedor**.
- **Totales del período.**
  - Unidades vendidas y unidades devueltas.
  - Ventas brutas, descuentos, devoluciones (valor) e **ingresos netos** = brutas − descuentos − devoluciones.
  - Valores **antes de impuestos** *(decisión del orquestador: el impuesto no es ingreso de la tienda)*. El IVA del período se muestra aparte, como dato.
- **Tabla.** Las mismas columnas, agrupadas por la dimensión elegida.
- **Reglas.**
  - La devolución cuenta en la fecha en que se hizo, no en la de la venta, como el kárdex.
  - El vendedor de una devolución es el de la venta original *(decisión del orquestador: así se ve el neto de cada vendedor)*.
  - Ventas anuladas, si existen en `ventas.estado`, no cuentan.
- **Conciliación (prueba obligatoria).** Para el mismo período y empresa:
  - las unidades vendidas del informe = Σ cantidades de movimientos `EGRESO` de venta del kárdex;
  - las unidades devueltas = Σ movimientos `DEVOLUCION`.
  - Se prueba con un escenario que tenga ventas, una devolución parcial, un descuento y un encargo.

### 5.4 Pendientes de reposición (RF-69)

- **Qué lista.** Productos con existencia < 0. Por cada uno, la o las ventas que lo dejaron en negativo (encargos):
  - fecha;
  - número de venta;
  - unidades faltantes (`cantidad_encargo`);
  - entrega estimada;
  - quién autorizó y vendedor.
- **Filtro de estado.**
  - «Pendientes»: existencia < 0. Es el filtro por omisión.
  - «Conciliados»: encargos de productos que ya volvieron a 0 o más en el período.
  - El estado se calcula al leer, comparando con la existencia actual. No se escribe nada.
- **Totales.** Referencias pendientes, unidades faltantes y su valor al costo.

### 5.5 Excel

- `GET /api/informes/<informe>.xlsx` con los mismos filtros.
- La primera hoja trae los totales y la tabla completa, sin paginar, recorrida con cursor.
- Memoria acotada con `SXSSFWorkbook(100)` y `dispose()`, igual que `GeneradorPlantillaPrecios`.
- Encabezados en español, montos con formato de pesos y nombre de archivo `informe-<informe>-<desde>-<hasta>.xlsx`.
- La web descarga con el `api.descargar` que ya existe.

### 5.6 Rendimiento

- **Prueba con base:** 10 000 referencias y un volumen de ventas razonable sembrado en la prueba. Cada informe (primera página y totales) responde en < 5 s.
- **Consultas constantes por página:** se miden y no dependen del número de filas.
- **Índices:** si `EXPLAIN` muestra un recorrido completo evitable, se agrega un índice en la migración **V53** (reservada para esta rama). Por ejemplo, `movimientos_inventario (empresa_id, tipo, fecha)`.

### 5.7 Pantalla

```
Informes
[Inventario valorizado] [Sin movimiento] [Ventas netas] [Pendientes de reposición]

Desde [01/11/2026]  Hasta [25/11/2026]  Agrupar por [Producto ▾]  [Descargar en Excel]

 Ventas brutas    Descuentos    Devoluciones    Ingresos netos
 $18.420.000      $310.000      $95.000         $18.015.000

 Producto           Vendidas  Devueltas  Brutas       Desc.     Devol.    Netas
 Disco corte 7"        120        2      $1.440.000   $12.000   $24.000   $1.404.000
 …                                                         [‹ Anterior] [Siguiente ›]
```

- La pestaña elegida y sus filtros quedan en la URL, para volver al mismo informe.
- Estados vacíos claros, por ejemplo: «No hubo ventas entre el 1 y el 25 de noviembre.».

## 6. Pruebas que fijan el comportamiento

- **Permisos.** Admin 200. Vendedor, super administrador y cliente 403. Otra empresa no aparece.
- **RF-57.**
  - Los totales excluyen la existencia negativa.
  - La sección negativa tiene sus propias unidades y valor.
  - Una categoría sin productos da totales en cero y encabezados presentes.
- **RF-58.**
  - Un producto vendido hace 100 días aparece con «Última venta».
  - Uno vendido hace 10 días no aparece.
  - Uno con existencia 0 no aparece.
  - Fechas invertidas devuelven 400.
  - El período por omisión es de 90 días.
- **RF-59.**
  - Conciliación con el kárdex.
  - Una devolución parcial resta del vendedor de la venta.
  - Un descuento resta.
  - Rango de más de 366 días devuelve 400.
- **RF-69.**
  - Un encargo deja el producto pendiente.
  - Una recepción que lo lleva a 0 o más lo pasa a conciliado.
- **Excel.** Abre con POI y trae los mismos totales que la pantalla.
- **Rendimiento.** Menos de 5 s con 10 000 referencias. Consultas constantes por página.
- **Web.** Las cuatro pestañas, los filtros en la URL, la descarga, el estado vacío y el error 400 mostrado.

## 7. Lo que queda al constructor

- Nombres de clases y rutas exactas, siguiendo `RepositorioPanel` y `PanelControlador`.
- Cómo reconoce el kárdex la salida «por venta». Hoy el documento del movimiento lleva el consecutivo de la venta; el plan lo verifica.
- Formato de las cifras con los helpers de formato existentes en la web.
