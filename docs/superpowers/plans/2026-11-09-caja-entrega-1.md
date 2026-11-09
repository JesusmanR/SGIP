# Caja (Sprint 9), entrega 1: plan de implementación

> **Para agentes:** SUB-SKILL REQUERIDA: usa superpowers:subagent-driven-development (recomendada) o superpowers:executing-plans para implementar este plan tarea por tarea. Los pasos usan casillas (`- [ ]`).

**Objetivo:** que una empresa venda en el mostrador y a domicilio, con turnos de caja, cobro mixto, encargos, devoluciones, autorizaciones con PIN y recibo, sin que el vendedor pueda saltarse al administrador.

**Arquitectura:**
- Un módulo «caja» en el backend existente (`api` → `logica` → `datos`), con JdbcClient para las escrituras transaccionales, como `ServicioInventario` y `ServicioCompras`.
- El cálculo de la venta (descuentos, prorrateo, impuestos y totales) es una clase pura y sin base, `CalculoDeVenta`. El servicio solo la orquesta dentro de una transacción.
- **Dos capas por operación.** La fachada (`ServicioVentas`, `ServicioDevoluciones`) **no** es transaccional: valida la autorización con PIN y después llama a un bean transaccional aparte (`RegistradorDeVenta`, `RegistradorDeDevolucion`). Son beans distintos para que el proxy de Spring aplique la transacción (una llamada dentro de la misma clase la ignoraría).
- El front añade la sección `/caja` y el campo de PIN en «Mi empresa».

**Stack:** Java 25, Spring Boot 4.1, JdbcClient y JPA, MySQL 9.7, Flyway, React 18.3.1, Vite y Vitest.

**Spec:** `docs/superpowers/specs/2026-11-09-caja-design.md`. Ese documento manda; la entrega 2 (cotizaciones) tendrá su propio plan.

## Restricciones globales

- **Rama:** `feature/caja`, creada desde `develop`. La rama D (`feature/prueba-30-dias`) ya está integrada y su migración V39 es la última, así que las migraciones de este plan van de la **V40 a la V46**, en `backend/src/main/resources/db/migration/` (ADR-0023). Los ADR toman 0032 para el PIN y 0033 para el saldo a favor; además se enmienda el ADR-0014 (tarea 8).
- **Roles:**
  - Venden el administrador de empresa y el vendedor. Los nombres reales de rol son `ADMINISTRADOR`, `VENDEDOR`, `CLIENTE` y `SUPER_ADMIN`; no están en `ConfiguracionSeguridad`, sino en los `@PreAuthorize` de los controladores (por ejemplo `PruebaControlador`) y en el prefijo `ROLE_` de `FiltroSesion`. Consulta también `AutorizacionPorRolTest`.
  - Todo controlador de la caja lleva `@PreAuthorize("hasAnyRole('ADMINISTRADOR','VENDEDOR')")`. Los que son solo del administrador llevan `hasRole('ADMINISTRADOR')`.
  - `CLIENTE` y `SUPER_ADMIN` reciben 403 en todo `/api/v1/caja/**`. **Cada `*AutorizacionTest` de este plan incluye la prueba de que `CLIENTE` recibe 403**; la tarea 14 añade una prueba transversal.
  - Una empresa sin escritura (modo consulta o prueba vencida) recibe 403 en las escrituras, por `FiltroEmpresaActiva`.
- **Datos:**
  - Toda sentencia filtra por `empresa_id` (ADR-0011) y cada subconsulta repite el filtro.
  - Las tablas nuevas siguen el patrón de claves foráneas compuestas `(empresa_id, x_id)` de V11 y V31.
  - El vendedor nunca recibe `costo` ni `utilidad` en ninguna respuesta (decisión del 2026-10-31).
- **Cálculo y fechas:**
  - El precio lo calcula **solo** el servidor con las vistas de V38. El cliente nunca envía un precio unitario. **El precio de la vista es SIN IVA**; el IVA se suma encima (`total_contado = precio · (1 + tarifa)`).
  - Dinero en `BigDecimal`, en pesos enteros: el precio al peso con HALF_UP (RNF-16), el IVA de cada línea con HALF_UP a pesos y los descuentos en porcentaje redondeados a pesos enteros. Cantidades enteras (ADR-0013).
  - «Hoy» y «ahora» salen de `Hoy.de(reloj)` y del `Clock` de la aplicación (ADR-0030), también `ventas.fecha` y los instantes del turno: no se usa el `DEFAULT CURRENT_TIMESTAMP` de la base.
- **Autorización con PIN y transacciones:**
  - El PIN se valida **antes** de abrir la transacción de la operación, en la fachada. `ServicioAutorizacion.autorizar` corre en su propia transacción (`REQUIRES_NEW`, con `noRollbackFor` de las excepciones de PIN) y persiste los intentos, el bloqueo y la auditoría aunque la operación falle después.
  - Devuelve un `Autorizacion` en memoria que el bean transaccional consume dentro de la misma petición. Nunca se guarda ni se reutiliza.
- **Bloqueos.** Orden global de los que toma la caja:
  1. el **turno** (`cierres_caja`): `FOR SHARE` en `exigirTurnoAbierto()` y `FOR UPDATE` en el cierre;
  2. la **venta** (`FOR UPDATE`), solo en devoluciones y cobros contra entrega;
  3. los **productos**, con `SELECT … FROM productos WHERE empresa_id=:e AND producto_id IN (:ids) ORDER BY producto_id FOR UPDATE` y `FORCE INDEX (PRIMARY)`, como hace `RepositorioImportacion.bloquearProductosDeLaCarga`;
  4. el **saldo del cliente** (`saldos_cliente`, `FOR UPDATE`);
  5. el **consecutivo** (`consecutivos`, `FOR UPDATE`), como último bloqueo antes de insertar. Si hace falta más de uno, en el orden `PRODUCTO_RAPIDO`, `VENTA`, `DEVOLUCION`;
  6. el **kárdex**.

  La caja no toma el bloqueo de la fila de empresa. Cada tarea que bloquea lo declara en su prueba de concurrencia.
- **Errores:**
  - 400 para un dato inválido; 403 para falta de permiso o de autorización; 404 para algo ajeno o inexistente; 409 para un conflicto de estado.
  - El texto va en español, sin eco del dato enviado, por `ManejadorErrores`.
- **Rendimiento (RNF-03):**
  - El número de consultas de una operación no depende del número de líneas: se protege con una prueba de conteo con base, como las existentes de `ListadoDeOrdenesConsultasConBaseTest`. **Los 500 ms de RNF-03 se cubren con ese conteo**; no hay aserción de milisegundos en la CI.
  - La tarea 14 mide los tiempos de «buscar por código» y «registrar una venta» y los anota en su informe.
- **Proceso:**
  - TDD: commit `test:` en rojo antes del `feat:`/`fix:`.
  - Las pruebas con base llevan `@Tag("conBase")` y corren en un mysqld propio con carpeta y puerto únicos (`C:/dev/SGIP/.superpowers/sdd/2026-11-03-puesta-a-punto/reglas-implementador.md`; ese archivo es local, `.superpowers` no se versiona a propósito).
  - Commits en español con `Co-Authored-By` y el modelo real.
  - **Documento de requisitos:** ninguna tarea de este plan lo edita. El usuario decidió que los cambios del apartado 15 del diseño se acuerdan a medida que avanza el desarrollo.
- **UI:**
  - Skill `impeccable` en modo Operate; lee `reference/craft-floor.md` antes de editar.
  - `tokens.css` es la única fuente de color.
  - Pesos colombianos sin decimales y fechas según RNF-16.
  - En el celular, tarjetas (`TablaOTarjetas`).
  - Hojas de estilo según `estilos/pantallas.prueba.js`, `secciones.prueba.js` y `selectores.prueba.js`.
- **Lo que hay en la base (léelo antes de cada migración):** las tablas `ventas`, `venta_items`, `clientes`, `cierres_caja` y `medios_pago` ya existen (V4, V9, V10, V11, V12, V31 y V34). Estos son los hechos que el plan da por comprobados; si algo no coincide al implementar, manda la migración:
  - `clientes` usa `razon_social` y `nit`, con `UNIQUE (empresa_id, tipo_documento, nit)`, y `ck_clientes_autorizacion` exige `medio_autorizacion` cuando hay `autorizacion_datos`.
  - `empresas.ciudad` existe (nula). `empresa_config` **no** tiene ciudad.
  - `productos.costo` es `NOT NULL CHECK (> 0)`, y `proveedor_id`, `categoria_id` y `codigo` son `NOT NULL`. `v_precios_producto` hace JOIN interno con `proveedores` y `categorias`.
  - `venta_items` tiene `UNIQUE (venta_id, producto_id)`, `costo_unitario NOT NULL CHECK (> 0)` y tres columnas **generadas** (`subtotal`, `valor_iva`, `utilidad`) que ignoran el descuento. `ventas.total` también es generada (`subtotal − descuento + iva + envio`).
  - `ventas` ya tiene `sin_existencia`, `autorizada_por`, `motivo_sin_existencia` y `reposicion_pendiente` (V9, con `ck_ventas_sin_existencia`), `estado_pago`, `cierre_id` y `medio_id` (V10), y `cotizacion_id`.
  - `medios_pago.tipo` es `ENUM('EFECTIVO','TARJETA','TRANSFERENCIA','CREDITO')` y la V10 sembró «Crédito» para la empresa 1.
  - `movimientos_inventario` no tiene columna de autorizador; `ck_movimientos_coherencia` fija el signo de cada tipo y el piso de la existencia es −9 999.
  - No existe ningún generador de consecutivos de venta: `UNIQUE (empresa_id, consecutivo)` solo impide repetirlo.

## Foco de revisión

