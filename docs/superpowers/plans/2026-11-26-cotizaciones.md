# Cotizaciones — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** crear, listar, anular y descargar en PDF cotizaciones con precios congelados, y convertirlas en venta desde la caja (RF-37, RF-39, RF-40).

**Architecture:** tablas existentes `cotizaciones`/`cotizacion_items` ajustadas por V50. Un servicio propio crea, consulta y anula. La conversión entra por el `RegistradorDeVenta` existente con un bloque opcional `cotizacion` en `SolicitudVenta`. La web suma una sección «Cotizaciones» y un diálogo «Cargar cotización» en la caja.

**Tech Stack:** Java 25, Spring Boot 4, JdbcClient, MySQL 9.7, Flyway, PDFBox, React 18.3.1, Vite y Vitest.

**Spec:** `docs/superpowers/specs/2026-11-25-cotizaciones-design.md` (revisión 2). Léela entera antes de cada tarea: los códigos HTTP, el orden de bloqueos y las decisiones están ahí.

## Global Constraints

- **TDD.** Primero un commit rojo `test: …`, luego `feat: …`. Mensajes en español con acentos. Trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Migración única de la rama:** `V50__cotizaciones_listas_para_vender.sql` en `backend/src/main/resources/db/migration/`. No crear otras migraciones (V51+ están reservadas para otras ramas).
- **Rutas** bajo `/api/v1/cotizaciones`, con `@PreAuthorize("hasAnyRole('ADMINISTRADOR','VENDEDOR')")`.
- **Fechas:** «hoy» siempre con `Hoy.de(reloj)`, nunca `CURDATE()` ni `LocalDate.now()` sin reloj.
- **Precios:** el navegador nunca manda precios; el servidor los toma de `RepositorioVentas.preciosVigentes` o de las líneas congeladas.
- **Número:** se guarda como `String.format("COT-%08d", n)`.
- **Web:** colores solo de `tokens.css`. Skill `impeccable`, modo Operate y diseño nuevo (`docs/decisiones/ADR-0034`). Textos en español con tildes.
- **Prohibido:**
  - leer o crear `application.yml` o `.env`;
  - usar MySQL en el puerto 3306: solo el mysqld aislado del encargo, en puertos 34xxx;
  - `--no-verify` y `push --force`;
  - commitear `docs/informe/*`, `SGIP_*.docx`, `SGIP_*.pdf` o `docs/mockups/svg/Logo-*`.
- **Verificación backend:**
  - `./mvnw -B -q test -DexcludedGroups=conBase` con EXIT=0;
  - las pruebas `conBase` nuevas o tocadas, con la base aislada.
- **Verificación web:** `npx vitest run`, `npm run -s lint` y `npm run -s build`, los tres limpios.

## Review Focus

1. **Doble clic en «Cobrar» con cotización.** La misma clave da la misma venta (201 y luego 200), nunca un 409. Lo prueba la tarea 6.
2. **Cotización vencida a medianoche entre cargar y cobrar.** Devuelve el 409 con el texto exacto, y la caja lo muestra con «vuelve a cargarla». Lo prueban las tareas 6 y 8.
3. **Producto desactivado después de cotizar.**
   - Crear con él: 400.
   - Detalle de una vencida: la línea viene `disponible=false`.
   - Cobrar una vigente que lo incluye: 400, por RF-17.
   - Lo prueban las tareas 3, 4 y 6.
4. **Número escrito «12», «cot-12» o «COT-00000012».** Los tres encuentran la misma cotización, y un número de otra empresa da 404. Lo prueba la tarea 4.
5. **Impuesto con centavos.** 1 × 12 345 al 19 % da `valor_iva` 2 346 en la cotización, en el PDF y en la venta convertida. Lo prueban las tareas 3 y 6.

---

### Task 1: Esquema V50

**Files:**
- Create: `backend/src/main/resources/db/migration/V50__cotizaciones_listas_para_vender.sql`
- Modify: `backend/src/main/java/co/com/bspstore/sgip/modelo/TipoConsecutivo.java` (agregar `COTIZACION` al final)
- Test: `backend/src/test/java/co/com/bspstore/sgip/datos/CotizacionEsquemaConBaseTest.java` (`@Tag("conBase")`, patrón de `VentaEsquemaConBaseTest`)

