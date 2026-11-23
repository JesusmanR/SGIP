# Mejoras posteriores a la muestra: plan de implementación (v2)

> **Para agentes:** SUB-SKILL REQUERIDA: superpowers:subagent-driven-development. Los pasos usan casillas (`- [ ]`).

**Objetivo:**
- **M4:** devolución del administrador sin turno y sin efectivo.
- **M1:** pantalla de clientes.
- **M2:** editar «Mi empresa», con la identidad NIT/CC por plataforma y una copia de los datos de la empresa en cada venta.
- **M3:** plantilla de precios con los productos del proveedor, rechazo de filas de otro proveedor, y asignación de proveedor a los productos sin proveedor.

**Arquitectura:** cada mejora sigue los patrones existentes:
- backend: controlador → servicio en `logica/` → repositorio JDBC en `datos/`, con Flyway;
- web: página en `paginas/`, cliente HTTP en `servicios/api/`, menú en `secciones.js`.

Cada mejora va en su propia rama `feat/*`, que nace de `develop`.

**Stack:** Java 25, Spring Boot 4.1, MySQL 9.7, Flyway y Apache POI, todos ya presentes; React 18.3.1, Vite y Vitest.

**Spec:** `docs/superpowers/specs/2026-11-21-mejoras-post-muestra-design.md`. Esta v2 del plan incorpora la revisión EGM `.superpowers/sdd/mejoras-post-muestra/egm-review-plan.md` y las decisiones del usuario del 2026-11-21: NIT o CC sin dígito de verificación, asignación a mano del proveedor, y productos creados solo desde el catálogo.

## Restricciones globales

- **Paso 0 (puerta).** Ninguna tarea empieza hasta que, en `develop`:
  - existan `backend/src/main/resources/db/migration/V46__devoluciones_y_saldo_a_favor.sql`, `RegistradorDeDevolucion`, `ClientesControlador`, `ServicioRecibos` y `CajaRolesTest`;
  - no existan `ServicioProductoNuevo` ni `ProductoNuevo.jsx`, que retira la ronda final de la caja;
  - la suite completa esté en verde.

  Toda referencia de este plan es por **símbolo** (clase, método o record), nunca por número de línea. Antes de empezar, se re-verifica cada afirmación contra ese `develop`.
- **Migraciones asignadas:**
  - M4: ninguna;
  - M1: `V47`, solo si el `EXPLAIN` exige un índice (si no, no hay migración y el número queda libre);
  - M2: `V48`;
  - M3: `V49`.

  Antes de cada merge se comprueba que no haya dos `V<n>` iguales. Nunca se edita una migración ya unida.
- **Roles:** `ADMINISTRADOR`, `VENDEDOR`, `CLIENTE` y `SUPER_ADMIN`.
  - Toda ruta nueva bajo `/api/v1/caja` lleva su fila `Fila(...)` en `CajaRolesTest`. Esa prueba afirma «ni 401 ni 403» para los roles permitidos; el 200 real se prueba en las pruebas con base.
  - El rol del usuario en sesión se lee con el helper `ContextoEmpresa.esAdministrador()`, que se extrae de `ServicioTurnos` en la tarea 1 y se reutiliza.
- **Tenencia:** la empresa sale del contexto de seguridad, y toda consulta filtra por `empresa_id`.
- **Ciclo de cada tarea:** primero el commit `test:` en rojo, ejecutado para ver que falla por la razón correcta. Después `feat:` o `fix:`, y `refactor:` si hace falta. La tarea termina cuando pasan:
  - `./mvnw -B test -DexcludedGroups=conBase`;
  - `./mvnw -B test` completa, contra un mysqld aislado propio en un puerto 34xxx libre, solo con variables de entorno (la suite NO levanta su propio mysqld; nunca leer ni crear `application.yml` ni `.env`);
  - en el web: `npx vitest run`, `npm run lint`, `npm run build` y capturas a 1440 y a 390 de cada pantalla tocada.