1. **Doble envío de la misma venta** (doble clic, reintento por red lenta, también en paralelo): debe quedar una sola venta y un solo descuento de inventario. Lo cubre la tarea 7.
2. **Un vendedor que manda en el JSON un descuento, campos de encargo o un producto nuevo sin PIN válido:** 403 y nada guardado. Lo cubren las tareas 7 y 8. La falta de existencia **sin** pedir encargo es otra cosa: 409.
3. **El PIN de un administrador de otra empresa, o el de un usuario que no es administrador:** 403, y el fallo se cuenta contra quien lo intentó. Lo cubre la tarea 3.
4. **Cinco PIN errados por la venta:** los intentos quedan contados aunque la venta se revierta. Lo cubren las tareas 3 y 7.
5. **Dos cajeros venden la última unidad a la vez sin autorización de encargo:** uno vende y el otro recibe 409; la existencia nunca queda negativa sin autorización. Lo cubre la tarea 7.
6. **Una venta y el cierre del mismo turno a la vez:** ningún pago queda fuera del esperado. Lo cubre la tarea 10.
7. **Cerrar un turno con cobros contra entrega y devoluciones en efectivo de otros turnos:** el esperado solo cuenta el dinero que pasó por **este** turno. Lo cubre la tarea 10.
8. **Los importes guardados con descuento:** `venta_items` y `ventas` cuadran con `CalculoDeVenta`, sin columnas generadas que los contradigan. Lo cubre la tarea 7.

---

### Tarea 1: Medios de pago por empresa (RF-43, M17)

**Archivos:**
- Crear: la migración `V40__medios_de_pago_de_cada_empresa.sql`.
- Crear: `logica/ServicioMediosPago.java`, `datos/RepositorioMediosPago.java`, `api/MediosPagoControlador.java` y los DTO `api/dto/MedioDePago.java` y `api/dto/SolicitudMedioPago.java`.
- Modificar: `logica/ServicioPlataforma.java`, para que el alta siembre los tres medios y el medio de sistema.
- Pruebas: `logica/ServicioMediosPagoTest.java`, `api/MediosPagoAutorizacionTest.java` y `datos/MediosPagoConBaseTest.java`.

**Interfaces que produce:**
- `record MedioDePago(Integer id, String nombre, TipoMedio tipo, boolean activo)`.
- `enum TipoMedio { EFECTIVO, TARJETA, TRANSFERENCIA, SALDO_A_FAVOR }`.
- Métodos de `ServicioMediosPago`:
  - `List<MedioDePago> activos()`;
  - `List<MedioDePago> todos()`;
  - `MedioDePago crear(SolicitudMedioPago)`;
  - `MedioDePago cambiarActivo(Integer id, boolean activo)`.
- Rutas:
  - `GET /api/v1/caja/medios-pago`: los medios activos; lo usan el administrador y el vendedor. El medio de sistema SALDO_A_FAVOR nunca aparece aquí (va inactivo).
  - `GET|POST|PATCH /api/v1/medios-pago`: solo el administrador. `todos()` incluye SALDO_A_FAVOR, marcado como de sistema.

**Reglas de la migración (en este orden, porque el `MODIFY` falla con filas que usan el valor que se quita):**
1. Donde `ventas.medio_id` apunte a un medio de tipo CREDITO, ponerlo en NULL. Después, borrar los medios de tipo CREDITO (la V10 sembró «Crédito» para la empresa 1).
2. Ajustar el ENUM: se quita `CREDITO`, que no se usa, y se añade `SALDO_A_FAVOR`.
3. Sembrar «Efectivo», «Tarjeta» y «Transferencia» (activos) en cada empresa que no tenga ningún medio. Una empresa que ya tiene alguno no se toca.
4. Añadir a **todas** las empresas el medio de sistema «Saldo a favor» (SALDO_A_FAVOR), **inactivo**.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `elAltaSiembraEfectivoTarjetaYTransferencia`: el alta de una empresa deja exactamente estos tres medios activos: «Efectivo» (EFECTIVO), «Tarjeta» (TARJETA) y «Transferencia» (TRANSFERENCIA).
  - `elAltaSiembraElSaldoAFavorInactivo`: el alta deja además «Saldo a favor» (SALDO_A_FAVOR) inactivo; los activos siguen siendo tres.
  - Migración, con base:
    - `unaEmpresaSinMediosRecibeLosTresAlMigrar` (M17);
    - `unaEmpresaConMediosNoSeToca`;
    - `elMedioCreditoSeRetiraAunqueUnaVentaLoUsara`: una venta con `medio_id` apuntando a «Crédito» queda con `medio_id` en NULL y el `MODIFY` no falla;
    - `todaEmpresaTieneElSaldoAFavorInactivo`.
  - `desactivarElUnicoMedioActivoEs409`: el medio de sistema no cuenta como activo.
  - `unMedioDeOtraEmpresaEs404`.
  - `elVendedorListaLosActivosPeroNoAdministra`: 200 en `/caja/medios-pago` y 403 en `/medios-pago`.
  - `elSaldoAFavorNoSePuedeCrearNiActivarNiDesactivarAMano`: 400. Lo gestiona el sistema y lo usa la tarea 9.
  - `elClienteYElSuperAdministradorRecibenUn403`: en ambas rutas.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan por la razón correcta.
- [ ] **Paso 3:** implementa la migración, el alta, el servicio y el controlador.
- [ ] **Paso 4:** ejecuta `./mvnw -B test -DexcludedGroups=conBase`, y las pruebas con base en tu mysqld. Todo debe quedar en verde.
- [ ] **Paso 5:** haz los commits `test:` y luego `feat:`.

### Tarea 2: Clientes de la caja (RF-66)

**Archivos:**
- Crear: la migración `V41__consumidor_final_y_datos_de_cliente.sql`.
- Crear: `logica/ServicioClientes.java`, `datos/RepositorioClientes.java`, `api/ClientesControlador.java` y los DTO `Cliente` y `SolicitudCliente`.
- Modificar: `ServicioPlataforma`, para que el alta siembre el «Consumidor final».
- Pruebas: `logica/ServicioClientesTest.java`, `api/ClientesAutorizacionTest.java` y `datos/ClientesConBaseTest.java`.

**Interfaces que produce:**
- `record Cliente(Integer id, String nombre, TipoDocumento tipoDocumento, String identificacion, String correo, String telefono, String direccion, String ciudad, boolean consumidorFinal)`. El repositorio mapea `nombre` ↔ `clientes.razon_social` e `identificacion` ↔ `clientes.nit`.
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

**Reglas:**
- El «Consumidor final» se distingue por una columna `consumidor_final BOOL NOT NULL DEFAULT FALSE` con unicidad por empresa (por ejemplo, un índice único sobre una columna generada `IF(consumidor_final, 1, NULL)`). Usa `tipo_documento = 'CC'` e identificación `222222222222` (el documento genérico de la DIAN).
- La migración, para cada empresa existente: si ya hay un cliente con CC `222222222222`, lo **marca** como consumidor final (y le deja el nombre «Consumidor final»); si no, inserta uno. Nunca inserta un segundo.
- Al guardar un cliente con autorización de datos, `autorizacion_datos` toma la hora del `Clock` y `medio_autorizacion = 'CAJA'` (si no, `ck_clientes_autorizacion` rechaza la fila).
- El correo se valida con `@Email`.
- La ciudad, cuando no viene, toma `empresas.ciudad`. Si la empresa tampoco la tiene, la ciudad es obligatoria (400).

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `crearExigeNombreIdentificacionYCorreo`: si falta cualquiera de los tres, 400.
  - `crearExigeLaAutorizacionDeDatos`: sin la marca, 400 (RNF-18).
  - `crearGuardaElMedioDeAutorizacionCaja`: con base; `medio_autorizacion = 'CAJA'` y no choca con el CHECK.
  - `laIdentificacionEsUnicaPorEmpresa`: la misma identificación en la misma empresa da 409; en otra empresa, 201.
  - `porIdentificacionDevuelveLosDatosGuardados`.
  - `cadaEmpresaTieneSuConsumidorFinal`: lo crean el alta y la migración para las empresas existentes. No se puede editar (409).
  - `laMigracionMarcaElClienteConElDocumentoGenerico` (con base): una empresa con un cliente CC `222222222222` queda con ese cliente como consumidor final y sin duplicados.
  - `laCiudadSaleDeLaEmpresaSiNoViene`, y `sinCiudadNiEnLaEmpresaEs400`.
  - `domicilioExigeTelefonoDireccionYCiudad`: aplicado a un cliente sin teléfono, da 400.
  - `elConsumidorFinalNuncaSirveParaDomicilio`: da 400.
  - `elClienteYElSuperAdministradorRecibenUn403`.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** pasa la suite completa, sin base y con base.
- [ ] **Paso 5:** haz los commits.

### Tarea 3: PIN de autorización del administrador

**Archivos:**
- Crear: la migración `V42__pin_del_administrador.sql`, que añade a `usuarios`:
  - `pin_hash VARCHAR(255) NULL`, `pin_intentos TINYINT UNSIGNED NOT NULL DEFAULT 0` y `pin_bloqueado_en DATETIME NULL`: los intentos fallidos **contra el PIN de ese administrador**;
  - `pin_errados_emitidos TINYINT UNSIGNED NOT NULL DEFAULT 0` y `pin_emision_bloqueada_en DATETIME NULL`: los intentos fallidos **de ese usuario contra un objetivo inválido** (ver las reglas).
- Modificar: `logica/PoliticaDeBloqueo.java`, con la sobrecarga `boolean vencido(boolean marcado, LocalDateTime desde, LocalDateTime ahora)` (y su par `bloqueadoHoy`). Hoy solo reciben un `Usuario` y leen los campos de la cuenta; la regla del plazo no se duplica en el PIN.
- Crear: `logica/ServicioAutorizacion.java`, `logica/ExcepcionAutorizable.java` (el enum), `logica/Autorizacion.java` (el record), `logica/PinInvalidoException.java`, `logica/PinBloqueadoException.java`, `logica/AutorizacionRequeridaException.java` y `api/PinControlador.java`, con los DTO `SolicitudAutorizacion`, `SolicitudPin`, `EstadoPin` y `AdministradorParaAutorizar`.
- Crear: `docs/decisiones/ADR-0032-autorizacion-con-pin.md`, con su fila en el README.
- Pruebas: `logica/ServicioAutorizacionTest.java`, `api/PinControladorTest.java`, `api/PinAutorizacionTest.java` y `datos/PinConBaseTest.java`.