**Interfaces:**
- Produces:
  - `TipoConsecutivo.COTIZACION`;
  - en `cotizacion_items`, `subtotal` y `valor_iva` pasan a ser `DECIMAL(14,2) NOT NULL` normales;
  - en `cotizaciones`, las columnas `anulada_por INT NULL`, `anulada_en DATETIME NULL` y `motivo_anulacion VARCHAR(200) NULL`, con el CHECK `ck_cotizaciones_anulacion`;
  - el índice `ix_cotizaciones_empresa_estado (empresa_id, estado, fecha_vencimiento)`.

- [ ] **Step 1: tests rojos en `CotizacionEsquemaConBaseTest`**
  - `losMontosDeLaLineaNoSonGenerados`: `information_schema.COLUMNS.EXTRA` de `subtotal` y `valor_iva` no contiene `GENERATED`.
  - `elConsecutivoAdmiteCotizacion`: `COLUMN_TYPE` de `consecutivos.tipo` contiene `'COTIZACION'`, y `TipoConsecutivo.values()` coincide con el ENUM en el mismo orden.
  - `anularExigeLosTresDatos`: un UPDATE a `estado='ANULADA'` sin `motivo_anulacion` falla con violación del CHECK; con los tres datos pasa.
  - `noSePuedeLlenarLaAnulacionSinAnular`: `estado='VIGENTE'` con `anulada_en` lleno falla.
  - `existeElIndiceDeEstado`: el índice `ix_cotizaciones_empresa_estado` existe con sus columnas en orden.
- [ ] **Step 2: ejecutar y ver el rojo** (falla porque la migración no existe).
- [ ] **Step 3: escribir V50**
  - Montos: seguir V44 §3 (`ventas`/`venta_items`). Agregar columnas nuevas, copiar los valores, quitar las generadas y renombrar.
  - ENUM `consecutivos.tipo`: re-listar los valores actuales de V44 y agregar `'COTIZACION'` al final, como V49.
  - `anulada_por`: FK compuesta `(empresa_id, anulada_por)` → `usuarios`, con el mismo patrón que `fk_cotizaciones_usuario_emp` de V11.
  - CHECK `ck_cotizaciones_anulacion`:
    ```sql
    (estado = 'ANULADA') = (anulada_por IS NOT NULL AND anulada_en IS NOT NULL AND motivo_anulacion IS NOT NULL)
    AND (estado = 'ANULADA' OR (anulada_por IS NULL AND anulada_en IS NULL AND motivo_anulacion IS NULL))
    ```
  - Agregar `COTIZACION` a `TipoConsecutivo`.
- [ ] **Step 4: verde** con la prueba `conBase` y la suite sin base completa.
- [ ] **Step 5: commit** (`test:` y luego `feat: esquema V50 de cotizaciones`).

### Task 2: vigencia en días hábiles

**Files:**
- Create: `backend/src/main/java/co/com/bspstore/sgip/logica/cotizacion/VigenciaHabil.java` (pura, sin Spring)
- Create: `backend/src/main/java/co/com/bspstore/sgip/datos/RepositorioCalendarioHabil.java`
- Test: `backend/src/test/java/co/com/bspstore/sgip/logica/cotizacion/VigenciaHabilTest.java`
- Test: `backend/src/test/java/co/com/bspstore/sgip/datos/CalendarioHabilConBaseTest.java` (`@Tag("conBase")`)

**Interfaces:**
- Produces:
  - `public static LocalDate VigenciaHabil.vence(LocalDate emision, int diasHabiles, Set<LocalDate> festivos)`. Cuenta desde el día siguiente a la emisión y salta sábado, domingo y festivos. `diasHabiles` < 1 lanza `IllegalArgumentException`.
  - `Set<LocalDate> RepositorioCalendarioHabil.festivosEntre(LocalDate desde, LocalDate hasta)`, sobre `calendario_habil.fecha`, ambos extremos inclusive.
  - `Set<Integer> RepositorioCalendarioHabil.aniosConFestivos(int desde, int hasta)`.
  - `int RepositorioCalendarioHabil.vigenciaDeLaEmpresa(Integer empresaId)`: lee `empresa_config.cotizacion_vigencia_dias` y devuelve 5 si no hay fila.

