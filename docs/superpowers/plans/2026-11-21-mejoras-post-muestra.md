# Mejoras posteriores a la muestra — plan de implementación

> **Para agentes:** SUB-SKILL REQUERIDA: superpowers:subagent-driven-development. Los pasos usan casillas (`- [ ]`).

**Objetivo:** cuatro mejoras pedidas tras la muestra:
- M4: devolución del administrador sin turno, sin efectivo;
- M1: pantalla de clientes;
- M2: editar «Mi empresa», con copia de los datos de la empresa en la venta;
- M3: plantilla de precios con los productos del proveedor, y rechazo de las filas de otro proveedor.

**Arquitectura:** se sigue el patrón del backend (controlador → servicio en `logica/` → repositorio JDBC en `datos/`, con migraciones Flyway) y del web (página en `paginas/`, servicio en `servicios/api/`, sección en `secciones.js`). Cada mejora es una rama `feat/*` desde `develop`, con merge `--no-ff` tras `code-reviewer`.

**Stack:** Java 25, Spring Boot 4, MySQL 9.7, Flyway, Apache POI (ya presente); React 18.3.1, Vite y Vitest.

**Spec:** `docs/superpowers/specs/2026-11-21-mejoras-post-muestra-design.md` (v2). El ejecutor lee la spec y este plan.

## Restricciones globales

- **Requisito previo:** la entrega 1 de la caja (`feature/caja` y `feature/caja-pantallas`) ya está unida a `develop`. Ninguna tarea empieza antes.
- **Roles:** con los nombres del código: `ADMINISTRADOR`, `VENDEDOR`, `CLIENTE` y `SUPER_ADMIN`. Toda ruta nueva bajo `/api/v1/caja` lleva su fila en `CajaRolesTest`.
- **Tenencia:** la empresa sale siempre del contexto de seguridad. Toda consulta filtra por `empresa_id`.
- **TDD:** el commit `test:` en rojo va primero. Cada tarea termina con:
  - `./mvnw -B test -DexcludedGroups=conBase` en verde;
  - `./mvnw -B test` completa en verde, contra un mysqld aislado propio en un puerto 34xxx libre, configurado solo con variables de entorno (nunca leer ni crear `application.yml` ni `.env`);
  - en el web, `npx vitest run`, `npm run lint` y `npm run build`.
- **Migraciones:** cada rama toma el siguiente número libre de `backend/src/main/resources/db/migration` al nacer. La que se une segunda renumera la suya antes del merge. Nunca se edita una migración ya unida.
- **Interfaz:** se usa la skill `impeccable` en modo Operate, los colores solo de `web/src/estilos/tokens.css`, y textos en español con tildes. El diseño de referencia es el Figma nuevo.
- **Rendimiento:** el número de consultas es constante en listados, probado con un conteo como en las tareas de la caja.
- **Commits:** Conventional Commits en español, con el trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Puntos de revisión

1. **Admin sin turno, con PIN.** Un VENDEDOR sin turno no debe poder devolver por mandar el PIN de un admin: 409 (tarea 1).
2. **Efectivo de cero.** Un reembolso EFECTIVO de 0 junto a uno con tarjeta no cuenta como efectivo: se ignora o se rechaza con 400, nunca exige turno (tarea 1, prueba `efectivoEnCeroNoExigeTurno`).
3. **Búsqueda de clientes con comodines.** `buscar` con `%` o `_` se trata como texto literal (tarea 3, `buscarConComodinesEsLiteral`).
4. **Plantilla con referencia repetida o vacía en el catálogo.** Una plantilla de ida y vuelta no genera rechazos. Si dos productos del proveedor comparten código, la plantilla los escribe por código y la prueba de ida y vuelta lo cubre (tarea 8).
5. **Cambio de NIT a uno con espacios o puntos.** `900.123.456-7` y ` 9001234567 ` se normalizan antes de validar el dígito de verificación (tarea 5, `nitConPuntosYEspaciosSeNormaliza`).

---