**Interfaces que produce:**
- `enum ExcepcionAutorizable { DESCUENTO, ENCARGO, PRODUCTO_NUEVO, DEVOLUCION }`.
- `record Autorizacion(Integer administradorId, Set<ExcepcionAutorizable> cubre)`, con `void exigir(ExcepcionAutorizable)`, que lanza `AutorizacionRequeridaException` (403) si no la cubre.
- `record SolicitudAutorizacion(Integer administradorId, String pin)`. El DTO viaja dentro de las peticiones de las tareas 7 y 9.
- Métodos de `ServicioAutorizacion`:
  - `Autorizacion autorizar(SolicitudAutorizacion s, Set<ExcepcionAutorizable> necesarias)`. Corre en `REQUIRES_NEW`, con `noRollbackFor = {PinInvalidoException.class, PinBloqueadoException.class}`.
  - `void fijarPin(String pinActual, String pinNuevo)`, solo para el administrador de su propia cuenta. `pinActual` es nulo la primera vez.
  - `EstadoPin estadoDelPin()`, con `record EstadoPin(boolean configurado)`.
  - `List<AdministradorParaAutorizar> administradores()`, que devuelve solo el id y el nombre de los administradores activos de la empresa que tienen PIN.
- Rutas:
  - `PUT /api/v1/perfil/pin`: solo el administrador.
  - `GET /api/v1/perfil/pin`: solo el administrador; responde `{ "configurado": true|false }` y nunca el PIN.
  - `GET /api/v1/caja/administradores`: el administrador y el vendedor.

**Reglas de `autorizar`:**
- `necesarias` vacío: devuelve una autorización que no cubre nada, ignora cualquier PIN recibido y no cuenta intentos.
- El usuario en sesión es administrador: devuelve una autorización propia que cubre `necesarias`, sin PIN (`s` puede ser nulo y, si viene, se ignora).
- Si no, `s` es obligatorio (403 si falta).
  - **Objetivo válido** = administrador activo de la **misma empresa** con PIN. Un PIN errado cuenta contra el **objetivo** (`pin_intentos`).
  - **Objetivo inválido** (de otra empresa, que no es administrador, inactivo o sin PIN) = 403 con el mismo texto que un PIN errado, y el fallo cuenta contra el **solicitante** (`pin_errados_emitidos`), con el mismo plazo y el mismo máximo (ADR-0028). Así quien tantea no puede bloquear el PIN de un administrador ajeno.
  - Un solicitante bloqueado por esa vía recibe `PinBloqueadoException` aunque el PIN que mande sea correcto.
- La verificación bloquea la fila del administrador (`FOR UPDATE`) para que dos intentos simultáneos no se salten el contador.
- Cada autorización es de una sola petición: el `Autorizacion` vive en memoria y no se guarda.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `elPinTieneDeCuatroASeisDigitos`: «123» y «1234567» dan 400, y también «12a4».
  - `elPinSeGuardaDerivadoYNuncaSeDevuelve`: `pin_hash` no contiene el PIN y ninguna respuesta lo incluye. Se usa el mismo derivador que las contraseñas (ADR-0002, `util/Seguridad`).
  - `elPinNoPuedeSerIgualALaContrasena`: da 400 (se compara con `Seguridad.verificar` contra `clave_hash`).
  - `elEstadoDelPinDiceSiEstaConfigurado`: `GET /perfil/pin` responde `configurado=false` y luego `true`; un vendedor recibe 403.
  - `pinCorrectoAutorizaLasExcepcionesPedidas`: `cubre` es igual a `necesarias`.
  - `sinNecesariasNoSeValidaNiSeCuentaElPin`.
  - `elAdministradorNoNecesitaPin`.
  - `pinDeUnAdministradorDeOtraEmpresaEs403YCuentaContraElSolicitante`: el contador del administrador ajeno no cambia y `pin_errados_emitidos` del solicitante sube en 1.
  - `pinDeUnUsuarioQueNoEsAdministradorEs403YCuentaContraElSolicitante`.
  - `cincoFallosBloqueanElPinQuinceMinutos`:
    - con un `Clock` fijo, el sexto intento da `PinBloqueadoException` (403), aunque el PIN sea correcto;
    - a los 15 min + 1 s vuelve a aceptar y el contador parte de cero;
    - el bloqueo se escribe en `auditoria_accesos`.
  - `cincoObjetivosInvalidosBloqueanAlSolicitante`: el sexto intento del vendedor da 403 aunque ahora apunte a un administrador y un PIN válidos.
  - `losIntentosSeCuentanPorAdministrador`: los fallos contra el administrador A no bloquean al administrador B.
  - `unAciertoReiniciaLosIntentos`.
  - `administradoresListaSoloLosQueTienenPinYSonDeLaEmpresa`.
  - `losIntentosPersistenAunqueLaOperacionFalle` (con base): una operación externa en transacción que llama a `autorizar` con PIN errado y revierte deja `pin_intentos` y la fila de `auditoria_accesos` escritos.
  - `elClienteYElSuperAdministradorRecibenUn403`: en `/caja/administradores`; y el vendedor recibe 403 en `/perfil/pin`.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
  - El bloqueo reutiliza la forma de `PoliticaDeBloqueo` (con la sobrecarga nueva) y el parámetro `sgip.seguridad.minutos-de-bloqueo` (ADR-0028), sin duplicar la regla.
  - Escribe el ADR-0032: por qué es un PIN y no la contraseña; por qué se elige el nombre del administrador en vez de probar contra todos; el bloqueo; que se valida **antes** de la transacción y en `REQUIRES_NEW` para que los intentos sobrevivan a la reversión; que es de una sola petición; el contador del solicitante cuando el objetivo es inválido; el **riesgo residual** (un vendedor puede mantener bloqueado el PIN de un administrador de su propia empresa con cinco fallos cada quince minutos, a costa de quedar él mismo en el registro); y la evaluación de un *pepper* de servidor, porque un PIN de 4 a 6 dígitos derivado con PBKDF2 se puede romper por fuerza bruta si se filtra la base.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 4: Abrir el turno y consultar el turno actual (RF-45, primera mitad)

**Archivos:**
- Crear: la migración `V43__turno_de_caja.sql`, que añade a `cierres_caja` la columna `base_efectivo DECIMAL(14,2) NOT NULL DEFAULT 0 CHECK (base_efectivo >= 0)` y la unicidad del turno abierto por cajero (ver el paso 3). **No cambia `esperado`**: lo calcula y lo guarda la tarea 10, sin cambio de esquema.
- Crear: `logica/ServicioTurnos.java`, `datos/RepositorioTurnos.java`, `api/TurnosControlador.java` y los DTO `Turno` y `SolicitudApertura`.
- Pruebas: `logica/ServicioTurnosTest.java`, `api/TurnosAutorizacionTest.java` y `datos/TurnosConBaseTest.java`.

**Interfaces que produce:**
- `record Turno(Long id, Integer cajeroId, String cajero, OffsetDateTime apertura, BigDecimal baseEfectivo, OffsetDateTime cierre)`.
- Métodos de `ServicioTurnos`:
  - `Turno abrir(SolicitudApertura)`;
  - `Optional<Turno> actual()`, que devuelve el turno abierto del usuario en sesión;
  - `Long exigirTurnoAbierto()`: devuelve el `cierre_id` o lanza `SinTurnoAbiertoException` (409). **Lee la fila con `FOR SHARE`** y por eso solo se llama dentro de la transacción de la operación (venta, cobro o devolución): el cierre, que bloquea con `FOR UPDATE`, espera a que esas operaciones terminen, y las que lleguen después reciben 409.
- Rutas:
  - `POST /api/v1/caja/turnos`;
  - `GET /api/v1/caja/turnos/actual`, que responde 204 si no hay turno.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `abrirRegistraCajeroHoraYBase`: la hora sale del `Clock`.
  - `unaBaseNegativaEs400`.
  - `unSegundoTurnoAbiertoDelMismoCajeroEs409`. Pruébalo también con base y dos hilos: queda un solo turno abierto.
  - `exigirTurnoAbiertoSinTurnoEs409`.
  - `exigirTurnoAbiertoTomaElBloqueoCompartido` (con base): mientras una transacción lo tiene, un `UPDATE` del turno en otra espera.
  - `elTurnoDeOtroCajeroNoEsElActual`.
  - `elClienteYElSuperAdministradorRecibenUn403`.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa la unicidad del turno abierto por cajero en la base. Por ejemplo, con una columna generada `abierto_de = IF(cierre IS NULL, usuario_id, NULL)` y un índice único `(empresa_id, abierto_de)`.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 5: Buscar productos desde la caja (M7, M8, M12, M13)

**Archivos:**
- Crear: `api/CajaProductosControlador.java`, el DTO `api/dto/ProductoParaCaja.java` y `logica/ServicioCajaProductos.java`.
- Modificar: `datos/ConsultaCatalogo.java`, para añadir la búsqueda exacta por `codigo` (hoy solo existe por `codigo_barras`) y lo que la caja necesita de la tarifa (M7). La vista ya trae `tarifa_contado`, `tratamiento_iva`, `total_contado` y `base_contado`; se devuelve lo necesario sin rehacer la vista. Modificar también el mensaje de código repetido de `ServicioProducto` y `ManejadorErrores` (M12).
- Pruebas: `logica/ServicioCajaProductosTest.java`, `api/CajaProductosAutorizacionTest.java`, `datos/CodigoDeBarrasConcurrenteConBaseTest.java` (M13) y `datos/BusquedaCajaConsultasConBaseTest.java`.