- [ ] **Step 1: tests rojos**
  - `VigenciaHabilTest.elEjemploDeRf37`:
    ```java
    var festivos = Set.of(LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 3));
    assertEquals(LocalDate.of(2026, 4, 10), VigenciaHabil.vence(LocalDate.of(2026, 4, 1), 5, festivos));
    ```
  - `emitidaUnViernesSaltaElFinDeSemana`: viernes 2026-11-27, 1 día hábil → lunes 2026-11-30.
  - `emitidaUnSabado`: sábado 2026-11-28, 1 día hábil → lunes 2026-11-30.
  - `ceroDiasEsInvalido`: lanza `IllegalArgumentException`.
  - `CalendarioHabilConBaseTest`:
    - `festivosDeAbril2026`: `festivosEntre(2026-04-01, 2026-04-10)` contiene 2026-04-02 y 2026-04-03;
    - `aniosCargados`: `aniosConFestivos(2026, 2028)` = {2026, 2027};
    - `vigenciaPorOmision`: una empresa sin `empresa_config` devuelve 5.
- [ ] **Step 2: rojo.**
- [ ] **Step 3: implementar.** `vence` usa un bucle simple día a día. Las consultas del repositorio usan `JdbcClient`, con el patrón de `RepositorioClientes`.
- [ ] **Step 4: verde.**
- [ ] **Step 5: commit.**

### Task 3: crear y ver una cotización (backend)

**Files:**
- Create: `backend/src/main/java/co/com/bspstore/sgip/logica/venta/LineaVendible.java`. Extrae `RegistradorDeVenta.lineaVendible` sin cambiar su comportamiento.
- Modify: `backend/src/main/java/co/com/bspstore/sgip/logica/RegistradorDeVenta.java`. Usa `LineaVendible` y documenta en el orden de bloqueos dónde entra la cotización (paso 2, ver la spec §5.5); la lógica de la conversión llega en la tarea 6.
- Create:
  - `backend/src/main/java/co/com/bspstore/sgip/datos/RepositorioCotizaciones.java`
  - `backend/src/main/java/co/com/bspstore/sgip/logica/ServicioCotizaciones.java`
  - `backend/src/main/java/co/com/bspstore/sgip/api/CotizacionesControlador.java`
- Create los DTO en `api/dto/`:
  - `SolicitudCotizacion`
  - `LineaCotizada`
  - `CotizacionDetalle`
  - `LineaDeCotizacion`
- Test:
  - `backend/src/test/java/co/com/bspstore/sgip/logica/venta/LineaVendibleTest.java`
  - `backend/src/test/java/co/com/bspstore/sgip/api/CotizacionesControladorTest.java` (MockMvc con servicio simulado; patrón de `VentasAutorizacionTest`)
  - `backend/src/test/java/co/com/bspstore/sgip/datos/CotizacionConBaseTest.java` (`@Tag("conBase")`)

**Interfaces:**
- Consumes:
  - `TipoConsecutivo.COTIZACION` (tarea 1);
  - `VigenciaHabil` y `RepositorioCalendarioHabil` (tarea 2);
  - `RepositorioVentas.preciosVigentes`;
  - `CalculoDeVenta.calcular`;
  - `RepositorioConsecutivos.tomarSiguiente`.
- Produces:
  - `public static Optional<LineaPedida> LineaVendible.de(Integer productoId, int cantidad, Descuento descuento, RepositorioVentas.PrecioVigente v)`;
  - `record SolicitudCotizacion(@Positive Integer clienteId, @NotEmpty @Size(max = LimitesDeVenta.LINEAS) List<@Valid LineaCotizada> lineas, @Size(max = 400) String observaciones)`;
  - `record LineaCotizada(@NotNull @Positive Integer productoId, @NotNull @Positive @Max(LimitesDeVenta.<unidades>) Integer cantidad)`. Usa la constante de unidades que ya exista en `LimitesDeVenta`;
  - `record CotizacionDetalle(Long id, String numero, String estado, LocalDate fechaEmision, LocalDate fechaVencimiento, int vigenciaDias, Integer clienteId, String clienteNombre, String clienteIdentificacion, Integer usuarioId, String creadaPor, List<LineaDeCotizacion> lineas, BigDecimal subtotal, BigDecimal iva, BigDecimal total, String observaciones, Long ventaId, String motivoAnulacion)`. `estado` es uno de `VIGENTE|VENCIDA|CONVERTIDA|ANULADA`, calculado con `:hoy`;
  - `record LineaDeCotizacion(Integer productoId, String codigo, String nombre, int cantidad, BigDecimal precioUnitario, BigDecimal tarifaIva, String tratamientoIva, BigDecimal subtotal, BigDecimal valorIva, BigDecimal precioHoy, boolean disponible)`. En una cotización que no está vencida, `precioHoy` es null y `disponible` es true;
  - `CotizacionDetalle ServicioCotizaciones.crear(Integer empresaId, Integer usuarioId, SolicitudCotizacion s)`;
  - `CotizacionDetalle ServicioCotizaciones.detalle(Integer empresaId, Long id)`;
  - `POST /api/v1/cotizaciones` → 201;
  - `GET /api/v1/cotizaciones/{id}` → 200.

