# Cotizaciones (RF-37, RF-39, RF-40) — diseño

- **Fecha:** 2026-11-25. Revisión 2 del 2026-11-26, tras la revisión EGM; los hallazgos se resuelven en el §9.
- **Rama:** `feat/cotizaciones`.
- **Antecedente aprobado por el usuario:** apartado 12 de `2026-11-09-caja-design.md` («Entrega 2: cotizaciones»). Este documento lo desarrolla y no reabre lo que el usuario ya decidió ahí.

## 1. Para qué

El usuario quiere el producto terminado para el 10 de diciembre. El 2026-11-25 dijo: «ve avanzando, el tiempo ya es secundario».

Las cotizaciones son lo que más notan los clientes de la tienda:

- el vendedor arma una propuesta de precios para un cliente;
- la entrega en PDF con la identidad de la empresa;
- cuando el cliente vuelve, la caja la cobra sin volver a digitar nada.

El usuario pidió avanzar sin más preguntas. Por eso las decisiones que no tomó él van marcadas **(decisión del orquestador)**, con su razón.

## 2. Lo que ya decidió el usuario

Sale del apartado 12 de la caja y de los Requisitos v3/v4.

- **Crear (RF-37).**
  - Varias líneas, cliente asociado y vigencia en días hábiles. Se descuentan fines de semana y festivos (`calendario_habil`).
  - Precios e impuestos quedan congelados en cada línea.
  - No toca el inventario.
  - Una cotización sin líneas devuelve 400.
- **PDF (RF-39).**
  - Contenido: empresa (nombre e identificación), cliente, líneas, subtotal, impuesto discriminado, total y fecha de vencimiento.
  - Tiempo: menos de 3 s para 20 líneas (RNF-03).
- **Convertir en venta desde la caja (RF-40, modificado por el usuario).**
  - La caja busca la cotización **por su número** y la carga al carrito.
  - **Vigente:** se cobra con los precios congelados.
  - **Vencida:** se carga igual, con los **precios de hoy**, y la caja lo avisa (no hay 409).
  - **Ya convertida:** no se puede volver a convertir (409).
  - La venta queda ligada (`ventas.cotizacion_id`) y la cotización pasa a `CONVERTIDA`.
- **Permisos (Tabla 10).** Administrador de empresa y Vendedor ejecutan en su empresa. Super administrador y Cliente reciben 403.
- **Aislamiento (RF-09).** Una cotización de otra empresa devuelve 404.

## 3. Objetivos, no objetivos y antiobjetivos

**Objetivos**

- Pantalla «Cotizaciones» para el admin y el vendedor: lista, crear, ver detalle, descargar PDF y anular.
- «Cargar cotización» en la caja.
- Las pantallas nuevas salen ya con el diseño nuevo de Figma (skill `impeccable`, modo Operate, colores solo de `tokens.css`).

**No objetivos**

- **Envío y obsequio de envío** (RF-38, prioridad baja, v2). Las columnas `envio`, `origen_envio`, etc. quedan en 0 o NULL.
- **Logo de la empresa (RF-64)** *(decisión del orquestador)*. Hoy no existe dónde subirlo; se construye después, como tarea propia junto a «Mi empresa». Mientras tanto, el PDF muestra el nombre comercial en grande en el lugar del logo.
- **Enviar la cotización por correo** desde la app.
- **Precio a crédito.** `forma_pago` siempre es `CONTADO` *(decisión del orquestador: hoy la caja solo vende de contado)*.
- **Descuentos dentro de la cotización.** La columna `descuento` queda en 0. El descuento con PIN del admin se sigue dando en la caja al cobrar *(decisión del orquestador: RF-36 ata el descuento a la venta y al PIN)*.
- **Editar una cotización guardada.** Se anula y se crea otra *(decisión del orquestador: los precios congelados no se tocan)*.
- **Copia de los datos de la empresa en la cotización.** El PDF lee los datos vigentes de «Mi empresa». Si la empresa cambia su dirección, un PDF viejo descargado de nuevo sale con la nueva. Se acepta *(decisión del orquestador)*: la cotización no es documento tributario, a diferencia del recibo, que sí guarda su copia (V48).

**Antiobjetivos (sería un fracaso aunque funcione)**

- Que el navegador mande precios al servidor. Los precios congelados salen siempre de la base.
- Que la conversión salte las reglas de la caja: turno abierto, PIN, encargo, 409 por existencia insuficiente e idempotencia.
- Que una misma cotización se convierta dos veces.
- Que un vendedor vea cotizaciones de otra empresa.