- **Interfaz:** la skill `impeccable` en modo Operate, colores solo de `tokens.css`, español con tildes y el Figma nuevo.
- **Rendimiento:** el número de consultas es constante en listados, medido con el mismo mecanismo de conteo que las pruebas de la caja.
- **Commits:** Conventional Commits en español, con el trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Hecho:** la mejora está unida a `develop` con `--no-ff`, después de que `code-reviewer` revise `develop...feat/*`, la suite esté en verde y la CI también.

## Puntos de revisión

- **Admin y vendedor con PIN:** un vendedor sin turno no puede devolver mandando el PIN de un admin (tarea 1).
- **Comodines en la búsqueda:** `%` y `_` se buscan como texto literal (tarea 3).
- **Ida y vuelta de la plantilla:** con costos positivos, sin rechazos (tarea 8).
- **Formato del documento:** con puntos, espacios o guiones se normaliza; con letras da 400 (tarea 5).
- **Recibo nuevo frente a reimpresión:** las dos rutas muestran los mismos datos de la empresa (tarea 7).

---

## Rama `feat/devolucion-sin-turno` (M4)

### Tarea 1: backend: devolución del admin sin turno y sin efectivo

**Archivos:**
- Modificar `logica/RegistradorDeDevolucion`:
  - la condición `reembolsado.signum() > 0 && turno.isEmpty()` → `SinTurnoAbiertoException`;
  - el insert que hoy hace `turno.get()` cuando hubo reembolso, que pasa a `turno.orElse(null)`;
  - el javadoc de la clase.
- Tomar el indicador de efectivo del record `Reembolsos` que ya devuelve `validarReembolsos`, que carga el `TipoMedio`. No se hace una segunda carga.
- Crear `logica/TurnoRequeridoParaEfectivoException`. `ManejadorErrores` la responde con 409 `urn:sgip:caja:turno-requerido-para-efectivo` y el mensaje «Abre un turno de caja para devolver efectivo.».
- Extraer `ContextoEmpresa.esAdministrador(): boolean` de `ServicioTurnos.esAdministrador()`, que es privado, y usarlo en los dos sitios.
- `docs/decisiones/ADR-0033-saldo-a-favor.md` lleva la enmienda de la regla del turno y del cierre que cuenta `devoluciones.cierre_id`. `ADR-0032` lleva una línea de referencia cruzada.
- Pruebas: `logica/RegistradorDeDevolucionSinTurnoTest` y `datos/DevolucionSinTurnoConBaseTest` (`@Tag("conBase")`).

**Regla:**
- Sin turno, siendo ADMINISTRADOR y sin ningún reembolso EFECTIVO: se registra con `cierre_id = null`.
- Sin turno y con efectivo: `TurnoRequeridoParaEfectivoException`.
- Sin turno y siendo VENDEDOR: `SinTurnoAbiertoException`, aunque traiga el PIN de un admin.
- Un admin CON turno que devuelve solo con tarjeta sigue asociando la devolución al turno, como hoy.
- Un reembolso de 0 sigue siendo 400 (`@Positive` y el CHECK de la base); esa regla no cambia.

- [ ] **Paso 1: pruebas en rojo.**
  - Unitarias:
    - `adminSinTurnoConTarjetaRegistra`
    - `adminSinTurnoConSaldoRegistra`
    - `adminSinTurnoTarjetaYSaldoRegistra`
    - `adminSinTurnoConEfectivoEs409TurnoRequerido`
    - `vendedorSinTurnoConPinDeAdminYTarjetaEs409SinTurno`
    - `ventaDeOtraEmpresaEs404AntesQueElTurno`
    - `adminConTurnoYTarjetaSigueAsociandoElTurno`
  - Con base:
    - `devolucionSinTurnoQuedaConCierreNulo`
    - `tarjetaMasEfectivoSinTurnoNoDejaNadaAMedias`: no queda fila en `devoluciones`, ni abono, ni movimiento de kárdex.
    - `ventaContraEntregaPendienteRespetaLasReglasActuales`
    - `reintentoConLaMismaClaveSinTurnoDevuelve200`
    - `cierreDeUnTurnoNoCuentaLaDevolucionSinTurno`
    - `aperturaDeTurnoConcurrenteNoAsociaLaDevolucion`: con un orden forzado entre dos hilos, como en las pruebas de concurrencia de devoluciones de la caja. Se nombra la clase que se toma de modelo.
