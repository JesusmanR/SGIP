# Caja (Sprint 9), entrega 1: plan de implementación

> **Para agentes:** SUB-SKILL REQUERIDA: usa superpowers:subagent-driven-development (recomendada) o superpowers:executing-plans para implementar este plan tarea por tarea. Los pasos usan casillas (`- [ ]`).

**Objetivo:** que una empresa venda en el mostrador y a domicilio, con turnos de caja, cobro mixto, encargos, devoluciones, autorizaciones con PIN y recibo, sin que el vendedor pueda saltarse al administrador.

**Arquitectura:**
- Un módulo «caja» en el backend existente (`api` → `logica` → `datos`), con JdbcClient para las escrituras transaccionales, como `ServicioInventario` y `ServicioCompras`.
- El cálculo de la venta (descuentos, prorrateo, impuestos y totales) es una clase pura y sin base, `CalculoDeVenta`. El servicio solo la orquesta dentro de una transacción.
- El front añade la sección `/caja` y el campo de PIN en «Mi empresa».

**Stack:** Java 25, Spring Boot 4.1, JdbcClient y JPA, MySQL 9.7, Flyway, React 18.3.1, Vite y Vitest.

**Spec:** `docs/superpowers/specs/2026-11-09-caja-design.md`. Ese documento manda; la entrega 2 (cotizaciones) tendrá su propio plan.

## Restricciones globales

- **Rama:** `feature/caja`, creada desde `develop` **después** de integrar D (`feature/prueba-30-dias`, que usa V39). Las migraciones de este plan empiezan en el siguiente número libre (se espera V40). Los ADR también toman el siguiente número libre (se espera 0032 para el PIN y 0033 para el saldo a favor).
- **Roles:**
  - Venden el administrador de empresa y el vendedor, con los nombres de rol que use el código (consulta `ConfiguracionSeguridad` y `AutorizacionPorRolTest`).
  - El super administrador recibe 403 en todo `/api/v1/caja/**`.
  - Una empresa sin escritura (modo consulta o prueba vencida) recibe 403 en las escrituras, por `FiltroEmpresaActiva`.
- **Datos:**
  - Toda sentencia filtra por `empresa_id` (ADR-0011) y cada subconsulta repite el filtro.
  - El vendedor nunca recibe `costo` ni `utilidad` en ninguna respuesta (decisión del 2026-10-31).
- **Cálculo y fechas:**
  - El precio lo calcula **solo** el servidor con las vistas de V38. El cliente nunca envía un precio unitario.
  - Dinero en `BigDecimal`. Precio de venta al peso con HALF_UP (RNF-16). Cantidades enteras (ADR-0013).
  - «Hoy» y «ahora» salen de `Hoy.de(reloj)` y del `Clock` de la aplicación (ADR-0030).
- **Bloqueos:**
  - Orden: empresa → productos (por id ascendente) → kárdex.
  - Una venta bloquea sus productos con `SELECT … FROM productos WHERE empresa_id=:e AND producto_id IN (:ids) ORDER BY producto_id FOR UPDATE`, con `FORCE INDEX (PRIMARY)`, como hace `RepositorioImportacion.bloquearProductosDeLaCarga`.
- **Errores:**
  - 400 para un dato inválido; 403 para falta de permiso o de autorización; 404 para algo ajeno o inexistente; 409 para un conflicto de estado.
  - El texto va en español, sin eco del dato enviado, por `ManejadorErrores`.
- **Rendimiento (RNF-03):**
  - Buscar por código y registrar una venta: menos de 500 ms.
  - El número de consultas de una venta no depende del número de líneas: se protege con una prueba de conteo con base, como las existentes de `ListadoDeOrdenesConsultasConBaseTest`.
- **Proceso:**
  - TDD: commit `test:` en rojo antes del `feat:`/`fix:`.
  - Las pruebas con base llevan `@Tag("conBase")` y corren en un mysqld propio con carpeta y puerto únicos (`reglas-implementador.md`).
  - Commits en español con `Co-Authored-By` y el modelo real.
- **UI:**
  - Skill `impeccable` en modo Operate; lee `reference/craft-floor.md` antes de editar.
  - `tokens.css` es la única fuente de color.
  - Pesos colombianos sin decimales y fechas según RNF-16.
  - En el celular, tarjetas (`TablaOTarjetas`).
  - Hojas de estilo según `estilos/pantallas.prueba.js`, `secciones.prueba.js` y `selectores.prueba.js`.
- **Lo que hay en la base:** las tablas `ventas`, `venta_items`, `clientes`, `cierres_caja` y `medios_pago` ya existen (V4, V10, V11, V31 y V34). Antes de escribir una migración, el implementador lee su definición **actual** recorriendo esas migraciones. No supone columnas.

## Foco de revisión

1. **Doble envío de la misma venta** (doble clic, reintento por red lenta): debe quedar una sola venta y un solo descuento de inventario. La prueba está en la tarea 7.
2. **Un vendedor que manda en el JSON un descuento, un encargo o un producto nuevo sin PIN válido:** 403 y nada guardado. Lo cubren las tareas 7 y 8.
3. **El PIN de un administrador de otra empresa, o el de un usuario que no es administrador:** 403, y el intento cuenta como fallo. Lo cubre la tarea 3.
4. **Dos cajeros venden la última unidad a la vez sin autorización de encargo:** uno vende y el otro recibe 409; la existencia nunca queda negativa sin autorización. Lo cubre la tarea 7.
5. **Cerrar un turno con cobros contra entrega y devoluciones en efectivo de otros turnos:** el esperado solo cuenta el dinero que pasó por **este** turno. Lo cubre la tarea 10.

---

### Tarea 1: Medios de pago por empresa (RF-43, M17)

**Archivos:**
- Crear: la migración `V40__medios_de_pago_de_cada_empresa.sql`, o el número libre que toque.
- Crear: `logica/ServicioMediosPago.java`, `datos/RepositorioMediosPago.java`, `api/MediosPagoControlador.java` y los DTO `api/dto/MedioDePago.java` y `api/dto/SolicitudMedioPago.java`.
- Modificar: `logica/ServicioPlataforma.java`, para que el alta siembre los tres medios.
- Pruebas: `logica/ServicioMediosPagoTest.java`, `api/MediosPagoAutorizacionTest.java` y `datos/MediosPagoConBaseTest.java`.

