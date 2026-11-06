package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioPlataforma;
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

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién puede desbloquear a un usuario desde la plataforma (I2).
 *
 * Pasa por la cadena de seguridad real. La empresa de la sesión está ACTIVA,
 * de modo que {@code FiltroEmpresaActiva} deja pasar las escrituras y un 403
 * solo puede venir de {@code @PreAuthorize}. Cada denegación comprueba que el
 * servicio ni se enteró.
 */
@WebMvcTest(controllers = PlataformaControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class PlataformaAutorizacionTest {

    private static final String RUTA = "/api/v1/plataforma/empresas/4/usuarios/20/desbloquear";

    @Autowired MockMvc mvc;

    @MockitoBean ServicioPlataforma servicio;
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

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

    @Test
    void elSuperAdministradorDesbloqueaAUnUsuarioDeLaEmpresaIndicada() throws Exception {
        mvc.perform(post(RUTA).with(como("SUPER_ADMIN")))
                .andExpect(status().isNoContent());

        verify(servicio).desbloquearUsuario(4, 20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR", "CLIENTE", "CONTADOR"})
    @NullSource
    void ningunOtroRolAlcanzaElDesbloqueo(String rol) throws Exception {
        mvc.perform(post(RUTA).with(como(rol)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(post(RUTA)).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    @Test
    void unUsuarioQueNoEsDeEsaEmpresaEsUn404ConMotivo() throws Exception {
        doThrow(new RecursoNoEncontradoException("El usuario no existe."))
                .when(servicio).desbloquearUsuario(4, 20);

        mvc.perform(post(RUTA).with(como("SUPER_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("El usuario no existe."));
    }
}