## Rama `feat/devolucion-sin-turno` (M4)

### Tarea 1: el backend permite la devolución del admin sin turno, sin efectivo

**Archivos:**
- Modificar: `backend/src/main/java/co/com/bspstore/sgip/logica/RegistradorDeDevolucion.java`. Hoy está en las líneas 223-224 (`if (reembolsado.signum() > 0 && turno.isEmpty()) throw new SinTurnoAbiertoException()`) y en el javadoc de las líneas 53-90.
- Crear: `logica/TurnoRequeridoParaEfectivoException.java`.
- Modificar: `api/ManejadorErrores.java`, que la mapea a 409 con el type `urn:sgip:caja:turno-requerido-para-efectivo`.
- Modificar: `docs/decisiones/ADR-0032-*.md` y `ADR-0033-*.md`, con una nota de enmienda que remite a la spec.
- Pruebas: `logica/RegistradorDeDevolucionSinTurnoTest.java` (unitaria) y `datos/DevolucionSinTurnoConBaseTest.java` (con base, `@Tag("conBase")`).

**Interfaces:**
- Usa: `ServicioTurnos.intentarTurnoAbierto(): Optional<Long>`; el rol del usuario en sesión, por el mismo mecanismo con que los servicios ya preguntan si es administrador; y el medio de cada reembolso, con su `TipoMedio`.
- Produce: la regla nueva:
  - sin turno y `rolEnSesion == ADMINISTRADOR` y ningún reembolso con `TipoMedio.EFECTIVO` y `monto > 0` → se registra con `cierre_id = null`;
  - sin turno y con efectivo → `TurnoRequeridoParaEfectivoException` («Abre un turno de caja para devolver efectivo.»);
  - sin turno y VENDEDOR → `SinTurnoAbiertoException`, como hoy.

- [ ] **Paso 1: las pruebas en rojo.** Unitarias:
  - `adminSinTurnoConTarjetaRegistra`
  - `adminSinTurnoConSaldoRegistra`
  - `adminSinTurnoTarjetaYSaldoRegistra`
  - `adminSinTurnoConEfectivoEs409TurnoRequerido`
  - `efectivoEnCeroNoExigeTurno`
  - `vendedorSinTurnoConPinDeAdminYTarjetaEs409SinTurno`
  - `ventaDeOtraEmpresaEs404AntesQueElTurno`

  Con base:
  - `devolucionSinTurnoQuedaConCierreNulo`
  - `cierreDeUnTurnoNoCuentaLaDevolucionSinTurno`
  - `reintentoConLaMismaClaveSinTurnoDevuelve200`
  - `aperturaDeTurnoConcurrenteNoAsociaLaDevolucion`: dos hilos, la apertura y la devolución, con un orden forzado como en las pruebas de la tarea 9.
- [ ] **Paso 2:** ejecútalas. Deben fallar por el 409 actual.
- [ ] **Paso 3:** implementa la regla en `RegistradorDeDevolucion`, sin cambiar el orden de bloqueos del ADR-0030. Actualiza el javadoc y los ADR.
- [ ] **Paso 4:** la suite completa en verde.
- [ ] **Paso 5:** los commits `test:` y `feat:`.

### Tarea 2: la pantalla de devolución deshabilita el efectivo si el admin no tiene turno

**Archivos:**
- Modificar: `web/src/paginas/caja/Devolucion.jsx` y `Devolucion.prueba.jsx`.
- Modificar: `web/src/paginas/caja/devolucion.js`, si el reparto vive ahí.

**Interfaces:**
- Usa: la consulta del turno actual que ya usa la caja (`servicios/api/caja.js`; un 404 significa «sin turno»).
- Usa: el 409 `urn:sgip:caja:turno-requerido-para-efectivo` de la tarea 1.

