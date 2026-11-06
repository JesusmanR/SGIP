package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.UsuarioEnPlataforma;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién puede desbloquear a un usuario desde la plataforma (I2) y quién puede
 * ver los usuarios de una empresa.
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

    // ------------------------------------------- GET de los usuarios de una empresa

    private static final String RUTA_USUARIOS = "/api/v1/plataforma/empresas/4/usuarios";

    @Test
    void elSuperAdministradorVeLosUsuariosDeLaEmpresaSinNingunaClave() throws Exception {
        when(servicio.usuariosDe(4)).thenReturn(List.of(
                new UsuarioEnPlataforma(20, "Ana Pérez", "ana@tienda.co", "VENDEDOR", true, true),
                new UsuarioEnPlataforma(21, "Luis Gómez", "luis@tienda.co", "ADMINISTRADOR", false, false)));

        mvc.perform(get(RUTA_USUARIOS).with(como("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(20))
                .andExpect(jsonPath("$[0].nombre").value("Ana Pérez"))
                .andExpect(jsonPath("$[0].correo").value("ana@tienda.co"))
                .andExpect(jsonPath("$[0].rol").value("VENDEDOR"))
                .andExpect(jsonPath("$[0].activo").value(true))
                .andExpect(jsonPath("$[0].bloqueado").value(true))
                .andExpect(jsonPath("$[1].activo").value(false))
                .andExpect(jsonPath("$[1].bloqueado").value(false));
    }

    /** Exactamente estos seis campos: lo que no se nombra aquí no sale, y menos la clave. */
    @Test
    void cadaUsuarioTieneSoloLosSeisCamposDelContrato() throws Exception {
        when(servicio.usuariosDe(4)).thenReturn(List.of(
                new UsuarioEnPlataforma(20, "Ana Pérez", "ana@tienda.co", "VENDEDOR", true, false)));

        mvc.perform(get(RUTA_USUARIOS).with(como("SUPER_ADMIN")))
                .andExpect(jsonPath("$[0].length()").value(6))
                .andExpect(jsonPath("$[0].clave").doesNotExist())
                .andExpect(jsonPath("$[0].claveHash").doesNotExist())
                .andExpect(jsonPath("$[0].empresaId").doesNotExist());
        // Y nada en el cuerpo que se parezca a un hash o a una clave.
        mvc.perform(get(RUTA_USUARIOS).with(como("SUPER_ADMIN")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsStringIgnoringCase("clave"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsStringIgnoringCase("hash"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR", "CLIENTE", "CONTADOR"})
    @NullSource
    void ningunOtroRolVeLosUsuariosDeUnaEmpresa(String rol) throws Exception {
        mvc.perform(get(RUTA_USUARIOS).with(como(rol)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarLaListaDeUsuariosEsUn401() throws Exception {
        mvc.perform(get(RUTA_USUARIOS)).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    @Test
    void unaEmpresaQueNoExisteEsUn404ConMotivo() throws Exception {
        when(servicio.usuariosDe(4)).thenThrow(new RecursoNoEncontradoException("La empresa no existe."));

        mvc.perform(get(RUTA_USUARIOS).with(como("SUPER_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("La empresa no existe."));
    }
}