- [ ] **Step 1: tests rojos**
  - **`LineaVendibleTest`**, mismos casos que hoy cubre el registrador:
    - precio null → vacío;
    - precio 0 → vacío;
    - precio > `PRECIO_MAXIMO` → vacío;
    - precio 12 345,4 se redondea a 12 345.
  - **`CotizacionConBaseTest`:**
    - `creaVigenteConNumeroYVencimiento`: con reloj fijo en 2026-04-01 y empresa con 5 días, crea `COT-00000001`, vence el 2026-04-10, estado VIGENTE y no hay movimientos en `movimientos_inventario`;
    - `elImpuestoSeRedondeaComoLaVenta`: 1 × 12 345 al 19 % → `valor_iva` 2 346,00 en la fila y en el detalle;
    - `losPreciosQuedanCongelados`: crear, cambiar la regla o el costo para que el precio de hoy suba, y comprobar que el detalle conserva el anterior;
    - `productoRepetidoSeSuma`: dos líneas del mismo producto (2 + 3) dan una sola línea de 5;
    - `numeracionPorEmpresa`: la empresa A y la empresa B tienen cada una su `COT-00000001`;
    - `vencidaAlLeer`: con el reloj después del vencimiento, el detalle trae `estado` VENCIDA, `precioHoy` lleno y `disponible` true; con el producto desactivado, `disponible` false y `precioHoy` null.
  - **`CotizacionesControladorTest`:**
    - sin líneas → 400;
    - cantidad 0 → 400;
    - super administrador → 403;
    - `CLIENTE` → 403;
    - vendedor → 201;
    - producto inactivo (el servicio lanza `VentaInvalidaException`) → 400;
    - cliente o producto de otra empresa (`RecursoNoEncontradoException`) → 404;
    - id de otra empresa → 404.
- [ ] **Step 2: rojo.**
- [ ] **Step 3: implementar**
  - **`crear`:** `@Transactional(isolation = READ_COMMITTED)`. Orden:
    1. Validar el cliente de la empresa (404).
    2. Agrupar las líneas por producto.
    3. Precios con `preciosVigentes` + `LineaVendible.de`. Sin precio o inactivo: `VentaInvalidaException` con el mensaje `NO_VENDIBLE` del registrador.
    4. Topar con los límites de la venta.
    5. `CalculoDeVenta.calcular(lineas, null)`.
    6. Vigencia: `vigenciaDeLaEmpresa`, `festivosEntre` y `VigenciaHabil.vence`. Avisar en el log si `aniosConFestivos` no cubre los años del rango.
    7. Al final, `tomarSiguiente(empresa, COTIZACION)`.
    8. INSERT de la cabecera, con `subtotal`, `iva`, `descuento=0`, `envio=0`, `vigencia_dias` y `estado='VIGENTE'`.
    9. INSERT de las líneas con `subtotal` y `valor_iva` del cálculo.
  - **`detalle`:** una consulta para la cabecera y una para las líneas. Si está vencida, una más con `preciosVigentes` para `precioHoy`/`disponible`.
- [ ] **Step 4: verde.** También la suite sin base completa: las pruebas de venta existentes siguen verdes tras extraer `LineaVendible`.
- [ ] **Step 5: commit.**

### Task 4: lista, búsqueda por número y anulación (backend)

**Files:**
- Modify:
  - `RepositorioCotizaciones`
  - `ServicioCotizaciones`
  - `CotizacionesControlador`
  - `api/ManejadorErrores.java` (409 de la anulación)
