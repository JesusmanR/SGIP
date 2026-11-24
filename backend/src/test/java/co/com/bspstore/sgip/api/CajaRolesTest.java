package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.BackendApplication;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.datos.RepositorioTurnos;
import co.com.bspstore.sgip.logica.ReciboPdf;
import co.com.bspstore.sgip.logica.ServicioAutorizacion;
import co.com.bspstore.sgip.logica.ServicioCajaProductos;
import co.com.bspstore.sgip.logica.ServicioClientes;
import co.com.bspstore.sgip.logica.ServicioConsultaDeVentas;
import co.com.bspstore.sgip.logica.ServicioDevoluciones;
import co.com.bspstore.sgip.logica.ServicioDomicilios;
import co.com.bspstore.sgip.logica.ServicioEncargos;
import co.com.bspstore.sgip.logica.ServicioMediosPago;
import co.com.bspstore.sgip.logica.ServicioRecibos;
import co.com.bspstore.sgip.logica.ServicioSaldoAFavor;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.logica.ServicioTurnos;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.http.server.PathContainer;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * La prueba transversal de la caja: quién entra a cada ruta, mirando <b>todas</b> las rutas y no las que
 * alguien se acordó de probar.
 *
 * <h2>De dónde sale la lista</h2>
 *
 * Los controladores. {@link #rutasDeLaCaja()} recorre cada {@code @RestController} de la aplicación (desde el paquete de {@code BackendApplication})
 * y le pregunta a Spring qué rutas declara, con el mismo lector que usa al arrancar. Las que son de la caja
 * ({@code /api/v1/caja/**}), los medios de pago ({@code /api/v1/medios-pago}) y el PIN
 * ({@code /api/v1/perfil/pin}) tienen que tener una fila en {@link #FILAS}. Una ruta nueva sin fila hace
 * fallar {@link #todaRutaDeLaCajaTieneSuFilaDeRoles()}, y una fila sin ruta también: la tabla no puede
 * quedarse con una ruta que ya no existe ni sin una que ya existe.
 *
 * <h2>Qué se comprueba en cada ruta</h2>
 *
 * Por la cadena de seguridad real, con cinco identidades:
 * <ul>
 *   <li>los roles de la fila entran: ni 401 ni 403 (qué responda el servicio es asunto de su propia prueba);</li>
 *   <li>todos los demás reciben 403, y el servicio ni se entera. Esto incluye siempre al cliente, al super
 *       administrador y a quien no tiene rol;</li>
 *   <li>sin sesión, 401.</li>
 * </ul>
 *
 * Cada petición lleva un cuerpo y unos parámetros válidos: la validación del cuerpo va antes que la
 * autorización del método y un cuerpo inválido daría un 400 que escondería si el 403 existe.
 *
 * <h2>Lo que no es un 403</h2>
 *
 * {@code GET /caja/turnos?desde&hasta} lo usan los dos roles, y el vendedor ve solo sus turnos: lo decide el
 * servicio, no la cadena de seguridad. Aquí se comprueba la fila (los dos entran) y, aparte, con el servicio
 * de verdad, que el administrador pide los turnos de toda la empresa y el vendedor solo los suyos.
 */
@WebMvcTest(controllers = {TurnosControlador.class, VentasControlador.class, CajaProductosControlador.class,
        MediosPagoControlador.class, PinControlador.class, EncargosControlador.class, DomiciliosControlador.class,
        DevolucionesControlador.class, ClientesControlador.class})
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class CajaRolesTest {

    private static final String ADMIN = "ADMINISTRADOR";
    private static final String VEND = "VENDEDOR";

    private static final Set<String> LOS_DOS = Set.of(ADMIN, VEND);
    private static final Set<String> SOLO_ADMIN = Set.of(ADMIN);

    /** Las identidades que se prueban en cada ruta; {@code null} es un usuario sin ningún rol. */
    private static final List<String> ROLES = java.util.Arrays.asList(ADMIN, VEND, "CLIENTE", "SUPER_ADMIN", null);

    private static final String UUID_DE_PRUEBA = "5b0b3f0e-9b0e-4e45-a1a1-0c0f6a5e7a11";

    private static final String CLIENTE_VALIDO = "{\"nombre\":\"Ana Ruiz\",\"tipoDocumento\":\"CC\","
            + "\"identificacion\":\"123456\",\"correo\":\"ana@correo.co\",\"autorizacionDatos\":true}";

    /**
     * Lo que esta prueba vigila: la caja ({@code /api/v1/caja}, con o sin barra final y con lo que cuelgue de
     * ella), los medios de pago y el PIN. Un nombre que solo empieza igual ({@code /api/v1/cajas}) no cuenta.
     * El resto de la API tiene sus propias pruebas.
     */
    private static boolean esDeLaCaja(String patron) {
        return bajo(patron, "/api/v1/caja") || bajo(patron, "/api/v1/medios-pago")
                || patron.equals("/api/v1/perfil/pin");
    }

    private static boolean bajo(String patron, String raiz) {
        return patron.equals(raiz) || patron.startsWith(raiz + "/");
    }

    /**
     * Una ruta, quién entra y una petición concreta que pasa la validación.
     *
     * @param metodo  el verbo
     * @param patron  la plantilla tal como la declara el controlador
     * @param ruta    la misma ruta con valores de verdad y los parámetros obligatorios
     * @param cuerpo  un cuerpo válido, o {@code null} si la ruta no lee ninguno
     * @param roles   los roles que entran; los demás reciben 403
     */
    record Fila(String metodo, String patron, String ruta, String cuerpo, Set<String> roles) {
        String clave() {
            return metodo + " " + patron;
        }
    }

    static final List<Fila> FILAS = List.of(
            new Fila("GET", "/api/v1/caja/medios-pago", "/api/v1/caja/medios-pago", null, LOS_DOS),
            new Fila("GET", "/api/v1/medios-pago", "/api/v1/medios-pago", null, SOLO_ADMIN),
            new Fila("POST", "/api/v1/medios-pago", "/api/v1/medios-pago",
                    "{\"nombre\":\"Nequi\",\"tipo\":\"TRANSFERENCIA\"}", SOLO_ADMIN),
            new Fila("PATCH", "/api/v1/medios-pago/{medioId}", "/api/v1/medios-pago/3",
                    "{\"activo\":true}", SOLO_ADMIN),
            new Fila("PUT", "/api/v1/perfil/pin", "/api/v1/perfil/pin", "{\"pinNuevo\":\"4829\"}", SOLO_ADMIN),
            new Fila("GET", "/api/v1/perfil/pin", "/api/v1/perfil/pin", null, SOLO_ADMIN),
            new Fila("GET", "/api/v1/caja/administradores", "/api/v1/caja/administradores", null, LOS_DOS),
            new Fila("GET", "/api/v1/caja/productos", "/api/v1/caja/productos?codigo=ABC-1", null, LOS_DOS),
            new Fila("POST", "/api/v1/caja/turnos", "/api/v1/caja/turnos", "{\"baseEfectivo\":50000}", LOS_DOS),
            new Fila("GET", "/api/v1/caja/turnos/actual", "/api/v1/caja/turnos/actual", null, LOS_DOS),
            new Fila("GET", "/api/v1/caja/turnos/actual/resumen", "/api/v1/caja/turnos/actual/resumen",
                    null, LOS_DOS),
            new Fila("POST", "/api/v1/caja/turnos/actual/cierre", "/api/v1/caja/turnos/actual/cierre",
                    "{\"efectivoContado\":50000}", LOS_DOS),
            // El vendedor entra y ve solo sus turnos: el filtro es del servicio, y se prueba más abajo.
            new Fila("GET", "/api/v1/caja/turnos", "/api/v1/caja/turnos?desde=2026-11-01&hasta=2026-11-12",
                    null, LOS_DOS),
            new Fila("POST", "/api/v1/caja/ventas", "/api/v1/caja/ventas",
                    "{\"claveIdempotencia\":\"" + UUID_DE_PRUEBA + "\",\"lineas\":[{\"productoId\":1,\"cantidad\":1}],"
                            + "\"pagos\":[{\"medioId\":1,\"monto\":1000}]}", LOS_DOS),
            new Fila("GET", "/api/v1/caja/ventas", "/api/v1/caja/ventas?consecutivo=V-00000001", null, LOS_DOS),
            new Fila("GET", "/api/v1/caja/ventas/{id}/recibo", "/api/v1/caja/ventas/9/recibo", null, LOS_DOS),
            new Fila("GET", "/api/v1/caja/ventas/{id}/recibo.pdf", "/api/v1/caja/ventas/9/recibo.pdf",
                    null, LOS_DOS),
            new Fila("POST", "/api/v1/caja/devoluciones", "/api/v1/caja/devoluciones",
                    "{\"clave\":\"" + UUID_DE_PRUEBA + "\",\"ventaId\":9,"
                            + "\"unidades\":[{\"productoId\":1,\"cantidad\":1}]}", LOS_DOS),
            new Fila("GET", "/api/v1/caja/clientes/{id}/saldo", "/api/v1/caja/clientes/2/saldo", null, LOS_DOS),
            new Fila("GET", "/api/v1/caja/clientes", "/api/v1/caja/clientes?tipo=CC&identificacion=123456",
                    null, LOS_DOS),
            new Fila("GET", "/api/v1/caja/clientes/lista", "/api/v1/caja/clientes/lista?buscar=ana&pagina=0&tamano=20",
                    null, LOS_DOS),
            new Fila("POST", "/api/v1/caja/clientes", "/api/v1/caja/clientes", CLIENTE_VALIDO, LOS_DOS),
            new Fila("PUT", "/api/v1/caja/clientes/{id}", "/api/v1/caja/clientes/2", CLIENTE_VALIDO, LOS_DOS),
            new Fila("GET", "/api/v1/caja/domicilios", "/api/v1/caja/domicilios?estado=POR_COBRAR", null, LOS_DOS),
            new Fila("POST", "/api/v1/caja/domicilios/{ventaId}/entrega", "/api/v1/caja/domicilios/9/entrega",
                    null, LOS_DOS),
            new Fila("POST", "/api/v1/caja/domicilios/{ventaId}/cobro", "/api/v1/caja/domicilios/9/cobro",
                    "[{\"medioId\":1,\"monto\":1000}]", LOS_DOS),
            new Fila("GET", "/api/v1/caja/encargos", "/api/v1/caja/encargos?estado=PENDIENTE", null, SOLO_ADMIN),
            new Fila("POST", "/api/v1/caja/encargos/{itemId}/entrega", "/api/v1/caja/encargos/5/entrega",
                    null, SOLO_ADMIN)
    );

    @Autowired MockMvc mvc;

    @MockitoBean ServicioTurnos turnos;
    @MockitoBean ServicioVentas ventas;
    @MockitoBean ServicioConsultaDeVentas consultaDeVentas;
    @MockitoBean ServicioRecibos recibos;
    @MockitoBean ReciboPdf reciboPdf;
    @MockitoBean ServicioCajaProductos productos;
    @MockitoBean ServicioMediosPago mediosPago;
    @MockitoBean ServicioAutorizacion autorizacion;
    @MockitoBean ServicioEncargos encargos;
    @MockitoBean ServicioDomicilios domicilios;
    @MockitoBean ServicioDevoluciones devoluciones;
    @MockitoBean ServicioSaldoAFavor saldos;
    @MockitoBean ServicioClientes clientes;
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    @BeforeEach
    void empresaActiva() {
        // Activa: el filtro de la empresa en consulta deja pasar las escrituras, de modo que un 403 solo
        // puede venir de @PreAuthorize.
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
    }

    @AfterEach
    void sinSesion() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------ la lista sale de los controladores

    /** Lee las rutas de cada controlador con el mismo lector que usa Spring al arrancar. */
    private static Map<String, Class<?>> rutasDeLaCaja() {
        class Lector extends RequestMappingHandlerMapping {
            RequestMappingInfo de(Method metodo, Class<?> clase) {
                return getMappingForMethod(metodo, clase);
            }

            /** No hay contexto del que registrar controladores: solo se usa para leer las rutas. */
            @Override
            protected void initHandlerMethods() {
            }
        }
        var lector = new Lector();
        lector.afterPropertiesSet();

        var buscador = new ClassPathScanningCandidateComponentProvider(false);
        buscador.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Map<String, Class<?>> rutas = new LinkedHashMap<>();
        // Todo controlador de la aplicación, esté en el paquete que esté: se parte del paquete de la clase
        // principal y no del de la API, para que una ruta de la caja en otro paquete también cuente.
        for (var definicion : buscador.findCandidateComponents(BackendApplication.class.getPackageName())) {
            Class<?> clase;
            try {
                clase = Class.forName(definicion.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            Map<Method, RequestMappingInfo> mapeos = MethodIntrospector.selectMethods(clase,
                    (MethodIntrospector.MetadataLookup<RequestMappingInfo>) m -> lector.de(m, clase));
            for (RequestMappingInfo info : mapeos.values()) {
                for (String patron : info.getPathPatternsCondition().getPatternValues()) {
                    if (!esDeLaCaja(patron)) {
                        continue;
                    }
                    for (var verbo : info.getMethodsCondition().getMethods()) {
                        rutas.put(verbo.name() + " " + patron, clase);
                    }
                }
            }
        }
        return rutas;
    }

    @Test
    void elPrefijoDeLaCajaCuentaConOSinBarraFinal() {
        assertTrue(esDeLaCaja("/api/v1/caja"));
        assertTrue(esDeLaCaja("/api/v1/caja/"));
        assertTrue(esDeLaCaja("/api/v1/caja/turnos"));
        assertTrue(esDeLaCaja("/api/v1/medios-pago"));
        assertTrue(esDeLaCaja("/api/v1/medios-pago/{medioId}"));
        assertTrue(esDeLaCaja("/api/v1/perfil/pin"));
        // Un nombre que solo empieza igual no es de la caja.
        assertFalse(esDeLaCaja("/api/v1/cajas"));
        assertFalse(esDeLaCaja("/api/v1/medios-pagos"));
        assertFalse(esDeLaCaja("/api/v1/perfil/pines"));
        assertFalse(esDeLaCaja("/api/v1/productos"));
    }

    @Test
    void todaRutaDeLaCajaTieneSuFilaDeRoles() {
        Set<String> declaradas = new TreeSet<>(rutasDeLaCaja().keySet());
        Set<String> enLaTabla = new TreeSet<>();
        FILAS.forEach(f -> enLaTabla.add(f.clave()));

        Set<String> sinFila = new TreeSet<>(declaradas);
        sinFila.removeAll(enLaTabla);
        Set<String> sinRuta = new TreeSet<>(enLaTabla);
        sinRuta.removeAll(declaradas);

        assertTrue(sinFila.isEmpty() && sinRuta.isEmpty(),
                "La tabla de roles y los controladores no coinciden.\n"
                        + "  Rutas sin fila (añádelas a FILAS con sus roles): " + sinFila + "\n"
                        + "  Filas sin ruta (bórralas o corrige el verbo y la plantilla): " + sinRuta);
        assertEquals(FILAS.size(), enLaTabla.size(), "Hay dos filas para la misma ruta.");
    }

    @Test
    void cadaFilaTieneUnaPeticionQueEncajaEnSuPlantilla() {
        for (Fila fila : FILAS) {
            String camino = fila.ruta().contains("?") ? fila.ruta().substring(0, fila.ruta().indexOf('?')) : fila.ruta();
            var plantilla = PathPatternParser.defaultInstance.parse(fila.patron());
            assertTrue(plantilla.matches(PathContainer.parsePath(camino)),
                    fila.clave() + ": la ruta de prueba " + camino + " no encaja en la plantilla");
        }
    }

    @Test
    void todoControladorDeLaCajaEstaCargadoEnEstaPrueba() {
        // Una ruta de un controlador que esta prueba no carga daría 404 y no 403, y parecería «denegada».
        Set<Class<?>> cargados = Set.of(TurnosControlador.class, VentasControlador.class,
                CajaProductosControlador.class, MediosPagoControlador.class, PinControlador.class,
                EncargosControlador.class, DomiciliosControlador.class, DevolucionesControlador.class,
                ClientesControlador.class);
        Set<String> sinCargar = new TreeSet<>();
        rutasDeLaCaja().forEach((ruta, clase) -> {
            if (!cargados.contains(clase)) {
                sinCargar.add(clase.getSimpleName() + " (" + ruta + ")");
            }
        });
        assertTrue(sinCargar.isEmpty(),
                "Añade estos controladores a @WebMvcTest(controllers = ...) y sus servicios a los @MockitoBean: "
                        + sinCargar);
    }

    // ------------------------------------------------------------------------ quién entra a cada ruta

    private static RequestPostProcessor como(String rol) {
        Integer empresa = "SUPER_ADMIN".equals(rol) ? null : 4;
        var usuario = new UsuarioAutenticado(7, "ana@tienda.co", rol == null ? "SIN_ROL" : rol, empresa);
        var autoridades = rol == null
                ? List.<SimpleGrantedAuthority>of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + rol));
        return authentication(new UsernamePasswordAuthenticationToken(usuario, null, autoridades));
    }

    private static MockHttpServletRequestBuilder peticion(Fila fila) {
        MockHttpServletRequestBuilder p = request(HttpMethod.valueOf(fila.metodo()), fila.ruta());
        return fila.cuerpo() == null ? p : p.contentType(MediaType.APPLICATION_JSON).content(fila.cuerpo());
    }

    static Stream<Arguments> filasPorRol() {
        List<Arguments> casos = new ArrayList<>();
        for (Fila fila : FILAS) {
            for (String rol : ROLES) {
                casos.add(Arguments.of(fila.clave() + " como " + (rol == null ? "usuario sin rol" : rol), fila, rol));
            }
        }
        return casos.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("filasPorRol")
    void cadaRolEntraSoloDondeLeCorresponde(String descripcion, Fila fila, String rol) throws Exception {
        int estado = estadoDe(fila, rol);

        if (rol != null && fila.roles().contains(rol)) {
            assertTrue(estado != 401 && estado != 403,
                    descripcion + ": debería entrar y recibió " + estado);
        } else {
            assertEquals(403, estado, descripcion + ": debería recibir 403 y recibió "
                    + (estado == EJECUTO_EL_METODO ? "nada: el método del controlador se ejecutó" : estado));
            verifyNoInteractions(turnos, ventas, consultaDeVentas, recibos, reciboPdf, productos, mediosPago,
                    autorizacion, encargos, domicilios, devoluciones, saldos, clientes);
        }
    }

    /**
     * El estado de la respuesta, o {@link #EJECUTO_EL_METODO} si el método del controlador llegó a ejecutarse y falló
     * por dentro: los servicios son dobles y devuelven nulo, y el controlador que los desreferencia lanza antes de
     * poder responder. Para esta prueba eso significa «entró», que es lo único que importa de la autorización.
     */
    private int estadoDe(Fila fila, String rol) throws Exception {
        try {
            return mvc.perform(peticion(fila).with(como(rol))).andReturn().getResponse().getStatus();
        } catch (jakarta.servlet.ServletException e) {
            return EJECUTO_EL_METODO;
        }
    }

    private static final int EJECUTO_EL_METODO = -1;

    @ParameterizedTest(name = "{0}")
    @MethodSource("filasSinSesion")
    void sinSesionTodasResponden401(String descripcion, Fila fila) throws Exception {
        int estado = mvc.perform(peticion(fila)).andReturn().getResponse().getStatus();
        assertEquals(401, estado, descripcion + ": sin sesión debería recibir 401");
    }

    static Stream<Arguments> filasSinSesion() {
        return FILAS.stream().map(f -> Arguments.of(f.clave() + " sin sesión", f));
    }

    // ----------------------------------- lo que el vendedor ve en la lista de turnos no es un 403

    @Test
    void elAdministradorPideLosTurnosDeTodaLaEmpresaYElVendedorSoloLosSuyos() {
        var repositorio = mock(RepositorioTurnos.class);
        var servicio = new ServicioTurnos(repositorio, Clock.systemUTC());
        LocalDate desde = LocalDate.of(2026, 11, 1);
        LocalDate hasta = LocalDate.of(2026, 11, 12);

        entrarComo("ADMINISTRADOR");
        servicio.listar(desde, hasta);
        verify(repositorio).turnosDelPeriodo(eq(4), any(), any(), isNull());

        entrarComo("VENDEDOR");
        servicio.listar(desde, hasta);
        verify(repositorio).turnosDelPeriodo(eq(4), any(), any(), eq(7));
    }

    private static void entrarComo(String rol) {
        var usuario = new UsuarioAutenticado(7, "ana@tienda.co", rol, 4);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                usuario, null, List.of(new SimpleGrantedAuthority("ROLE_" + rol))));
    }
}
