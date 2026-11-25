# Cotizaciones (RF-37, RF-39, RF-40) — diseño

Fecha: 2026-11-25. Rama: `feat/cotizaciones`.
Antecedente aprobado por el usuario: apartado 12 de `2026-11-09-caja-design.md` («Entrega 2: cotizaciones»).
Este documento lo desarrolla. No reabre lo que el usuario ya decidió ahí.

## 1. Para qué

El usuario quiere el producto terminado para el 10 de diciembre. El 2026-11-25 dijo: «ve avanzando, el tiempo ya es secundario».
Las cotizaciones son lo que más notan los clientes de la tienda:

- el vendedor arma una propuesta de precios para un cliente;
- la entrega en PDF con la identidad de la empresa;
- cuando el cliente vuelve, la caja la cobra sin volver a digitar nada.

El usuario pidió avanzar sin más preguntas. Por eso las decisiones que no tomó él van marcadas **(decisión del orquestador)**, con su razón.

## 2. Lo que ya decidió el usuario (apartado 12 de la caja y Requisitos v3/v4)

- **Crear (RF-37).**
  - Varias líneas, cliente asociado y vigencia en días hábiles.
  - Por omisión son 5 días hábiles, descontando fines de semana y festivos (`calendario_habil`).
  - Precios e impuestos quedan congelados en cada línea.
  - No toca el inventario.
  - Una cotización sin líneas devuelve 400.
- **PDF (RF-39).**
  - Contenido: empresa (nombre e identificación), cliente, líneas, subtotal, impuesto discriminado, total y fecha de vencimiento.
  - Menos de 3 s para 20 líneas (RNF-03).
- **Convertir en venta desde la caja (RF-40, modificado por el usuario).**
  - La caja busca la cotización **por su número** y la carga al carrito.
  - **Vigente:** se cobra con los precios congelados.
  - **Vencida:** se carga igual, con los **precios de hoy**, y la caja lo avisa (no hay 409).
  - **Ya convertida:** no se puede volver a convertir (409).
  - La venta queda ligada a la cotización (`ventas.cotizacion_id`) y la cotización pasa a `CONVERTIDA`.
- **Permisos (Tabla 10).** Administrador de empresa y Vendedor ejecutan en su empresa. Super administrador y Cliente reciben 403.
- **Aislamiento (RF-09).** Una cotización de otra empresa devuelve 404.

## 3. Objetivos, no objetivos y antiobjetivos

**Objetivos**
- Pantalla «Cotizaciones» para el admin y el vendedor: lista, crear, ver detalle, descargar PDF y anular.
- «Cargar cotización» en la caja.
- Las pantallas nuevas se construyen ya con el diseño nuevo de Figma (skill `impeccable`, modo Operate, colores solo de `tokens.css`).

**No objetivos (quedan fuera)**
- Envío y obsequio de envío (RF-38, prioridad baja v2). Las columnas `envio`, `origen_envio`, etc. quedan en 0 o NULL.
- **Logo de la empresa (RF-64)** *(decisión del orquestador)*. Hoy no existe dónde subirlo. Se construye después como tarea propia, junto a «Mi empresa». El PDF reserva el espacio del logo y, mientras no haya uno, muestra el nombre comercial en grande. Si no se hace, el PDF sale sin logo: es lo mismo que el recibo hoy.
- Enviar la cotización por correo desde la app.
- Precio a crédito (`forma_pago`): siempre `CONTADO` *(decisión del orquestador: hoy la caja solo vende de contado)*.
- Descuentos dentro de la cotización: la columna `descuento` queda en 0. El descuento con PIN del admin se sigue dando en la caja al cobrar, como en cualquier venta *(decisión del orquestador: RF-36 ata el descuento a la venta y al PIN)*.
- Editar una cotización ya guardada: para cambiarla se anula y se crea otra *(decisión del orquestador: los precios congelados no se tocan)*.

**Antiobjetivos (sería un fracaso aunque funcione)**
- Que el navegador mande precios al servidor. Los precios congelados salen siempre de la base.
- Que la conversión salte las reglas de la caja: turno abierto, PIN, encargo y 409 por existencia insuficiente.
- Que una misma cotización se convierta dos veces bajo concurrencia.
- Que un vendedor vea cotizaciones de otra empresa.

## 4. Enfoques considerados