## 4. Enfoques considerados

1. **La cotización como «venta en borrador» dentro de `ventas`.** Se descarta: mezcla documentos con reglas distintas (inventario, turno, kárdex). Además, ya existen tablas propias.
2. **Tablas propias `cotizaciones` / `cotizacion_items`, con la conversión por el `RegistradorDeVenta` existente** ← **elegido**. Reutiliza todo lo que protege la venta (turno, PIN, encargo, idempotencia, consecutivo, kárdex). La cotización solo aporta los precios congelados y el cambio de estado.
3. **Un endpoint aparte que crea la venta.** Se descarta: duplicaría el registrador y sus reglas.

## 5. Diseño

### 5.1 Datos

Estado actual de las tablas:

- `cotizaciones` y `cotizacion_items` vienen de V4.
- `cotizaciones.empresa_id` viene de V10, con claves compuestas en V11.
- Las claves de `cotizacion_items` por empresa vienen de V31.
- El número ya es único por empresa: `uq_cotizaciones_consecutivo (empresa_id, consecutivo)`, de V12.
- `ventas` ya tiene `cotizacion_id` y `uq_ventas_cotizacion UNIQUE (cotizacion_id)` (V4), y su FK compuesta a `cotizaciones` es de V11.
- La vigencia ya es configuración de cada empresa: `empresa_config.cotizacion_vigencia_dias` (V12), editable por el admin en Configuración. Cada cotización guarda su copia en `cotizaciones.vigencia_dias` (V12).

Una sola migración nueva, **V50** (reservada para esta rama):

1. **Montos de `cotizacion_items` como columnas normales.** `cotizacion_items.subtotal` y `valor_iva` dejan de ser columnas generadas. Pasan a ser normales, escritas por la aplicación con `CalculoDeVenta`, con el mismo procedimiento que V44 §3 usó en `venta_items`.
   - **Por qué:** la columna generada guarda el impuesto con centavos, y la venta lo redondea por línea a pesos. Con columnas generadas, la cotización, su PDF y la venta convertida no cuadrarían.
2. **Tipo de consecutivo `COTIZACION`.** Se agrega al ENUM `consecutivos.tipo`, re-listando los valores de V44 (patrón de V49). También va en `TipoConsecutivo`.
3. **Datos de la anulación.** En `cotizaciones`:
   - `anulada_por` (INT NULL, FK compuesta a `usuarios`);
   - `anulada_en` (DATETIME NULL);
   - `motivo_anulacion` (VARCHAR(200) NULL);
   - un CHECK: los tres van llenos si y solo si `estado = 'ANULADA'`.
4. **Índice `(empresa_id, estado, fecha_vencimiento)`** para los filtros de estado. El índice existente `ix_cotizaciones_estado` no tiene la empresa. El orden «más reciente primero» usa `cotizacion_id DESC` sobre `uq_cotizaciones_empresa (empresa_id, cotizacion_id)`.

**Numeración**

- Se toma de `RepositorioConsecutivos` con el tipo `COTIZACION`.
- Se guarda como `COT-%08d` (las ventas usan `V-%08d`).
- En la caja se puede escribir el número con o sin prefijo y sin ceros (por ejemplo «12»). El servidor lo convierte a `COT-00000012` y busca por igualdad exacta en el índice único.

**Estados**

- Se guarda directo en `VIGENTE`; no se usa `BORRADOR`.
- `VENCIDA` **no se escribe**. Se calcula al leer, con `:hoy = Hoy.de(reloj)` (hora de Bogotá), nunca `CURDATE()`:
  - Vigente: `estado = 'VIGENTE' AND fecha_vencimiento >= :hoy`.
  - Vencida: `estado = 'VIGENTE' AND fecha_vencimiento < :hoy`.
- `CONVERTIDA` y `ANULADA` sí se escriben.
- Si la venta ligada se devuelve después, la cotización sigue `CONVERTIDA`. La restricción única impide ligarla a otra venta.

### 5.2 Vigencia en días hábiles