- [ ] **Paso 1: las pruebas en rojo.**
  - `adminSinTurnoVeEfectivoDeshabilitadoConAyuda`: el campo «Efectivo» está deshabilitado y su ayuda dice «Para devolver efectivo, abre un turno de caja».
  - `adminSinTurnoPuedeConfirmarConTarjeta`
  - `elVendedorSinTurnoSigueViendoElAvisoActual`
  - `el409TurnoRequeridoMuestraSuMensaje`
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** vitest, lint y build en verde.
- [ ] **Paso 5:** los commits.

---

## Rama `feat/pantalla-clientes` (M1)

### Tarea 3: el listado paginado de clientes con su saldo

**Archivos:**
- Modificar: `api/ClientesControlador.java`, que añade `GET /api/v1/caja/clientes/lista`. El GET por documento no se toca.
- Modificar: `logica/ServicioClientes.java` y `datos/RepositorioClientes.java`.
- Crear: `api/dto/PaginaDeClientes.java` y `api/dto/ClienteEnLista.java`.
- Migración: el índice que haga falta, justificado con `EXPLAIN` en el informe.
- Pruebas: `ClientesListaConBaseTest`, una fila nueva en `CajaRolesTest` y `ServicioClientesListaTest`.

**Interfaces:**
- Produce:
  - `record ClienteEnLista(Integer id, TipoDocumento tipo, String identificacion, String nombre, String telefono, String correo, BigDecimal saldoAFavor, boolean editable)`. `editable` es falso solo para el consumidor final.
  - `record PaginaDeClientes(List<ClienteEnLista> elementos, int pagina, int tamano, long total)`.
  - `PaginaDeClientes listar(String buscar, int pagina, int tamano)`.
  - Valores: `tamano` 20 por defecto, máximo 100, y 400 si es menor que 1. `pagina` empieza en 0.
- La búsqueda es por prefijo en `identificacion` o por contenido en `nombre`, sin distinguir mayúsculas. El saldo es el mismo cálculo de `/caja/clientes/{id}/saldo`, agregado en la misma consulta.

- [ ] **Paso 1: las pruebas en rojo.**
  - `listaSoloLosDeLaEmpresa`
  - `buscaPorNombreYPorDocumento`
  - `buscarConComodinesEsLiteral`
  - `paginaYTotalCorrectos`
  - `saldoIgualAlDelEndpointDeSaldo`
  - `consultasConstantesCon1Y50Clientes`
  - `tamanoMayorQue100SeLimita`
  - `consumidorFinalNoEsEditable`
  - Las filas de roles: CLIENTE y SUPER_ADMIN reciben 403; ADMINISTRADOR y VENDEDOR, 200.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** la suite completa en verde.
- [ ] **Paso 5:** los commits.

### Tarea 4: la pantalla «Clientes»

**Archivos:**
- Crear: `web/src/paginas/clientes/Clientes.jsx`, `FormularioCliente.jsx` y sus `*.prueba.jsx`.
- Modificar:
  - `web/src/servicios/api/caja.js`, con `listarClientes({buscar, pagina})`, `crearCliente` y `actualizarCliente`, que ya existen para la caja (reutilizarlos);
  - `web/src/secciones.js`, con la sección `{ ruta: '/clientes', etiqueta: 'Clientes', grupo: 'Operación' }` para ADMINISTRADOR y VENDEDOR;
  - `web/src/App.jsx`;
  - `web/src/muestra.js`, solo si la rama de la muestra se vuelve a integrar; aquí no.

**Interfaces:**
- Usa: la tarea 3, y `DatosCliente.jsx` de la caja, que tiene las validaciones y la casilla de la Ley 1581. Se reutiliza o se extrae un componente común; no se copia.

- [ ] **Paso 1: las pruebas en rojo.**
  - `listaYBusca`
  - `paginaSiguiente`
  - `creaClienteConAutorizacionObligatoria`
  - `editaElDocumento`: permitido siempre, por decisión del usuario.
  - `consumidorFinalSinBotonEditar`
  - `muestraElSaldoSinEditarlo`
  - `estadoVacioYError`
  - `elVendedorVeLaSeccion`
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** vitest, lint y build en verde, con capturas a 1440 y a 390.
- [ ] **Paso 5:** los commits.

---