**Interfaces que produce:**
- `record MedioDePago(Integer id, String nombre, TipoMedio tipo, boolean activo)`.
- `enum TipoMedio { EFECTIVO, TARJETA, TRANSFERENCIA, SALDO_A_FAVOR }`. La migración ajusta el ENUM: se quita `CREDITO`, que no se usa, y se añade `SALDO_A_FAVOR`.
- Métodos de `ServicioMediosPago`:
  - `List<MedioDePago> activos()`;
  - `List<MedioDePago> todos()`;
  - `MedioDePago crear(SolicitudMedioPago)`;
  - `MedioDePago cambiarActivo(Integer id, boolean activo)`.
- Rutas:
  - `GET /api/v1/caja/medios-pago`: los medios activos; lo usan el administrador y el vendedor.
  - `GET|POST|PATCH /api/v1/medios-pago`: solo el administrador.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `elAltaSiembraEfectivoTarjetaYTransferencia`: el alta de una empresa deja exactamente estos tres medios activos: «Efectivo» (EFECTIVO), «Tarjeta» (TARJETA) y «Transferencia» (TRANSFERENCIA).
  - Migración, con base: `unaEmpresaSinMediosRecibeLosTresAlMigrar`. Es M17. Una empresa que ya tiene algún medio no se toca.
  - `desactivarElUnicoMedioActivoEs409`.
  - `unMedioDeOtraEmpresaEs404`.
  - `elVendedorListaLosActivosPeroNoAdministra`: 200 en `/caja/medios-pago` y 403 en `/medios-pago`.
  - `elSaldoAFavorNoSePuedeCrearNiDesactivarAMano`: 400. Lo gestiona el sistema; se siembra inactivo y lo usa la tarea 9.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan por la razón correcta.
- [ ] **Paso 3:** implementa la migración, que siembra los tres medios en las empresas sin medios y añade el medio de sistema «Saldo a favor» (SALDO_A_FAVOR) a todas. Después el alta, el servicio y el controlador.
- [ ] **Paso 4:** ejecuta `./mvnw -B test -DexcludedGroups=conBase`, y las pruebas con base en tu mysqld. Todo debe quedar en verde.
- [ ] **Paso 5:** haz los commits `test:` y luego `feat:`.

### Tarea 2: Clientes de la caja (RF-66)

**Archivos:**
- Crear: la migración `V41__consumidor_final_y_datos_de_cliente.sql`.
- Crear: `logica/ServicioClientes.java`, `datos/RepositorioClientes.java`, `api/ClientesControlador.java` y los DTO `Cliente` y `SolicitudCliente`.
- Modificar: `ServicioPlataforma`, para que el alta siembre el «Consumidor final».
- Pruebas: `logica/ServicioClientesTest.java`, `api/ClientesAutorizacionTest.java` y `datos/ClientesConBaseTest.java`.

**Interfaces que produce:**
- `record Cliente(Integer id, String nombre, TipoDocumento tipoDocumento, String identificacion, String correo, String telefono, String direccion, String ciudad, boolean consumidorFinal)`.
- Métodos de `ServicioClientes`:
  - `Optional<Cliente> porIdentificacion(TipoDocumento, String)`;
  - `Cliente crear(SolicitudCliente)`;
  - `Cliente actualizar(Integer id, SolicitudCliente)`;
  - `Cliente consumidorFinal()`;
  - `void exigirDatosDeDomicilio(Cliente)`, que lanza `DatosDeDomicilioIncompletosException` (400).
- Rutas `/api/v1/caja/clientes`:
  - `GET ?tipo=&identificacion=`;
  - `POST`;
  - `PUT /{id}`.

  Las usan el administrador y el vendedor.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `crearExigeNombreIdentificacionYCorreo`: si falta cualquiera de los tres, 400.
  - `crearExigeLaAutorizacionDeDatos`: sin la marca, 400 (RNF-18).
  - `laIdentificacionEsUnicaPorEmpresa`: la misma identificación en la misma empresa da 409; en otra empresa, 201.
  - `porIdentificacionDevuelveLosDatosGuardados`.
  - `cadaEmpresaTieneSuConsumidorFinal`: lo crean el alta y la migración para las empresas existentes. No se puede editar (409).
  - `domicilioExigeTelefonoDireccionYCiudad`: aplicado a un cliente sin teléfono, da 400.
  - `elConsumidorFinalNuncaSirveParaDomicilio`: da 400.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
  - El «Consumidor final» se distingue por una columna `consumidor_final BOOL` con unicidad por empresa (por ejemplo, un índice único sobre una columna generada). Usa `tipo_documento = 'CC'` e identificación `222222222222`.
  - El correo se valida con `@Email`.
  - La ciudad, cuando no viene, toma la ciudad de la empresa si existe en `empresa_config`. Si no existe, la ciudad es obligatoria.
- [ ] **Paso 4:** pasa la suite completa, sin base y con base.
- [ ] **Paso 5:** haz los commits.

### Tarea 3: PIN de autorización del administrador

**Archivos:**
- Crear: la migración `V42__pin_del_administrador.sql`, que añade a `usuarios` las columnas `pin_hash`, `pin_intentos` y `pin_bloqueado_en`.
- Crear: `logica/ServicioAutorizacion.java`, `logica/Excepcion.java` (el enum), `logica/Autorizacion.java` (el record), `logica/PinInvalidoException.java`, `logica/PinBloqueadoException.java` y `api/PinControlador.java`.
- Crear: `docs/decisiones/ADR-00NN-autorizacion-con-pin.md`, con su fila en el README.
- Pruebas: `logica/ServicioAutorizacionTest.java`, `api/PinControladorTest.java` y `datos/PinConBaseTest.java`.