- `fecha_emision` = `Hoy.de(reloj)`.
- `fecha_vencimiento` = `fecha_emision` + N días hábiles, saltando sábados, domingos y las fechas de `calendario_habil`.
- **N:** `empresa_config.cotizacion_vigencia_dias` de la empresa. Por omisión es 5, y se copia a `cotizaciones.vigencia_dias`. La petición no trae N: lo fija el admin para toda la empresa *(decisión del orquestador: una sola regla por tienda)*.
- **Dónde se calcula:** en Java. Se leen los festivos del rango con una consulta. No se usa `fn_sumar_dias_habiles` (V5), que depende de `CURDATE()` y no avisa si falta un año.
- **Test obligatorio, el ejemplo de RF-37:** emitida el miércoles 1 de abril de 2026 con 5 días hábiles, vence el viernes 10 de abril.
- **Año sin festivos cargados.** `calendario_habil` tiene 2026 y 2027. Si la cuenta entra en un año sin festivos, se saltan solo los fines de semana y el log avisa *(decisión del orquestador: no bloquear por un dato de plataforma)*.

### 5.3 Crear (POST `/api/v1/cotizaciones`)

Requiere `@PreAuthorize("hasAnyRole('ADMINISTRADOR','VENDEDOR')")`, como `VentasControlador`.

**Entrada:** `clienteId` (puede ser Consumidor final), líneas `{productoId, cantidad}` y `observaciones` opcional (máximo 400).

**Cómo arma cada línea el servidor**

- Usa la misma regla que la venta, sin duplicarla:
  - `RepositorioVentas.preciosVigentes`;
  - la lógica de `RegistradorDeVenta.lineaVendible`, que se extrae a un componente compartido;
  - `CalculoDeVenta` para el redondeo y los totales.
- Así la cotización congela exactamente el triple que hoy cobraría la caja: **precio, tarifa y tratamiento**.
- Mismos códigos que la caja:
  - producto inactivo o sin precio: **400**;
  - producto inexistente o de otra empresa: 404.
- Un producto repetido se suma en una sola línea.
- Se aplican los mismos límites de la venta (`LimitesDeVenta`: 200 líneas, 99 999 unidades, subtotal máximo). Una cotización que no se podría cobrar no se puede guardar.

**Errores**

- Sin líneas o con cantidad ≤ 0: 400.
- Cliente de otra empresa: 404.

**Transacción**

- `@Transactional(isolation = READ_COMMITTED)`, como todos los que numeran.
- El número se toma **al final**, justo antes de insertar.

**Respuesta y turno**

- Respuesta: 201 con la cotización completa.
- **No hace falta turno abierto** para cotizar: no mueve dinero ni inventario *(decisión del orquestador)*.

### 5.4 Consultar, anular y PDF

**Lista paginada**

- Muestra todas las cotizaciones de la empresa, para que cualquier cajero encuentre la que va a cobrar *(decisión del orquestador)*.
- Filtros:
  - estado: Vigente, Vencida, Convertida o Anulada;
  - texto: número, o nombre o identificación del cliente.
- Orden: la más reciente primero.
- Número de consultas constante por página.

**Detalle por id y por número**

- Cada línea trae el precio congelado.
- Si la cotización está **vencida**, cada línea trae además el **precio de hoy**. Si el producto está inactivo o ya no tiene precio, la línea dice «no disponible».
- La caja carga lo que corresponda al estado.

**Anular** (`POST …/{id}/anulacion` con `{motivo}`, obligatorio y de máximo 200 caracteres)

- Solo se anula una vigente o vencida.
- Puede anular el admin, o el vendedor que la creó (`cotizaciones.usuario_id`) *(decisión del orquestador)*.
- Errores:
  - un vendedor que no la creó: 403;
  - anulada o convertida: 409.
- Toma la fila con `FOR UPDATE` y guarda quién, cuándo y por qué.

**PDF** (`GET …/{id}/pdf`)

- Tamaño carta.
- Clase nueva de diagramación: `ReciboPdf` es de 80 mm. Reutiliza su fuente y su limpieza de texto (ADR-0035).
- Datos de la empresa: los de «Mi empresa».
- Si está vencida, anulada o convertida, el PDF lo dice en el encabezado.
- Una empresa en modo consulta puede descargar el PDF (es una lectura), pero no crear ni anular: `FiltroEmpresaActiva` ya lo cubre.

### 5.5 Convertir en la caja

**En la pantalla**

- El cajero pulsa «Cargar cotización» y escribe el número. La caja llama al detalle y luego:
  - pone el cliente de la cotización y **bloquea el cambio de cliente** mientras la cotización esté cargada;
  - carga las líneas al carrito:
    - si está vigente, con los precios congelados;
    - si está vencida, con los precios de hoy;
  - muestra un aviso:
    - vigente: «Cotización COT-00000123 vigente hasta el 10 abr.»;
    - vencida: «Esta cotización venció el …; se cobra con los precios de hoy.».