## Rama `feat/editar-empresa` (M2)

### Tarea 5: el esquema, la edición por el admin y la identidad por plataforma

**Archivos:**
- Migración `V<n>__datos_de_empresa_y_copia_en_venta.sql`:
  - `empresas` gana `nombre_comercial VARCHAR(150) NULL` y `direccion VARCHAR(200) NULL`;
  - `ventas` gana `empresa_nombre VARCHAR(200) NULL`, `empresa_nit VARCHAR(20) NULL`, `empresa_direccion VARCHAR(200) NULL`, `empresa_ciudad VARCHAR(100) NULL` y `empresa_telefono VARCHAR(30) NULL`;
  - `auditoria_plataforma` amplía su ENUM y sus CHECK con la acción `CAMBIO_IDENTIDAD_EMPRESA`.
  - Antes de escribir la migración, se leen las definiciones reales de las columnas y los CHECK.
- Crear:
  - `api/EmpresaControlador.java`: `GET /api/v1/empresa` se mueve aquí si hoy vive en otro lado, más `PUT /api/v1/empresa`;
  - `api/dto/SolicitudDatosEmpresa.java`;
  - `logica/ServicioEmpresa.java`;
  - `logica/ValidadorNit.java`.
- Modificar:
  - `api/PlataformaControlador.java`, con `PUT /api/v1/plataforma/empresas/{id}/identidad`;
  - `logica/ServicioPlataforma.java`;
  - `modelo/AccionPlataforma.java`, con `CAMBIO_IDENTIDAD_EMPRESA`;
  - `datos/EmpresaRepositorio.java`;
  - `api/dto/EmpresaResumen.java`.
- Pruebas: `ValidadorNitTest`, `EmpresaEdicionAutorizacionTest`, `EmpresaEdicionConBaseTest` e `IdentidadEmpresaConBaseTest`.

**Interfaces:**
- Produce:
  - `record SolicitudDatosEmpresa(@Size(max=150) String nombreComercial, @Size(max=200) String direccion, @Size(max=100) String ciudad, @Size(max=30) String telefono, @Email @Size(max=150) String correoContacto)`, anotado con `@JsonIgnoreProperties(ignoreUnknown = false)`. Un campo desconocido, como `nit`, da 400. Los textos se recortan; los vacíos se guardan como null.
  - `record SolicitudIdentidad(@NotBlank String nit, @NotBlank @Size(max=200) String razonSocial, @NotBlank @Size(min=10, max=300) String motivo)`.
  - `ValidadorNit.normalizar(String): String` y `ValidadorNit.esValido(String): boolean`, con el dígito de verificación por módulo 11 de la DIAN:
    - pesos, de derecha a izquierda sobre los dígitos sin el DV: 3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71;
    - `r = suma % 11`; `DV = r ≥ 2 ? 11 − r : r`;
    - el formato guardado es el que ya usa la columna `empresas.nit` (se verifica en los datos semilla).
  - Errores:
    - 400 `urn:sgip:empresa:nit-invalido`;
    - 409 `urn:sgip:empresa:nit-repetido`.
  - Auditoría: una fila por campo cambiado, con el valor anterior, el nuevo, el motivo, el usuario y la fecha, en la misma transacción.

- [ ] **Paso 1: las pruebas en rojo.**
  - `ValidadorNitTest`:
    - `nitValidoConDv`, con al menos 3 NIT reales de ejemplo calculados a mano en la prueba;
    - `dvIncorrectoEsInvalido`;
    - `nitConPuntosYEspaciosSeNormaliza`;
    - `letrasSonInvalidas`.
  - Del admin:
    - `adminEditaDatosYSeVenEnElResumen`;
    - `adminQueMandaNitRecibe400`;
    - `adminQueMandaRazonSocialRecibe400`;
    - `vendedorClienteYSuperAdminReciben403EnPutEmpresa`;
    - `correoInvalidoEs400`;
    - `vaciosSeGuardanComoNull`.
  - De plataforma:
    - `superAdminCambiaIdentidadYAudita`, que comprueba 2 filas de auditoría cuando cambian los dos campos;
    - `sinMotivoEs400`;
    - `nitRepetidoEs409`;
    - `adminRecibe403EnIdentidad`;
    - `empresaInexistenteEs404`.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** la suite completa en verde.