1. **Cotización como «venta en borrador» dentro de `ventas`.** Se descarta: mezcla documentos con reglas distintas (inventario, turno, kárdex). Además ya existen tablas propias.
2. **Tablas propias `cotizaciones` / `cotizacion_items` y conversión por el `RegistradorDeVenta` existente** ← **elegido**. Reutiliza todo lo que ya protege la venta (turno, PIN, encargo, idempotencia, consecutivo, kárdex). La cotización solo aporta los precios congelados y el cambio de estado.
3. **Conversión con un endpoint aparte que crea la venta.** Se descarta: duplicaría el registrador y sus reglas.

## 5. Diseño

### 5.1 Datos

Se usan las tablas de V4, que ya tienen `empresa_id` desde V10 y claves compuestas desde V11.
Las líneas guardan el impuesto igual que `venta_items` (`tarifa_iva`, `tratamiento_iva`), así que no hay que cambiar su forma.

Migraciones nuevas, numeradas **V50 y V51** (reservadas para esta rama):

- **V50.**
  - `uq_cotizaciones_consecutivo (consecutivo)` es global. Pasa a ser único **por empresa** `(empresa_id, consecutivo)`: dos empresas pueden tener su cotización número 1.
  - Agregar `COTIZACION` al ENUM `consecutivos.tipo`, re-listando los valores de V44 (mismo patrón que V49). También va en `TipoConsecutivo`.
- **V51.** Índice para la lista: `(empresa_id, creado_en)`. El plan confirma con `EXPLAIN` si hace falta otro para buscar por cliente.

**Numeración.**
- Se toma de `RepositorioConsecutivos` con el tipo `COTIZACION`, dentro de la transacción que guarda la cotización.
- Se muestra con el prefijo `COT-` y el mismo relleno de ceros que las ventas.
- En la caja se puede escribir el número con o sin prefijo.

**Estados.**
- Se guarda directo en `VIGENTE`; no se usa `BORRADOR`.
- `VENCIDA` **no se escribe**. Se calcula al leer: estado `VIGENTE` y `fecha_vencimiento` (23:59:59, hora de Bogotá) ya pasó. No hay tareas programadas.
- `CONVERTIDA` y `ANULADA` sí se escriben.

### 5.2 Vigencia en días hábiles

- `fecha_emision` es el día de hoy en Bogotá.
- `fecha_vencimiento` resulta de sumar N días hábiles, saltando sábados, domingos y las fechas de `calendario_habil`.
- N sale de la propiedad `sgip.cotizaciones.dias-vigencia` (por omisión 5).
- Test obligatorio, el ejemplo de RF-37: emitida el miércoles 1 de abril de 2026 con 5 días hábiles, vence el viernes 10 de abril. Se descuentan el jueves 2 y el viernes 3 (festivos) y el fin de semana.
- `calendario_habil` tiene 2026 y 2027. Si la cuenta entra en un año sin festivos cargados, se saltan solo los fines de semana y se escribe una advertencia en el log *(decisión del orquestador: no bloquear la venta por un dato de plataforma)*.

### 5.3 Crear (POST)

- **Entrada:** `clienteId` (puede ser Consumidor final), líneas `{productoId, cantidad}` y `observaciones` opcional (máximo 400).
- **El servidor arma cada línea:**
  - toma el precio de contado vigente y el impuesto del producto, con el mismo cálculo que usa la caja;
  - un producto inactivo, inexistente o de otra empresa devuelve 409 o 404, como en la caja;
  - un producto repetido se suma en una sola línea (la tabla tiene `UNIQUE (cotizacion_id, producto_id)`);
  - calcula subtotal, IVA y total.
- **Respuestas:**
  - sin líneas o con cantidad ≤ 0 devuelve 400;
  - cliente de otra empresa devuelve 404;
  - el éxito devuelve 201 con la cotización completa.
- **No hace falta turno abierto** para cotizar: no mueve dinero ni inventario *(decisión del orquestador)*.

### 5.4 Consultar y anular

- **Lista paginada.**
  - Todas las cotizaciones de la empresa, para que cualquier cajero encuentre la que va a cobrar *(decisión del orquestador)*.
  - Filtros: estado (Vigente, Vencida, Convertida, Anulada) y texto (número o nombre o identificación del cliente).
  - Orden: la más reciente primero.
  - Número de consultas constante por página.
- **Detalle por id y por número** (la caja busca por número).
- **Anular.**
  - Solo una `VIGENTE` o vencida, nunca una `CONVERTIDA`.
  - Puede el admin, o el vendedor que la creó *(decisión del orquestador)*.
  - Anular una anulada o convertida devuelve 409.
