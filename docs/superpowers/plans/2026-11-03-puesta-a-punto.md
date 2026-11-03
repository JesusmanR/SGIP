# Puesta a punto de SGIP · Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Corregir los 11 defectos de Prioridad 1 de la auditoría y aplicar los cambios de reglas del usuario (margen por producto, impuestos y UVT opcionales) para que el punto de pago (Sprint 9) no se construya sobre ellos.

**Architecture:** Cada arreglo es una rama que nace de `develop`, se empuja a GitHub, pasa la CI y `code-reviewer`, y entra a `develop` con commit de merge (`--no-ff`) antes de abrir la siguiente. Las migraciones nuevas (V32 en adelante) pasan a Flyway; V1–V31 quedan congeladas y las sigue aplicando `database/aplicar.sh`.

**Tech Stack:** Java 25, Spring Boot 4.1.1, JPA + JdbcClient, MySQL 9.7, Flyway, JUnit 5 + Mockito + MockMvc; React 18, Vite, Vitest.

**Spec:** Informe de auditoría <https://claude.ai/artifact/CHuwtQ3xWGy4ZftSgpfM8s> (tabla «Prioridad 1») y decisiones del usuario del 2026-10-31 (costo, proveedores y kárdex solo para ADMINISTRADOR; puesta a punto antes del Sprint 9) y del 2026-11-03, que mandan sobre las anteriores.

## Global Constraints

