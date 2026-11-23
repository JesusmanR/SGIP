package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.MedioDePago;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.MedioDePagoInvalidoException;
import co.com.bspstore.sgip.logica.NombreRepetidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioMediosPago;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.logica.UltimoMedioActivoException;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.modelo.TipoMedio;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién llega a los medios de pago (RF-43): el administrador y el vendedor
 * listan los activos para cobrar; solo el administrador los administra. El
 * cliente y el super administrador reciben 403 en ambas rutas.
 *
 * Pasa por la cadena de seguridad real. La empresa de la sesión está ACTIVA,
 * de modo que {@code FiltroEmpresaActiva} deja pasar las escrituras y un 403
 * solo puede venir de {@code @PreAuthorize}. Cada denegación comprueba que el
 * servicio ni se enteró.
 */
@WebMvcTest(controllers = MediosPagoControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class MediosPagoAutorizacionTest {

    private static final String CAJA = "/api/v1/caja/medios-pago";
    private static final String ADMINISTRACION = "/api/v1/medios-pago";
    private static final String CUERPO_NUEVO = "{\"nombre\":\"Nequi\",\"tipo\":\"TRANSFERENCIA\"}";
    private static final String CUERPO_ACTIVO = "{\"activo\":false}";

    private static final MedioDePago EFECTIVO = new MedioDePago(10, "Efectivo", TipoMedio.EFECTIVO, true);
    private static final MedioDePago SALDO = new MedioDePago(13, "Saldo a favor", TipoMedio.SALDO_A_FAVOR, false);

    @Autowired MockMvc mvc;

    @MockitoBean ServicioMediosPago servicio;
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

    private static MockHttpServletRequestBuilder crear() {
        return post(ADMINISTRACION).contentType(MediaType.APPLICATION_JSON).content(CUERPO_NUEVO);
    }

    private static MockHttpServletRequestBuilder cambiar(int id) {
        return patch(ADMINISTRACION + "/" + id).contentType(MediaType.APPLICATION_JSON).content(CUERPO_ACTIVO);
    }

    // ------------------------------------------------------- lo que sí pueden

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorListanLosActivos(String rol) throws Exception {
        when(servicio.activos()).thenReturn(List.of(EFECTIVO));

        mvc.perform(get(CAJA).with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].nombre").value("Efectivo"))
                .andExpect(jsonPath("$[0].tipo").value("EFECTIVO"))
                .andExpect(jsonPath("$[0].activo").value(true));
    }

    @Test
    void elVendedorListaLosActivosPeroNoAdministra() throws Exception {
        when(servicio.activos()).thenReturn(List.of(EFECTIVO));

        mvc.perform(get(CAJA).with(como("VENDEDOR"))).andExpect(status().isOk());
        mvc.perform(get(ADMINISTRACION).with(como("VENDEDOR"))).andExpect(status().isForbidden());
        mvc.perform(crear().with(como("VENDEDOR"))).andExpect(status().isForbidden());
        mvc.perform(cambiar(10).with(como("VENDEDOR"))).andExpect(status().isForbidden());

        // Solo la primera llamada llegó al servicio.
        verify(servicio).activos();
        org.mockito.Mockito.verifyNoMoreInteractions(servicio);
    }

    @Test
    void elAdministradorVeTodosYElDeSistemaVaMarcado() throws Exception {
        when(servicio.todos()).thenReturn(List.of(EFECTIVO, SALDO));

        mvc.perform(get(ADMINISTRACION).with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deSistema").value(false))
                .andExpect(jsonPath("$[1].tipo").value("SALDO_A_FAVOR"))
                .andExpect(jsonPath("$[1].deSistema").value(true));
    }

    @Test
    void elAdministradorCreaUnMedioYRecibeUn201ConSuUbicacion() throws Exception {
        when(servicio.crear(any())).thenReturn(new MedioDePago(20, "Nequi", TipoMedio.TRANSFERENCIA, true));

        mvc.perform(crear().with(como("ADMINISTRADOR")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/medios-pago/20"))
                .andExpect(jsonPath("$.id").value(20))
                .andExpect(jsonPath("$.nombre").value("Nequi"));
    }

    @Test
    void elAdministradorActivaODesactivaConUnPatch() throws Exception {
        when(servicio.cambiarActivo(10, false))
                .thenReturn(new MedioDePago(10, "Efectivo", TipoMedio.EFECTIVO, false));

        mvc.perform(cambiar(10).with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
    }

    // ------------------------------------------------------ lo que no pueden

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void elClienteYElSuperAdministradorRecibenUn403(String rol) throws Exception {
        for (MockHttpServletRequestBuilder llamada : List.of(
                get(CAJA), get(ADMINISTRACION), crear(), cambiar(10))) {
            mvc.perform(llamada.with(como(rol)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));
        }

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(get(CAJA)).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMINISTRACION)).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    // ----------------------------------------------------- los errores, en español

    @Test
    void unMedioDeOtraEmpresaEsUn404() throws Exception {
        doThrow(new RecursoNoEncontradoException("El medio de pago no existe."))
                .when(servicio).cambiarActivo(99, false);

        mvc.perform(cambiar(99).with(como("ADMINISTRADOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("El medio de pago no existe."));
    }

    @Test
    void desactivarElUnicoMedioActivoEsUn409() throws Exception {
        doThrow(new UltimoMedioActivoException("La empresa necesita al menos un medio de pago activo."))
                .when(servicio).cambiarActivo(10, false);

        mvc.perform(cambiar(10).with(como("ADMINISTRADOR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La empresa necesita al menos un medio de pago activo."));
    }

    @Test
    void unNombreRepetidoEsUn409() throws Exception {
        doThrow(new NombreRepetidoException("Ya existe un medio de pago llamado «Nequi»."))
                .when(servicio).crear(any());

        mvc.perform(crear().with(como("ADMINISTRADOR")))
                .andExpect(status().isConflict());
    }

    @Test
    void elSaldoAFavorNoSeActivaAMano_400() throws Exception {
        doThrow(new MedioDePagoInvalidoException("«Saldo a favor» lo gestiona el sistema."))
                .when(servicio).cambiarActivo(13, true);

        mvc.perform(patch(ADMINISTRACION + "/13").with(como("ADMINISTRADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("«Saldo a favor» lo gestiona el sistema."));
    }

    @Test
    void elSaldoAFavorNoSePuedeCrearAMano_400() throws Exception {
        doThrow(new MedioDePagoInvalidoException("«Saldo a favor» lo gestiona el sistema."))
                .when(servicio).crear(any());

        mvc.perform(post(ADMINISTRACION).with(como("ADMINISTRADOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Saldo\",\"tipo\":\"SALDO_A_FAVOR\"}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"tipo\":\"EFECTIVO\"}",
            "{\"nombre\":\"  \",\"tipo\":\"EFECTIVO\"}",
            "{\"nombre\":\"Nequi\"}",
            "{\"nombre\":\"Nequi\",\"tipo\":\"CREDITO\"}",
            "{\"nombre\":\"Nequi\",\"tipo\":\"CHEQUE\"}"})
    void unCuerpoInvalidoEsUn400SinTocarElServicio(String cuerpo) throws Exception {
        mvc.perform(post(ADMINISTRACION).with(como("ADMINISTRADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    @Test
    void elPatchSinElCampoActivoEsUn400() throws Exception {
        mvc.perform(patch(ADMINISTRACION + "/10").with(como("ADMINISTRADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }
}
