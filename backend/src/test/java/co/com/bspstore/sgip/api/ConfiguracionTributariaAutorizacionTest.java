package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ConfiguracionTributaria;
import co.com.bspstore.sgip.api.dto.ReglaDeIva;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.ServicioConfiguracionTributaria;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién configura los impuestos de la empresa (tarea 9).
 *
 * Solo el administrador de la empresa: las seis rutas cuelgan de un
 * {@code @PreAuthorize} en la clase. Pasa por la cadena de seguridad real,
 * con la seguridad de métodos activa, para que un 403 solo pueda venir de la
 * anotación: la empresa de la sesión está ACTIVA (ver {@link #empresaActiva}),
 * de modo que {@code FiltroEmpresaActiva} deja pasar las escrituras, y los
 * cuerpos de las escrituras son válidos, porque {@code @Valid} se resuelve
 * antes de que la anotación se evalúe y un cuerpo malo daría 400 en lugar
 * de 403. Que el administrador sí llegue al servicio es lo que prueba que la
 * anotación decide y que no es la ruta la que falla para todos.
 *
 * El super administrador recibe 403 también: la plataforma no opera dentro
 * de una empresa (ADR-0020).
 */
@WebMvcTest(controllers = ConfiguracionTributariaControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class ConfiguracionTributariaAutorizacionTest {

    @Autowired MockMvc mvc;

    @MockitoBean ServicioConfiguracionTributaria servicio;
    // Dependencias de los filtros: con ellas simuladas, los filtros son los
    // de verdad y lo único que se finge es la base de datos.
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    private static final String BASE = "/api/v1/empresa/tributacion";

    private static final String CONFIGURACION = """
            {"responsableIva":true,"aplicaExclusionUmbral":false,"practicaRetenciones":false,
             "tarifaIca":null,"manejaEnvios":true,"cotizacionVigenciaDias":5}""";
    private static final String REGLA = """
            {"categoriaId":4,"codigoImpuesto":"IVA_19","vigenteDesde":"2026-01-01"}""";

    /** Las seis rutas del brief, con un cuerpo válido donde lo piden. */
    private record Ruta(HttpMethod metodo, String camino, String cuerpo) {
        MockHttpServletRequestBuilder peticion() {
            var b = request(metodo, camino);
            if (cuerpo != null) {
                b.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
            }
            return b;
        }
    }

    private static final List<Ruta> LAS_SEIS_RUTAS = List.of(
            new Ruta(HttpMethod.GET, BASE, null),
            new Ruta(HttpMethod.PUT, BASE, CONFIGURACION),
            new Ruta(HttpMethod.GET, BASE + "/impuestos", null),
            new Ruta(HttpMethod.GET, BASE + "/reglas", null),
            new Ruta(HttpMethod.POST, BASE + "/reglas", REGLA),
            new Ruta(HttpMethod.POST, BASE + "/reglas/5/cerrar", null));

    @BeforeEach
    void empresaActiva() {
        // Sin esto el mock devuelve vacío y FiltroEmpresaActiva (que falla
        // cerrado) respondería 403 a toda escritura antes del controlador.
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
    }

    /** El super administrador no tiene empresa: es el único con {@code empresaId} nulo. */
    private static RequestPostProcessor como(String rol) {
        Integer empresa = "SUPER_ADMIN".equals(rol) ? null : 4;
        var u = new UsuarioAutenticado(7, "ana@tienda.co", rol, empresa);
        var autoridades = rol == null
                ? List.<SimpleGrantedAuthority>of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + rol));
        return authentication(new UsernamePasswordAuthenticationToken(u, null, autoridades));
    }

    private static ResultActions esDenegado(ResultActions r) throws Exception {
        return r.andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"))
                .andExpect(jsonPath("$.detail").value("Tu rol no tiene permiso para hacer esto."));
    }

    private static ReglaDeIva regla() {
        return new ReglaDeIva(5, 4, "Granos", null, null, "IVA_19", "IVA tarifa general (19 %)",
                "GRAVADO", new BigDecimal("0.1900"), LocalDate.of(2026, 1, 1), null, null, 1, true);
    }

    // ---- lo que se les niega ----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"VENDEDOR", "CLIENTE", "CONTADOR", "SUPER_ADMIN"})
    @NullSource
    void quienNoEsAdministradorDeEmpresaRecibe403EnLasSeisRutas(String rol) throws Exception {
        for (Ruta ruta : LAS_SEIS_RUTAS) {
            esDenegado(mvc.perform(ruta.peticion().with(como(rol))));
        }

        // Lo importante: el servicio ni se enteró. Un 403 con el servicio ya
        // llamado sería una escritura hecha y mal contestada.
        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarLasSeisRutasSonUn401() throws Exception {
        for (Ruta ruta : LAS_SEIS_RUTAS) {
            mvc.perform(ruta.peticion()).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(servicio);
    }

    // ---- el administrador llega al servicio ---------------------------------

    @Test
    void elAdministradorLeeYGuardaLaConfiguracion() throws Exception {
        var guardada = new ConfiguracionTributaria(true, false, false, null, true, 5, true);
        when(servicio.configuracion()).thenReturn(guardada);
        when(servicio.guardar(any())).thenReturn(guardada);

        mvc.perform(request(HttpMethod.GET, BASE).with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responsableIva").value(true))
                .andExpect(jsonPath("$.configuracionCompleta").value(true));
        mvc.perform(LAS_SEIS_RUTAS.get(1).peticion().with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cotizacionVigenciaDias").value(5));

        verify(servicio).configuracion();
        verify(servicio).guardar(any());
    }

    @Test
    void elAdministradorListaElCatalogoYLasReglas() throws Exception {
        when(servicio.impuestos()).thenReturn(List.of());
        when(servicio.reglas()).thenReturn(List.of(regla()));

        mvc.perform(request(HttpMethod.GET, BASE + "/impuestos").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk());
        mvc.perform(request(HttpMethod.GET, BASE + "/reglas").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigoImpuesto").value("IVA_19"));

        verify(servicio).impuestos();
        verify(servicio).reglas();
    }

    @Test
    void elAdministradorCreaUnaReglaYRecibe201ConSuDireccion() throws Exception {
        when(servicio.crearRegla(any())).thenReturn(regla());

        mvc.perform(LAS_SEIS_RUTAS.get(4).peticion().with(como("ADMINISTRADOR")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", BASE + "/reglas/5"))
                .andExpect(jsonPath("$.reglaId").value(5));

        verify(servicio).crearRegla(any());
    }

    @Test
    void elAdministradorCierraUnaReglaYUnaAjenaEsUn404() throws Exception {
        when(servicio.cerrarRegla(5, LocalDate.of(2026, 12, 31))).thenReturn(Optional.of(regla()));
        when(servicio.cerrarRegla(6, null)).thenReturn(Optional.empty());

        mvc.perform(request(HttpMethod.POST, BASE + "/reglas/5/cerrar")
                        .param("hasta", "2026-12-31").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk());
        mvc.perform(request(HttpMethod.POST, BASE + "/reglas/6/cerrar")
                        .with(como("ADMINISTRADOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void unaVigenciaDeCotizacionFueraDeRangoEsUn400ConProblemDetail() throws Exception {
        String cuerpo = CONFIGURACION.replace("\"cotizacionVigenciaDias\":5",
                "\"cotizacionVigenciaDias\":366");

        mvc.perform(request(HttpMethod.PUT, BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo).with(como("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(servicio);
    }

    @Test
    void noHayPutNiDeleteDeReglas() throws Exception {
        // Una venta se explica con la regla que regía: las reglas se cierran,
        // no se editan ni se borran. Sin ruta, la respuesta es de «método no
        // permitido» o «no encontrada», nunca un 2xx.
        for (HttpMethod metodo : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
            mvc.perform(request(metodo, BASE + "/reglas/5").contentType(MediaType.APPLICATION_JSON)
                            .content(REGLA).with(como("ADMINISTRADOR")))
                    .andExpect(status().is4xxClientError());
        }
        verifyNoInteractions(servicio);
    }
}