- [ ] **Paso 2:** implementar sin cambiar el orden de bloqueos (ADR-0030). Actualizar el javadoc y los ADR.

### Tarea 2: web: el efectivo deshabilitado cuando el admin no tiene turno

**Archivos:** `web/src/paginas/caja/Devolucion.jsx` y `Devolucion.prueba.jsx`.

**Contexto:**
- `turnoActual()` de `servicios/api/caja.js` devuelve `null` cuando no hay turno (el backend responde 204).
- El rol sale de `esAdministrador(leerSesion())`, de `servicios/sesion.js`.
- Hoy la pantalla no consulta el turno. Desde esta tarea lo consulta al montar.
- El campo «Efectivo» ya se deshabilita cuando no hay medio de efectivo. La condición nueva se combina así: deshabilitado si no hay medio de efectivo **o** si (es admin **y** no tiene turno).
- Cuando la causa es el turno, la ayuda dice «Para devolver efectivo, abre un turno de caja». Esa ayuda tiene prioridad sobre «Sale del cajón.».

- [ ] **Paso 1: pruebas en rojo.**
  - `adminSinTurnoVeEfectivoDeshabilitadoConAyuda`
  - `adminSinTurnoConfirmaConTarjeta`
  - `vendedorSinTurnoNoVeElBloqueoPorTurnoYRecibeEl409DelServidor`
  - `el409TurnoRequeridoMuestraSuMensaje`
  - `falloAlConsultarElTurnoNoBloqueaLaPantalla`: si falla la consulta, se deja el campo habilitado y el servidor decide.
- [ ] **Paso 2:** implementar.

---

## Rama `feat/pantalla-clientes` (M1)

### Tarea 3: backend: listado paginado de clientes con su saldo

**Archivos:**
- `api/ClientesControlador`: añade `GET /api/v1/caja/clientes/lista`. El GET por documento no se toca.
- `logica/ServicioClientes` y `datos/RepositorioClientes`.
- Crear `api/dto/ClienteEnLista` y `api/dto/PaginaDeClientes`.
- Fila `LOS_DOS` en `CajaRolesTest`.
- `V47`, solo si el `EXPLAIN` lo pide. El `EXPLAIN` va en el informe.
- Pruebas: `ServicioClientesListaTest` y `ClientesListaConBaseTest`.

**Interfaces:**
- `record ClienteEnLista(Integer id, TipoDocumento tipoDocumento, String identificacion, String nombre, String telefono, String correo, String direccion, String ciudad, BigDecimal saldoAFavor, boolean editable)`.
  - `editable` es `false` solo para el consumidor final, que se identifica con las constantes `ServicioClientes.TIPO_CONSUMIDOR_FINAL` e `IDENTIFICACION_CONSUMIDOR_FINAL`.
  - `saldoAFavor` sale de `LEFT JOIN saldos_cliente`, con 0 si no hay fila. Es el mismo valor que devuelve `/caja/clientes/{id}/saldo`.