**Interfaces que produce:**
- `enum Excepcion { DESCUENTO, ENCARGO, PRODUCTO_NUEVO, DEVOLUCION }`.
- `record Autorizacion(Integer administradorId, Set<Excepcion> cubre)`.
- `record SolicitudAutorizacion(Integer administradorId, String pin)`. El DTO viaja dentro de las peticiones de las tareas 7, 8 y 9.
- Métodos de `ServicioAutorizacion`:
  - `Autorizacion autorizar(SolicitudAutorizacion s, Set<Excepcion> necesarias)`. Si el usuario en sesión es administrador y `s` es nulo, devuelve una autorización propia sin pedir el PIN.
  - `void fijarPin(String pinActual, String pinNuevo)`, solo para el administrador de su propia cuenta. `pinActual` es nulo la primera vez.
  - `List<AdministradorParaAutorizar> administradores()`, que devuelve solo el id y el nombre de los administradores activos de la empresa que tienen PIN.
- Rutas:
  - `PUT /api/v1/perfil/pin`: solo el administrador.
  - `GET /api/v1/caja/administradores`: el administrador y el vendedor.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `elPinTieneDeCuatroASeisDigitos`: «123» y «1234567» dan 400, y también «12a4».
  - `elPinSeGuardaDerivadoYNuncaSeDevuelve`: `pin_hash` no contiene el PIN y ninguna respuesta lo incluye. Se usa el mismo derivador que las contraseñas (ADR-0002, `util/Seguridad`).
  - `elPinNoPuedeSerIgualALaContrasena`: da 400.
  - `pinCorrectoAutorizaLasExcepcionesPedidas`: `cubre` es igual a `necesarias`.
  - `pinDeOtraEmpresaODeUnVendedorEs403YCuentaComoFallo`.
  - `cincoFallosBloqueanElPinQuinceMinutos`:
    - con un `Clock` fijo, el sexto intento da `PinBloqueadoException` (403), aunque el PIN sea correcto;
    - a los 15 min + 1 s vuelve a aceptar;
    - el bloqueo se escribe en `auditoria_accesos`.
  - `losIntentosSeCuentanPorAdministrador`: los fallos contra el administrador A no bloquean al administrador B.
  - `unAciertoReiniciaLosIntentos`.
  - `administradoresListaSoloLosQueTienenPinYSonDeLaEmpresa`.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
  - El bloqueo reutiliza la forma de `PoliticaDeBloqueo` y el parámetro `sgip.seguridad.minutos-de-bloqueo` (ADR-0028), sin duplicar la regla.
  - La verificación bloquea la fila del administrador (`FOR UPDATE`) para que dos intentos simultáneos no se salten el contador.
  - Escribe el ADR: por qué es un PIN y no la contraseña, por qué se elige el nombre del administrador en vez de probar contra todos, el bloqueo, y que es de un solo uso por petición.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 4: Abrir el turno y consultar el turno actual (RF-45, primera mitad)

**Archivos:**
- Crear: la migración `V43__turno_de_caja.sql`, que añade a `cierres_caja` la columna `base_efectivo DECIMAL(14,2) NOT NULL DEFAULT 0` y cambia el cálculo de `esperado` según lo que pida la tarea 10.
- Crear: `logica/ServicioTurnos.java`, `datos/RepositorioTurnos.java`, `api/TurnosControlador.java` y los DTO `Turno` y `SolicitudApertura`.
- Pruebas: `logica/ServicioTurnosTest.java` y `datos/TurnosConBaseTest.java`.

**Interfaces que produce:**
- `record Turno(Long id, Integer cajeroId, String cajero, OffsetDateTime apertura, BigDecimal baseEfectivo, OffsetDateTime cierre)`.
- Métodos de `ServicioTurnos`:
  - `Turno abrir(SolicitudApertura)`;
  - `Optional<Turno> actual()`, que devuelve el turno abierto del usuario en sesión;
  - `Long exigirTurnoAbierto()`, que devuelve el `cierre_id` o lanza `SinTurnoAbiertoException` (409).
- Rutas:
  - `POST /api/v1/caja/turnos`;
  - `GET /api/v1/caja/turnos/actual`, que responde 204 si no hay turno.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `abrirRegistraCajeroHoraYBase`.
  - `unaBaseNegativaEs400`.
  - `unSegundoTurnoAbiertoDelMismoCajeroEs409`. Pruébalo también con base y dos hilos: queda un solo turno abierto.
  - `exigirTurnoAbiertoSinTurnoEs409`.
  - `elTurnoDeOtroCajeroNoEsElActual`.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa la unicidad del turno abierto por cajero en la base. Por ejemplo, con una columna generada `abierto_de = IF(cierre IS NULL, usuario_id, NULL)` y un índice único `(empresa_id, abierto_de)`.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 5: Buscar productos desde la caja (M7, M8, M12, M13)

**Archivos:**
- Crear: `api/CajaProductosControlador.java`, el DTO `api/dto/ProductoParaCaja.java` y `logica/ServicioCajaProductos.java`.
- Modificar: `datos/ConsultaCatalogo.java`, para que devuelva lo que la caja necesita de la tarifa (M7). Modificar también el mensaje de código repetido de `ServicioProducto` y `ManejadorErrores` (M12).
- Pruebas: `logica/ServicioCajaProductosTest.java`, `api/CajaProductosAutorizacionTest.java`, `datos/CodigoDeBarrasConcurrenteConBaseTest.java` (M13) y `datos/BusquedaCajaConsultasConBaseTest.java`.

**Interfaces que produce:**
- `record ProductoParaCaja(Integer id, String codigo, String nombre, String presentacion, String codigoBarras, int existencia, BigDecimal precio, BigDecimal tarifaIva, String tratamientoIva, boolean vendible, String motivoNoVendible)`. Sin costo, margen ni utilidad.
- Métodos de `ServicioCajaProductos`:
  - `Optional<ProductoParaCaja> porCodigo(String codigo)`, que busca por `codigo_barras` y después por `codigo`;
  - `List<ProductoParaCaja> buscar(String texto, int limite)`, con un límite máximo de 20.