- TDD: test rojo por la razón correcta → mínimo código → refactor. Ningún código de producción sin test previo.
- Rama por tarea desde `develop`; un solo agente escribiendo; no tocar `main`. Merge `--no-ff` a `develop` solo con suite verde y aprobación de `code-reviewer`.
- Nunca incluir en un commit los archivos ajenos que hoy están sin confirmar: `docs/informe/SGIP_Informe_Inicio_Proyecto_v5.docx`, `SGIP_*.docx`, `SGIP_*.pdf`, `docs/mockups/svg/Logo-*`. Usar `git add` con rutas explícitas.
- Commits Conventional en español. Terminan con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Nunca `--no-verify`.
- Código, comentarios y mensajes de usuario en español; los comentarios explican el *por qué*, como el resto del proyecto.
- Toda consulta acota por `ContextoEmpresa.empresaActual()`; todo error de API es `ProblemDetail`; ningún mensaje expone SQL ni nombres de tablas.
- Migraciones nuevas solo en `backend/src/main/resources/db/migration/` (desde la tarea 2). V1–V31 no se editan.
- Tareas de interfaz: usar la skill `impeccable`, modo *Operate*.
- Toda prueba que necesite base lleva `@Tag("conBase")`, incluida `BackendApplicationTests`, que se etiqueta en la tarea 2.
- Verificación sin base: `cd backend && ./mvnw -B test -DexcludedGroups=conBase`.
- Verificación con base: la CI de la rama, legible sin credenciales en `https://api.github.com/repos/JesusmanR/SGIP/actions/runs?branch=<rama>` porque el repositorio es público. En este equipo MySQL está en marcha, pero sin credenciales para los agentes; no se piden ni se buscan.
- Integración sin `gh`: `git merge --no-ff` a `develop` y `git push`, autorizado por el usuario el 2026-11-03 tras CI verde y aprobación de `code-reviewer`. `main` no se toca.
- Punto de partida: `git pull --ff-only` en `develop`. La línea base local estaba 3 commits atrás (PR #22, panel P-02), que tocó `Acceso.jsx`, `Catalogo.jsx`, `App.jsx`, `api.js` y `sesion.js`; los números de línea de este plan se confirman contra el código actualizado.
- Las ramas usan los prefijos que dispara la CI: `fix/`, `feature/`, `chore/`.
- Este plan se confirma como primer commit de la rama de la tarea 1 y entra a `develop` con ella. Los números de la lista `sgip-puesta-a-punto` son los del arreglo («arreglo N»), no los de la tarea.
- Verificación web: `npm run lint --prefix web && npm test --prefix web && npm run build --prefix web`.
- Línea base: backend sin base en verde; web 88/88 antes del PR #22, que añadió pruebas; se confirma al empezar cada tarea.

## Decisiones tomadas en este plan

Cambios del usuario del 2026-11-03, que mandan sobre el informe de auditoría:

- **El margen es por producto y editable.** No se siembran márgenes automáticos. Porcentaje y precio de venta se completan entre sí, al registrar y al editar. Lo añade la tarea 12.
- **El color pasa a morado**, porque otros proveedores usan el azul verdoso. Lo aplica la tarea 13.
- **Planes por etapas, y el recibo de venta impreso va en todos.** Fuera de esta puesta a punto; ver «Después de esta puesta a punto».
- **Dos supuestos de la tarea 12, avisados al usuario:** contado y crédito siguen existiendo, y crédito es opcional; al subir el costo se conserva el dato que el usuario escribió.
- **Se puede vender sin configurar impuestos.** Se retira el bloqueo de la RN-25: muchos comercios pequeños no están obligados a cobrarlos. Una empresa nace sin cobrar IVA, y `tributacion_sin_configurar` vale 1 solo si la empresa declara ser responsable de IVA y un producto queda sin regla, como aviso que no impide vender. Lo implementa la tarea 9.
- **La UVT es opcional.** Solo la usa la empresa que activa `aplica_exclusion_umbral`. Un año sin UVT no bloquea a nadie ni tiene fecha límite. Lo ajusta la tarea 11.

Otras decisiones:

- **`reglas_iva` no se siembra en el alta:** nadie eligió todavía, y sin regla la empresa ya puede vender sin impuestos.
- **Flyway no aplica V1–V31.** V8 (demostración) va en medio de la cadena y `aplicar.sh` decide si se carga. Migrarlas exigiría reorganizar la cadena sin beneficio para este objetivo.
- **La configuración de Flyway va en código**, no en `application.yml`, que no se versiona. Es el mismo motivo de `ConfiguracionSubidas`.
- **Productos anteriores a la tarea 6** con existencia sin kárdex mostrarán `cuadra=false`. Es cierto y no se maquilla; no hay datos de producción.

## Review Focus

1. Usuario de una empresa desactivada que aún debe cambiar su clave: debe poder cambiarla y cerrar sesión. Test en tarea 7.
2. Productos sin código de barras o con el campo en blanco: varios deben convivir sin choque. Test en tarea 5.
3. Error de integridad del motor: el cliente no debe ver SQL, tablas ni restricciones. Test en tarea 3.
4. Vendedor que abre el catálogo: sin proveedores permitidos, la pantalla debe seguir cargando categorías y productos. Test en tarea 8.
5. Alta de producto cuyo movimiento inicial falla: no debe quedar el producto sin su kárdex. Test en tarea 6.

---

### Task 1: El 401 del login dice «credenciales incorrectas» (arreglo 11)

**Rama:** `fix/mensaje-login`

**Files:**
- Modify: `web/src/servicios/api.js:89-92`
- Test: `web/src/servicios/api.prueba.js`, `web/src/paginas/Acceso.prueba.jsx`

**Interfaces:** Produces: `api.entrar` rechaza con `ErrorApi(401, <detail del backend>)`.

- [ ] **Step 1: Write the failing tests**

```js
it('un 401 del login muestra el detalle del backend y no habla de sesión', async () => {
  responder(401, { detail: 'El correo o la contraseña no son correctos.' })
  await expect(api.entrar('a@b.co', 'x')).rejects.toThrow('El correo o la contraseña no son correctos.')
})
it('un 401 del login no borra una sesión que ya exista', async () => {
  guardarSesion({ token: 'vigente', nombre: 'Ana' })
  responder(401, { detail: 'x' })
  await expect(api.entrar('a@b.co', 'x')).rejects.toBeInstanceOf(ErrorApi)
  expect(haySesion()).toBe(true)
})
```

En `Acceso.prueba.jsx`: credenciales rechazadas muestran ese texto en `role="alert"` y nunca «La sesión expiró».

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm test --prefix web -- api.prueba Acceso.prueba`
Expected: FAIL; el mensaje recibido es «La sesión expiró. Vuelve a entrar.»

- [ ] **Step 3: Implement** en `pedir`: el bloque del 401 se salta cuando `ruta === '/auth/login'` y la respuesta sigue por el tratamiento general. El test existente «un 401 cierra la sesión local» debe seguir pasando.

- [ ] **Step 4: Run verificación web completa.** Expected: todo en verde.

- [ ] **Step 5: Commit** `fix(web): el rechazo del login ya no se presenta como sesión expirada`

---

### Task 2: Flyway con base en V31 (arreglo 6)

**Rama:** `chore/flyway-baseline`

**Files:**
- Modify: `backend/pom.xml`, `database/aplicar.sh:134-136`, `database/README.md`, `.github/workflows/ci.yml`, `docs/decisiones/README.md`
- Create: `backend/src/main/java/co/com/bspstore/sgip/config/ConfiguracionMigraciones.java`, `docs/decisiones/ADR-0023-flyway-desde-la-v32.md`
- Test: `backend/src/test/java/co/com/bspstore/sgip/config/ConfiguracionMigracionesTest.java`

**Interfaces:** Produces: bean `FlywayConfigurationCustomizer`; carpeta `db/migration` para V32+; las tareas 4, 5 y 11 añaden ahí sus migraciones.

- [ ] **Step 1: Write the failing test.** Aplicar el customizer a `Flyway.configure()` y afirmar:

```java
assertTrue(c.isBaselineOnMigrate());
assertEquals("31", c.getBaselineVersion().getVersion());
assertEquals("classpath:db/migration", c.getLocations()[0].getDescriptor());
```

Etiquetar además `BackendApplicationTests` con `@Tag("conBase")`.

- [ ] **Step 2: Run** `./mvnw -B test -Dtest=ConfiguracionMigracionesTest`. Expected: FAIL de compilación; la clase no existe.

- [ ] **Step 3: Implement.** Añadir `spring-boot-starter-flyway` y `org.flywaydb:flyway-mysql` sin versión propia, y `ConfiguracionMigraciones` con esos tres valores.

- [ ] **Step 4: Run the test.** Expected: PASS.

- [ ] **Step 5: Actualizar `aplicar.sh` y `database/README.md`.** La lista queda congelada en V31 y el aviso «al agregar una migración, añádela aquí» pasa a decir que V32+ van en `db/migration` y las aplica el backend al arrancar. Sobre una base vacía se ejecuta primero `aplicar.sh`.

- [ ] **Step 6: Actualizar la CI.**
  - Trabajo `esquema`: después de V31, aplicar con el cliente `mysql` los `V*.sql` de `db/migration` en orden `sort -V`, tolerando que no haya ninguno, para que `verificar_sql_del_backend.py` vea el esquema final.
  - Trabajo `backend`: tras `mvn verify`, comprobar que `flyway_schema_history` tiene una fila `BASELINE` con versión `31`. Este trabajo prueba las migraciones sobre datos, porque parte de la cadena con V8.

- [ ] **Step 7: Escribir ADR-0023 (corto) y añadirlo al índice.** Qué se adopta, por qué V1–V31 quedan fuera, cómo se arranca una base nueva y qué pasa si una migración falla.

- [ ] **Step 8: Verificación con base.** Expected: arranca con Flyway sobre MySQL 9.7. Si Flyway rechaza esa versión del motor, detenerse e informar; no cambiar de motor ni de herramienta sin decisión.

- [ ] **Step 9: Commit** `chore(bd): Flyway gobierna las migraciones desde la V32`

---

### Task 3: Errores de integridad y códigos coherentes (arreglo 5)

**Rama:** `fix/errores-de-integridad`

**Files:**
- Create: `logica/RecursoNoEncontradoException.java`, `logica/EstadoDeCargaInvalidoException.java`
- Modify: `api/ManejadorErrores.java`, `logica/ServicioCompras.java:112`, `logica/ServicioImagenes.java:65,106,133,173`, `api/ImagenControlador.java:105`, `logica/ServicioImportacion.java:132,147,180`
- Test: Create `backend/src/test/java/co/com/bspstore/sgip/api/ManejadorErroresTest.java`; modify `ServicioComprasTest`, `ServicioImagenesTest`, `ServicioImportacionTest`

(Rutas relativas a `backend/src/main/java/co/com/bspstore/sgip/`.)

**Interfaces:**
- Produces: `ManejadorErrores.integridad(DataIntegrityViolationException): ProblemDetail`.
- Produces: `new ServicioCompras(RepositorioCompras, ServicioInventario, ProveedorRepositorio)`.
- Regla: identificador en la **ruta** que no es de la empresa → 404; **referencia** en el cuerpo o en un parámetro → 422.

- [ ] **Step 1: Write the failing tests.** `ManejadorErroresTest`, con la causa envuelta dos veces para imitar a Hibernate:

```java
var e = new DataIntegrityViolationException("x", new RuntimeException(
        new SQLIntegrityConstraintViolationException("Duplicate entry for uq_productos_sku", "23000", 1062)));
assertEquals(409, manejador.integridad(e).getStatus());
assertFalse(manejador.integridad(e).getDetail().contains("uq_productos"));
```

Casos: 1062→409, 1452→422, 3819→500; ninguno de los tres expone el texto del motor.

`ServicioComprasTest`: `unProveedorAjenoSeRechazaAntesDeEscribir` espera `ReferenciaAjenaException` y `verify(compras, never()).insertarOrden(...)`.

`ServicioImagenesTest`: producto o imagen ajenos lanzan `RecursoNoEncontradoException`. `ServicioImportacionTest`: aplicar una carga no validada o rechazar una aplicada lanza `EstadoDeCargaInvalidoException`.

- [ ] **Step 2: Run** `./mvnw -B test -Dtest='ManejadorErroresTest,ServicioComprasTest,ServicioImagenesTest,ServicioImportacionTest'`. Expected: FAIL por las clases y el método que faltan.

- [ ] **Step 3: Implement.**
  - `integridad` recorre la cadena de causas hasta un `SQLException` y traduce por `getErrorCode()`. Títulos: «Dato ya registrado» y «Referencia no válida»; los demás, 500 con mensaje genérico y el error completo solo en el log.
  - `ServicioCompras.registrar` valida `proveedores.findByEmpresaIdAndId` antes de insertar.
  - `RecursoNoEncontradoException` → 404. `EstadoDeCargaInvalidoException` → 409, título «La carga no admite esa operación».
  - `ServicioImportacion:75` conserva 422.

- [ ] **Step 4: Run verificación sin base.** Expected: verde.

- [ ] **Step 5: Commit** `fix(api): los errores del motor llegan como 409 y 422, sin exponer SQL`

---

### Task 4: Editar un producto no pisa la existencia (arreglo 3)

**Rama:** `fix/edicion-no-pisa-existencia`

**Files:**
- Create: `backend/src/main/resources/db/migration/V32__version_de_producto.sql`
- Modify: `modelo/Producto.java:74`, `api/ManejadorErrores.java`, `datos/RepositorioImportacion.java:309`
- Test: Create `backend/src/test/java/co/com/bspstore/sgip/datos/ProductoConcurrenciaTest.java` (`@SpringBootTest`, `@Tag("conBase")`); modify `ManejadorErroresTest`

**Interfaces:** Produces: `Producto.version` (`@Version int`); conflicto → 409 «El producto cambió mientras lo editabas».

- [ ] **Step 1: Write the failing tests.** Con filas propias y limpieza en `@AfterEach`:
  - `unaEdicionNoDevuelveLaExistenciaAUnValorViejo`: la transacción A lee el producto; `RepositorioInventario.fijarSaldo` deja 50; A edita y guarda; la existencia en base sigue en 50.
  - `dosEdicionesSimultaneasNoSePisan`: dos transacciones leen la misma versión; la segunda en guardar lanza `ObjectOptimisticLockingFailureException`.
  - `laImportacionDelataLaEdicionEnCurso`: una edición que leyó antes de aplicar una carga falla al guardar.
  - `ManejadorErroresTest`: la excepción optimista da 409.

- [ ] **Step 2: Run** la verificación con base. Expected: FAIL; la primera termina con la existencia antigua en lugar de 50.

- [ ] **Step 3: Implement.**
  - `V32`: `ALTER TABLE productos ADD COLUMN version INT NOT NULL DEFAULT 0;`
  - `Producto`: `existencia` con `updatable = false`, y `@Version`.
  - `RepositorioImportacion:309`: sumar `p.version = p.version + 1` porque cambia el costo. `fijarSaldo` no la toca: la entidad ya no escribe `existencia`.
  - No se expone la versión al cliente: dos personas con el formulario abierto queda fuera de este arreglo.

- [ ] **Step 4: Run** verificación con base. Expected: verde.

- [ ] **Step 5: Commit** `fix(producto): editar no sobrescribe la existencia ni pisa otra edición`

---

### Task 5: Un código de barras por empresa (arreglo 4)

**Rama:** `fix/codigo-de-barras-unico`

**Files:**
- Create: `backend/src/main/resources/db/migration/V33__codigo_de_barras_unico.sql`, `database/verificar_puesta_a_punto.sh`
- Modify: `datos/ProductoRepositorio.java`, `logica/ServicioProducto.java`, `.github/workflows/ci.yml`
- Test: `ServicioProductoTest`

**Interfaces:** Produces: `existsByEmpresaIdAndCodigoBarras(Integer, String)`, `existsByEmpresaIdAndCodigoBarrasAndIdNot(Integer, String, Integer)`; `database/verificar_puesta_a_punto.sh` (mismos argumentos que `verificar_kardex.sh`), que la tarea 11 amplía.

- [ ] **Step 1: Write the failing tests** en `ServicioProductoTest`:
  - `unCodigoDeBarrasRepetidoSeRechazaAlCrear` y `...AlEditar` → `NombreRepetidoException` con el código en el mensaje.
  - `editarSinCambiarSuCodigoDeBarrasNoChocaConsigoMismo`.
  - `unCodigoDeBarrasEnBlancoSeGuardaComoNulo`: entrada `"   "`, guardado `null` y sin consultar duplicados.
  - `elCodigoDeBarrasSeGuardaSinEspaciosAlBorde`.

- [ ] **Step 2: Run** `./mvnw -B test -Dtest=ServicioProductoTest`. Expected: FAIL.

- [ ] **Step 3: Implement** la normalización y las dos comprobaciones en `crear` y `actualizar`.

- [ ] **Step 4: Escribir `V33`.** Convierte en blanco a `NULL`; deja `NULL` en los duplicados salvo en el producto de menor `producto_id`, para que la migración no falle con datos; elimina `ix_productos_barras`; añade `uq_productos_barras UNIQUE (empresa_id, codigo_barras)`. Un comentario explica que el código retirado se vuelve a capturar y por qué.

- [ ] **Step 5: Escribir `verificar_puesta_a_punto.sh` y añadirlo al trabajo `esquema`.** Comprueba con filas de prueba, y las deja limpias: mismo código en la misma empresa se rechaza; en otra empresa entra; varios `NULL` entran.

- [ ] **Step 6: Run** verificación con base y el guion. Expected: verde.

- [ ] **Step 7: Commit** `fix(catalogo): el código de barras es único dentro de cada empresa`

---

### Task 6: La existencia inicial pasa por el kárdex (arreglo 2)

**Rama:** `fix/existencia-inicial-en-kardex`

**Files:**
- Create: `api/dto/CuadreDeInventario.java`
- Modify: `logica/ServicioProducto.java:120-126`, `modelo/Producto.java`, `logica/ServicioInventario.java`, `datos/RepositorioInventario.java`, `api/InventarioControlador.java:70-84`
- Test: `ServicioProductoTest`, `ServicioInventarioTest`, `backend/src/test/java/co/com/bspstore/sgip/datos/ProductoConcurrenciaTest.java`

**Interfaces:**
- Produces: `ServicioInventario.registrarSaldoInicial(Integer productoId, int cantidad): Optional<MovimientoDeInventario>`, sin transacción propia, como `ingresarPorRecepcion`.
- Produces: `ServicioInventario.cuadre(Integer productoId): Optional<CuadreDeInventario>`.
- Produces: `RepositorioInventario.saldoActual(Integer empresaId, Integer productoId): Optional<Integer>`, sin bloqueo.
- Produces: `record CuadreDeInventario(Integer productoId, int existencia, int sumaDeMovimientos, boolean cuadra)`, que reemplaza a `InventarioControlador.Cuadre`.
- Produces: `Producto.fijarExistenciaMinima(int)`, que reemplaza a `fijarExistencias`.

- [ ] **Step 1: Write the failing tests.**
  - `ServicioProductoTest`: `elAltaNaceEnCeroYPideElSaldoInicialAlInventario` (`solicitud` con 120 → guardado con existencia 0 y `verify(inventario).registrarSaldoInicial(id, 120)`).
  - `ServicioProductoTest`: `unAltaConExistenciaCeroNoEscribeMovimientos`, y lo mismo con existencia nula.
  - `ServicioInventarioTest`: `elSaldoInicialEsUnIngresoConMotivo` (tipo `INGRESO`, anterior 0, posterior 120, motivo `Saldo inicial del producto`).
  - `ServicioInventarioTest`: `elCuadreLeeLaExistenciaDeLaTablaDeProductos` (saldo 40, suma 30 → `cuadra=false`); `unProductoSinMovimientosYEnCeroCuadra`; `unProductoAjenoNoTieneCuadre` → vacío.
  - Con base: `siElMovimientoInicialFallaNoQuedaElProducto` (el movimiento lanza dentro de `crear`; el producto no existe tras el rollback).
  - Ajustar el test que espera `getExistencia() == 120` en la edición (`ServicioProductoTest:264`) para que siga afirmando que editar no cambia la existencia.

- [ ] **Step 2: Run** `./mvnw -B test -Dtest='ServicioProductoTest,ServicioInventarioTest'`. Expected: FAIL.

- [ ] **Step 3: Implement** el alta con existencia 0 y luego `registrarSaldoInicial` en la misma transacción cuando la cantidad es mayor que cero. `cuadre` responde 404 solo si el producto es ajeno.

- [ ] **Step 4: Run** verificación con base. Expected: verde, incluido `verificar_kardex.sh` en CI.

- [ ] **Step 5: Commit** `fix(inventario): la existencia inicial queda en el kárdex y el cuadre mira el saldo real`

---

### Task 7: Cambio de contraseña obligatorio (arreglo 1)

**Rama:** `fix/cambio-de-clave-obligatorio`. Backend y pantalla van en la misma rama: sin la pantalla, el filtro dejaría a todos los usuarios sin poder trabajar.

**Files:**
- Create: `config/FiltroCambioClave.java`, `api/dto/SolicitudCambioClave.java`, `logica/ClaveActualIncorrectaException.java`, `logica/ClaveNuevaInvalidaException.java`, `web/src/paginas/CambiarClave.jsx`
- Modify: `config/UsuarioAutenticado.java`, `config/ConfiguracionSeguridad.java`, `config/FiltroEmpresaActiva.java`, `logica/ServicioAutenticacion.java`, `logica/ServicioSesion.java`, `api/AutenticacionControlador.java`, `api/ManejadorErrores.java`, `web/src/App.jsx`, `web/src/paginas/Acceso.jsx`, `web/src/servicios/api.js`
- Test: Create `config/FiltroCambioClaveTest.java`, `web/src/paginas/CambiarClave.prueba.jsx`; modify `FiltroEmpresaActivaTest`, `ServicioAutenticacionTest`, `ServicioSesionTest`, `Acceso.prueba.jsx`

**Interfaces:**
- Produces: `record UsuarioAutenticado(Integer usuarioId, String correo, String rol, Integer empresaId, boolean debeCambiarClave)` con un constructor secundario de 4 argumentos (`debeCambiarClave = false`), para no romper las pruebas existentes.
- Produces: `POST /api/v1/auth/clave`, cuerpo `{claveActual, claveNueva}`, respuesta 204.
- Produces: `ServicioAutenticacion.cambiarClave(Integer usuarioId, String actual, String nueva): void`.
- Produces: `ServicioSesion.cerrarLasDemasDe(Integer usuarioId, String tokenActual): int`.
- Produces: `api.cambiarClave(claveActual, claveNueva)`.

- [ ] **Step 1: Write the failing backend tests.**
  - `FiltroCambioClaveTest`, con el estilo de `FiltroEmpresaActivaTest`:
    - con el cambio pendiente, `GET /api/v1/productos` da 403, `status`/`title` «Cambio de contraseña obligatorio», `codigo` `CAMBIO_CLAVE_REQUERIDO`, y la cadena no sigue;
    - `POST /api/v1/auth/clave` y `POST /api/v1/auth/logout` siguen;
    - sin cambio pendiente o sin autenticar, sigue.
  - `FiltroEmpresaActivaTest` (Review Focus 1): en empresa `DESACTIVADA`, `POST /api/v1/auth/clave` y `POST /api/v1/auth/logout` siguen; `POST /api/v1/productos` da 403.
  - `ServicioAutenticacionTest`:
    - `cambiarLaClaveGuardaLaNuevaYQuitaLaObligacion`;
    - `laClaveActualIncorrectaSeRechazaYCuentaComoIntento` (excepción, `intentosFallidos` = 1, el hash no cambia);
    - `laClaveNuevaIgualALaActualSeRechaza`.
  - `ServicioSesionTest`: `cambiarLaClaveCierraLasDemasSesionesYConservaLaActual`.

- [ ] **Step 2: Run** `./mvnw -B test -Dtest='FiltroCambioClaveTest,FiltroEmpresaActivaTest,ServicioAutenticacionTest,ServicioSesionTest'`. Expected: FAIL.

- [ ] **Step 3: Implement the backend.**
  - `FiltroCambioClave` va después de `FiltroSesion`, responde a mano como `FiltroEmpresaActiva.rechazar` y compara método y `getRequestURI()` exactos.
  - `FiltroEmpresaActiva` deja pasar `/api/v1/auth/`.
  - `SolicitudCambioClave.claveNueva`: `@NotBlank @Size(min = 8, max = 100)`, con el mensaje de `SolicitudUsuario`.
  - `cambiarClave`: `@Transactional(noRollbackFor = ClaveActualIncorrectaException.class)`, usa `registrarIntentoFallido(intentosMaximos)` y `Usuario.cambiarClave(Seguridad.derivar(nueva))`.
  - Ambas excepciones dan **400, no 401**: el cliente trata el 401 como sesión expirada.

- [ ] **Step 4: Run** verificación sin base. Expected: verde.

- [ ] **Step 5: Write the failing web tests** en `CambiarClave.prueba.jsx` y `Acceso.prueba.jsx`:
  - con `debeCambiarClave: true`, entrar lleva a `/cambiar-clave`; cualquier ruta privada también;
  - las claves nueva y de confirmación distintas no llaman al API;
  - al guardar con éxito, la sesión queda con `debeCambiarClave: false` y navega a `/catalogo`;
  - un 400 muestra el detalle del backend en `role="alert"`.

- [ ] **Step 6: Run** `npm test --prefix web -- CambiarClave Acceso`. Expected: FAIL.

- [ ] **Step 7: Implement la pantalla** con la skill `impeccable`, modo *Operate*, reutilizando las clases de `Acceso.jsx`. `Privada` redirige a `/cambiar-clave` cuando la sesión lo exige. Tres campos con su etiqueta y `autoComplete="current-password"`/`"new-password"`; aviso del mínimo de 8 caracteres; opción de cerrar sesión.

- [ ] **Step 8: Run** verificación web y con base. Expected: verde.

- [ ] **Step 9: Commit** `feat(seguridad): el cambio de contraseña inicial se exige y se puede hacer`

---

### Task 8: Costo, proveedores y kárdex solo para el administrador (arreglo 7)

**Rama:** `fix/costo-solo-administrador`

**Files:**
- Create: `api/dto/VistaDeProducto.java`, `api/dto/ProductoParaVenta.java`
- Modify: `api/dto/ProductoEnCatalogo.java`, `api/ProductoControlador.java`, `api/ProveedorControlador.java`, `api/InventarioControlador.java`, `web/src/paginas/Catalogo.jsx`
- Test: Create `backend/src/test/java/co/com/bspstore/sgip/api/AutorizacionPorRolTest.java`; modify `web/src/paginas/Catalogo.prueba.jsx`

**Interfaces:**
- Produces: `sealed interface VistaDeProducto permits ProductoEnCatalogo, ProductoParaVenta`.
- Produces: `ProductoParaVenta.de(ProductoEnCatalogo)`: todos los campos **menos** `costo`, `proveedorId` y `proveedor`. `baseContado` y `baseCredito` se conservan: el RF-32 pide mostrarlas y `Catalogo.jsx:312,319` las usa.
- Regla: solo `ADMINISTRADOR` recibe `ProductoEnCatalogo`; **cualquier otro rol** recibe `ProductoParaVenta`.

- [ ] **Step 1: Write the failing tests.** `@WebMvcTest` de los tres controladores con `@Import` de `ConfiguracionSeguridad` y los filtros, servicios con `@MockitoBean` y autenticación vía `SecurityMockMvcRequestPostProcessors.authentication` con un `UsuarioAutenticado` y su `ROLE_*`. Parametrizar `VENDEDOR` y `CLIENTE`:

```java
mvc.perform(get("/api/v1/productos").with(como(rol)))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$[0].precioContado").exists())
   .andExpect(jsonPath("$[0].existencia").exists())
   .andExpect(jsonPath("$[0].costo").doesNotExist())
   .andExpect(jsonPath("$[0].proveedor").doesNotExist())
   .andExpect(jsonPath("$[0].proveedorId").doesNotExist())
   .andExpect(jsonPath("$[0].baseContado").exists());
```

  - Igual para `/productos/{id}` y `/productos/barras/{codigo}`.
  - `GET /productos?proveedor=9`, `/proveedores`, `/proveedores/9`, `/inventario/movimientos`, `/inventario/productos/1/movimientos` y `/inventario/productos/1/cuadre` → 403.
  - `ADMINISTRADOR`: recibe `costo` y puede todo lo anterior.
  - Sin autenticar: 401.

  En `Catalogo.prueba.jsx` (Review Focus 4), con sesión de vendedor: no llama a `api.proveedores`; se ven categorías y productos; no hay columna de costo ni filtro de proveedor. Con administrador: todo sigue igual.

- [ ] **Step 2: Run** `./mvnw -B test -Dtest=AutorizacionPorRolTest` y `npm test --prefix web -- Catalogo`. Expected: FAIL; el vendedor recibe `costo`.

- [ ] **Step 3: Implement.**
  - `@PreAuthorize("hasRole('ADMINISTRADOR')")` a nivel de clase en `ProveedorControlador` e `InventarioControlador`; actualizar el Javadoc de este último, que justifica lo contrario.
  - `ProductoControlador` devuelve `VistaDeProducto`, decide con `ContextoEmpresa.actual()` y rechaza el filtro `proveedor` a quien no es administrador.
  - `POST` y `PUT` siguen devolviendo `ProductoEnCatalogo`.
  - `Catalogo.jsx`: pedir proveedores solo si `esAdministrador`; separar esa petición de la de categorías; ocultar columna y filtro.

- [ ] **Step 4: Run** verificación sin base y web. Expected: verde.

- [ ] **Step 5: Commit** `fix(seguridad): costo, proveedores y kárdex solo los ve el administrador`

---

### Task 9: API de configuración tributaria por empresa (arreglo 8)

**Rama:** `feature/configuracion-tributaria`

**Files:**
- Create: `api/ConfiguracionTributariaControlador.java`, `logica/ServicioConfiguracionTributaria.java`, `datos/RepositorioConfiguracionTributaria.java`, `api/dto/ConfiguracionTributaria.java`, `api/dto/SolicitudConfiguracionTributaria.java`, `api/dto/ReglaDeIva.java`, `api/dto/SolicitudReglaIva.java`
- Test: Create `logica/ServicioConfiguracionTributariaTest.java`, `api/ConfiguracionTributariaAutorizacionTest.java`

**Interfaces:** Todo bajo `/api/v1/empresa/tributacion`, con `@PreAuthorize("hasRole('ADMINISTRADOR')")` en la clase y la empresa tomada de la sesión. `new ServicioConfiguracionTributaria(RepositorioConfiguracionTributaria, CategoriaRepositorio, ProductoRepositorio, Clock)`.

| Método | Ruta | Efecto |
|---|---|---|
| GET | `/` | `ConfiguracionTributaria(responsableIva, aplicaExclusionUmbral, practicaRetenciones, tarifaIca, manejaEnvios, cotizacionVigenciaDias, configuracionCompleta)` |
| PUT | `/` | Fija todo lo anterior y deja `configuracion_completa = TRUE` |
| GET | `/impuestos` | Tipos activos del catálogo nacional, solo lectura |
| GET | `/reglas` | Reglas de IVA de la empresa |
| POST | `/reglas` | `{categoriaId?, productoId?, codigoImpuesto, vigenteDesde, vigenteHasta?, fundamento?}` → 201 |
| POST | `/reglas/{id}/cerrar?hasta=` | Cierra la vigencia; sin fecha, cierra ayer; 404 si es ajena |

No hay `PUT` ni `DELETE` de reglas: una venta se explica con la regla que regía, igual que en `PreciosControlador`.

- [ ] **Step 1: Write the failing tests** en `ServicioConfiguracionTributariaTest`:
  - `guardarLaConfiguracionLaMarcaCompleta`.
  - `elIcaExigePracticarRetenciones` → `DatoTributarioInvalidoException`, sin escribir.
  - `laVigenciaDeCotizacionVaDeUnoA365`: 0 y 366 se rechazan por validación.
  - `unaReglaNoPuedeSerDeCategoriaYDeProductoALaVez`.
  - `unaCategoriaOProductoAjenosSonReferenciaAjena` → 422.
  - `unImpuestoInexistenteOInactivoSeRechaza` → 422.
  - `dosReglasDelMismoAlcanceConVigenciaSolapadaChocan` → `RegistroRepetidoException`.
  - `cerrarUnaReglaAjenaDevuelveVacio`.
  - `cerrarSinFechaCierraAyer`, con `Clock` fijo.

  En `ConfiguracionTributariaAutorizacionTest`: `VENDEDOR` y `CLIENTE` reciben 403 en las seis rutas; `SUPER_ADMIN` recibe 403 porque la plataforma no opera dentro de una empresa (ADR-0020).

- [ ] **Step 2: Run** `./mvnw -B test -Dtest='ServicioConfiguracionTributariaTest,ConfiguracionTributariaAutorizacionTest'`. Expected: FAIL de compilación.

- [ ] **Step 3: Implement** con `JdbcClient`, patrón de `RepositorioTributario`. Antes de escribir el SQL, confirmar las columnas finales de `reglas_iva` en V12 (bloque 8.4). `verificar_sql_del_backend.py` valida ese SQL en CI.

- [ ] **Step 4: Retirar el bloqueo de la RN-25.** Crear `backend/src/main/resources/db/migration/V34__vender_sin_configurar_impuestos.sql` y `docs/decisiones/ADR-0024-los-impuestos-son-opcionales.md`, que enmienda ADR-0016; añadirlo al índice.
  - `v_regla_iva` devuelve una fila por producto aunque no haya regla (`LEFT JOIN`), y expone `responsable_iva`. Sin regla y sin ser responsable: `NO_GRAVADO`, tarifa 0.
  - `v_precios_producto`, copia de V25 con estos únicos cambios: `tributacion_sin_configurar` vale 1 solo con `responsable_iva = TRUE` y sin regla; sin regla, el total es igual al precio, nunca `NULL`.
  - En `Catalogo.jsx`, revisar el aviso de la línea 278: dice que falta configurar, no que no se pueda vender.

- [ ] **Step 5: Comprobar el resultado en `verificar_puesta_a_punto.sh`** y actualizar `verificar_tributacion.sh:105` a la regla nueva:

| Empresa | Regla | `tratamiento` | `sin_configurar` | total |
|---|---|---|---|---|
| No responsable | ninguna | `NO_GRAVADO` | 0 | igual al precio |
| Responsable | ninguna | `NULL` | 1 | igual al precio |
| Responsable | general `IVA_19` | `GRAVADO` | 0 | precio + 19 % |

- [ ] **Step 6: Run** verificación con base y los guiones. Expected: verde.

- [ ] **Step 7: Commit** `feat(tributacion): los impuestos son opcionales y se configuran por la API`

---

### Task 10: El alta siembra los medios de pago; CI sin datos de demostración (arreglo 9)

**Rama:** `fix/alta-siembra-lo-necesario`

**Files:**
- Modify: `logica/ServicioPlataforma.java:82-110`, `datos/RepositorioPlataforma.java`, `.github/workflows/ci.yml`
- Test: `ServicioPlataformaTest`

**Interfaces:**
- Produces: `RepositorioPlataforma.sembrarMediosDePago(Integer empresaId): void`: `Efectivo/EFECTIVO`, `Transferencia/TRANSFERENCIA`, `Tarjeta/TARJETA`, `Crédito/CREDITO`.

- [ ] **Step 1: Write the failing tests** en `ServicioPlataformaTest`:
  - `elAltaSiembraLosMediosDePago`.
  - `elAltaNoSiembraMargenesNiImpuestos`: `insertarConfiguracion` se llama una vez, sin reglas de margen ni de IVA; un comentario explica que el margen lo decide el comercio por producto y que el impuesto es opcional.
  - `siLaSiembraFallaElAltaSePropagaYNoSeAudita`: la excepción sube y `registrar` no se llama.

- [ ] **Step 2: Run** `./mvnw -B test -Dtest=ServicioPlataformaTest`. Expected: FAIL.

- [ ] **Step 3: Implement** en la misma transacción y actualizar el Javadoc, que hoy habla de «cinco escrituras».

- [ ] **Step 4: Añadir el trabajo de CI `esquema-sin-demo`:** servicio MySQL 9.7 sin `MYSQL_DATABASE`, credencial en `~/.my.cnf`, `bash database/aplicar.sh -u root -h 127.0.0.1` y `verificar_sql_del_backend.py`. Prueba el guion real con sus centinelas. Si la cadena falla sin V8, usar `superpowers:systematic-debugging`; V1–V31 solo se tocan con decisión expresa, porque están congeladas.

- [ ] **Step 5: Run** verificación sin base y empujar la rama para ver la CI. Expected: los trabajos en verde.

- [ ] **Step 6: Commit** `fix(plataforma): la empresa nueva nace con sus medios de pago`

---

### Task 11: Año sin UVT: aviso que no bloquea (arreglo 10)

**Rama:** `fix/alerta-uvt`

La UVT solo afecta a la empresa responsable de IVA que activó `aplica_exclusion_umbral`. A las demás, un año sin UVT no les cambia nada.

**Files:**
- Create: `backend/src/main/resources/db/migration/V35__el_anio_sin_uvt_avisa.sql`, `api/dto/AlertaUvt.java`, `config/AvisoUvtAlArrancar.java`
- Modify: `logica/ServicioTributario.java`, `api/TributarioControlador.java`, `database/verificar_puesta_a_punto.sh`
- Test: `ServicioTributarioTest`, Create `config/AvisoUvtAlArrancarTest.java`

**Interfaces:**
- Produces: `record AlertaUvt(boolean activa, int anio, long diasRestantes)`.
- Produces: `ServicioTributario.alertaUvt(): AlertaUvt`, con `Clock` añadido al constructor.
- Produces: `GET /api/v1/plataforma/tributario/alerta-uvt`.

- [ ] **Step 1: Write the failing tests** en `ServicioTributarioTest`, con `Clock` fijo:

| Hoy | Años cargados | Esperado |
|---|---|---|
| 2026-10-31 | 2026 | `activa=false` |
| 2026-11-01 | 2026 | `activa=true, anio=2027, diasRestantes=61` |
| 2026-11-01 | 2026, 2027 | `activa=false` |
| 2027-01-01 | 2026 | `activa=true, anio=2027, diasRestantes=0` |

  En `AvisoUvtAlArrancarTest`: con alerta activa escribe un `WARN` con el año y los días; sin alerta, no escribe nada.

- [ ] **Step 2: Run** `./mvnw -B test -Dtest='ServicioTributarioTest,AvisoUvtAlArrancarTest'`. Expected: FAIL.

- [ ] **Step 3: Implement** `alertaUvt`, el endpoint y un `ApplicationRunner` para el aviso. El aviso en el log existe porque aún no hay pantalla de plataforma donde mostrar la alerta.

- [ ] **Step 4: Escribir `V35`:** copia de `v_precios_producto` tal como la dejó V34, con un solo cambio: `tributacion_sin_configurar` también vale 1 cuando `ri.tratamiento = 'EXCLUIDO_HASTA_UMBRAL'` y `fn_valor_uvt(CURDATE()) IS NULL`. El cálculo del impuesto no cambia: sin UVT no se puede demostrar la exclusión y se cobra la tarifa, que es el tratamiento conservador de V5.

- [ ] **Step 5: Ampliar `verificar_puesta_a_punto.sh`.** Dentro de `START TRANSACTION … ROLLBACK`: borrar la fila del año en curso de `uvt_anual`; un producto `EXCLUIDO_HASTA_UMBRAL` marca 1, y uno `GRAVADO` y uno de empresa no responsable marcan 0. Tras el `ROLLBACK`, todos marcan 0.

  Además, hacer que `verificar_precios.sh` no dependa de la fecha: si falta la UVT del año en curso, inserta una de prueba y la retira al terminar. Sin esto la CI se pondría roja el 2027-01-01.

- [ ] **Step 6: Run** verificación con base y el guion. Expected: verde.

- [ ] **Step 7: Commit** `fix(tributacion): un año sin UVT avisa sin impedir vender`

- [ ] **Step 8 (opcional, fuera de esta puesta a punto): cargar la cifra oficial.** Cuando la DIAN publique la resolución: `PUT /api/v1/plataforma/tributario/uvt/2027` y una migración con `INSERT IGNORE INTO uvt_anual`. No tiene fecha límite para operar.

---

### Task 12: Porcentaje y precio de venta que se completan entre sí (cambio del usuario)

**Rama:** `feature/precio-y-margen-por-producto`

Decisión del usuario: al registrar y al editar, el producto muestra **% de ganancia** y **precio de venta**; al escribir uno, se completa el otro. Se retira la prohibición de capturar el precio del RF-31 (ADR-0025).

**Files:**
- Create: `backend/src/main/resources/db/migration/V36__precio_y_margen_por_producto.sql`, `docs/decisiones/ADR-0025-precio-y-margen-por-producto.md`, `web/src/servicios/precio.js`
- Modify: `logica/ServicioPrecios.java`, `logica/ServicioProducto.java`, `api/ProductoControlador.java`, `api/PreciosControlador.java` (Javadoc del RF-31), `api/dto/SolicitudProducto.java`, `api/dto/SolicitudEdicionProducto.java`, `api/dto/ProductoEnCatalogo.java`, `datos/ConsultaCatalogo.java`, `.github/workflows/ci.yml`, `web/src/componentes/FormularioProducto.jsx`, `web/src/paginas/Catalogo.jsx`
- Test: `ServicioPreciosTest`, `ServicioProductoTest`, `AutorizacionPorRolTest`, `FormularioProducto.prueba.jsx`, `web/src/servicios/precio.prueba.js`, `database/verificar_precios.sh`

**Interfaces:**
- Produces: `reglas_margen.producto_id` y `reglas_margen.precio_fijo`; `factor` admite `NULL` solo si hay `precio_fijo`, y `precio_fijo` exige `producto_id`. Precedencia: producto → categoría/proveedor → general.
- Produces: en alta y edición, `SolicitudPrecio(BigDecimal porcentaje, BigDecimal precio)` para `contado` (obligatorio) y `credito` (opcional). Debe venir exactamente uno de los dos valores: el que el usuario escribió.
- Produces: en `ProductoEnCatalogo`, `margenContado`, `margenCredito`, `precioFijoContado` y `precioFijoCredito`, calculados por la vista para que los reportes los lean de un solo sitio; **no** en `ProductoParaVenta`.
- Produces: `web/src/servicios/precio.js`: `precioDesdePorcentaje(costo, porcentaje)` y `porcentajeDesdePrecio(costo, precio)`. Son solo vista previa; lo que manda es la respuesta del backend. Comparten una tabla de casos con el test del backend para que no diverjan.

**Reglas (los ejemplos son los valores de los tests):**

| Regla | Ejemplo |
|---|---|
| El porcentaje se calcula sobre el costo | costo 1.000 y 20 % → precio 1.200 |
| El precio se redondea al peso | costo 999 y 20 % → 1.199 |
| Precio escrito → porcentaje con dos decimales | costo 1.000 y precio 1.250 → 25,00 % |
| Se escribió el **porcentaje** y sube el costo: se conserva el %, cambia el precio | 20 %, costo 1.500 → precio 1.800 |
| Se escribió el **precio** y sube el costo: se conserva el precio, cambia el % | precio 1.250, costo 1.100 → 13,64 % |
| Precio por debajo del costo: se permite, con advertencia | costo 1.000 y precio 900 → −10,00 % |
| Un porcentaje escrito de cero o negativo se rechaza; ese caso se hace escribiendo el precio | 0 % → 400 |
| Precio de cero o negativo se rechaza | 0 → 400 |
| Crédito vacío: se guarda igual que el de contado | contado 1.200 → crédito 1.200 |
| Sin ninguna regla, el precio es `NULL`, nunca cero | — |

Cambiar el precio o el porcentaje cierra la regla vigente del producto ayer y abre otra desde hoy, para conservar la historia; dos cambios el mismo día corrigen la misma fila.

- [ ] **Step 1: Write the failing tests.**
  - `verificar_precios.sh`: cada fila de la tabla anterior que dependa de la base; la regla del producto gana a la de categoría y a la general; no afecta a otro producto ni a otra empresa; un precio fijo también decide el umbral del Art. 424.
  - `ServicioProductoTest`: `elAltaExigePorcentajeOPrecioDeContado`; `mandarPorcentajeYPrecioALaVezSeRechaza`; `cambiarElPrecioCierraLaReglaAnteriorYAbreOtra`; `dosCambiosElMismoDiaCorrigenLaMismaRegla`; `sinCreditoSeGuardaIgualAlContado`; `unProductoDeOtraEmpresaNoRecibeRegla`.
  - `ServicioPreciosTest`: la simulación y el alcance de una regla general no cuentan los productos con regla propia.
  - `AutorizacionPorRolTest`: el vendedor no recibe `margenContado` ni `precioFijoContado`.
  - `precio.prueba.js`: la tabla de reglas, y `0` o vacío devuelven vacío, no `NaN` ni `Infinity`.
  - `FormularioProducto.prueba.jsx`:
    - escribir el % completa el precio; escribir el precio completa el %;
    - cambiar el costo recalcula el que **no** se escribió a mano;
    - un precio menor que el costo muestra la advertencia y deja guardar;
    - al editar, los campos abren con los valores guardados;
    - solo se envía el valor que el usuario escribió.

- [ ] **Step 2: Run** los tests. Expected: FAIL.

- [ ] **Step 3: Implement the backend.**
  - `V36`: columnas, `fk_reglas_margen_producto_emp`, índice y restricciones. `v_regla_margen`, con `(r.producto_id IS NOT NULL) DESC` en primer lugar, expone `precio_fijo`.
  - En `v_precios_producto`, calcular `precio_contado` y `precio_credito` **una sola vez** en una tabla derivada. V25 repite la fórmula en más de diez sitios, y añadir `precio_fijo` a cada copia es como se llegaría a un total calculado con otro precio. `verificar_precios.sh` debe dar lo mismo que antes con las reglas existentes.
  - Añadir `margen_contado` y `margen_credito` a la vista, con dos decimales.
  - Subir el conteo de claves `_emp` de 46 a 47 en `ci.yml`. `aplicar.sh` sigue en 46: se detiene en V31.
  - Revisar `RepositorioImportacion` y su vista previa: aplicar una lista cambia el costo, y los productos con precio fijo conservan su precio.

- [ ] **Step 4: Run** verificación sin base. Expected: verde.

- [ ] **Step 5: Interfaz** con la skill `impeccable`, modo *Operate*.
  - Dos campos lado a lado, contado arriba y crédito bajo «Usar otro precio a crédito».
  - Columna de margen en el catálogo, solo para el administrador. Sin precio: «Falta el precio de venta».

- [ ] **Step 6: Escribir ADR-0025 y añadirlo al índice.** Retira la prohibición del RF-31, explica qué se conserva al cambiar el costo y por qué la vista previa del navegador no decide el precio.

- [ ] **Step 7: Run** verificación web y CI de la rama. Expected: verde.

- [ ] **Step 8: Commit** `feat(precios): el porcentaje y el precio de venta se completan entre sí`

---

### Task 13: El color del producto pasa a morado (cambio del usuario)

**Rama:** `feature/color-morado`

Va ahora porque es barato: `tokens.css` es la única fuente de color, y así toda pantalla nueva nace morada. La reorganización de pantallas según Figma no entra aquí.

**Files:**
- Create: `docs/decisiones/ADR-0026-el-acento-pasa-a-morado.md`
- Modify: `web/src/estilos/tokens.css`, los 5 archivos con color suelto fuera de `tokens.css` (`grep -rnE "#[0-9a-fA-F]{3,6}" web/src`), `docs/decisiones/README.md`
- Test: Create `web/src/estilos/tokens.prueba.js`

**Interfaces:** Los nombres de los tokens no cambian, solo sus valores. Valores de partida, tomados del diseño: `--acento` `#6C54DF`, `--acento-claro` `#9B88F5`, `--acento-sutil` `#EEEAFF`, `--lienzo` `#F7F7F9`.

- [ ] **Step 1: Write the failing test.** `tokens.prueba.js` lee `tokens.css`, calcula el contraste y exige 4,5:1 en cada par de texto y fondo: blanco sobre `--acento`, `--tinta`, `--tinta-2` y `--tinta-3` sobre `--tarjeta` y `--lienzo`, y cada par de estado. Exige 3:1 en `--borde-control` sobre `--tarjeta` (RNF-13). Afirma también `--acento` igual a `#6C54DF`.

- [ ] **Step 2: Run** `npm test --prefix web -- tokens`. Expected: FAIL; `--acento` todavía es `#15707F`.

- [ ] **Step 3: Implement** con la skill `impeccable`, modo *Operate*. Cambiar los tokens, incluidos `--chrome`, los neutros con tinte verde y el nombre del sistema en el encabezado; mover a `tokens.css` los colores sueltos. Si un valor de Figma no llega al contraste, se ajusta el valor y se anota por qué.

- [ ] **Step 4: Run** la verificación web y `impeccable detect --json web/src`; revisar acceso, catálogo y panel a 1440 y 390 px. Expected: verde, sin restos de azul verdoso.

- [ ] **Step 5: Escribir ADR-0026:** el motivo es diferenciarse de otros proveedores que usan azul verdoso. Reemplaza la paleta de ADR-0009, cuyo resto sigue vigente, y deja pendiente qué pasa con los logos de Black Steel Parts en Aurora.

- [ ] **Step 6: Commit** `feat(web): el acento del producto pasa a morado`

---

## Después de esta puesta a punto

**Sprint 9, punto de pago.** Decisión del usuario del 2026-11-03: **todos los planes permiten facturar**, entendido como imprimir un recibo de venta. La factura electrónica no existe todavía y debe poder conectarse después:

- El documento se llama «Recibo de venta» y aclara que no es una factura electrónica. Lleva el consecutivo de la venta y los datos de la empresa.
- Se imprime desde el navegador, sin servicios externos.
- Emitir el recibo no depende de `facturas_electronicas`. La emisión electrónica queda detrás de una interfaz sin implementación, fuera de la transacción de venta (ADR-0005).

**Rediseño según Figma** (`Downloads/Nueva carpeta (5)`), proyecto aparte con su propio diseño y plan. El código de Figma Make es de referencia visual: un solo `App.tsx` de 2.658 líneas con datos de ejemplo, en React 19, TypeScript y Tailwind; el proyecto usa React 18, JavaScript y CSS propio. Decisiones que ya lo condicionan:

- **Planes por etapas.** Primero Emprende, Crece y Pro con límite de usuarios y productos, y cambio de plan manual. Las varias bodegas y el cobro automático van después.
- **El diseño debe corregirse:** el plan Emprende oculta Ventas y Facturación, y `App.tsx:416` presenta «Ventas y facturación» como ventaja de Crece. Vender e imprimir recibos va en todos los planes.
- Pantallas que faltan y mejoras acordadas: ver la conversación del 2026-11-03, resumidas en la memoria del proyecto.

## Cierre

- [ ] Tras cada merge, ejecutar la suite completa en `develop` y marcar el arreglo en la lista `sgip-puesta-a-punto`.
- [ ] Al terminar el último, `superpowers:verification-before-completion`: CI de `develop` en verde y entrada manual con un usuario nuevo (clave obligatoria → catálogo de vendedor sin costo).
- [ ] Anotar como riesgo conocido para más adelante: el calendario de festivos solo cubre 2026 y 2027 (informe, arreglo 10).