- Convertida o anulada: mensaje claro y no se carga.
- Una línea no disponible (producto inactivo o sin precio): la caja lo avisa y esa línea no entra al carrito. RF-17 prohíbe vender un producto inactivo.
- **Lo que el cajero puede cambiar en el carrito:**
  - quitar líneas y bajar cantidades;
  - agregar otros productos, que van al precio de hoy.
- **Lo que no puede:** subir la cantidad de una línea cotizada por encima de lo cotizado.
- «Quitar cotización» limpia el vínculo y deja el carrito con precios de hoy.

**Petición de venta**

- Lleva un bloque opcional `cotizacion: {id, preciosCongelados: boolean}` y **nunca precios**.
- `preciosCongelados` dice con qué precios armó la caja el total.
- `NormalizadorDeVenta.forma` conserva el bloque.
- La huella de idempotencia lo incluye **solo cuando está presente**, igual que el bloque de domicilio. Así las huellas existentes no cambian.

**Orden de bloqueos dentro de `RegistradorDeVenta`** (se agrega a su documentación):

1. Turno (`FOR SHARE`).
2. **Cotización (`FOR UPDATE`)**, validando que sea de la empresa (404).
3. Productos.
4. Búsqueda de la clave de idempotencia: si la venta ya existe, se devuelve esa venta (200), aunque la cotización ya esté `CONVERTIDA` por esa misma venta.
5. Solo después, el estado de la cotización:
   - convertida o anulada: **409**;
   - `preciosCongelados = true` pero la cotización ya está vencida (pasó la medianoche entre cargar y cobrar): **409** «La cotización venció mientras la cobrabas; vuelve a cargarla.»;
   - `preciosCongelados = false` y la cotización sigue vigente: se cobra a precios de hoy, que es lo que el cajero vio *(decisión del orquestador)*.
6. **Validación de la venta contra la cotización:**
   - el cliente de la venta debe ser el de la cotización: si no, 400;
   - una línea de un producto cotizado no puede superar la cantidad cotizada: si la supera, 400.
7. **Precios:**
   - vigente con `preciosCongelados = true`: cada línea de producto cotizado usa el **triple congelado** (precio, tarifa, tratamiento) y se calcula con `CalculoDeVenta`. Esto vale aunque hoy el producto no tenga regla de precio, mientras siga activo; si está inactivo, devuelve 400;
   - los demás productos y el caso de vencida usan la regla de siempre.
8. Las reglas de siempre: PIN para descuentos y encargos, 409 por existencia insuficiente sin encargo y validación de pagos contra el total del servidor.
9. Inserta la venta con `cotizacion_id` y pasa la cotización a `CONVERTIDA`.
   - La restricción `uq_ventas_cotizacion` es la guarda en la base. Si se viola, la respuesta es **409**, no 500.

El resultado:

- dos cobros con claves distintas de la misma cotización dan una venta y un 409;
- dos con la misma clave dan 201 y 200 con la misma venta.

**Anular frente a convertir.** Anular bloquea solo la fila de la cotización. Por el paso 2, anular y convertir se excluyen entre sí.

### 5.6 Pantallas (diseño nuevo, impeccable, modo Operate)

Menú «Cotizaciones» para el admin y el vendedor, junto a «Caja».

```
Cotizaciones                                   [+ Nueva cotización]
[Buscar número o cliente…]  [Estado: Todas ▾]
 Número         Fecha    Cliente            Total        Vence    Estado
 COT-00000012   25 nov   Ferretería Ruiz    $1.250.000   2 dic    Vigente
 COT-00000011   20 nov   Consumidor final   $   89.000   27 nov   Vencida
```

```
Nueva cotización
Cliente: [Buscar cliente…] [Consumidor final]
Producto: [Buscar por nombre o código…]
 Producto            Cant.   Precio      IVA      Total     [x]
 Disco corte 7"        10    $12.000   $22.800  $142.800
Observaciones: [______________]
Vence: miércoles 2 dic. (5 días hábiles)
Subtotal $120.000   IVA $22.800   Total $142.800     [Guardar cotización]
```

Al guardar se va al detalle, con «Descargar PDF» y, si aplica, «Anular» (pide el motivo). Los buscadores de cliente y de producto reutilizan los de la caja.

