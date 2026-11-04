package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.AlertaUvt;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.logica.ServicioTributario;
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

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién ve la alerta de la UVT (arreglo 10).
 *
 * El controlador entero cuelga de {@code @PreAuthorize("hasRole('SUPER_ADMIN')")}:
 * la ruta nueva lo hereda y esta prueba lo demuestra pasando por la cadena
 * de seguridad real. Un 403 con el servicio ya llamado sería una lectura
 * hecha y mal contestada, de modo que cada denegación comprueba que el
 * servicio ni se enteró.
 */
@WebMvcTest(controllers = TributarioControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class TributarioAutorizacionTest {

    @Autowired MockMvc mvc;

    @MockitoBean ServicioTributario servicio;
    // Dependencias de los filtros: con ellas simuladas, los filtros son los
    // de verdad y lo único que se finge es la base de datos.
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    private static final String ALERTA = "/api/v1/plataforma/tributario/alerta-uvt";

    @BeforeEach
    void empresaActiva() {
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

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR", "CLIENTE", "CONTADOR"})
    @NullSource
    void quienNoEsSuperAdministradorRecibe403SinTocarElServicio(String rol) throws Exception {
        mvc.perform(get(ALERTA).with(como(rol)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"))
                .andExpect(jsonPath("$.detail").value("Tu rol no tiene permiso para hacer esto."));

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401SinTocarElServicio() throws Exception {
        mvc.perform(get(ALERTA)).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    @Test
    void elSuperAdministradorRecibeLaAlerta() throws Exception {
        when(servicio.alertaUvt()).thenReturn(new AlertaUvt(true, 2027, 61));

        mvc.perform(get(ALERTA).with(como("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activa").value(true))
                .andExpect(jsonPath("$.anio").value(2027))
                .andExpect(jsonPath("$.diasRestantes").value(61));

        verify(servicio).alertaUvt();
    }
}
