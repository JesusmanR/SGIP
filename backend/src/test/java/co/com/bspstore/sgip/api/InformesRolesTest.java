package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.BackendApplication;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.logica.informes.Periodo;
import co.com.bspstore.sgip.logica.informes.PeriodoInvalidoException;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién entra a los informes: solo el administrador de la empresa, también cuando la empresa está en modo
 * consulta, y un período mal escrito es un 400 con su texto.
 *
 * <h2>De dónde sale la lista de rutas</h2>
 *
 * Como en {@code CajaRolesTest}: de los controladores. Toda ruta bajo {@code /api/v1/informes} tiene que tener
 * su fila en {@link #RUTAS}, y una fila sin ruta también falla. Cuando una tarea agrega un informe o su
 * {@code /excel}, esta prueba la obliga a declararlo aquí.
 */
@WebMvcTest(controllers = InformesControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class InformesRolesTest {

    private static final String RAIZ = "/api/v1/informes";

    /** Las rutas de los informes, con la petición concreta que usa esta prueba (todas son GET). */
    private static final List<String> RUTAS = List.of(
            RAIZ + "/inventario-valorizado");

    /** Las identidades que no deben entrar; {@code null} es un usuario sin ningún rol. */
    private static final List<String> SIN_ACCESO = java.util.Arrays.asList("VENDEDOR", "SUPER_ADMIN", "CLIENTE", null);

    @Autowired MockMvc mvc;

    // Dependencias de los filtros: los filtros son los de verdad y lo único que se finge es la base.
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    private static RequestPostProcessor como(String rol) {
        Integer empresa = "SUPER_ADMIN".equals(rol) ? null : 4;
        var usuario = new UsuarioAutenticado(7, "ana@tienda.co", rol == null ? "SIN_ROL" : rol, empresa);
        var autoridades = rol == null
                ? List.<SimpleGrantedAuthority>of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + rol));
        return authentication(new UsernamePasswordAuthenticationToken(usuario, null, autoridades));
    }

    // ------------------------------------------------------------ la lista sale de los controladores

    /** Los patrones GET que los controladores declaran bajo la raíz de los informes. */
    private static Set<String> rutasDeclaradas() {
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

        Set<String> rutas = new TreeSet<>();
        for (var definicion : buscador.findCandidateComponents(BackendApplication.class.getPackageName())) {
            Class<?> clase;
            try {
                clase = Class.forName(definicion.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            var mapeos = MethodIntrospector.selectMethods(clase,
                    (MethodIntrospector.MetadataLookup<RequestMappingInfo>) m -> lector.de(m, clase));
            for (RequestMappingInfo info : mapeos.values()) {
                for (String patron : info.getPathPatternsCondition().getPatternValues()) {
                    if (patron.equals(RAIZ) || patron.startsWith(RAIZ + "/")) {
                        for (var verbo : info.getMethodsCondition().getMethods()) {
                            rutas.add(verbo.name() + " " + patron);
                        }
                    }
                }
            }
        }
        return rutas;
    }

    @Test
    void todaRutaDeLosInformesTieneSuFilaYSoloEsGet() {
        Set<String> enLaTabla = new TreeSet<>();
        RUTAS.forEach(r -> enLaTabla.add("GET " + r));

        assertEquals(enLaTabla, rutasDeclaradas(),
                "La tabla de rutas y los controladores no coinciden (añade la ruta a RUTAS, o bórrala).");
    }

    // ------------------------------------------------------------------------ quién entra a cada ruta

    static Stream<Arguments> rutasSinAcceso() {
        List<Arguments> casos = new ArrayList<>();
        for (String ruta : RUTAS) {
            for (String rol : SIN_ACCESO) {
                casos.add(Arguments.of(ruta + " como " + (rol == null ? "usuario sin rol" : rol), ruta, rol));
            }
        }
        return casos.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rutasSinAcceso")
    void quienNoEsAdministradorRecibe403(String descripcion, String ruta, String rol) throws Exception {
        mvc.perform(get(ruta).with(como(rol))).andExpect(status().isForbidden());
    }

    static Stream<String> rutas() {
        return RUTAS.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rutas")
    void elAdministradorEntra(String ruta) throws Exception {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));

        mvc.perform(get(ruta).with(como("ADMINISTRADOR"))).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rutas")
    void unaEmpresaEnModoConsultaDejaPasarAlAdministrador(String ruta) throws Exception {
        // El filtro de la empresa en consulta solo bloquea escrituras: ver y descargar informes es leer.
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.CONSULTA));

        mvc.perform(get(ruta).with(como("ADMINISTRADOR"))).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rutas")
    void sinSesionRecibe401(String ruta) throws Exception {
        mvc.perform(get(ruta)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ un período malo es un 400

    @RestController
    static class Lanzador {
        @GetMapping("/periodo")
        void periodo(@org.springframework.web.bind.annotation.RequestParam(required = false) String desde,
                     @org.springframework.web.bind.annotation.RequestParam(required = false) String hasta) {
            Periodo.de(desde, hasta, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 26));
        }

        @GetMapping("/lanza")
        void lanza() {
            throw new PeriodoInvalidoException("La fecha no es válida.");
        }
    }

    @Test
    void unPeriodoInvalidoDa400ConSuTexto() throws Exception {
        MockMvc solo = MockMvcBuilders.standaloneSetup(new Lanzador())
                .setControllerAdvice(new ManejadorErrores()).build();

        solo.perform(get("/lanza")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La fecha no es válida."));
        solo.perform(get("/periodo").param("desde", "2026-11-20").param("hasta", "2026-11-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La fecha inicial no puede ser posterior a la final."));
        solo.perform(get("/periodo").param("desde", "2025-01-01").param("hasta", "2026-11-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Elige un período de máximo un año."));
        solo.perform(get("/periodo").param("desde", "2026-02-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La fecha no es válida."));
        solo.perform(get("/periodo")).andExpect(status().isOk());
    }

    @Test
    void elControladorDeInformesEsSoloParaAdministradores() {
        assertTrue(InformesControlador.class.isAnnotationPresent(
                        org.springframework.security.access.prepost.PreAuthorize.class),
                "La clase lleva @PreAuthorize: una ruta nueva no puede quedar abierta por olvido.");
        assertEquals("hasRole('ADMINISTRADOR')", InformesControlador.class
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
    }
}