- Rutas:
  - `GET /api/v1/caja/productos?codigo=`, que devuelve 404 si no existe;
  - `GET /api/v1/caja/productos?q=`.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `sinPrecioNoEsVendible`: un precio nulo da `vendible=false` con `motivoNoVendible = "Este producto no tiene precio de venta"`. Lo mismo para un precio cero y para un precio de más de 12 cifras enteras (M8).
  - `unProductoInactivoNoAparece`.
  - `elVendedorNoRecibeCosto`: el JSON de la respuesta no tiene las claves `costo`, `margen` ni `utilidad`.
  - `porCodigoDeOtraEmpresaEs404`.
  - `codigoRepetidoDeUnInactivoLoDice` (M12). Al crear o editar un producto con el código de barras de un producto inactivo, el detalle del 409 dice: «Ese código de barras ya lo usa un producto inactivo de tu empresa.»
  - `dosAltasSimultaneasConElMismoCodigoDejanUnaYDan409` (M13, con base, dos hilos).
  - `buscarPorCodigoHaceUnNumeroFijoDeConsultas` (con base): dos o menos, cuéntalas como en las pruebas existentes.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa sobre `ConsultaCatalogo.porCodigoBarras` y `listar`. No hagas una consulta nueva a la vista sin su filtro de empresa.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 6: Cálculo de la venta, sin base

**Archivos:**
- Crear: `logica/venta/CalculoDeVenta.java`, `logica/venta/LineaPedida.java`, `logica/venta/Descuento.java`, `logica/venta/VentaCalculada.java` y `logica/venta/LineaCalculada.java`.
- Pruebas: `logica/venta/CalculoDeVentaTest.java`.

**Interfaces que produce:**
- `record Descuento(TipoDescuento tipo, BigDecimal valor)`, con `TipoDescuento { PORCENTAJE, PESOS }`.
- `record LineaPedida(Integer productoId, int cantidad, BigDecimal precioUnitario, BigDecimal tarifaIva, String tratamientoIva, Descuento descuento /* nullable */)`.
- `record LineaCalculada(Integer productoId, int cantidad, BigDecimal precioUnitario, BigDecimal descuento, BigDecimal base, BigDecimal tarifaIva, String tratamientoIva, BigDecimal iva, BigDecimal total)`.
- `record VentaCalculada(List<LineaCalculada> lineas, BigDecimal subtotal, BigDecimal descuento, BigDecimal iva, BigDecimal total, boolean tieneDescuento)`.
- `static VentaCalculada calcular(List<LineaPedida> lineas, Descuento descuentoTotal /* nullable */)`.
- `static BigDecimal cambio(BigDecimal totalEfectivo, BigDecimal recibido)`.

Reglas fijadas por el diseño:
- El descuento de una línea se aplica antes que el de toda la compra.
- El descuento sobre toda la compra se reparte entre las líneas en proporción al valor de cada una después de su propio descuento, al peso, con HALF_UP. El residuo de redondeo va a la línea de mayor valor, y si empatan, a la de menor `productoId`. La suma de los descuentos repartidos es exactamente igual al descuento total.
- Se reutiliza el cálculo del impuesto del precio, `CalculoDePrecio`, si sirve; el IVA se calcula sobre la base ya descontada.
- `EXCLUIDO_HASTA_UMBRAL` se convierte en `EXCLUIDO` cuando la tarifa aplicada es 0 y en `GRAVADO` cuando es mayor que 0 (M6). `NO_GRAVADO` y nulo siguen la ADR-0024.

- [ ] **Paso 1: escribe las pruebas en rojo**, con valores exactos:
  - `sinDescuentosElTotalEsLaSumaDeLineas`: 2 × 1 500 + 1 × 3 000 a tarifa 0 da un total de 6 000.
  - `descuentoPorcentajeEnLinea`: una línea de 10 000 con 10 % da un descuento de 1 000 y una base de 9 000.
  - `descuentoEnPesosEnLinea`: 10 000 menos 2 500 da una base de 7 500.
  - `descuentoTotalSeRepartePorValor`: las líneas son de 6 000 y 3 000 y el descuento total es de 1 000 pesos, así que el reparto es 667 y 333. La suma da exactamente 1 000.
  - `elResiduoVaALaLineaMayor`: tres líneas de 1 000 y un descuento de 100 dan 34, 33 y 33.
  - `ivaSobreLaBaseDescontada`: 11 900 a tarifa 0,19 con 10 % de descuento, con el IVA calculado sobre la base descontada. Fija las cifras en la prueba según cómo traiga el precio V38 (con IVA incluido o sin él): mira `CalculoDePrecio` y `PrecioDeProductoTest` y escribe el caso con los números que salgan de esa regla.
  - `descuentoMayorQueLaLineaEs400` y `porcentajeNegativoOMayorA100Es400`, que lanzan `DescuentoInvalidoException`.
  - `excluidoHastaUmbralSeGuardaComoExcluidoOGravado` (M6).
  - `cambioDeEfectivo`: con un total en efectivo de 18 700 y 20 000 recibidos, el cambio es 1 300; si se recibe menos que el total, es 400.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** ejecuta `./mvnw -B test -DexcludedGroups=conBase`; todo debe quedar en verde.
- [ ] **Paso 5:** haz los commits.

### Tarea 7: Registrar una venta en el mostrador (RF-41, RF-42, RF-44, RF-36)

**Archivos:**
- Crear: la migración `V44__venta_en_caja.sql`, con:
  - la tabla `pagos_venta (pago_id, empresa_id, venta_id, medio_id, monto, referencia, recibido, cierre_id, registrado_por, registrado_en)`;
  - en `ventas`: `clave_idempotencia` (único por empresa), `autorizado_por`, `descuento_tipo` y `descuento_valor`, `tipo_entrega ENUM('MOSTRADOR','DOMICILIO')` y `estado_pago` reutilizado;
  - en `venta_items`: `descuento` y `cantidad_encargo`, `entrega_estimada DATE` y `motivo_encargo`; el ENUM de `tratamiento_iva` revisado (M6).
  - **M9:** se elimina `ventas.medio_id`, porque el medio va en cada pago. Antes de eliminarla, comprueba que nada la lee.