## 6. Pruebas que fijan el comportamiento

El plan da los nombres.

**Crear**

- La vigencia del ejemplo de RF-37 (1 abr → 10 abr 2026) y la vigencia configurada por la empresa.
- Códigos de respuesta:
  - 201 al crear;
  - sin líneas, 400;
  - producto inactivo, 400;
  - cliente de otra empresa, 404;
  - el vendedor sí puede; el super administrador y el cliente, 403;
  - por encima de los límites de venta, 400.
- El impuesto de la línea va redondeado a pesos igual que la venta. Ejemplo: 1 × 12 345 al 19 % da 2 346.
- Precios congelados: después de cambiar el precio del producto, la cotización conserva el anterior.

**Convertir**

- Vigente: precio y tarifa congelados aunque hoy sean otros.
- Vencida: precio de hoy.
- Convertida o anulada: 409.
- Vencida entre la carga y el cobro, con reloj fijo a la medianoche: 409.
- Dos conversiones con claves distintas: una venta y un 409.
- Misma clave dos veces: 201 y 200 con la misma venta.
- Cliente distinto: 400. Cantidad mayor a la cotizada: 400.
- Producto agregado: precio de hoy.
- Existencia insuficiente sin encargo: 409, y la cotización sigue vigente.

**Anular**

- El admin puede.
- El vendedor que la creó puede; otro vendedor recibe 403.
- Anular una convertida: 409.
- Sin motivo: 400.

**PDF y aislamiento**

- PDF de 20 líneas en menos de 3 s, con el texto esperado.
- Aislamiento: el id o el número de otra empresa devuelve 404.

**Web**

- Lista, crear y anular.
- Cargar en la caja con aviso de vigente o vencida.
- Línea no disponible avisada.
- Cliente bloqueado mientras la cotización está cargada.
- Tope de cantidad.

## 7. Riesgos y cómo se cubren

- **La medianoche entre cargar y cobrar.** Devuelve un 409 explícito, con prueba de reloj fijo.
- **Cambio de tarifa o de UVT** (por ejemplo, el 1 de enero) con cotizaciones vigentes. Se cobra el triple congelado, como promete RF-37. Las cotizaciones duran pocos días hábiles, así que el efecto es acotado.
- **Año sin festivos cargados.** Se cuentan solo los fines de semana y el log avisa.
- **Abuso de precios congelados** (vender más unidades o a otro cliente). Se impide con la validación de cliente y la de cantidad.
- **Desbordar montos.** Se usan los límites de la venta.

## 8. Lo que queda al constructor

- Nombres de clases y DTO, siguiendo los existentes. Las rutas van bajo `/api/v1/cotizaciones`.
- Dónde vive el componente compartido extraído de `lineaVendible`.
- La forma exacta del PDF carta, dentro del estilo de `ReciboPdf`.

## 9. Hallazgos de la revisión EGM (2026-11-26) y cómo se resolvieron

| Hallazgo | Resolución |
|---|---|
| B1 orden de bloqueos frente a idempotencia | §5.5: cotización después del turno; la clave se revisa antes del estado |
| B2 `valor_iva` generado con centavos | V50 las vuelve columnas normales, escritas por `CalculoDeVenta` |
| B3 único por empresa ya hecho en V12 | Se quitó de V50 |
| S1 vigencia por empresa ya existe | Se usa `empresa_config.cotizacion_vigencia_dias` |
| S2 tarifa congelada | Se congela el triple completo |
| S3 medianoche y precios de hoy de la vencida | `preciosCongelados` y 409; el detalle trae el precio de hoy |
| S4 normalizador y huella | Se conserva el bloque; entra en la huella solo si viene |
| S5 `uq_ventas_cotizacion` daba 500 | Ahora 409 |
| S6 cantidad y cliente | Validados (400) |
| S7 READ COMMITTED y límites | Adoptados |
| S8 clases de precio | `preciosVigentes`, `lineaVendible` compartido y `CalculoDeVenta` |
| S9 inactivo da 400 | Ajustado |
| S10 auditoría de anulación | Columnas en V50 y `FOR UPDATE` |
| S11 riesgos | §7 |
| M1–M7 | Número `COT-%08d`; filtros con `:hoy`; sin V51; comparación por fecha; sin copia de la empresa (aceptado); `/api/v1`; V31; sigue `CONVERTIDA` |
