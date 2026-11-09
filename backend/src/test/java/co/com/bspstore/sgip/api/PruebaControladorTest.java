package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.ServicioPrueba;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/empresa/prueba (RF-73): el tiempo restante lo calcula el servidor
 * con su reloj, en la hora de Bogotá, y solo lo ven el administrador y el
 * vendedor de la empresa.
 *
 * Pasa por la cadena de seguridad real y por el servicio de verdad; solo el
 * reloj y el repositorio son simulados. El reloj va en UTC, como la JVM de la CI.
 */
@WebMvcTest(controllers = PruebaControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class, ServicioPrueba.class})
class PruebaControladorTest {

    private static final String RUTA = "/api/v1/empresa/prueba";
    private static final LocalDate FIN = LocalDate.of(2026, 12, 10);

    @Autowired MockMvc mvc;

    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;
    @MockitoBean Clock reloj;

    @BeforeEach
    void empresaEnPrueba() {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.PRUEBA));
        when(plataforma.finDePruebaDe(4)).thenReturn(Optional.of(FIN));
        when(reloj.getZone()).thenReturn(ZoneOffset.UTC);
        ahora("2026-12-01T00:00:00-05:00");
    }

    private void ahora(String bogota) {
        when(reloj.instant()).thenReturn(OffsetDateTime.parse(bogota).toInstant());
    }

    private static RequestPostProcessor como(String rol) {
        Integer empresa = "SUPER_ADMIN".equals(rol) ? null : 4;
        var u = new UsuarioAutenticado(7, "ana@tienda.co", rol, empresa);
        var autoridades = rol == null
                ? List.<SimpleGrantedAuthority>of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + rol));
        return authentication(new UsernamePasswordAuthenticationToken(u, null, autoridades));
    }

    // ------------------------------------------------------ tres instantes

    @Test
    void aDiezDiasDelVencimiento() throws Exception {
        mvc.perform(get(RUTA).with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PRUEBA"))
                .andExpect(jsonPath("$.venceEn").value("2026-12-10T23:59:59-05:00"))
                .andExpect(jsonPath("$.segundosRestantes").value(10 * 86_400));
    }

    @Test
    void aCincoHorasDelVencimiento() throws Exception {
        ahora("2026-12-10T19:00:00-05:00");

        mvc.perform(get(RUTA).with(como("VENDEDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PRUEBA"))
                .andExpect(jsonPath("$.venceEn").value("2026-12-10T23:59:59-05:00"))
                .andExpect(jsonPath("$.segundosRestantes").value(5 * 3_600));
    }

    /** Es una lectura: responde también en modo consulta. */
    @Test
    void unSegundoDespuesDelVencimiento() throws Exception {
        ahora("2026-12-11T00:00:00-05:00");

        mvc.perform(get(RUTA).with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PRUEBA_VENCIDA"))
                .andExpect(jsonPath("$.venceEn").value("2026-12-10T23:59:59-05:00"))
                .andExpect(jsonPath("$.segundosRestantes").value(0));
        verify(plataforma, never()).cambiarEstado(anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unaEmpresaActivaNoTieneVencimiento() throws Exception {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));

        mvc.perform(get(RUTA).with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ACTIVA"))
                .andExpect(jsonPath("$.venceEn").doesNotExist())
                .andExpect(jsonPath("$.segundosRestantes").doesNotExist());
    }

    @Test
    void unaEmpresaDesactivadaLoDiceSinVencimiento() throws Exception {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));

        mvc.perform(get(RUTA).with(como("VENDEDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DESACTIVADA"))
                .andExpect(jsonPath("$.venceEn").doesNotExist());
    }

    // --------------------------------------------------------------- roles

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "CONTADOR", "SUPER_ADMIN"})
    @NullSource
    void otroRolRecibe403ConProblemDetail(String rol) throws Exception {
        mvc.perform(get(RUTA).with(como(rol)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
        verify(plataforma, never()).finDePruebaDe(anyInt());
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
    }
}