- Crear: `logica/ServicioVentas.java`, `datos/RepositorioVentas.java`, `api/VentasControlador.java` y los DTO `SolicitudVenta`, `LineaSolicitada`, `PagoSolicitado` y `VentaRegistrada`.
- Pruebas: `logica/ServicioVentasTest.java`, `api/VentasAutorizacionTest.java`, `datos/VentaConBaseTest.java`, `datos/VentaConcurrenteConBaseTest.java` y `datos/VentaConsultasConBaseTest.java`.

**Interfaces:**
- Consume:
  - `ServicioTurnos.exigirTurnoAbierto()`;
  - `ServicioAutorizacion.autorizar(...)`;
  - `CalculoDeVenta.calcular(...)` y `cambio(...)`;
  - `ServicioClientes`;
  - `RepositorioInventario.insertarMovimiento` y `fijarSaldo`, con `TipoMovimiento.EGRESO`;
  - los medios de la tarea 1.
- Produce:
  - `record SolicitudVenta(UUID claveIdempotencia, Integer clienteId /* null = consumidor final */, List<LineaSolicitada> lineas, Descuento descuentoTotal, List<PagoSolicitado> pagos, SolicitudAutorizacion autorizacion, Domicilio domicilio /* null en mostrador */)`.
  - `record Domicilio(String direccion, String ciudad, String telefono, String recibe, boolean contraEntrega)`. Esta tarea solo crea el record; hasta la tarea 8, una venta con domicilio responde 400 con el detalle «Los domicilios aún no están disponibles».
  - `record LineaSolicitada(Integer productoId, int cantidad, Descuento descuento, LocalDate entregaEstimada, String motivoEncargo)`.
  - `record PagoSolicitado(Integer medioId, BigDecimal monto, BigDecimal recibido /* solo efectivo */, String referencia)`.
  - `record VentaRegistrada(Long id, String consecutivo, BigDecimal total, BigDecimal cambio, List<LineaCalculada> lineas)`. Al vendedor se le responde sin costo.
  - `VentaRegistrada ServicioVentas.registrar(SolicitudVenta)`.
  - La ruta `POST /api/v1/caja/ventas`, que responde 201, o 200 con la misma venta si la clave ya existe.

**Reglas que se prueban:**
- Una sola transacción: bloquear los productos en orden, recalcular el precio en el servidor, calcular, insertar la venta, las líneas y los pagos, y después el EGRESO en el kárdex por cada línea.
- Si la existencia no alcanza:
  - sin autorización de ENCARGO, 409 «No hay existencia suficiente de <nombre>»;
  - con autorización, se venden las unidades disponibles y el resto queda en `cantidad_encargo`, con `entrega_estimada` y `motivo_encargo` obligatorios (400).
  - La existencia queda negativa. El tope técnico de −9 999 sigue vigente y el kárdex consigna quién autorizó.
- Cualquier descuento exige una autorización de DESCUENTO; sin ella, 403.
- Pagos:
  - la suma de los montos debe ser igual al total (400);
  - medio inactivo o ajeno: 400;
  - tarjeta sin referencia: 400;
  - transferencia sin referencia: 400;
  - efectivo con `recibido` menor que el monto: 400;
  - el cambio se calcula sobre el efectivo.
- Cada pago guarda el `cierre_id` del turno actual.
- El consecutivo es por empresa (V12).

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `ventaSimpleDescuentaExistenciaYDejaKardex`: con existencia 5 y una venta de 2, queda 3; hay un movimiento EGRESO con anterior 5 y posterior 3; `RNF-10` cuadra.
  - `elPrecioLoPoneElServidor`: el JSON no acepta `precioUnitario`; un campo desconocido da 400, o se ignora y el precio sale de V38.
  - `pagoMixtoDebeSumarElTotal`.
  - `efectivoCalculaElCambio`.
  - `sinTurnoAbiertoEs409`.
  - `descuentoSinPinEs403YNoGuardaNada`.
  - `encargoSinPinEs409`.
  - `encargoParcial`: con existencia 3 y una petición de 5 con PIN, se venden 3 y quedan 2 de encargo, y la existencia queda en −2.
  - `encargoSinMotivoOFechaEs400`.
  - `elAdministradorNoNecesitaPin`.
  - `mismaClaveDosVecesUnaSolaVenta`: el segundo envío responde 200 con el mismo id, y hay un solo EGRESO.
  - `siFallaElKardexNoQuedaVenta` (RF-41): se fuerza el fallo del segundo paso y no queda ni venta ni existencia cambiada.
  - `elSuperAdministradorRecibe403` y `empresaEnModoConsultaRecibe403`.
  - `dosCajerosLaUltimaUnidad` (con base, dos hilos): una venta y un 409; la existencia queda en 0.
  - `unaVentaHaceLasMismasConsultasConUnaQueConDiezLineas` (con base): el número de consultas no crece con las líneas, salvo las inserciones por lotes.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 8: Producto no registrado, domicilio y contra entrega

**Archivos:**
- Crear: la migración `V45__domicilios.sql`, con:
  - la tabla `domicilios_venta (venta_id, empresa_id, direccion, ciudad, telefono, recibe, entregado_en, entregado_por)`;
  - en `ventas`: `pago_contra_entrega BOOL`.
- Modificar: `ServicioVentas`, `VentasControlador` y `ServicioCajaProductos`, que suma `crearRapido`.
- Crear: el DTO `Domicilio` y `api/DomiciliosControlador.java`.
- Pruebas: `logica/ServicioDomiciliosTest.java`, `logica/ProductoRapidoTest.java` y `datos/DomiciliosConBaseTest.java`.

**Interfaces:**
- Consume: el record `Domicilio` de la tarea 7.
- Produce:
  - La lista de encargos pendientes: `GET /api/v1/caja/encargos?estado=PENDIENTE|LLEGO`, solo para el administrador. Devuelve las líneas con `cantidad_encargo > 0` que no se han entregado, con el cliente, la fecha estimada y si la existencia del producto ya es ≥ 0. En ese caso el estado es «LLEGO», que es como el diseño resuelve RF-69 en esta entrega.
  - `POST /api/v1/caja/encargos/{itemId}/entrega` marca la entrega a mano. Requiere la migración `venta_items.encargo_entregado_en` y `encargo_entregado_por`.