- [ ] **Paso 5:** los commits.

### Tarea 6: la copia de la empresa en la venta, que alimenta el recibo

**Archivos:**
- Modificar: `logica/RegistradorDeVenta.java` y `datos/RepositorioVentas.java`, para guardar la copia al insertar la venta.
- Modificar: `logica/ServicioRecibos.java`, que lee la copia y, si es nula, usa los datos actuales.
- Modificar: `api/dto/Recibo.java`, con `direccionEmpresa` y `telefonoEmpresa` añadidos al final.
- Modificar: `logica/ReciboPdf.java`, que imprime la dirección y el teléfono bajo el NIT.
- Pruebas: `CopiaDeEmpresaEnVentaConBaseTest` y `ServicioRecibosTest`, ampliada.

**Interfaces:**
- Usa: las columnas de la tarea 5. `empresa_nombre = COALESCE(nombre_comercial, razon_social)`.
- Lee la empresa sin bloqueo, sin `FOR UPDATE` ni `FOR SHARE`. No altera el orden del ADR-0030.

- [ ] **Paso 1: las pruebas en rojo.**
  - `laVentaGuardaLosDatosDeLaEmpresaDeEseDia`
  - `cambiarLaEmpresaDespuesNoCambiaElReciboViejo`
  - `ventaSinCopiaUsaLosDatosActuales`
  - `elReciboMuestraDireccionYTelefono`
  - `consultasDelReciboSiguenConstantes`
  - `registrarVentaSigueConElMismoNumeroDeConsultasMasUna`: se fija el conteo nuevo.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** la suite completa en verde.
- [ ] **Paso 5:** los commits.

### Tarea 7: «Mi empresa» editable y la identidad en plataforma

**Archivos:**
- Modificar: `web/src/paginas/Empresa.jsx` y `Empresa.prueba.jsx`.
- Modificar: `web/src/paginas/plataforma-empresas/FichaEmpresa.jsx` y su prueba.
- Modificar: `web/src/paginas/caja/ReciboImprimible.jsx`, que muestra la dirección y el teléfono de la empresa si existen.

**Interfaces:**
- Usa: `PUT /api/v1/empresa` y `PUT /api/v1/plataforma/empresas/{id}/identidad` de la tarea 5.

- [ ] **Paso 1: las pruebas en rojo.**
  - `adminEditaYGuarda`
  - `nitYRazonSocialSonSoloLecturaConNotaDeSoporte`: la nota dice «Para cambiarlos, contacta a soporte».
  - `vendedorVeSoloLectura`
  - `superAdminCambiaIdentidadConMotivoObligatorio`
  - `nitInvalidoMuestraElError`
  - `reciboMuestraDireccionYTelefono`
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** vitest, lint y build en verde, con capturas.
- [ ] **Paso 5:** los commits.

---

## Rama `feat/plantilla-precios` (M3)

### Tarea 8: el rechazo de filas de otro proveedor y la plantilla en xlsx

**Archivos:**
- Modificar: `logica/ServicioImportacion.java` y `datos/RepositorioImportacion.java`.
  - El catálogo indexado (líneas 61-80) trae también `proveedor_id`.
  - Una fila cuyo producto tiene otro `proveedor_id` se rechaza con `PRODUCTO_DE_OTRO_PROVEEDOR`.