**Interfaces que produce:**
- `record ProductoParaCaja(Integer id, String codigo, String nombre, String presentacion, String codigoBarras, int existencia, BigDecimal precio, BigDecimal tarifaIva, String tratamientoIva, boolean vendible, String motivoNoVendible)`. El `precio` es **sin IVA**. Sin costo, margen ni utilidad: el servicio mapea desde `ProductoEnCatalogo` (que sí trae `costo`) y nunca lo copia.
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
  - `elClienteYElSuperAdministradorRecibenUn403`.
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
- `record LineaPedida(Integer productoId, int cantidad, BigDecimal precioUnitario, BigDecimal tarifaIva, String tratamientoIva, Descuento descuento /* nullable */)`. El `precioUnitario` es sin IVA.
- `record LineaCalculada(Integer productoId, int cantidad, BigDecimal precioUnitario, BigDecimal subtotal, BigDecimal descuento, BigDecimal base, BigDecimal tarifaIva, String tratamientoIva, BigDecimal iva, BigDecimal total)`, con `subtotal = cantidad · precioUnitario` antes de descuentos, `base = subtotal − descuento` y `total = base + iva`.
- `record VentaCalculada(List<LineaCalculada> lineas, BigDecimal subtotal, BigDecimal descuento, BigDecimal iva, BigDecimal total, boolean tieneDescuento)`.
- `static VentaCalculada calcular(List<LineaPedida> lineas, Descuento descuentoTotal /* nullable */)`.
- `static BigDecimal cambio(BigDecimal totalEfectivo, BigDecimal recibido)`.

Reglas fijadas por el diseño:
- El descuento de una línea se aplica antes que el de toda la compra.
- Un descuento en porcentaje se redondea a pesos enteros con HALF_UP.
- El descuento sobre toda la compra se reparte entre las líneas en proporción al valor de cada una después de su propio descuento, al peso, con HALF_UP. El residuo de redondeo (que puede ser positivo o negativo) va a la línea de mayor valor, y si empatan, a la de menor `productoId`. La suma de los descuentos repartidos es exactamente igual al descuento total.
- El IVA de cada línea es `base · tarifa` redondeado a pesos con HALF_UP, sobre la base ya descontada. **No se reutiliza `CalculoDePrecio`**: solo convierte porcentaje de ganancia en precio y no calcula impuestos. La regla de IVA se escribe aquí y se contrasta con `total_contado` de la vista en un caso del CSV `database/muestras/casos_de_precio.csv`, o en un caso propio con las mismas cifras.
- Tratamiento guardado (M6):
  - `EXCLUIDO_HASTA_UMBRAL` se convierte en `EXCLUIDO` cuando la tarifa aplicada es 0 y en `GRAVADO` cuando es mayor que 0 (la vista ya pone `tarifa_contado` en 0 bajo el umbral);
  - un tratamiento nulo (empresa responsable sin regla, ADR-0024) se guarda como `NO_GRAVADO`, porque `venta_items.tratamiento_iva` es `NOT NULL`;
  - `GRAVADO`, `EXCLUIDO`, `EXENTO` y `NO_GRAVADO` pasan tal cual. El ENUM de la columna ya admite los cuatro (V34): no se revisa.

- [ ] **Paso 1: escribe las pruebas en rojo**, con valores exactos:
  - `sinDescuentosElTotalEsLaSumaDeLineas`: 2 × 1 500 + 1 × 3 000 a tarifa 0 da un total de 6 000.
  - `descuentoPorcentajeEnLinea`: una línea de 10 000 con 10 % da un descuento de 1 000 y una base de 9 000.
  - `descuentoPorcentajeSeRedondeaAPesos`: una línea de 1 005 con 10 % da un descuento de 101 (100,5 con HALF_UP) y una base de 904.
  - `descuentoEnPesosEnLinea`: 10 000 menos 2 500 da una base de 7 500.
  - `descuentoTotalSeRepartePorValor`: las líneas son de 6 000 y 3 000 y el descuento total es de 1 000 pesos, así que el reparto es 667 y 333. La suma da exactamente 1 000.
  - `elResiduoVaALaLineaMayor`: tres líneas de 1 000 y un descuento de 100 dan 34, 33 y 33.
  - `elResiduoNegativoVaALaLineaMayor`: tres líneas de 100 y un descuento de 50 dan 16, 17 y 17 (cada parte es 16,67 y redondea a 17; la suma da 51 y el 1 sobrante se resta a la línea de menor `productoId`).
  - `ivaSobreLaBaseDescontada`: precio de 10 000 sin IVA, tarifa 0,19 y 10 % de descuento dan descuento 1 000, base 9 000, IVA 1 710 y total 10 710. Sin descuento, el mismo caso da IVA 1 900 y total 11 900.
  - `elIvaSeRedondeaPorLineaConHalfUp`: una base de 1 003 a tarifa 0,19 da IVA 191 (190,57); dos líneas así suman IVA 382, no el redondeo de la suma.
  - `descuentoMayorQueLaLineaEs400` y `porcentajeNegativoOMayorA100Es400`, que lanzan `DescuentoInvalidoException`.
  - `excluidoHastaUmbralSeGuardaComoExcluidoOGravado` (M6).
  - `tratamientoNuloSeGuardaComoNoGravado`.
  - `cambioDeEfectivo`: con un total en efectivo de 18 700 y 20 000 recibidos, el cambio es 1 300; si se recibe menos que el total, es 400.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** ejecuta `./mvnw -B test -DexcludedGroups=conBase`; todo debe quedar en verde.
- [ ] **Paso 5:** haz los commits.

### Tarea 7: Registrar una venta en el mostrador (RF-41, RF-42, RF-44, RF-36)

**Archivos:**
- Crear: la migración `V44__venta_en_caja.sql`, con:
  - la tabla `consecutivos (empresa_id, tipo ENUM('VENTA','DEVOLUCION','PRODUCTO_RAPIDO'), siguiente BIGINT NOT NULL DEFAULT 1)`, con clave `(empresa_id, tipo)`. La fila de cada empresa se crea al primer uso (`INSERT IGNORE` y luego `SELECT … FOR UPDATE`);
  - la tabla `pagos_venta (pago_id, empresa_id, venta_id, medio_id, monto, referencia, recibido, cierre_id /* nulo solo con SALDO_A_FAVOR */, registrado_por, registrado_en)`;
  - en `ventas`:
    - `clave_idempotencia CHAR(36) NULL` con `UNIQUE (empresa_id, clave_idempotencia)`, y `huella_carga CHAR(64) NULL`;
    - `descuento_tipo` y `descuento_valor` (el descuento sobre toda la compra tal como se pidió);
    - `tipo_entrega ENUM('MOSTRADOR','DOMICILIO') NOT NULL DEFAULT 'MOSTRADOR'`;
    - **no se crea `autorizado_por`**: se reutilizan `sin_existencia`, `autorizada_por`, `motivo_sin_existencia` y `reposicion_pendiente` (V9) y `estado_pago` y `cierre_id` (V10). `forma_pago` es siempre `CONTADO` y `plazo_dias` 0;
    - **M9:** se elimina `medio_id`, porque el medio va en cada pago. Primero se suelta la clave foránea `fk_ventas_medio_emp` (V11) y su índice; verifica que ninguna vista ni código la lee (hoy ninguno lo hace);
  - en `venta_items`:
    - se eliminan y se recrean como columnas **normales** (`DECIMAL(14,2) NOT NULL`, con el mismo CHECK de no negativos) `subtotal`, `valor_iva` y `utilidad`, que hoy son generadas, ignoran el descuento y no se pueden escribir;
    - `descuento DECIMAL(14,2) NOT NULL DEFAULT 0`;
    - `cantidad_encargo INT NOT NULL DEFAULT 0`, con CHECK `0 <= cantidad_encargo <= cantidad`, y `entrega_estimada DATE NULL`, con CHECK `cantidad_encargo = 0 OR entrega_estimada IS NOT NULL`;
    - el ENUM de `tratamiento_iva` no se toca;
  - en `movimientos_inventario`: `autorizado_por INT NULL`, con clave foránea compuesta `(empresa_id, autorizado_por)` → `usuarios (empresa_id, usuario_id)`.
- Crear: `logica/ServicioVentas.java` (la fachada sin transacción), `logica/RegistradorDeVenta.java` (el bean transaccional), `datos/RepositorioVentas.java`, `datos/RepositorioConsecutivos.java`, `api/VentasControlador.java` y los DTO `SolicitudVenta`, `LineaSolicitada`, `ProductoNuevo`, `PagoSolicitado` y `VentaRegistrada`.
- Modificar: `datos/RepositorioInventario.java`: `insertarMovimiento` gana el parámetro `Integer autorizadoPor`; la firma actual se conserva como sobrecarga que delega con `null`, para no tocar a `ServicioInventario`, `ServicioCompras` ni `ServicioImportacion`.
- Pruebas: `logica/ServicioVentasTest.java`, `api/VentasAutorizacionTest.java`, `datos/VentaConBaseTest.java`, `datos/VentaConcurrenteConBaseTest.java`, `datos/PinEnVentaConBaseTest.java` y `datos/VentaConsultasConBaseTest.java`.

**Interfaces:**
- Consume:
  - `ServicioTurnos.exigirTurnoAbierto()`;
  - `ServicioAutorizacion.autorizar(...)` y `Autorizacion.exigir(...)`;
  - `CalculoDeVenta.calcular(...)` y `cambio(...)`;
  - `ServicioClientes`;
  - `RepositorioInventario.insertarMovimiento` y `fijarSaldo`, con `TipoMovimiento.EGRESO`;
  - los medios de la tarea 1.
- Produce:
  - `record SolicitudVenta(UUID claveIdempotencia, Integer clienteId /* null = consumidor final */, List<LineaSolicitada> lineas, Descuento descuentoTotal, List<PagoSolicitado> pagos, String motivoEncargo, SolicitudAutorizacion autorizacion, Domicilio domicilio /* null en mostrador */)`.
  - `record LineaSolicitada(Integer productoId /* null si trae productoNuevo */, ProductoNuevo productoNuevo /* null si trae productoId */, int cantidad, Descuento descuento, LocalDate entregaEstimada)`. Exactamente uno de `productoId` y `productoNuevo` (400 si vienen los dos o ninguno).
  - `record ProductoNuevo(String nombre, BigDecimal precioVenta /* sin IVA */, String codigoBarras /* nullable */)`. Esta tarea solo crea el record; hasta la tarea 8, una línea con `productoNuevo` responde 400 con el detalle «Los productos nuevos aún no están disponibles».
  - `record Domicilio(String direccion, String ciudad, String telefono, String recibe, boolean contraEntrega)`. Esta tarea solo crea el record; hasta la tarea 8, una venta con domicilio responde 400 con el detalle «Los domicilios aún no están disponibles».
  - `record PagoSolicitado(Integer medioId, BigDecimal monto, BigDecimal recibido /* solo efectivo */, String referencia)`.
  - `record VentaRegistrada(Long id, String consecutivo, BigDecimal total, BigDecimal cambio, List<LineaCalculada> lineas)`. Al vendedor se le responde sin costo.
  - `VentaRegistrada ServicioVentas.registrar(SolicitudVenta)`: valida la autorización y llama a `RegistradorDeVenta.registrar(SolicitudVenta, Autorizacion)`, que es `@Transactional`.
  - La ruta `POST /api/v1/caja/ventas`, que responde 201, o 200 con la misma venta si la clave ya existe. Solo el administrador y el vendedor.