- Create:
  - `api/dto/CotizacionEnLista.java`
  - `api/dto/SolicitudAnulacion.java`
  - `logica/CotizacionNoAnulableException.java`
- Test:
  - `CotizacionesListaConBaseTest.java` (`@Tag("conBase")`)
  - casos nuevos en `CotizacionesControladorTest`

**Interfaces:**
- Produces:
  - `record CotizacionEnLista(Long id, String numero, LocalDate fechaEmision, String clienteNombre, BigDecimal total, LocalDate fechaVencimiento, String estado)`;
  - `GET /api/v1/cotizaciones?estado=VIGENTE|VENCIDA|CONVERTIDA|ANULADA&q=&pagina=&tamano=`: la misma forma de página que `ClientesControlador`. Ordena por `cotizacion_id DESC`;
  - `GET /api/v1/cotizaciones/numero/{numero}` → `CotizacionDetalle`. Normaliza el número: quita `COT-` sin importar mayúsculas y los ceros a la izquierda, y vuelve a formar `COT-%08d`. Un texto no numérico da 404;
  - `POST /api/v1/cotizaciones/{id}/anulacion` con `record SolicitudAnulacion(@NotBlank @Size(max = 200) String motivo)` → 200 `CotizacionDetalle`;
  - `CotizacionDetalle ServicioCotizaciones.porNumero(Integer empresaId, String numero)`;
  - `CotizacionDetalle ServicioCotizaciones.anular(Integer empresaId, Integer usuarioId, boolean esAdministrador, Long id, String motivo)`.

- [ ] **Step 1: tests rojos**
  - **`CotizacionesListaConBaseTest`:**
    - `filtraPorEstado`: con una VIGENTE, una vencida, una CONVERTIDA y una ANULADA, cada filtro devuelve exactamente la suya. La vencida se reconoce con `:hoy`;
    - `buscaPorTexto`: encuentra por número, por nombre del cliente y por identificación;
    - `consultasConstantes`: la cantidad de consultas para 5 y para 50 cotizaciones es la misma (patrón de conteo de `ClientesListaConBaseTest`);
    - `otraEmpresaNoAparece`;
    - `porNumero`: «12», «cot-12» y «COT-00000012» dan la misma; «abc» da 404; el número de otra empresa da 404;
    - `anular`: queda ANULADA con `anulada_por`, `anulada_en` y motivo; anular de nuevo da 409; anular una CONVERTIDA da 409.
  - **`CotizacionesControladorTest`:**
    - motivo vacío → 400;
    - vendedor que no la creó → 403;
    - vendedor creador → 200;
    - admin → 200;
    - `CotizacionNoAnulableException` → 409 con el texto «Solo se anula una cotización vigente o vencida.».
- [ ] **Step 2: rojo.**
- [ ] **Step 3: implementar**
  - **`anular`:** `SELECT … FOR UPDATE` sobre la fila de la empresa (404 si no existe). Luego verificar el permiso, verificar el estado y hacer el UPDATE.
  - **Filtros de estado:**
    - VIGENTE: `estado = 'VIGENTE' AND fecha_vencimiento >= :hoy`;
    - VENCIDA: `estado = 'VIGENTE' AND fecha_vencimiento < :hoy`.
  - **`estado` de cada fila:** se calcula en el SQL con `CASE`.
- [ ] **Step 4: verde.**
- [ ] **Step 5: commit.**

### Task 5: PDF carta

**Files:**
- Create: `backend/src/main/java/co/com/bspstore/sgip/logica/CotizacionPdf.java`. Reutiliza la fuente y la limpieza de texto de `ReciboPdf`: si son privadas, extráelas a un ayudante compartido en el mismo paquete.
- Modify: `CotizacionesControlador`, para agregar `GET /api/v1/cotizaciones/{id}/pdf`.
- Test: `backend/src/test/java/co/com/bspstore/sgip/logica/CotizacionPdfTest.java` (patrón de `ReciboPdfTest`, texto extraído con `PDFTextStripper`).

**Interfaces:**
- Consumes:
  - `CotizacionDetalle` (tarea 3);
  - los datos de la empresa que ya usa `ReciboPdf` (servicio de «Mi empresa»).