- Migración: amplía el ENUM o CHECK de causas de rechazo con `PRODUCTO_DE_OTRO_PROVEEDOR`. Antes se verifica dónde guarda la V30 las causas.
- Crear: `logica/GeneradorPlantillaPrecios.java`, que importa POI y vive junto a `LectorHojaDeCalculo`.
- Modificar: `api/ImportacionControlador.java`, con `GET /api/v1/importaciones/plantilla?proveedorId=` solo para ADMINISTRADOR.
- Modificar: `docs/decisiones/ADR-0007-*.md`, con la enmienda «POI solo en las clases de lectura y escritura de hojas de cálculo».
- Pruebas: `GeneradorPlantillaPreciosTest`, `PlantillaConBaseTest` e `ImportacionOtroProveedorConBaseTest`. El ArchUnit que acota POI se actualiza si existe.

**Interfaces:**
- Produce: `void escribir(Integer empresaId, Integer proveedorId, OutputStream salida)`, en streaming con `SXSSFWorkbook`, con una ventana de 100 filas, y `dispose()` en un `finally`.
  - Hoja 0 «Precios»: los encabezados `Referencia`, `Descripción` y `Costo`. Trae los productos activos del proveedor con su `codigo`, su nombre y su `costo`. Un costo nulo o cero va en una celda vacía.
  - Hoja 1 «Instrucciones».
  - Los textos van como celdas de texto. Si un valor empieza por `=`, `+`, `-` o `@`, se le antepone `'`.
- Respuesta HTTP:
  - `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`;
  - `Content-Disposition: attachment; filename="plantilla-precios-<nombre del proveedor en ASCII, minúsculas y con guiones>.xlsx"`.
  - Un proveedor ajeno o el reservado (`SIN-PROVEEDOR`) da 404. Un `proveedorId` ausente da 400.

- [ ] **Paso 1: las pruebas en rojo.**
  - `filaDeOtroProveedorSeRechazaYElRestoSeAplica`
  - `plantillaTraeLosProductosDelProveedorConSuCosto`
  - `hojaCeroEsPrecios`
  - `costoNuloVaVacio`
  - `proveedorSinProductosDaSoloEncabezados`
  - `valorQueEmpiezaConIgualNoEsFormula`
  - `idaYVueltaSinCambiosNoRechazaNiCambiaCostos`
  - `consultasConstantesCon1Y1000Productos`
  - `memoriaAcotadaCon20000Productos`: el crecimiento del montón medido es menor de 64 MB.
  - `proveedorAjenoEs404`
  - `proveedorReservadoEs404`
  - `vendedorRecibe403`
  - `contentDispositionConNombreSeguro`
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** la suite completa en verde.
- [ ] **Paso 5:** los commits.

### Tarea 9: el botón de plantilla y la causa nueva en la pantalla

**Archivos:**
- Modificar: `web/src/paginas/Importacion.jsx` y `Importacion.prueba.jsx`.
  - `PASTILLAS` y `QUE_HACER` reciben la causa `PRODUCTO_DE_OTRO_PROVEEDOR`, con el texto «De otro proveedor» y la indicación «Esta referencia pertenece a otro proveedor; súbela en la lista de ese proveedor».
- Modificar: `web/src/servicios/api.js`, si hace falta descargar un binario.

**Interfaces:**
- Usa: la tarea 8.

- [ ] **Paso 1: las pruebas en rojo.**
  - `alElegirProveedorApareceDescargarPlantilla`: el botón dice «Descargar plantilla con mis productos».
  - `laDescargaPideLaRutaConElProveedor`
  - `errorDeDescargaSeMuestra`
  - `causaDeOtroProveedorSeMuestraConSuIndicacion`
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** vitest, lint y build en verde.
- [ ] **Paso 5:** los commits.

---

## Orden de ejecución

1. Unir la caja a `develop`. Lo hace el orquestador.
2. Tarea 1 → 2, en `feat/devolucion-sin-turno`.
3. Tarea 3 → 4, en `feat/pantalla-clientes`.
4. Tarea 5 → 6 → 7, en `feat/editar-empresa`.
5. Tarea 8 → 9, en `feat/plantilla-precios`.

M1 y M3 pueden ir en paralelo con las demás, cada una en su worktree y con un solo escritor por rama. Cada rama termina con su revisión `code-reviewer` sobre `develop...feat/*`, la suite completa, la CI en verde y el merge `--no-ff`.