**Reglas que se prueban:**
- **Autorización, antes de la transacción.** La fachada deduce de la carga las excepciones necesarias: `DESCUENTO` si hay un descuento de línea o total; `ENCARGO` si alguna línea trae `entregaEstimada` o la solicitud trae `motivoEncargo`; `PRODUCTO_NUEVO` y `ENCARGO` si hay un `productoNuevo`. Llama a `autorizar` con ese conjunto y pasa el `Autorizacion` al bean transaccional, que lo consume con `exigir`.
  - Un descuento sin autorización: 403.
  - Campos de encargo sin PIN válido: 403.
  - Una falta de existencia **sin** campos de encargo (sin pedir el encargo): 409 «No hay existencia suficiente de <nombre>», aunque el que vende sea un vendedor.
- **Una sola transacción, y en este orden:**
  1. fusionar las líneas repetidas del mismo producto sumando las cantidades; si traen descuentos distintos (o fechas de entrega distintas), 400;
  2. `exigirTurnoAbierto()` (`FOR SHARE`);
  3. bloquear los productos en orden ascendente de id;
  4. comprobar la clave de idempotencia (ver abajo);
  5. si hay pago con SALDO_A_FAVOR, bloquear el saldo del cliente;
  6. crear los productos nuevos, si los hay (tarea 8; toman el consecutivo `PRODUCTO_RAPIDO`);
  7. recalcular el precio en el servidor con **una sola** consulta a la vista (`IN`); un producto no vendible (sin precio, precio cero o desbordado) es 400 aunque el cliente lo mande;
  8. tomar el consecutivo `VENTA` (`FOR UPDATE`) y calcular con `CalculoDeVenta`;
  9. insertar la venta, las líneas y los pagos, y después el EGRESO en el kárdex por cada línea.
- **Importes guardados** (coherentes con la tarea 6):
  - `venta_items`: `precio_unitario` sin IVA; `subtotal`, `descuento`, `valor_iva` y `utilidad` tal como los calcula `CalculoDeVenta`; `utilidad = base − cantidad · costo_unitario`; `costo_unitario` lo lee el servidor del producto y nunca sale al vendedor;
  - `ventas`: `subtotal` = suma de los subtotales de línea; `descuento` = suma de todos los descuentos (de línea y repartido); `iva` = suma del IVA; `envio` = 0. El `total` generado de la base queda igual al `total` calculado.
- **Existencia insuficiente con autorización de ENCARGO:**
  - se venden las unidades disponibles y el resto queda en `cantidad_encargo`, con `entrega_estimada` por línea y `motivoEncargo` obligatorios (400). Si la existencia ya era negativa, toda la línea es encargo;
  - la venta se marca con `sin_existencia`, `reposicion_pendiente`, `autorizada_por` y `motivo_sin_existencia`. **El motivo se guarda una sola vez, en la venta** (`motivo_sin_existencia`, que ya exige `ck_ventas_sin_existencia`), porque el administrador lo dicta una vez al autorizar; la fecha estimada sí va por línea, porque cada proveedor tarda distinto;
  - la existencia queda negativa. El EGRESO del kárdex lleva `autorizado_por`;
  - el tope técnico de −9 999 sigue vigente: si la venta lo superaría, 409 (antes de que lo rechace el CHECK de la base).
- **Pagos:**
  - la suma de los montos debe ser igual al total (400);
  - medio inactivo o ajeno: 400, **salvo** SALDO_A_FAVOR, que va inactivo y lo gestiona el sistema (se implementa en la tarea 9; aquí se rechaza con 400 «El saldo a favor aún no está disponible»);
  - tarjeta sin referencia: 400;
  - transferencia sin referencia: 400;
  - efectivo con `recibido` menor que el monto: 400;
  - el cambio se calcula sobre el efectivo.
- Cada pago guarda el `cierre_id` del turno actual, y la venta también (`ventas.cierre_id`).
- **Consecutivo.** Tabla `consecutivos`, tipo VENTA. Formato `V-` seguido de 8 dígitos (`V-00000001`). Se toma con `FOR UPDATE` como último bloqueo antes de insertar.
- **Idempotencia.** `claveIdempotencia` es obligatoria.
  - Se comprueba **después** de tomar los bloqueos: la segunda petición, que esperó a la primera, devuelve 200 con la venta ya registrada y no recalcula nada.
  - Además se captura `DuplicateKeyException` al insertar: se descarta la transacción y se devuelve la venta existente, leída en una consulta nueva.
  - `huella_carga` guarda el SHA-256 de la carga normalizada, **sin** el PIN. La misma clave con otra carga: 409.
- `ventas.fecha` sale del `Clock`, no del `DEFAULT` de la base.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `ventaSimpleDescuentaExistenciaYDejaKardex`: con existencia 5 y una venta de 2, queda 3; hay un movimiento EGRESO con anterior 5 y posterior 3; `RNF-10` cuadra.
  - `elPrecioLoPoneElServidor`: el JSON trae un `precioUnitario` inventado; el campo desconocido se **ignora** y el precio sale de la vista.
  - `descuentoNoRompeElIvaGuardado` (con base): precio 10 000, tarifa 0,19 y 10 % de descuento. `venta_items` queda con `subtotal` 10 000, `descuento` 1 000, `valor_iva` 1 710; `ventas` queda con `subtotal` 10 000, `descuento` 1 000, `iva` 1 710 y `total` 10 710. Las columnas ya no son generadas.
  - `lineasRepetidasSeFusionan`: dos líneas del producto 7 con 2 y 3 unidades dejan una sola línea de 5 y un solo EGRESO.
  - `lineasRepetidasConDescuentoDistintoEs400`.
  - `productoNoVendibleEs400`: sin precio, con precio cero o desbordado, aunque el cliente lo mande.
  - `pagoMixtoDebeSumarElTotal`.
  - `efectivoCalculaElCambio`.
  - `tarjetaYTransferenciaExigenReferencia`.
  - `sinTurnoAbiertoEs409`.
  - `descuentoSinPinEs403YNoGuardaNada`.
  - `camposDeEncargoSinPinEs403YNoGuardaNada`.
  - `faltaDeExistenciaSinPedirEncargoEs409`: un vendedor, sin campos de encargo.
  - `encargoParcial`: con existencia 3 y una petición de 5 con PIN, se venden 3 y quedan 2 de encargo, la existencia queda en −2, la venta queda con `sin_existencia`, `reposicion_pendiente`, `autorizada_por` y `motivo_sin_existencia`.
  - `encargoConExistenciaNegativaEsTodoEncargo`.
  - `encargoSinMotivoOFechaEs400`.
  - `elKardexGuardaQuienAutorizo`: el EGRESO de un encargo lleva `autorizado_por` del administrador; el de una venta normal lo lleva nulo.
  - `elTopeDeExistenciaEs409`: una venta que dejaría la existencia por debajo de −9 999 da 409 y no un error de la base.
  - `elAdministradorNoNecesitaPin`.
  - `elConsecutivoTieneElFormatoPorEmpresa`: `V-00000001`, y otra empresa también empieza en `V-00000001`.
  - `mismaClaveDosVecesUnaSolaVenta`: el segundo envío responde 200 con el mismo id, y hay un solo EGRESO.
  - `mismaClaveConOtraCargaEs409`.
  - `siFallaElKardexNoQuedaVenta` (RF-41): se fuerza el fallo del segundo paso y no queda ni venta ni existencia cambiada ni consecutivo consumido.
  - `laFechaSaleDelReloj`: con un `Clock` fijo, `ventas.fecha` es esa hora.
  - `unDomicilioOUnProductoNuevoRespondenNoDisponibleHastaLaTarea8`: 400 con los detalles indicados.
  - `elSuperAdministradorElClienteYLaEmpresaEnModoConsultaRecibenUn403`.
  - `dosCajerosLaUltimaUnidad` (con base, dos hilos): una venta y un 409; la existencia queda en 0.
  - `mismaClaveEnParaleloUnaSolaVenta` (con base, dos hilos): el mismo envío por dos hilos deja una sola venta y un solo EGRESO, y los dos reciben la misma venta (201 y 200).
  - `ventasSimultaneasTienenConsecutivosDistintosYSeguidos` (con base, dos hilos): `V-00000001` y `V-00000002`, sin huecos ni repetidos.
  - `cincoPinErradosPorLaVentaDejanLosIntentosContados` (con base, `PinEnVentaConBaseTest`): cinco `POST /caja/ventas` de un vendedor con un PIN errado dan 403 cada uno; después `usuarios.pin_intentos` del administrador vale 5, existe la fila de `auditoria_accesos` y no hay ninguna venta.
  - `unaVentaHaceLasMismasConsultasConUnaQueConDiezLineas` (con base): el número de consultas no crece con las líneas, salvo las inserciones por lotes.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 8: Producto nuevo dentro de la venta, domicilio, contra entrega y encargos

**Archivos:**
- Crear: la migración `V45__domicilios_y_producto_nuevo.sql`, con:
  - la tabla `domicilios_venta (venta_id, empresa_id, direccion, ciudad, telefono, recibe, entregado_en, entregado_por)`;
  - en `ventas`: `pago_contra_entrega BOOL NOT NULL DEFAULT FALSE`;
  - en `productos`: `costo_provisional BOOL NOT NULL DEFAULT FALSE`;
  - en `venta_items`: `encargo_entregado_en DATETIME NULL` y `encargo_entregado_por INT NULL` (clave foránea compuesta).
