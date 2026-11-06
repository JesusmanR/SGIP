package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ReglaDeMargen;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.RegistroRepetidoException;
import co.com.bspstore.sgip.logica.ReglaInvalidaException;
import co.com.bspstore.sgip.logica.ServicioPrecios;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién reemplaza una regla de ganancia y cómo se contestan sus errores.
 *
 * Pasa por la cadena de seguridad real: la empresa de la sesión está ACTIVA
 * para que {@code FiltroEmpresaActiva} deje pasar la escritura y un 403 solo
 * pueda venir de la anotación de la clase. El cuerpo es válido, porque
 * {@code @Valid} se resuelve antes y un cuerpo malo daría 400 en lugar de 403.
 */
@WebMvcTest(controllers = PreciosControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class PreciosAutorizacionTest {

    @Autowired MockMvc mvc;

    @MockitoBean ServicioPrecios servicio;
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    private static final String RUTA = "/api/v1/precios/reglas/5/reemplazar";
    private static final String CUERPO = """
            {"formaPago":"CONTADO","formaCalculo":"SOBRE_COSTO","factor":1.5,
             "vigenteDesde":"2026-11-06"}""";

    @BeforeEach
    void empresaActiva() {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
    }

    private static RequestPostProcessor como(String rol) {
        Integer empresa = "SUPER_ADMIN".equals(rol) ? null : 4;
        var u = new UsuarioAutenticado(7, "ana@tienda.co", rol, empresa);
        var autoridades = rol == null
                ? List.<SimpleGrantedAuthority>of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + rol));
        return authentication(new UsernamePasswordAuthenticationToken(u, null, autoridades));
    }

    private static ReglaDeMargen nueva() {
        return new ReglaDeMargen(9, null, null, null, null, "CONTADO", "SOBRE_COSTO",
                new BigDecimal("1.5000"), LocalDate.of(2026, 11, 6), null, 0, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VENDEDOR", "CLIENTE", "CONTADOR", "SUPER_ADMIN"})
    @NullSource
    void quienNoEsAdministradorRecibe403YElServicioNoSeEntera(String rol) throws Exception {
        mvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO).with(como(rol)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    @Test
    void elAdministradorRecibe201ConLaReglaNuevaYSuDireccion() throws Exception {
        when(servicio.reemplazar(eq(5), any())).thenReturn(Optional.of(nueva()));

        mvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO)
                        .with(como("ADMINISTRADOR")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/precios/reglas/9"))
                .andExpect(jsonPath("$.reglaId").value(9));
    }

    @Test
    void unaReglaAjenaOInexistenteEsUn404ConProblemDetail() throws Exception {
        when(servicio.reemplazar(eq(5), any())).thenReturn(Optional.empty());

        mvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO)
                        .with(como("ADMINISTRADOR")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("La regla 5 no existe en esta empresa."));
    }

    @Test
    void unaReglaQueYaTerminoEsUn400ConProblemDetail() throws Exception {
        when(servicio.reemplazar(eq(5), any()))
                .thenThrow(new ReglaInvalidaException("La regla ya terminó el 2026-01-01."));

        mvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO)
                        .with(como("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("La regla ya terminó el 2026-01-01."));
    }

    @Test
    void unSolapeEsUn409ConProblemDetail() throws Exception {
        when(servicio.reemplazar(eq(5), any()))
                .thenThrow(new RegistroRepetidoException("Se solapa con otra regla."));

        mvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO)
                        .with(como("ADMINISTRADOR")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unCuerpoInvalidoEsUn400SinLlegarAlServicio() throws Exception {
        mvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"formaPago\":\"CONTADO\"}").with(como("ADMINISTRADOR")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }
}