- `ProductoParaCaja ServicioCajaProductos.crearRapido(SolicitudProductoRapido s, SolicitudAutorizacion a)`:
  - con `s = (nombre, precioVenta, codigoBarras /* nullable */)`, exige la autorización PRODUCTO_NUEVO;
  - crea el producto con existencia 0, en la categoría «Sin categoría» (la crea si no existe) y con precio fijo propio (ADR-0025);
  - el costo queda vacío, si el esquema lo permite. Si no lo permite, usa el valor más neutro que admita y documéntalo en el informe.
- Rutas:
  - `GET /api/v1/caja/domicilios?estado=POR_DESPACHAR|POR_COBRAR`;
  - `POST /api/v1/caja/domicilios/{ventaId}/entrega`;
  - `POST /api/v1/caja/domicilios/{ventaId}/cobro`, con un cuerpo `List<PagoSolicitado>` por el total completo. Los pagos llevan el `cierre_id` del turno de quien cobra.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `productoRapidoSinPinEs403`.
  - `productoRapidoQuedaEnElCatalogoConSuPrecio`.
  - `domicilioExigeDatosCompletosYNoConsumidorFinal`.
  - `contraEntregaQuedaPorCobrarSinPagos`: `estado_pago = PENDIENTE`, la existencia se descuenta y no hay pagos.
  - `domicilioPagadoNoApareceEnPorCobrar`.
  - `cobroPosteriorVaAlTurnoDeQuienCobra`: la venta se hizo en el turno A y se cobra en el turno B; el pago lleva el `cierre_id` de B.
  - `cobroParcialEs400`: es una decisión del orquestador recogida en el diseño.
  - `cobrarDosVecesEs409`.
  - `marcarEntregadoRegistraQuienYCuando`, y `entregarDosVecesEs409`.
  - `lasListasNoMezclanEmpresas`.
  - `encargoPasaALlegoCuandoLaExistenciaVuelveACero`: con existencia −2, un ingreso de 2 cambia el estado a LLEGO.
  - `entregarEncargoRegistraQuienYCuando`, `entregarEncargoDosVecesEs409` y `elVendedorNoVeLaListaDeEncargos` (403).
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 9: Devoluciones, anulaciones y saldo a favor (RF-68)

**Archivos:**
- Crear: la migración `V46__devoluciones_y_saldo_a_favor.sql`, con:
  - `devoluciones (devolucion_id, empresa_id, venta_id, motivo, autorizado_por, registrado_por, cierre_id, creado_en)`;
  - `devolucion_items (devolucion_id, producto_id, cantidad, valor)`;
  - `reembolsos (devolucion_id, medio_id, monto, referencia)`;
  - `saldos_cliente (empresa_id, cliente_id, saldo)`;
  - `movimientos_saldo (…)`;
  - en `ventas.estado`: ANULADA y DEVUELTA_PARCIAL.
- Crear: `logica/ServicioDevoluciones.java`, `datos/RepositorioDevoluciones.java`, `api/DevolucionesControlador.java` y `docs/decisiones/ADR-00NN-saldo-a-favor.md`.
- Modificar: `ServicioVentas`, para aceptar pagos con SALDO_A_FAVOR (sin `cierre_id` de efectivo; descuentan del saldo bloqueando la fila del saldo).
- Pruebas: `logica/ServicioDevolucionesTest.java` y `datos/DevolucionesConBaseTest.java`.

**Interfaces que produce:**
- `record SolicitudDevolucion(Long ventaId, List<UnidadDevuelta> unidades /* vacía = anulación total */, String motivo, List<Reembolso> reembolsos, SolicitudAutorizacion autorizacion)`.
- `record Reembolso(Integer medioId, BigDecimal monto, String referencia)`. Con un medio de tipo SALDO_A_FAVOR, el dinero se abona al saldo del cliente.
- `DevolucionRegistrada ServicioDevoluciones.registrar(SolicitudDevolucion)`.
- La ruta `POST /api/v1/caja/devoluciones`.

**Reglas:**
- Exige la autorización DEVOLUCION; el administrador no necesita PIN.
- El valor devuelto de cada unidad es su total de línea, ya descontado, dividido por la cantidad.
- La suma de los reembolsos es igual al valor de lo devuelto (400).
- El saldo a favor solo existe para clientes identificados (400 con Consumidor final).
- El kárdex registra una DEVOLUCION por producto.
- La devolución se registra en el turno abierto de quien la hace (409 sin turno).
- Lo que se devuelve en efectivo resta del esperado de **ese** turno (tarea 10).

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `devolverSinPinEs403`.
  - `motivoVacioEs400`.
  - `devolverMasDeLoVendidoEs400`, contando también las devoluciones anteriores.
  - `anularDosVecesEs409`.
  - `devolucionParcialReintegraExistenciaYKardex`.
  - `reembolsoMixtoEfectivoYSaldo`.
  - `saldoAFavorSeUsaEnUnaCompraPosterior`, y `noSePuedeUsarMasSaldoDelQueHay` (400).
  - `consumidorFinalNoRecibeSaldo`.
  - `devolucionConDescuentoProrrateadoDevuelveLaParteJusta`: usa la venta de 6 000 y 3 000 con 1 000 de descuento de la tarea 6 y devuelve la línea de 3 000; el valor devuelto es 2 667.
  - `dosDevolucionesSimultaneasNoSuperanLoVendido` (con base, dos hilos).
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa. Escribe el ADR del saldo a favor: qué es, por qué no admite al Consumidor final y por qué no vence.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 10: Cerrar el turno (RF-45, segunda mitad)

**Archivos:**
- Modificar: `ServicioTurnos` y `RepositorioTurnos`, y agrega la ruta de cierre y de listado a `TurnosControlador`.
- Crear: los DTO `SolicitudCierre` y `ResumenDeTurno`.
- Pruebas: `logica/ServicioTurnosCierreTest.java` y `datos/CierreDeTurnoConBaseTest.java`.