- Crear: `logica/ServicioProductoNuevo.java`, `api/DomiciliosControlador.java` y `api/EncargosControlador.java`.
- Modificar: `RegistradorDeVenta` (acepta `productoNuevo` y domicilio), `ServicioVentas` y `VentasControlador`; `datos/ConsultaCatalogo.java` y `ProductoEnCatalogo`, para exponer `costoProvisional` **solo al administrador**, con una subconsulta sobre `productos` como la de `version` (no se rehace la vista); `ServicioProducto`, para que editar el costo apague `costo_provisional`.
- Modificar: `docs/decisiones/ADR-0014-venta-sin-existencia.md`: nota posterior que enmienda el mecanismo. El encargo sigue marcando `ventas.sin_existencia`, `autorizada_por`, `motivo_sin_existencia` y `reposicion_pendiente`; ahora además guarda `cantidad_encargo` y `entrega_estimada` por línea y `autorizado_por` en el kárdex; y `reposicion_pendiente` es la marca de que la venta tuvo encargo, no el estado vigente (el estado «LLEGÓ» se calcula, ver abajo).
- Pruebas: `logica/ServicioDomiciliosTest.java`, `logica/ProductoNuevoTest.java`, `api/DomiciliosAutorizacionTest.java`, `api/EncargosAutorizacionTest.java`, `datos/DomiciliosConBaseTest.java` y `datos/ProductoNuevoConBaseTest.java`.

**Interfaces:**
- Consume: los records `Domicilio` y `ProductoNuevo` de la tarea 7 (esta tarea **no** los vuelve a crear, solo los activa).
- Produce:
  - La lista de encargos pendientes: `GET /api/v1/caja/encargos?estado=PENDIENTE|LLEGO`, solo para el administrador. Devuelve las líneas con `cantidad_encargo > 0` que no se han entregado, con el cliente, la fecha estimada y si la existencia del producto ya es ≥ 0. En ese caso el estado es «LLEGO». **El estado se calcula al consultar**; no hay trigger ni se actualiza `reposicion_pendiente` (RF-69 en esta entrega).
  - `POST /api/v1/caja/encargos/{itemId}/entrega` marca la entrega a mano (`encargo_entregado_en` y `encargo_entregado_por`), solo para el administrador.
  - Rutas de domicilios, para el administrador y el vendedor:
    - `GET /api/v1/caja/domicilios?estado=POR_DESPACHAR|POR_COBRAR`, que en «por cobrar» muestra, por venta, el **valor por cobrar** y la **antigüedad en días**;
    - `POST /api/v1/caja/domicilios/{ventaId}/entrega`;
    - `POST /api/v1/caja/domicilios/{ventaId}/cobro`, con un cuerpo `List<PagoSolicitado>` por el **saldo por cobrar actual** (`ventas.total` menos lo ya devuelto, ver la tarea 9; hasta entonces, el total). Los pagos llevan el `cierre_id` del turno de quien cobra.

**Reglas de `ServicioProductoNuevo.crear(Integer empresaId, ProductoNuevo p)`** (se invoca desde `RegistradorDeVenta`, dentro de la transacción de la venta, en el paso 6 de su orden (después de bloquear los productos existentes y el saldo del cliente); **no hay endpoint aparte**):
- La línea exige la autorización `{PRODUCTO_NUEVO, ENCARGO}` con un solo PIN, más `entregaEstimada` y `motivoEncargo` (400 si faltan), porque el producto nace con existencia 0 y toda su venta es encargo.
- Proveedor «Sin proveedor» y categoría «Sin categoría» por empresa: se crean si no existen. Hacen falta porque `proveedor_id` y `categoria_id` son `NOT NULL` y la vista hace JOIN interno con ambas.
- El código es `RAP-<n>`, con `n` del consecutivo `PRODUCTO_RAPIDO` de la empresa (sin ceros a la izquierda).
- Costo provisional de **1,00** y `costo_provisional = TRUE` (`ck_productos_costo` exige costo mayor que cero). El `costo_unitario` de la línea es ese 1,00.
- Precio con una regla `precio_fijo` propia (ADR-0025), creada por `ServicioPrecios`, vigente desde `Hoy.de(reloj)`. `precioVenta` es el precio **sin IVA**, igual que el de la vista, y debe ser un entero positivo de a lo más 12 cifras (400); la pantalla lo rotula así.
- Existencia 0 y el código de barras escaneado si viene (su unicidad da el 409 de M12).

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `productoNuevoSinPinEs403`: un vendedor, sin PIN; no queda producto, ni proveedor, ni categoría, ni regla.
  - `productoNuevoSeCreaDentroDeLaVentaConUnSoloPin`: un PIN cubre `{PRODUCTO_NUEVO, ENCARGO}`; la venta queda registrada con el producto y su EGRESO.
  - `productoNuevoQuedaEnElCatalogoConSuPrecio` (con base): aparece en `v_precios_producto` con el precio indicado, y en `GET /caja/productos?codigo=RAP-1` y `?q=`.
  - `productoNuevoSeVendeComoEncargoConExistenciaNegativa`: la existencia queda en −cantidad, `cantidad_encargo` igual a la cantidad.
  - `productoNuevoUsaSinProveedorYSinCategoria`: se crean una sola vez por empresa aunque haya dos productos nuevos.
  - `elCodigoRapidoEsConsecutivoPorEmpresa`: `RAP-1`, `RAP-2`; otra empresa también empieza en `RAP-1`.
  - `productoNuevoLlevaCostoProvisional`: `costo = 1,00` y `costo_provisional = TRUE`.
  - `editarElCostoApagaElCostoProvisional`.
  - `precioInvalidoDelProductoNuevoEs400`: cero, negativo, con decimales o de más de 12 cifras.
  - `siLaVentaFallaElProductoNuevoNoQuedaCreado`: ni el producto ni la regla ni el consecutivo.
  - `elCatalogoDelAdministradorTraeCostoProvisionalYElDelVendedorNo`.
  - `domicilioExigeDatosCompletosYNoConsumidorFinal`.
  - `contraEntregaQuedaPorCobrarSinPagos`: `estado_pago = PENDIENTE`, la existencia se descuenta y no hay pagos.
  - `domicilioPagadoNoApareceEnPorCobrar`.
  - `porCobrarMuestraValorYAntiguedad`: con un `Clock` fijo, una venta de hace 3 días muestra 3 y su valor.
  - `cobroPosteriorVaAlTurnoDeQuienCobra`: la venta se hizo en el turno A y se cobra en el turno B; el pago lleva el `cierre_id` de B.
  - `cobroParcialEs400`: es una decisión del orquestador recogida en el diseño.
  - `cobrarDosVecesEs409`.
  - `marcarEntregadoRegistraQuienYCuando`, y `entregarDosVecesEs409`.
  - `lasListasNoMezclanEmpresas`.
  - `encargoPasaALlegoCuandoLaExistenciaVuelveACero`: con existencia −2, un ingreso de 2 cambia el estado a LLEGO sin tocar `reposicion_pendiente`.
  - `entregarEncargoRegistraQuienYCuando`, `entregarEncargoDosVecesEs409` y `elVendedorNoVeLaListaDeEncargos` (403).
  - `elClienteYElSuperAdministradorRecibenUn403`: en las rutas de domicilios y de encargos.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 9: Devoluciones, anulaciones y saldo a favor (RF-68)

**Archivos:**
- Crear: la migración `V46__devoluciones_y_saldo_a_favor.sql`, con:
  - `devoluciones (devolucion_id, empresa_id, consecutivo, venta_id, motivo, autorizado_por, registrado_por, cierre_id /* nulo si no movió dinero */, creado_en)`, con `UNIQUE (empresa_id, consecutivo)`;
  - `devolucion_items (devolucion_id, empresa_id, producto_id, cantidad, valor)`;
  - `reembolsos (devolucion_id, empresa_id, medio_id, monto, referencia)`;
  - `saldos_cliente (empresa_id, cliente_id, saldo)`, con `saldo >= 0`;
  - `movimientos_saldo (movimiento_id, empresa_id, cliente_id, tipo ENUM('ABONO','USO'), monto, devolucion_id NULL, venta_id NULL, creado_en)`;
  - en `ventas.estado`: ANULADA y DEVUELTA_PARCIAL.
- Crear: `logica/ServicioDevoluciones.java` (la fachada sin transacción), `logica/RegistradorDeDevolucion.java` (transaccional), `datos/RepositorioDevoluciones.java`, `api/DevolucionesControlador.java` y `docs/decisiones/ADR-0033-saldo-a-favor.md`.
- Modificar: `RegistradorDeVenta`, para aceptar pagos con SALDO_A_FAVOR; `ServicioMediosPago`, para exceptuar el medio de sistema de la regla de «medio inactivo» de los pagos; `datos/RepositorioConsecutivos.java`, con el tipo DEVOLUCION.
- Pruebas: `logica/ServicioDevolucionesTest.java`, `api/DevolucionesAutorizacionTest.java` y `datos/DevolucionesConBaseTest.java`.

**Interfaces que produce:**
- `record UnidadDevuelta(Integer productoId, int cantidad)`. Como una venta tiene una sola línea por producto, `productoId` identifica la línea.
- `record Reembolso(Integer medioId, BigDecimal monto, String referencia)`. Con un medio de tipo SALDO_A_FAVOR, el dinero se abona al saldo del cliente.
- `record SolicitudDevolucion(Long ventaId, List<UnidadDevuelta> unidades /* vacía = anulación total */, String motivo, List<Reembolso> reembolsos, SolicitudAutorizacion autorizacion)`.
- `record DevolucionRegistrada(Long id, String consecutivo, BigDecimal valorDevuelto, BigDecimal reembolsado, BigDecimal saldoPorCobrar, String estadoVenta, List<Reembolso> reembolsos)`.
- `DevolucionRegistrada ServicioDevoluciones.registrar(SolicitudDevolucion)`: valida la autorización DEVOLUCION antes de la transacción y llama al bean transaccional.
- Rutas:
  - `POST /api/v1/caja/devoluciones`;
  - `GET /api/v1/caja/clientes/{id}/saldo`, que responde `{ "saldo": <pesos> }`; 404 si el cliente es de otra empresa; el cliente «Consumidor final» responde 0.