- `record PaginaDeClientes(List<ClienteEnLista> elementos, int pagina, int tamano, long total)`.
- `PaginaDeClientes listar(String buscar, int pagina, int tamano)`:
  - `pagina` empieza en 0; un valor menor que 0 da 400;
  - `tamano` vale 20 por defecto, el máximo es 100 (lo que pase se recorta) y un valor menor que 1 da 400;
  - el orden es `nombre, id`;
  - `buscar` se recorta, y `%`, `_` y `\` se escapan;
  - la búsqueda es por prefijo sobre `nit` (la identificación) o por contenido sobre `nombre`, sin distinguir mayúsculas.

- [ ] **Paso 1: pruebas en rojo.**
  - `listaSoloLosDeLaEmpresa`
  - `buscaPorNombreYPorDocumento`
  - `buscarConComodinesEsLiteral`
  - `paginaYTotalCorrectos`
  - `paginaNegativaEs400`
  - `tamanoMayorQue100SeLimita`
  - `saldoIgualAlDelEndpointDeSaldo`
  - `clienteSinFilaDeSaldoTraeCero`
  - `consultasConstantesCon1Y50Clientes`
  - `consumidorFinalNoEsEditable`
  - `traeDireccionYCiudad`
- [ ] **Paso 2:** implementar.

### Tarea 4: web: la pantalla «Clientes»

**Archivos:**
- Crear `web/src/paginas/clientes/Clientes.jsx` y `Clientes.prueba.jsx`.
- Extraer `FormularioCliente` de `paginas/caja/DatosCliente.jsx` (hoy es local y no está exportado) a `web/src/componentes/clientes/FormularioCliente.jsx`:
  - sin buscador y sin la parte de domicilio;
  - con los campos de tipo y número de documento editables;
  - `DatosCliente.jsx` pasa a usarlo, y sus pruebas siguen en verde.
- `servicios/api/caja.js`: **crear** `listarClientes({buscar, pagina})` y reutilizar `crearCliente` y `actualizarCliente`.
- `secciones.js`: `{ ruta: '/clientes', etiqueta: 'Clientes', roles: VENDEN, grupo: 'Operación' }`.
- `App.jsx`: la ruta.

**Reglas:**
- Al crear, la casilla «El cliente autoriza el tratamiento de sus datos» es obligatoria.
- Al editar, primero se verifica en `ServicioClientes` qué exige el PUT:
  - si exige `autorizacionDatos`, el formulario de edición la muestra marcada según el valor guardado y no permite guardar sin ella;
  - si no la exige, no la muestra.
- El documento se puede editar siempre, por decisión del usuario.

- [ ] **Paso 1: pruebas en rojo.**
  - `listaYBusca`
  - `paginaSiguiente`
  - `creaClienteConAutorizacionObligatoria`
  - `editaElDocumento`
  - `editarNoBorraDireccionNiCiudad`
  - `consumidorFinalSinBotonEditar`
  - `muestraElSaldoSinEditarlo`
  - `estadoVacioYError`
  - `elVendedorVeLaSeccion`
  - `datosClienteDeLaCajaSigueFuncionando`
- [ ] **Paso 2:** implementar.

---

## Rama `feat/editar-empresa` (M2)

### Tarea 5: backend: datos de la empresa por el admin, e identidad NIT/CC por plataforma

**Archivos:**
- **`V48__datos_de_empresa_y_copia_en_venta.sql`:**
  - `empresas` suma `nombre_comercial VARCHAR(150) NULL`, `direccion VARCHAR(200) NULL` y `tipo_documento ENUM('NIT','CC') NOT NULL DEFAULT 'NIT'`.
  - `ventas` suma `empresa_nombre VARCHAR(160) NULL`, `empresa_tipo_documento ENUM('NIT','CC') NULL`, `empresa_nit VARCHAR(20) NULL`, `empresa_direccion VARCHAR(200) NULL` y `empresa_telefono VARCHAR(20) NULL`.
  - `auditoria_plataforma.accion`: se vuelve a listar el ENUM con los 8 valores existentes más `CAMBIO_IDENTIDAD_EMPRESA`, siguiendo el patrón de quitar, modificar y volver a añadir de V37. La acción nueva entra también en `ck_auditoria_plataforma_cambio` y en `ck_auditoria_plataforma_ambito`.
- **`modelo/Empresa`:** mapea los campos nuevos más `telefono`, `ciudad` y `correoContacto`.
- **`api/dto/EmpresaResumen`:** amplía con esos campos.
- **`api/EmpresaControlador`:** nuevo, con **solo** `PUT /api/v1/empresa` para ADMINISTRADOR. `GET /api/v1/empresa` y el logo se quedan en `ImagenControlador`.
- **`logica/ServicioEmpresa`:** nuevo.
- **`logica/DocumentoEmpresa`:** nuevo, con `normalizar(String): String`, que quita puntos, espacios y guiones y exige de 5 a 15 dígitos. Lanza `DocumentoEmpresaInvalidoException`, que da 400 `urn:sgip:empresa:documento-invalido`. **No hay dígito de verificación.**
- **`api/PlataformaControlador`** y **`logica/ServicioPlataforma`:** `PUT /api/v1/plataforma/empresas/{id}/identidad` para SUPER_ADMIN. `darDeAlta` y `SolicitudAltaEmpresa` adoptan `tipoDocumento` más el número normalizado.
- **`modelo/AccionPlataforma`:** suma `CAMBIO_IDENTIDAD_EMPRESA`, clasificada en los dos switches (`esDeEmpresa` → true, `esCambio` → true). `RepositorioPlataformaTest` se actualiza.
- **`datos/RepositorioPlataforma.existeNit`:** compara el número normalizado y excluye la empresa que se edita.
- **Errores:** el 409 reutiliza `NitRepetidoException` y `urn:sgip:nit-repetido`.

**Interfaces:**
- `record SolicitudDatosEmpresa(@Size(max=150) String nombreComercial, @Size(max=200) String direccion, @Size(max=80) String ciudad, @Size(max=20) String telefono, @Email @Size(max=120) String correoContacto)`.
  - Lleva un `@JsonAnySetter` que guarda las claves desconocidas. Si hay alguna (por ejemplo `nit`), da 400 y nombra el campo. Esto es necesario porque Boot no falla ante campos desconocidos, y `application.yml` no se toca.
  - Los textos se recortan, y los vacíos quedan en null.
- `record SolicitudIdentidad(@NotNull TipoDocumentoEmpresa tipoDocumento, @NotBlank String numero, @NotBlank @Size(max=160) String razonSocial, @NotBlank @Size(min=10, max=200) String motivo)`.
- **Auditoría:** una fila por cada campo que cambia (`tipo_documento`, `nit` y `razon_social`). Cada fila lleva `entidad='empresas'`, `referencia` igual al NIT anterior, el valor anterior, el valor nuevo, el motivo, el usuario y la fecha, en la misma transacción. Sin cambios, no se escribe ninguna fila y la respuesta es 200.

- [ ] **Paso 1: pruebas en rojo.**
  - `DocumentoEmpresaTest`:
    - `nitConPuntosYEspaciosSeNormaliza` (`900.123.456` → `900123456`)
    - `ccSeNormaliza`
    - `letrasSonInvalidas`
    - `cortoOLargoEsInvalido`
    - `nuncaExigeDigitoDeVerificacion`
  - Edición por el admin:
    - `adminEditaDatosYSeVenEnElResumen`
    - `adminQueMandaNitRecibe400NombrandoElCampo`
    - `adminQueMandaRazonSocialRecibe400`
    - `vendedorClienteYSuperAdminReciben403EnPutEmpresa`
    - `elIdDeOtraEmpresaEnElCuerpoNoCambiaNada`
    - `correoInvalidoEs400`
    - `telefonoDe21CaracteresEs400`
    - `vaciosQuedanNull`
  - Identidad por plataforma:
    - `superAdminCambiaIdentidadYAuditaUnaFilaPorCampo`
    - `soloRazonSocialNoDaFalso409`
    - `sinCambiosNoAudita`
    - `sinMotivoEs400`
    - `motivoDe201Es400`
    - `numeroRepetidoEs409`
    - `adminRecibe403EnIdentidad`
    - `empresaInexistenteEs404`
  - Alta: `altaConCcSinDigitoDeVerificacion`.
- [ ] **Paso 2:** implementar.

### Tarea 6: backend: copia de la empresa en la venta y recibo

**Archivos:**
- `datos/RepositorioVentas`: el record `NuevaVenta` y su INSERT llevan las 5 columnas `empresa_*`.
- `logica/RegistradorDeVenta`: lee la empresa sin bloqueo (sin `FOR UPDATE` ni `FOR SHARE`) y llena la copia. `empresa_nombre` es `COALESCE(nombre_comercial, razon_social)`.
- `logica/ResultadoDeVenta`: devuelve la copia, para que el web arme el recibo inmediato con ella.
- `datos/RepositorioRecibos` y el record `CabeceraDeRecibo`: leen la copia, o los datos vivos si `empresa_nit IS NULL`.
- `api/dto/Recibo`: suma `tipoDocumentoEmpresa`, `direccionEmpresa` y `telefonoEmpresa` al final.
- `logica/ReciboPdf`: imprime la dirección y el teléfono bajo el documento, si existen.
- Pruebas: `CopiaDeEmpresaEnVentaConBaseTest` y `ServicioRecibosTest`.

- [ ] **Paso 1: pruebas en rojo.**
  - `laVentaGuardaLosDatosDeLaEmpresaDeEseDia`
  - `cambiarLaEmpresaDespuesNoCambiaElReciboViejo`
  - `ventaSinCopiaUsaLosDatosActuales`, con `empresa_nit` nulo.
  - `elReciboMuestraDireccionYTelefono`
  - `elResultadoDeLaVentaTraeLaCopia`
  - `consultasDelReciboSiguenConstantes`
  - `registrarVentaUsaElConteoAnteriorMasUna`: se fija el número exacto en la prueba.
- [ ] **Paso 2:** implementar y volver a correr las pruebas concurrentes de la venta.

### Tarea 7: web: «Mi empresa» editable, identidad en plataforma, y el mismo recibo en las dos rutas

**Archivos:**
- `web/src/paginas/Empresa.jsx` y su prueba.
- `web/src/paginas/plataforma-empresas/FichaEmpresa.jsx` y su prueba: una sección «Identidad» con su formulario (tipo, número, razón social y motivo) y los errores 400 y 409 en línea, siguiendo el patrón de la ficha.
- `web/src/paginas/caja/ReciboImprimible.jsx`: recibe `empresa = {nombre, tipoDocumento, nit, direccion, telefono}`.
  - El recibo inmediato toma esos datos de la respuesta de la venta (tarea 6).
  - La reimpresión los toma del `Recibo`.
  - `Caja.jsx` deja de usar `empresa.razonSocial` del GET vivo para el recibo.

- [ ] **Paso 1: pruebas en rojo.**
  - `adminEditaYGuarda`
  - `nitYRazonSocialSonSoloLecturaConNotaDeSoporte`: la nota dice «Para cambiarlos, contacta a soporte».
  - `vendedorVeSoloLectura`
  - `superAdminCambiaIdentidadConMotivoObligatorio`
  - `documentoInvalidoMuestraElError`
  - `numeroRepetidoMuestraElError`
  - `reciboInmediatoYReimpresionMuestranLoMismo`
  - `reciboMuestraDireccionYTelefono`
- [ ] **Paso 2:** implementar.

---

## Rama `feat/plantilla-precios` (M3)

### Tarea 8: backend: rechazo de filas de otro proveedor y plantilla en xlsx

**Archivos:**
- `datos/RepositorioImportacion.catalogoPorReferencia`: el SELECT suma `proveedor_id`.
- `logica/ProductoDelCatalogo`: el record suma `proveedorId`; se actualizan sus usos.
- `logica/ServicioImportacion`:
  - `juzgar` recibe `proveedorId`;
  - la causa nueva `PRODUCTO_DE_OTRO_PROVEEDOR` se comprueba **después** de `REFERENCIA_INEXISTENTE` y **antes** de `COSTO_NO_NUMERICO`;
  - el motivo no pasa de 200 caracteres;
  - se actualiza el javadoc que describe el orden de las comprobaciones.
- `V49__causa_producto_de_otro_proveedor.sql`: `ALTER TABLE detalle_carga_precios MODIFY COLUMN motivo_codigo ENUM(<los 5 valores actuales>,'PRODUCTO_DE_OTRO_PROVEEDOR') NULL`. Los valores actuales se leen del ENUM definido en `database/migrations/V30`.
- Crear `logica/GeneradorPlantillaPrecios`, que importa POI. El javadoc de `LectorHojaDeCalculo` y el ADR-0007 se enmiendan: «POI solo en las clases de lectura y escritura de hojas de cálculo». No hay ArchUnit.
- `datos/RepositorioImportacion`: un método nuevo que recorre los productos activos del proveedor con un cursor (`RowCallbackHandler` o un stream de `JdbcClient`) y **no** devuelve una `List`.
- `api/ImportacionControlador`: `GET /api/v1/importaciones/plantilla?proveedorId=` para ADMINISTRADOR. Un `proveedorId` ausente o no numérico da 400.
  - En `logica`, un proveedor de otra empresa, o el reservado (`RepositorioPlataforma.NIT_SIN_PROVEEDOR`), da 404.

**Interfaces:**
- `void escribir(Integer empresaId, Integer proveedorId, OutputStream salida)`:
  - usa `SXSSFWorkbook(100)`, con `dispose()` en un `finally`;
  - **hoja 0 «Precios»:** `Referencia | Descripción | Costo`. Cada producto activo del proveedor va con su código, su nombre y su costo. Un costo nulo o de 0 queda en celda vacía;
  - **hoja 1 «Instrucciones»;**
  - los textos van como celdas STRING con estilo `quotePrefixed`, sin anteponer un `'` literal;
  - `Content-Disposition: attachment; filename="plantilla-precios-<nombre del proveedor en ASCII, en minúsculas y con guiones>.xlsx"`.