- Produces:
  - `byte[] CotizacionPdf.generar(CotizacionDetalle c, <datos de empresa que usa ReciboPdf>)`;
  - la respuesta `application/pdf` con `Content-Disposition: attachment; filename="cotizacion-COT-00000012.pdf"`.

- [ ] **Step 1: tests rojos**
  - `contieneLoQuePideRf39`: el texto contiene:
    - nombre comercial e identificación de la empresa;
    - nombre e identificación del cliente;
    - `COT-00000001`;
    - cada producto;
    - «Subtotal», «IVA 19 %» con su valor, «Total»;
    - «Válida hasta el 10 de abril de 2026».
  - `paginaCarta`: el MediaBox es `PDRectangle.LETTER`.
  - `veinteLineasEnMenosDeTresSegundos`: 20 líneas y `assertTimeoutPreemptively(Duration.ofSeconds(3), …)`.
  - `marcaElEstado`: una anulada contiene «ANULADA»; una vencida, «VENCIDA».
  - `muchasLineasPasanDePagina`: 60 líneas dan más de 1 página y el total aparece una sola vez, en la última.
  - Controlador: tipo de contenido y nombre del archivo; id de otra empresa → 404.
- [ ] **Step 2: rojo.**
- [ ] **Step 3: implementar.** Encabezado con el nombre comercial en grande en el lugar del logo (no hay logo, ver la spec §3), tabla de líneas, totales con el IVA discriminado por tarifa, fecha de vencimiento y observaciones.
- [ ] **Step 4: verde.**
- [ ] **Step 5: commit.**

### Task 6: convertir en venta (backend)

**Files:**
- Create:
  - `api/dto/CotizacionEnVenta.java`
  - `logica/CotizacionNoConvertibleException.java`
- Modify:
  - `api/dto/SolicitudVenta.java` (campo nuevo al final: `@Valid CotizacionEnVenta cotizacion`)
  - `logica/NormalizadorDeVenta.java` (`forma` lo conserva; `huella` lo incluye solo si no es null, como `domicilio`)
  - `logica/RegistradorDeVenta.java`
  - `datos/RepositorioCotizaciones.java`
  - `datos/RepositorioVentas.java` (insertar `cotizacion_id`)
  - `api/ManejadorErrores.java`
- Test:
  - `logica/NormalizadorDeVentaTest.java` (casos nuevos)
  - `datos/VentaConCotizacionConBaseTest.java` (`@Tag("conBase")`)
  - `datos/VentaConCotizacionConcurrenteConBaseTest.java` (`@Tag("conBase")`, patrón de `VentaConcurrenteConBaseTest`)

**Interfaces:**
- Consumes: el esquema de la tarea 1 y `RepositorioCotizaciones` (tareas 3 y 4).
- Produces:
  - `record CotizacionEnVenta(@NotNull @Positive Long id, @NotNull Boolean preciosCongelados)`;
  - `CotizacionBloqueada RepositorioCotizaciones.bloquear(Integer empresaId, Long id)` (`FOR UPDATE`; vacío → 404), con `record CotizacionBloqueada(Long id, String numero, String estado, LocalDate fechaVencimiento, Integer clienteId, Map<Integer, LineaCongelada> lineas)`;
  - `record LineaCongelada(int cantidad, BigDecimal precioUnitario, BigDecimal tarifaIva, String tratamientoIva)`;
  - `void RepositorioCotizaciones.marcarConvertida(Integer empresaId, Long id)`;
  - la venta guardada con `ventas.cotizacion_id`.

**Textos exactos (409 o 400):**
- 409 «La cotización {numero} ya se convirtió en venta.»
- 409 «La cotización {numero} está anulada.»
- 409 «La cotización venció mientras la cobrabas; vuelve a cargarla.»
- 400 «El cliente de la venta no es el de la cotización.»
- 400 «La cotización tiene {n} unidades de {nombre}; no se pueden vender más con sus precios.»