**Interfaces que produce:**
- `record ResumenDeTurno(Long id, String cajero, OffsetDateTime apertura, OffsetDateTime cierre, BigDecimal baseEfectivo, BigDecimal efectivoEsperado, BigDecimal efectivoContado, BigDecimal diferencia, Map<TipoMedio, BigDecimal> porMedio, String observacion)`.
- `ResumenDeTurno cerrar(SolicitudCierre s)`, con `s = (efectivoContado, observacion)`.
- `ResumenDeTurno resumenActual()`.
- `List<ResumenDeTurno> listar(LocalDate desde, LocalDate hasta)`. El administrador ve todos los turnos de la empresa; el vendedor, solo los suyos.
- Rutas:
  - `POST /api/v1/caja/turnos/actual/cierre`;
  - `GET /api/v1/caja/turnos/actual/resumen`;
  - `GET /api/v1/caja/turnos?desde=&hasta=`.

**Regla del esperado (fijada por el diseño):** efectivo esperado = base + los pagos de tipo EFECTIVO con `cierre_id` = este turno (ventas y cobros contra entrega) − los reembolsos de tipo EFECTIVO de las devoluciones con `cierre_id` = este turno. El cambio entregado no cuenta, porque `monto` ya es el neto.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `esperadoEsBaseMasEfectivoMenosReembolsos`: base de 100 000, ventas en efectivo de 50 000 y 20 000, y un reembolso en efectivo de 5 000. El esperado es 165 000.
  - `cobrosYDevolucionesDeOtroTurnoNoCuentan`.
  - `diferenciaDistintaDeCeroExigeObservacion` (400), y con diferencia cero no la exige.
  - `turnoCerradoRechazaVentas` (409), y `cerrarDosVecesEs409`.
  - `porMedioTotalizaTarjetaYTransferencia`.
  - `elVendedorSoloVeSusTurnos`.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa. El cierre bloquea la fila del turno; una venta que llegue mientras se cierra espera al cierre y luego recibe 409.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 11: El recibo (RF-70)

**Archivos:**
- Crear: el DTO `api/dto/Recibo.java`, `logica/ServicioRecibos.java` y `logica/ReciboPdf.java`.
- Modificar: `VentasControlador`, que añade `GET /api/v1/caja/ventas/{id}/recibo` (JSON) y `GET /api/v1/caja/ventas/{id}/recibo.pdf`.
- Pruebas: `logica/ServicioRecibosTest.java` y `logica/ReciboPdfTest.java`.

**Interfaces que produce:**
- `record Recibo(String empresa, String nitEmpresa, String consecutivo, OffsetDateTime fecha, String cajero, String cliente, String identificacionCliente, List<LineaDeRecibo> lineas, BigDecimal subtotal, BigDecimal descuento, BigDecimal iva, BigDecimal total, List<PagoDeRecibo> pagos, BigDecimal cambio, List<String> marcas)`.
- `marcas` incluye «Encargo — entrega estimada: <dd/mm/aaaa>», «Domicilio» y «Pago contra entrega — por cobrar».

**Reglas:**
- El impuesto se discrimina solo si la empresa es responsable de IVA (RF-34).
- Al final dice: «Este recibo no es una factura electrónica.»
- Las fechas van en dd/mm/aaaa, porque es un documento (RNF-16).

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `reciboTraeLoQuePideRf70`.
  - `empresaNoResponsableNoMuestraIva`.
  - `marcasDeEncargoYDomicilio`.
  - `reciboDeOtraEmpresaEs404`.
  - `elPdfSeGeneraEnMenosDeTresSegundosCon20Lineas`.
  - `elPdfMideOchentaMilimetrosDeAncho`.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
  - Para el PDF, usa la librería que ya tenga el proyecto. Si no tiene ninguna, agrega una mantenida con licencia compatible y deja un ADR corto, porque es una dependencia nueva.
  - La plantilla es sobria y fácil de cambiar: el usuario diseñará el recibo después.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 12: Pantalla de la caja: vender y cobrar

**Archivos:**
- Crear:
  - `web/src/paginas/caja/Caja.jsx`, la pantalla;
  - `web/src/paginas/caja/Carrito.jsx`;
  - `web/src/paginas/caja/BuscadorProducto.jsx`, con el lector como teclado y la cámara;
  - `web/src/paginas/caja/Cobro.jsx`;
  - `web/src/paginas/caja/DialogoPin.jsx`;
  - `web/src/paginas/caja/DatosCliente.jsx`;
  - `web/src/paginas/caja/ReciboImprimible.jsx`;
  - `web/src/servicios/api/caja.js`;
  - `web/src/estilos/caja.css`;
  - una prueba `*.prueba.jsx` por cada componente.
- Modificar: `web/src/secciones.js` (`{ ruta: '/caja', etiqueta: 'Caja', roles: DE_EMPRESA, grupo: 'Operación' }`, en primer lugar) y `App.jsx`, si la ruta lo requiere.

**Comportamiento que se prueba:**
- **Sin turno abierto:** la pantalla muestra «Abrir turno», con un campo para la base. Con turno, muestra la venta.
- **Buscador:**
  - El campo tiene el foco al cargar, después de cada producto agregado y al cerrar un diálogo.
  - Enter con un código lo agrega al carrito; el mismo código otra vez suma 1 a la cantidad.
  - La cámara se ofrece solo si existe `BarcodeDetector` o la librería elegida, y siempre en un diálogo que se puede cerrar.
- **Producto no encontrado:** muestra «No encontramos ese código» con dos acciones, «Buscar por nombre» y «Crear producto (requiere administrador)».
- **Producto no vendible:** el mensaje del servidor aparece y no se agrega.
- **Cliente:**
  - Por defecto, el botón «Consumidor final» está activo y visible.
  - «Identificar cliente» pide tipo e identificación; si el cliente existe, completa sus datos; si no, pide nombre, correo y la autorización de datos.
  - Al marcar «Domicilio» se piden teléfono, dirección, ciudad, quién recibe y «¿Paga contra entrega?».
- **Cobro:**
  - Muestra el total y permite agregar pagos (efectivo con «Recibido» y el cambio calculado; tarjeta con «N.º de aprobación»; transferencia con «Referencia»).
  - El botón «Cobrar» solo se habilita cuando lo pagado es igual al total, salvo en contra entrega.