- [ ] **Paso 1: pruebas en rojo.**
  - `filaDeOtroProveedorSeRechazaYElRestoSeAplica`
  - `ordenDeCausas`: si la referencia no existe, sale `REFERENCIA_INEXISTENTE` aunque el costo sea inválido.
  - `plantillaTraeLosProductosDelProveedorConSuCosto`
  - `hojaCeroEsPrecios`
  - `costoNuloVaVacioYAlSubirloSinLlenarSeRechaza`
  - `proveedorSinProductosDaSoloEncabezados`
  - `referenciaQueEmpiezaConIgualSeReimportaIgual`
  - `idaYVueltaSinCambiosNoRechazaNiCambiaCostos`: todos los costos del fixture son positivos.
  - `consultasConstantesCon1Y1000Productos`
  - `elRepositorioRecorreSinCargarTodo`
  - `memoriaAcotadaCon20000Productos`: el montón crece menos de 64 MB. Si resulta inestable en la CI, baja a 5 000 filas con el mismo margen, y el cambio se anota en el informe.
  - `proveedorAjenoEs404`
  - `proveedorReservadoEs404`
  - `proveedorIdAusenteEs400`
  - `rutaPlantillaNoChocaConCargaId`
  - `vendedorRecibe403`
  - `nombreDeArchivoConTildesYEspacios`