- [ ] **Step 1: tests rojos**
  - **`NormalizadorDeVentaTest`:**
    - `conservaLaCotizacion`;
    - `laHuellaSinCotizacionNoCambia`: la huella de una solicitud sin cotización es idéntica a la de antes del cambio (valor fijo calculado en la rama base);
    - `laHuellaConCotizacionDifiere`.
  - **`VentaConCotizacionConBaseTest`** (reloj fijo, turno abierto, existencia suficiente):
    - `vigenteCobraElTripleCongelado`: cotizar a 10 000 al 19 %, cambiar el precio a 12 000 y la tarifa a 5 %, y cobrar con `preciosCongelados=true`. La línea de la venta queda con 10 000 y 19 %, `ventas.cotizacion_id` lleno y la cotización CONVERTIDA;
    - `vigenteSinReglaDePrecioHoySeCobra`: el producto sigue activo pero sin precio hoy, y se cobra con el congelado;
    - `vigenteConProductoInactivo` → 400;
    - `vencidaCobraPrecioDeHoy` (`preciosCongelados=false`) → precio de hoy, CONVERTIDA;
    - `vencioEntreCargarYCobrar`: reloj al día siguiente del vencimiento y `preciosCongelados=true` → 409 con el texto exacto; la cotización sigue VIGENTE en la fila;
    - `vigenteCobradaAPreciosDeHoy` (`preciosCongelados=false`) → precio de hoy y CONVERTIDA;
    - `convertida` → 409;
    - `anulada` → 409;
    - `clienteDistinto` → 400;
    - `cantidadMayor` → 400;
    - `cantidadMenor` → precio congelado;
    - `productoAgregado` → precio de hoy;
    - `existenciaInsuficienteSinEncargo` → 409, y la cotización sigue VIGENTE y sin venta;
    - `otraEmpresa` → 404;
    - `impuestoConCentavos`: 1 × 12 345 al 19 % → `valor_iva` de la venta 2 346, igual al de la cotización.
  - **`VentaConCotizacionConcurrenteConBaseTest`:**
    - `dosClavesDistintas`: dos hilos con un `CyclicBarrier` dan exactamente una venta, un 409 y la cotización CONVERTIDA una sola vez;
    - `mismaClaveDosVeces`: dos hilos con la misma clave dan 201 y 200 con el mismo `ventaId`, y una sola venta en la base.
- [ ] **Step 2: rojo.**
- [ ] **Step 3: implementar**, en `RegistradorDeVenta.registrar`:
  - **Orden:** turno (`FOR SHARE`) → `cotizaciones.bloquear` (si viene el bloque) → productos → búsqueda por clave de idempotencia (si existe, devolver la venta) → validaciones de la cotización, en el orden de la spec §5.5 pasos 5 y 6 → precios (paso 7) → lo demás, igual que hoy → insertar la venta con `cotizacion_id` → `marcarConvertida`.
  - **Duplicado de `uq_ventas_cotizacion`:** atrapar la `DuplicateKeyException` cuyo mensaje nombre esa restricción y lanzar `CotizacionNoConvertibleException` (409). Hoy cae en el `throw` genérico de las líneas ~245-249.
  - **Documentación:** actualizar el javadoc del orden de bloqueos del registrador.
- [ ] **Step 4: verde.** Pruebas de las tareas 3–6 con base y la suite sin base completa.
- [ ] **Step 5: commit.**

### Task 7: sección «Cotizaciones» (web)

**Files:**
- Modify:
  - `web/src/servicios/api.js`
  - `web/src/secciones.js` (agregar `{ ruta: '/cotizaciones', etiqueta: 'Cotizaciones', roles: VENDEN, grupo: 'Operación' }` justo después de «Devoluciones»)
  - `web/src/App.jsx` (ruta perezosa `/cotizaciones`)
  - las pruebas de menú que listan las secciones
- Create:
  - `web/src/paginas/cotizaciones/Rutas.jsx`
  - `web/src/paginas/cotizaciones/ListaCotizaciones.jsx`
  - `web/src/paginas/cotizaciones/NuevaCotizacion.jsx`
  - `web/src/paginas/cotizaciones/DetalleCotizacion.jsx`
  - `web/src/paginas/cotizaciones/cotizaciones.prueba.jsx`

**Interfaces:**
- Consumes: las rutas de las tareas 3–5.
- Produces, en `api.js`:
  - `api.cotizaciones({estado, q, pagina})`
  - `api.cotizacion(id)`
  - `api.cotizacionPorNumero(numero)`
  - `api.crearCotizacion({clienteId, lineas, observaciones})`
  - `api.anularCotizacion(id, motivo)`
  - `api.descargarCotizacionPdf(id)`: con `descargar(…)`, respaldo `cotizacion.pdf` y falla «No se pudo descargar la cotización.».

