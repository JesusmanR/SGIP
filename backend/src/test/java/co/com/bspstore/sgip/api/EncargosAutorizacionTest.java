package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.EncargoPendiente;
import co.com.bspstore.sgip.api.dto.EntregaRegistrada;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.EstadoNoAdmiteLaOperacionException;
import co.com.bspstore.sgip.logica.FiltroInvalidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioEncargos;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
 * Quién llega a los encargos (RF-42, RF-69): solo el administrador ve la lista y marca la entrega; el
 * vendedor, el cliente y el super administrador reciben 403 en las dos rutas. Pasa por la cadena de
 * seguridad real y la empresa de la sesión está ACTIVA, de modo que un 403 solo puede venir de
 * {@code @PreAuthorize}.
 */
@WebMvcTest(controllers = EncargosControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class EncargosAutorizacionTest {

    private static final String RUTA = "/api/v1/caja/encargos";

    private static final EncargoPendiente PENDIENTE = new EncargoPendiente(21L, 9L, "V-00000001",
            OffsetDateTime.parse("2026-11-09T09:00:00-05:00"), "Ana Pérez", "3001112233", 5, "RAP-1", "Tornillo", 3, 3,
            LocalDate.of(2026, 11, 20), -3, "PENDIENTE", 3);

    @Autowired MockMvc mvc;

    @MockitoBean ServicioEncargos servicio;
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

    // ------------------------------------------------------- lo que sí puede el administrador

    @Test
    void elAdministradorVeLaListaConElClienteLaFechaEstimadaYElEstado() throws Exception {
        when(servicio.listar("PENDIENTE")).thenReturn(List.of(PENDIENTE));

        mvc.perform(get(RUTA).param("estado", "PENDIENTE").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemId").value(21))
                .andExpect(jsonPath("$[0].cliente").value("Ana Pérez"))
                .andExpect(jsonPath("$[0].telefono").value("3001112233"))
                .andExpect(jsonPath("$[0].producto").value("Tornillo"))
                .andExpect(jsonPath("$[0].cantidadEncargo").value(3))
                .andExpect(jsonPath("$[0].entregaEstimada").value("2026-11-20"))
                .andExpect(jsonPath("$[0].existencia").value(-3))
                .andExpect(jsonPath("$[0].estado").value("PENDIENTE"))
                .andExpect(jsonPath("$[0].costo").doesNotExist());
        verify(servicio).listar("PENDIENTE");
    }

    @Test
    void elAdministradorMarcaLaEntregaYRecibeQuienYCuando() throws Exception {
        when(servicio.entregar(21L)).thenReturn(new EntregaRegistrada(21L,
                OffsetDateTime.parse("2026-11-12T08:30:45-05:00"), 7));

        mvc.perform(post(RUTA + "/21/entrega").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.entregadoPor").value(7))
                .andExpect(jsonPath("$.entregadoEn").exists());
    }

    @Test
    void losErroresDeLaReglaLleganConSuEstado() throws Exception {
        doThrow(new FiltroInvalidoException("El estado no existe.")).when(servicio).listar("OTRO");
        doThrow(new RecursoNoEncontradoException("El encargo no existe.")).when(servicio).entregar(404L);
        doThrow(new EstadoNoAdmiteLaOperacionException("Este encargo ya fue entregado.")).when(servicio).entregar(409L);

        mvc.perform(get(RUTA).with(como("ADMINISTRADOR"))).andExpect(status().isBadRequest());
        mvc.perform(get(RUTA).param("estado", "OTRO").with(como("ADMINISTRADOR"))).andExpect(status().isBadRequest());
        mvc.perform(post(RUTA + "/404/entrega").with(como("ADMINISTRADOR"))).andExpect(status().isNotFound());
        mvc.perform(post(RUTA + "/409/entrega").with(como("ADMINISTRADOR"))).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Este encargo ya fue entregado."));
        mvc.perform(post(RUTA + "/abc/entrega").with(como("ADMINISTRADOR"))).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------- lo que no puede nadie más

    @Test
    void elVendedorNoVeLaListaDeEncargosNiMarcaSuEntrega() throws Exception {
        mvc.perform(get(RUTA).param("estado", "PENDIENTE").with(como("VENDEDOR")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));
        mvc.perform(post(RUTA + "/21/entrega").with(como("VENDEDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));

        verifyNoInteractions(servicio);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void elClienteYElSuperAdministradorRecibenUn403(String rol) throws Exception {
        mvc.perform(get(RUTA).param("estado", "PENDIENTE").with(como(rol))).andExpect(status().isForbidden());
        mvc.perform(post(RUTA + "/21/entrega").with(como(rol))).andExpect(status().isForbidden());

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(get(RUTA).param("estado", "PENDIENTE")).andExpect(status().isUnauthorized());
        mvc.perform(post(RUTA + "/21/entrega")).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    @Test
    void unaEmpresaEnConsultaVeLaListaPeroNoMarcaEntregas() throws Exception {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));
        when(servicio.listar("PENDIENTE")).thenReturn(List.of(PENDIENTE));

        mvc.perform(get(RUTA).param("estado", "PENDIENTE").with(como("ADMINISTRADOR"))).andExpect(status().isOk());
        mvc.perform(post(RUTA + "/21/entrega").with(como("ADMINISTRADOR"))).andExpect(status().isForbidden());

        verify(servicio, never()).entregar(anyLong());
    }
}