- **Autorización:**
  - Si el carrito tiene descuento, encargo o producto nuevo y el usuario es vendedor, al cobrar se abre `DialogoPin`. Este diálogo lista lo que se va a autorizar, deja elegir al administrador y pide el PIN, con un campo `inputmode="numeric"` y `autocomplete="off"`.
  - Si el PIN falla, el diálogo muestra el error del servidor sin cerrarse.
- **Recibo y reintentos:**
  - Al registrar la venta se muestra el recibo con dos acciones: «Imprimir», que usa `window.print` con `@media print` y `@page { size: 80mm auto }`, y «Descargar PDF».
  - La clave de idempotencia se genera una vez por venta en curso y se reutiliza si el envío se reintenta.
- **Sin conexión:** si `navigator.onLine` es falso o la petición falla por red, aparece `AvisoConexion` y el botón «Cobrar» queda deshabilitado. Nada se cobra a medias.
- **Vendedor:** nunca ve costo ni margen.

- [ ] **Paso 1: escribe las pruebas en rojo**, con Testing Library, simulando la API:
  - `sinTurnoMuestraAbrirTurno`;
  - `enterConCodigoAgregaYSumaCantidad`;
  - `elFocoVuelveAlBuscador`;
  - `noEncontradoOfreceBuscarYCrear`;
  - `consumidorFinalPorDefecto`;
  - `domicilioPideSusDatos`;
  - `cobrarDeshabilitadoHastaCuadrar`;
  - `cambioEnEfectivo`;
  - `vendedorConDescuentoAbrePin`;
  - `adminConDescuentoNoPidePin`;
  - `pinErradoMuestraErrorSinCerrar`;
  - `reintentoUsaLaMismaClave`;
  - `sinConexionNoCobra`;
  - `noHayCostoEnPantalla`;
  - `reciboImprimibleTieneLasMarcas`.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa con `impeccable`, en modo Operate.
  - En el escritorio: buscador arriba, carrito al centro, total y cobro a la derecha.
  - En el celular: buscador y carrito en una columna, con el total fijo abajo.
- [ ] **Paso 4:** ejecuta `npm run lint --prefix web`, `npm test --prefix web` y `npm run build --prefix web`; todo debe quedar en verde.
- [ ] **Paso 5:** haz los commits.

### Tarea 13: Pantallas del turno, domicilios, devoluciones y PIN

**Archivos:**
- Crear:
  - `web/src/paginas/caja/CierreTurno.jsx`;
  - `web/src/paginas/caja/Turnos.jsx`, el listado del administrador;
  - `web/src/paginas/caja/Domicilios.jsx`, con las pestañas «Por despachar» y «Por cobrar»;
  - `web/src/paginas/caja/Encargos.jsx`, solo para el administrador, con las pestañas «Pendientes» y «Ya llegó» y la acción «Marcar entregado»;
  - `web/src/paginas/caja/Devolucion.jsx`;
  - `web/src/paginas/caja/MediosPago.jsx`, solo para el administrador;
  - una prueba por cada uno.
- Modificar: `web/src/paginas/Empresa.jsx`, para añadir «Mi PIN de autorización» (solo el administrador). Pide el PIN actual cuando ya existe, el nuevo y su confirmación, y nunca muestra el PIN.

**Comportamiento que se prueba:**
- **Cierre del turno:**
  - Muestra la base, el efectivo esperado y los totales por medio.
  - Pide el efectivo contado y muestra la diferencia («Sobran $ 2.000» / «Faltan $ 2.000»).
  - La observación es obligatoria si la diferencia es distinta de cero.
- **Turnos:** el administrador ve todos los cierres con su diferencia; el vendedor, solo los suyos.
- **Domicilios:**
  - «Marcar entregado» funciona en las dos pestañas.
  - «Registrar cobro» en «Por cobrar» abre el mismo componente `Cobro` de la tarea 12, por el total.
- **Devolución:**
  - Se busca la venta por su consecutivo y se eligen las unidades. El motivo es obligatorio.
  - El reembolso se reparte entre efectivo, el mismo medio y saldo a favor; el saldo a favor se deshabilita con Consumidor final.
  - El vendedor puede preparar la devolución, y al confirmar se abre `DialogoPin` con DEVOLUCION.
- **Medios de pago:** activar o desactivar; el último activo no se puede desactivar y el mensaje lo explica.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `cierreMuestraSobranteYFaltante`;
  - `observacionObligatoriaConDiferencia`;
  - `vendedorNoVeTurnosAjenos`;
  - `domiciliosPestanasYAcciones`;
  - `devolucionPideMotivoYPin`;
  - `saldoAFavorDeshabilitadoConConsumidorFinal`;
  - `pinNuncaSeMuestra`;
  - `noSePuedeDesactivarElUltimoMedio`.
  - `encargosSoloParaElAdministrador`.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa con `impeccable`.
- [ ] **Paso 4:** ejecuta lint, test y build del front; todo debe quedar en verde.
- [ ] **Paso 5:** haz los commits.

### Tarea 14: Verificación de punta a punta y documentación

**Archivos:**
- Crear: `database/verificar_caja.sh`, al estilo de los `verificar_*.sh` existentes. Recorre un turno completo contra una base desechable:
  1. abrir el turno;
  2. una venta mixta;
  3. un encargo con PIN;
  4. un domicilio contra entrega;
  5. el cobro posterior;
  6. una devolución parcial con saldo a favor;
  7. el cierre con su diferencia;
  8. la comprobación de que el kárdex cuadra (RNF-10).
- Modificar: `docs/decisiones/README.md` (filas de los ADR nuevos) y, si existe, `docs/architecture.md` o su equivalente.
- Pruebas: el script mismo y la suite completa.

- [ ] **Paso 1:** escribe el script; debe fallar contra develop, donde no hay rutas de caja.
- [ ] **Paso 2:** ejecútalo en la rama; todo debe quedar en verde.
- [ ] **Paso 3:** añade el script a la CI, si las demás verificaciones de `database/` ya están en ella.
- [ ] **Paso 4:** pasa la suite completa: backend sin base y con base, y lint, test y build del front.
- [ ] **Paso 5:** haz los commits.