- [ ] **Step 1: tests rojos** en `cotizaciones.prueba.jsx`, con la API simulada:
  - la lista muestra número, cliente, total, vence y la pastilla de estado. El filtro de estado y la búsqueda van en la URL;
  - el estado vacío dice «Todavía no hay cotizaciones.», con el botón «Nueva cotización»;
  - la nueva cotización:
    - busca un cliente (o pulsa «Consumidor final») y agrega dos productos con el buscador de la caja;
    - cambiar la cantidad recalcula el total con `calculo.js` de la caja;
    - muestra «Vence: …» con la vigencia que devuelve el servidor al guardar;
    - «Guardar cotización» llama a `crearCotizacion` sin precios y navega al detalle;
  - el 400 del servidor se muestra en un `Aviso`;
  - el detalle:
    - «Descargar PDF» llama a `descargarCotizacionPdf`;
    - «Anular» abre una confirmación con motivo obligatorio y llama a `anularCotizacion`;
    - el 409 se muestra;
  - el vendedor no ve «Anular» en una cotización ajena (`usuarioId` distinto del de la sesión).
- [ ] **Step 2: rojo.**
- [ ] **Step 3: implementar** con la skill `impeccable` (modo Operate, diseño nuevo), los componentes `comun/*` y `TablaOTarjetas`, y los buscadores de la caja (`BuscadorProducto`, búsqueda de cliente de `DatosCliente`).
- [ ] **Step 4: verde:** vitest, lint y build.
- [ ] **Step 5: capturas.** 1440 px y 390 px de la lista, la nueva y el detalle, con datos simulados. Van a `.superpowers/sdd/cotizaciones/capturas/`, no se commitean.
- [ ] **Step 6: commit.**

### Task 8: «Cargar cotización» en la caja (web)

**Files:**
- Create:
  - `web/src/paginas/caja/CargarCotizacion.jsx`
  - `web/src/paginas/caja/CargarCotizacion.prueba.jsx`
- Modify:
  - `web/src/paginas/caja/Caja.jsx`
  - `web/src/paginas/caja/Carrito.jsx` (tope de cantidad)
  - `web/src/paginas/caja/DatosCliente.jsx` (bloqueo)
  - `web/src/paginas/caja/Cobro.jsx`, o donde se arma la solicitud de venta (bloque `cotizacion`)
  - sus pruebas

**Interfaces:**
- Consumes:
  - `api.cotizacionPorNumero` (tarea 7);
  - el bloque `cotizacion: {id, preciosCongelados}` de la venta (tarea 6).

**Textos exactos:**
- vigente: «Cotización {numero} vigente hasta el {d MMM}.»
- vencida: «Esta cotización venció el {d MMM}; se cobra con los precios de hoy.»
- convertida o anulada: el texto del servidor, sin cargar nada.
- línea no disponible: «{nombre} ya no está disponible y no se cargó.»

- [ ] **Step 1: tests rojos**
  - el botón «Cargar cotización» abre el diálogo, se escribe «12» y se llama a `cotizacionPorNumero('12')`;
  - **vigente:** carrito con los precios congelados, cliente puesto y bloqueado, y aviso de vigente;
  - **vencida:** precios `precioHoy`, aviso de vencida y la línea no disponible omitida con su aviso;
  - **convertida:** muestra el error y el carrito no cambia;
  - **cantidad:** no deja subir por encima de lo cotizado, pero sí bajar y quitar;
  - **cobrar:** la solicitud lleva `cotizacion: {id, preciosCongelados: true}` (false si estaba vencida) y ningún precio;
  - **409 «venció mientras la cobrabas»:** se muestra y ofrece «Volver a cargar»;
  - **«Quitar cotización»:** desbloquea el cliente y vuelve a los precios de hoy.
- [ ] **Step 2: rojo.**
- [ ] **Step 3: implementar** con `impeccable`, el diseño nuevo y el `Dialogo.jsx` existente.
- [ ] **Step 4: verde:** vitest, lint y build.
- [ ] **Step 5: capturas** de la caja con una cotización vigente y con una vencida.
- [ ] **Step 6: commit.**