**Reglas:**
- Exige la autorización DEVOLUCION; el administrador no necesita PIN. El consecutivo es `D-` seguido de 8 dígitos.
- **Valor devuelto de k unidades de una línea de n:** `HALF_UP(total de línea · k / n)`, donde el total de línea es el ya descontado y con IVA; la devolución que **completa** la línea toma el resto (`total de línea` menos lo ya devuelto), de modo que la suma de todas las devoluciones de una línea es exactamente su total.
- **Reembolso = lo realmente pagado por esas unidades.**
  - Venta PAGADA: la suma de los reembolsos es igual al valor devuelto (400 si no).
  - Venta PENDIENTE (contra entrega sin cobrar): no se pagó nada, el reembolso es 0, no se exige turno abierto y la devolución no mueve dinero (`devoluciones.cierre_id` queda nulo).
  - Devolución **parcial** de una venta PENDIENTE: el valor devuelto reduce lo que queda por cobrar (`saldoPorCobrar = ventas.total − Σ devolucion_items.valor`), y el cobro de la tarea 8 pasa a ser por ese saldo actualizado. Si el saldo llega a 0 (se devolvió todo), la venta queda ANULADA con `estado_pago` ANULADA.
- Un reembolso de tarjeta o de transferencia exige `referencia` (400), porque se hace por fuera (datáfono o banco).
- El saldo a favor solo existe para clientes identificados (400 con Consumidor final).
- Pagar una venta con SALDO_A_FAVOR: bloquea la fila de `saldos_cliente` (después de los productos y antes del consecutivo), descuenta y deja un `movimientos_saldo` de tipo USO; el pago no lleva `cierre_id`; no se puede usar más saldo del que hay (400).
- El kárdex registra una DEVOLUCION por producto.
- La devolución con dinero se registra en el turno abierto de quien la hace (409 sin turno); toma el turno con `FOR SHARE`, la venta con `FOR UPDATE`, y después productos, saldo, consecutivo y kárdex.
- Lo que se devuelve en efectivo resta del esperado de **ese** turno (tarea 10).
- Anular o devolver más de lo vendido (contando las devoluciones anteriores) es 400; anular algo ya anulado es 409.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `devolverSinPinEs403`.
  - `motivoVacioEs400`.
  - `devolverMasDeLoVendidoEs400`, contando también las devoluciones anteriores.
  - `anularDosVecesEs409`.
  - `devolucionParcialReintegraExistenciaYKardex`.
  - `elConsecutivoDeDevolucionTieneElFormato`: `D-00000001`.
  - `reembolsoMixtoEfectivoYSaldo`.
  - `reembolsoQueNoCuadraConLoPagadoEs400`.
  - `reembolsoPorTarjetaOTransferenciaSinReferenciaEs400`.
  - `saldoAFavorSeUsaEnUnaCompraPosterior`, y `noSePuedeUsarMasSaldoDelQueHay` (400).
  - `elPagoConSaldoNoLlevaCierreYDejaMovimientoDeUso`.
  - `consumidorFinalNoRecibeSaldo`.
  - `elSaldoDelClienteSeConsultaYEsDeLaEmpresa`: 200 con el saldo; un cliente de otra empresa, 404.
  - `devolucionConDescuentoProrrateadoDevuelveLaParteJusta`: usa la venta de 6 000 y 3 000 con 1 000 de descuento de la tarea 6 y devuelve la línea de 3 000; el valor devuelto es 2 667.
  - `lasDevolucionesDeUnaLineaSumanSuTotal`: una línea de 1 000 con 3 unidades: devolver 1 vale 333, devolver otra 333 y devolver la última 334; juntas, 1 000. Devolver 2 de una vez vale 667.
  - `anularUnaVentaPendienteNoExigeTurnoNiReembolso`: sin turno abierto responde 200, `reembolsado` es 0, la venta queda ANULADA y la existencia vuelve.
  - `devolucionParcialDeVentaPendienteReduceLoPorCobrar`: venta de 9 000 pendiente, devolución de 3 000: `saldoPorCobrar` es 6 000.
  - `cobrarUnaVentaConDevolucionParcialCobraElSaldoActualizado`: el cobro de la tarea 8 por 9 000 da 400 y por 6 000 se acepta.
  - `dosDevolucionesSimultaneasNoSuperanLoVendido` (con base, dos hilos).
  - `elClienteYElSuperAdministradorRecibenUn403`.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa. Escribe el ADR-0033 del saldo a favor: qué es, por qué no admite al Consumidor final y por qué no vence.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 10: Cerrar el turno (RF-45, segunda mitad)

**Archivos:**
- Modificar: `ServicioTurnos` y `RepositorioTurnos`, y agrega la ruta de cierre y de listado a `TurnosControlador`.
- Crear: los DTO `SolicitudCierre` y `ResumenDeTurno`.
- Pruebas: `logica/ServicioTurnosCierreTest.java`, `api/TurnosCierreAutorizacionTest.java` y `datos/CierreDeTurnoConBaseTest.java`.

**Interfaces que produce:**
- `record ResumenDeTurno(Long id, String cajero, OffsetDateTime apertura, OffsetDateTime cierre, BigDecimal baseEfectivo, BigDecimal efectivoEsperado, BigDecimal efectivoContado, BigDecimal diferencia, Map<TipoMedio, BigDecimal> porMedio, String observacion)`.
- `ResumenDeTurno cerrar(SolicitudCierre s)`, con `s = (efectivoContado, observacion)`.
- `ResumenDeTurno resumenActual()`.
- `List<ResumenDeTurno> listar(LocalDate desde, LocalDate hasta)`. El administrador ve todos los turnos de la empresa; el vendedor, solo los suyos.
- Rutas:
  - `POST /api/v1/caja/turnos/actual/cierre`;
  - `GET /api/v1/caja/turnos/actual/resumen`;
  - `GET /api/v1/caja/turnos?desde=&hasta=`.

**Reglas:**
- Efectivo esperado = base + los pagos de tipo EFECTIVO con `cierre_id` = este turno (ventas y cobros contra entrega) − los reembolsos de tipo EFECTIVO de las devoluciones con `cierre_id` = este turno. El cambio entregado no cuenta, porque `monto` ya es el neto. El pago con SALDO_A_FAVOR no tiene `cierre_id` y no cuenta.
- `porMedio` es la suma de los pagos del turno por tipo de medio (ventas y cobros); los reembolsos solo restan en el efectivo esperado, no en `porMedio`.
- El cierre toma la fila del turno con `FOR UPDATE`: espera a las ventas, cobros y devoluciones en curso (que la leen con `FOR SHARE`) y las que lleguen después reciben 409. `esperado` y `contado` se guardan en `cierres_caja` en esa misma transacción; `diferencia` es la columna generada `contado − esperado`.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `esperadoEsBaseMasEfectivoMenosReembolsos`: base de 100 000, ventas en efectivo de 50 000 y 20 000, y un reembolso en efectivo de 5 000. El esperado es 165 000.
  - `cobrosYDevolucionesDeOtroTurnoNoCuentan`.
  - `elPagoConSaldoNoCuentaEnElEsperado`.
  - `diferenciaDistintaDeCeroExigeObservacion` (400), y con diferencia cero no la exige.
  - `turnoCerradoRechazaVentas` (409), y `cerrarDosVecesEs409`.
  - `porMedioTotalizaTarjetaYTransferencia`.
  - `elVendedorSoloVeSusTurnos`.
  - `ventaYCierreSimultaneosNingunPagoQuedaFuera` (con base, dos hilos): una venta en efectivo y el cierre del mismo turno a la vez. O la venta entra y el esperado la incluye, o la venta recibe 409; nunca queda un pago del turno fuera del esperado.
  - `elClienteYElSuperAdministradorRecibenUn403`.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 11: El recibo (RF-70)

**Archivos:**
- Crear: el DTO `api/dto/Recibo.java`, `logica/ServicioRecibos.java` y `logica/ReciboPdf.java`.
- Modificar: `VentasControlador`, que añade `GET /api/v1/caja/ventas/{id}/recibo` (JSON) y `GET /api/v1/caja/ventas/{id}/recibo.pdf`.
- Pruebas: `logica/ServicioRecibosTest.java`, `logica/ReciboPdfTest.java` y `api/RecibosAutorizacionTest.java`.

**Interfaces que produce:**
- `record Recibo(String empresa, String nitEmpresa, String consecutivo, OffsetDateTime fecha, String cajero, String cliente, String identificacionCliente, List<LineaDeRecibo> lineas, BigDecimal subtotal, BigDecimal descuento, BigDecimal iva, BigDecimal total, List<PagoDeRecibo> pagos, BigDecimal cambio, List<String> marcas)`.
- `marcas` incluye «Encargo — entrega estimada: <dd/mm/aaaa>» (por cada línea con `cantidad_encargo > 0`, con su `entrega_estimada`), «Domicilio» y «Pago contra entrega — por cobrar».

**Reglas:**
- El impuesto se discrimina solo si la empresa es responsable de IVA (RF-34). La empresa sale de `empresas` (razón social y NIT) y de `empresa_config`.
- Al final dice: «Este recibo no es una factura electrónica.»
- Las fechas van en dd/mm/aaaa, porque es un documento (RNF-16).
- Un pago con saldo a favor aparece como tal entre los pagos.
- El vendedor nunca recibe costo en el recibo.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `reciboTraeLoQuePideRf70`.
  - `empresaNoResponsableNoMuestraIva`.
  - `marcasDeEncargoYDomicilio`.
  - `reciboDeOtraEmpresaEs404`.
  - `elReciboNoTraeCosto`.
  - `elPdfSeGeneraEnMenosDeTresSegundosCon20Lineas`.
  - `elPdfMideOchentaMilimetrosDeAncho`.
  - `elClienteYElSuperAdministradorRecibenUn403`: en las dos rutas.
- [ ] **Paso 2:** ejecútalas y comprueba que fallan.
- [ ] **Paso 3:** implementa.
  - Para el PDF, usa la librería que ya tenga el proyecto (hoy solo trae `poi-ooxml`, que no genera PDF). Si no sirve, agrega una mantenida con licencia compatible y deja un ADR corto, porque es una dependencia nueva.
  - La plantilla es sobria y fácil de cambiar: el usuario diseñará el recibo después.
