package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.AdministradorParaAutorizar;
import co.com.bspstore.sgip.api.dto.EstadoPin;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.ServicioAutorizacion;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién llega al PIN del administrador (ADR-0032): fijarlo y consultarlo es solo
 * del administrador; la lista de administradores para autorizar es del
 * administrador y del vendedor. El cliente y el super administrador reciben 403 en
 * las tres rutas.
 *
 * Pasa por la cadena de seguridad real. La empresa de la sesión está ACTIVA, de
 * modo que {@code FiltroEmpresaActiva} deja pasar la escritura y un 403 solo puede
 * venir de {@code @PreAuthorize}. Cada denegación comprueba que el servicio ni se
 * enteró.
 */
@WebMvcTest(controllers = PinControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class PinAutorizacionTest {

    private static final String PIN = "/api/v1/perfil/pin";
    private static final String ADMINISTRADORES = "/api/v1/caja/administradores";
    private static final String CUERPO = "{\"pinNuevo\":\"483920\"}";

    @Autowired MockMvc mvc;

    @MockitoBean ServicioAutorizacion servicio;
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

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

    private static MockHttpServletRequestBuilder fijar() {
        return put(PIN).contentType(MediaType.APPLICATION_JSON).content(CUERPO);
    }

    // ------------------------------------------------------- lo que sí pueden

    @Test
    void elAdministradorFijaSuPinConUn204() throws Exception {
        mvc.perform(fijar().with(como("ADMINISTRADOR")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(servicio).fijarPin(null, "483920");
    }

    @Test
    void elAdministradorConsultaSiTienePin() throws Exception {
        when(servicio.estadoDelPin()).thenReturn(new EstadoPin(true));

        mvc.perform(get(PIN).with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configurado").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorListanLosAdministradoresParaAutorizar(String rol) throws Exception {
        when(servicio.administradores()).thenReturn(List.of(new AdministradorParaAutorizar(10, "Ana Admin")));

        mvc.perform(get(ADMINISTRADORES).with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].nombre").value("Ana Admin"));
    }

    // ------------------------------------------------------ lo que no pueden

    @Test
    void elVendedorListaPeroNoTocaElPin() throws Exception {
        when(servicio.administradores()).thenReturn(List.of());

        mvc.perform(get(ADMINISTRADORES).with(como("VENDEDOR"))).andExpect(status().isOk());
        mvc.perform(get(PIN).with(como("VENDEDOR"))).andExpect(status().isForbidden());
        mvc.perform(fijar().with(como("VENDEDOR"))).andExpect(status().isForbidden());

        // Solo la primera llamada llegó al servicio.
        verify(servicio).administradores();
        verifyNoMoreInteractions(servicio);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void elClienteYElSuperAdministradorRecibenUn403(String rol) throws Exception {
        for (MockHttpServletRequestBuilder llamada : List.of(
                get(ADMINISTRADORES), get(PIN), fijar())) {
            mvc.perform(llamada.with(como(rol)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));
        }

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(get(ADMINISTRADORES)).andExpect(status().isUnauthorized());
        mvc.perform(get(PIN)).andExpect(status().isUnauthorized());
        mvc.perform(fijar()).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }
}