- [ ] **Paso 2:** implementar.

### Tarea 9: web: descarga de la plantilla, causa nueva y «Sin proveedor» en el catálogo

**Archivos:**
- `web/src/servicios/api.js`: una función de descarga de binario basada en `imagenAutenticada`. Toma el nombre de `Content-Disposition` y dispara el guardado.
- `web/src/paginas/Importacion.jsx` y su prueba:
  - al elegir el proveedor aparece «Descargar plantilla con mis productos»;
  - `PASTILLAS` y `QUE_HACER` suman `PRODUCTO_DE_OTRO_PROVEEDOR`, con el texto «De otro proveedor» y la indicación «Esta referencia pertenece a otro proveedor; súbela en la lista de ese proveedor».
- `web/src/paginas/Catalogo.jsx` y su prueba:
  - los productos del proveedor reservado muestran la pastilla «Sin proveedor», y hay un filtro «Sin proveedor»;
  - la edición del producto permite elegir el proveedor. Si ya lo permite, se verifica y se deja así; si no, se añade usando `SolicitudEdicionProducto.proveedorId`, que ya existe.

- [ ] **Paso 1: pruebas en rojo.**
  - `alElegirProveedorApareceDescargarPlantilla`
  - `laDescargaPideLaRutaConElProveedorYUsaElNombreDelServidor`
  - `errorDeDescargaSeMuestra`
  - `causaDeOtroProveedorSeMuestraConSuIndicacion`
  - `catalogoMarcaYFiltraSinProveedor`
  - `editarProductoPermiteAsignarProveedor`
- [ ] **Paso 2:** implementar.

---

## Orden de ejecución

0. La puerta: la caja unida a `develop`, incluida su ronda final.
1. Tareas 1 → 2.
2. Tareas 3 → 4.
3. Tareas 5 → 6 → 7.
4. Tareas 8 → 9.

M1 y M3 pueden ir en paralelo con M4 o con M2, cada una en su propio worktree y con un solo escritor por rama.