- [ ] **Paso 4:** pasa la suite completa.
- [ ] **Paso 5:** haz los commits.

### Tarea 12: Pantalla de la caja: vender y cobrar

**Archivos:**
- Crear:
  - `web/src/paginas/caja/Caja.jsx`, la pantalla;
  - `web/src/paginas/caja/Carrito.jsx`;
  - `web/src/paginas/caja/BuscadorProducto.jsx`, con el lector como teclado y la cámara;
  - `web/src/paginas/caja/ProductoNuevo.jsx`, el formulario del producto no registrado;
  - `web/src/paginas/caja/Cobro.jsx`;
  - `web/src/paginas/caja/DialogoPin.jsx`;
  - `web/src/paginas/caja/DatosCliente.jsx`;
  - `web/src/paginas/caja/ReciboImprimible.jsx`;
  - `web/src/servicios/api/caja.js`;
  - `web/src/estilos/caja.css`;
  - una prueba `*.prueba.jsx` por cada componente.
- Modificar: `web/src/secciones.js` y `web/src/estilos/secciones.prueba.js`, y `App.jsx`, si la ruta lo requiere. En `secciones.js` se añade la constante `VENDEN = ['ADMINISTRADOR', 'VENDEDOR']` y la entrada `{ ruta: '/caja', etiqueta: 'Caja', roles: VENDEN, grupo: 'Operación' }`, en primer lugar. **No** se usa `DE_EMPRESA`, que incluye `CLIENTE`.

**Comportamiento que se prueba:**
- **Sin turno abierto:** la pantalla muestra «Abrir turno», con un campo para la base. Con turno, muestra la venta.
- **Buscador:**
  - El campo tiene el foco al cargar, después de cada producto agregado y al cerrar un diálogo.
  - Enter con un código lo agrega al carrito; el mismo código otra vez suma 1 a la cantidad.
  - La cámara se ofrece solo si existe `BarcodeDetector` o la librería elegida, y siempre en un diálogo que se puede cerrar.
- **Producto no encontrado:** muestra «No encontramos ese código» con dos acciones, «Buscar por nombre» y «Crear producto (requiere administrador)».
- **Producto nuevo:** la acción abre `ProductoNuevo.jsx` (nombre, «Precio de venta (sin IVA)», código de barras escaneado si lo hay, fecha de entrega estimada y motivo). No llama al servidor: agrega al carrito una línea marcada «Producto nuevo — encargo». El producto se crea al cobrar, dentro de la venta, con **un solo** PIN.
- **Producto no vendible:** el mensaje del servidor aparece y no se agrega.
- **Cliente:**
  - Por defecto, el botón «Consumidor final» está activo y visible.
  - «Identificar cliente» pide tipo e identificación; si el cliente existe, completa sus datos; si no, pide nombre, correo y la autorización de datos.
  - Al marcar «Domicilio» se piden teléfono, dirección, ciudad, quién recibe y «¿Paga contra entrega?».
- **Cobro:**
  - Muestra el total y permite agregar pagos (efectivo con «Recibido» y el cambio calculado; tarjeta con «N.º de aprobación»; transferencia con «Referencia»).
  - **Saldo a favor:** la opción «Saldo a favor» solo aparece con un cliente identificado cuyo saldo (de `GET /caja/clientes/{id}/saldo`) sea mayor que 0, y su monto no puede pasar del saldo.
  - El botón «Cobrar» solo se habilita cuando lo pagado es igual al total, salvo en contra entrega.
- **Autorización:**
  - Si el carrito tiene descuento, encargo o producto nuevo y el usuario es vendedor, al cobrar se abre `DialogoPin`. Este diálogo lista lo que se va a autorizar, deja elegir al administrador (`GET /caja/administradores`) y pide el PIN, con un campo `inputmode="numeric"` y `autocomplete="off"`.
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
  - `productoNuevoSeAgregaAlCarritoSinCrearloYSeEnviaEnLaVenta`;
  - `consumidorFinalPorDefecto`;
  - `domicilioPideSusDatos`;
  - `cobrarDeshabilitadoHastaCuadrar`;
  - `cambioEnEfectivo`;
  - `saldoAFavorSoloConClienteIdentificadoConSaldo`;
  - `vendedorConDescuentoAbrePin`;
  - `vendedorConProductoNuevoAbreUnSoloPin`;
  - `adminConDescuentoNoPidePin`;
  - `pinErradoMuestraErrorSinCerrar`;
  - `reintentoUsaLaMismaClave`;
  - `sinConexionNoCobra`;
  - `noHayCostoEnPantalla`;
  - `reciboImprimibleTieneLasMarcas`;
  - en `secciones.prueba.js`: `cajaSoloParaAdministradorYVendedor` (`CLIENTE` no la ve).
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
- Modificar: `web/src/paginas/Empresa.jsx`, para añadir «Mi PIN de autorización» (solo el administrador). Lee `GET /perfil/pin`: pide el PIN actual solo cuando `configurado` es verdadero, el nuevo y su confirmación, y nunca muestra el PIN.
- Modificar: `web/src/paginas/Catalogo.jsx`, para que el administrador vea el aviso «Costo pendiente de registrar» en los productos con `costoProvisional`. El vendedor no lo ve (no ve costos).
- Modificar: `web/src/secciones.js` y `web/src/estilos/secciones.prueba.js`, con estas entradas, que usan `ADMIN` o `VENDEN` y nunca `DE_EMPRESA`:
  - `/caja/turnos`, `/caja/encargos` y `/caja/medios-pago`: solo `ADMIN`;
  - `/caja/domicilios` y `/caja/devoluciones`: `VENDEN`.

  El cierre del turno no tiene entrada de menú propia: se abre desde `/caja`.

**Comportamiento que se prueba:**
- **Cierre del turno:**
  - Muestra la base, el efectivo esperado y los totales por medio.
  - Pide el efectivo contado y muestra la diferencia («Sobran $ 2.000» / «Faltan $ 2.000»).
  - La observación es obligatoria si la diferencia es distinta de cero.
- **Turnos:** el administrador ve todos los cierres con su diferencia; el vendedor, solo los suyos (por `/caja`, sin la entrada de menú del administrador).
- **Domicilios:**
  - «Marcar entregado» funciona en las dos pestañas.
  - «Por cobrar» muestra el valor y la antigüedad de cada venta.
  - «Registrar cobro» en «Por cobrar» abre el mismo componente `Cobro` de la tarea 12, por el saldo por cobrar.
- **Devolución:**
  - Se busca la venta por su consecutivo y se eligen las unidades. El motivo es obligatorio.
  - En una venta pendiente no se pide reembolso.
  - El reembolso se reparte entre efectivo, el mismo medio (con su referencia si es tarjeta o transferencia) y saldo a favor; el saldo a favor se deshabilita con Consumidor final.
  - El vendedor puede preparar la devolución, y al confirmar se abre `DialogoPin` con DEVOLUCION.
- **Medios de pago:** activar o desactivar; el último activo no se puede desactivar y el mensaje lo explica; el medio de sistema «Saldo a favor» aparece sin interruptor.

- [ ] **Paso 1: escribe las pruebas en rojo.**
  - `cierreMuestraSobranteYFaltante`;
  - `observacionObligatoriaConDiferencia`;
  - `vendedorNoVeTurnosAjenos`;
  - `domiciliosPestanasYAcciones`;
  - `porCobrarMuestraValorYAntiguedad`;
  - `devolucionPideMotivoYPin`;
  - `devolucionDeVentaPendienteNoPideReembolso`;
  - `devolucionPorTarjetaPideReferencia`;
  - `saldoAFavorDeshabilitadoConConsumidorFinal`;
  - `pinNuncaSeMuestra`;
  - `elPinPideElActualSoloSiYaEstaConfigurado`;
  - `noSePuedeDesactivarElUltimoMedio`;
  - `encargosSoloParaElAdministrador`;
  - `catalogoAvisaCostoPendienteSoloAlAdministrador`;
  - en `secciones.prueba.js`: `rutasDeLaCajaPorRol` (`/caja/turnos`, `/caja/encargos` y `/caja/medios-pago` solo `ADMINISTRADOR`; `/caja/domicilios` y `/caja/devoluciones` para `ADMINISTRADOR` y `VENDEDOR`; `CLIENTE` no ve ninguna).
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
  4. un producto nuevo vendido con un solo PIN;
  5. un domicilio contra entrega;
  6. el cobro posterior;
  7. una devolución parcial con saldo a favor;
  8. el cierre con su diferencia;
  9. la comprobación de que el kárdex cuadra (RNF-10).
- Crear: `backend/src/test/java/co/com/bspstore/sgip/api/CajaRolesTest.java`, la prueba transversal: recorre todas las rutas de `/api/v1/caja/**` (y `/perfil/pin`) y comprueba que `CLIENTE` y `SUPER_ADMIN` reciben 403 y que el vendedor recibe 403 en las que son solo del administrador (`/medios-pago`, `/caja/encargos`, `/caja/turnos` sin filtro de propios).
- Modificar: `docs/decisiones/README.md` (filas de los ADR nuevos) y, si existe, `docs/architecture.md` o su equivalente.
- Pruebas: el script mismo y la suite completa.

**RNF-03 y rendimiento:** el script mide el tiempo de «buscar por código» y de «registrar una venta» con una base de tamaño realista y los anota en el informe de la tarea. No hay aserción de milisegundos en la CI: el presupuesto se protege con el conteo de consultas de las tareas 5 y 7.

- [ ] **Paso 1:** escribe el script y `CajaRolesTest`; deben fallar contra develop, donde no hay rutas de caja.
- [ ] **Paso 2:** ejecútalos en la rama; todo debe quedar en verde.
- [ ] **Paso 3:** añade el script a la CI, si las demás verificaciones de `database/` ya están en ella.
- [ ] **Paso 4:** pasa la suite completa: backend sin base y con base, y lint, test y build del front.
- [ ] **Paso 5:** haz los commits.