- **PDF:** `GET …/{id}/pdf`, tamaño carta, con PDFBox siguiendo el patrón de `ReciboPdf` (ADR-0035) y los datos de empresa de «Mi empresa».

### 5.5 Convertir en la caja

**En la pantalla de la caja**
- Botón «Cargar cotización»: el cajero escribe el número.
- El servidor devuelve la cotización con su estado calculado. La caja:
  - pone el cliente de la cotización;
  - carga las líneas al carrito;
  - muestra un aviso: «Cotización COT-000123 vigente hasta el 10 abr.», o «Esta cotización venció el …; se cobra con los precios de hoy.».
- Convertida o anulada: mensaje claro, no se carga.
- Una línea cuyo producto se desactivó: se avisa y no entra al carrito *(decisión del orquestador: RF-17 prohíbe venderlo)*.
- El cajero puede quitar líneas, cambiar cantidades o agregar otros productos. Los productos agregados van al precio de hoy *(decisión del orquestador)*.

**En el servidor, al cobrar**
- La solicitud de venta lleva `cotizacionId` opcional. Nunca lleva precios.
- Dentro de la misma transacción de `RegistradorDeVenta`:
  1. Bloquea la cotización (`SELECT … FOR UPDATE`) y valida empresa (404) y estado: convertida o anulada devuelve 409.
  2. Si está vigente, el precio unitario de cada línea cuyo producto esté en la cotización es el congelado, sin importar la cantidad. Si está vencida, todos los precios son los de hoy.
  3. Aplica las reglas de siempre: turno, PIN para descuentos y encargos, 409 por existencia insuficiente sin encargo, e idempotencia.
  4. Guarda `ventas.cotizacion_id` y pasa la cotización a `CONVERTIDA` con un `UPDATE … WHERE estado = 'VIGENTE'`. Si no afecta una fila, revierte todo con 409.
- Dos cajas que cobran la misma cotización a la vez dan una venta y un 409.

### 5.6 Pantallas (diseño nuevo, impeccable, modo Operate)

Menú «Cotizaciones» para el admin y el vendedor, junto a «Caja».

```
Cotizaciones                                   [+ Nueva cotización]
[Buscar número o cliente…]  [Estado: Todas ▾]
 Número       Fecha     Cliente            Total        Vence     Estado
 COT-000012   25 nov    Ferretería Ruiz    $1.250.000   2 dic     Vigente
 COT-000011   20 nov    Consumidor final   $  89.000   27 nov    Vencida
```

```
Nueva cotización
Cliente: [Buscar cliente…] [Consumidor final]
Producto: [Buscar por nombre o código…]
 Producto            Cant.   Precio      IVA      Total     [x]
 Disco corte 7"        10    $12.000   $22.800  $142.800
Observaciones: [______________]
Vence: martes 2 dic. (5 días hábiles)
Subtotal $120.000   IVA $22.800   Total $142.800     [Guardar cotización]
```

Al guardar se va al detalle, que tiene «Descargar PDF» y, si aplica, «Anular».
Los buscadores de cliente y producto reutilizan los de la caja.

## 6. Pruebas que fijan el comportamiento (resumen; el plan da los nombres)

- La vigencia del ejemplo de RF-37 (1 abr → 10 abr 2026).
- Crear: 201; sin líneas, 400; producto inactivo, 409; cliente de otra empresa, 404; vendedor sí, super admin y cliente, 403.
- Los precios quedan congelados: después de cambiar el precio del producto, la cotización conserva el anterior.
- Convertir:
  - vigente: precio congelado aunque el precio de hoy sea otro;
  - vencida: precio de hoy;
  - convertida o anulada: 409;
  - dos conversiones a la vez: una sola gana;
  - producto agregado: precio de hoy;
  - existencia insuficiente sin encargo: 409 y la cotización sigue vigente.
- PDF de 20 líneas en menos de 3 s, con el texto esperado.
- Aislamiento: el id o el número de otra empresa devuelve 404.
- Web: lista, crear, anular, cargar en la caja con aviso de vigente o vencida, y línea inactiva avisada.

## 7. Lo que queda al constructor

- Nombres de clases, DTO y rutas, siguiendo los existentes (`VentasControlador`, `RepositorioVentas`, etc.). Ruta sugerida: `/api/cotizaciones`.
- Reutilizar el cálculo de precio e impuesto de la caja (`ServicioCajaProductos` / `CalculoDePrecio`) en lugar de duplicarlo.
- La forma exacta del PDF, dentro del estilo de `ReciboPdf`.
