package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.EmpresaEnPlataforma;
import co.com.bspstore.sgip.api.dto.SolicitudIdentidad;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.DocumentoEmpresaInvalidoException;
import co.com.bspstore.sgip.logica.NitRepetidoException;
import co.com.bspstore.sgip.logica.ServicioPlataforma;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.modelo.TipoDocumentoEmpresa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/v1/plataforma/empresas/{id}/identidad: solo el super administrador cambia el NIT o la CC y
 * la razón social de una empresa, y siempre con un motivo.
 *
 * Pasa por la cadena de seguridad real. Cada rechazo comprueba que el servicio ni se enteró. El efecto
 * sobre la base (la fila de la empresa y las de la bitácora) está en {@code EmpresaConBaseTest}.
 */
@WebMvcTest(controllers = PlataformaControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class PlataformaIdentidadAutorizacionTest {

    private static final String RUTA = "/api/v1/plataforma/empresas/4/identidad";
    private static final String MOTIVO = "Corrección pedida por la tienda";

    @Autowired MockMvc mvc;

    @MockitoBean ServicioPlataforma servicio;
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

    private static String cuerpo(String tipo, String numero, String razon, String motivo) {
        return "{\"tipoDocumento\":" + comillas(tipo) + ",\"numero\":" + comillas(numero)
                + ",\"razonSocial\":" + comillas(razon) + ",\"motivo\":" + comillas(motivo) + "}";
    }

    private static String comillas(String valor) {
        return valor == null ? "null" : "\"" + valor + "\"";
    }

    private static MockHttpServletRequestBuilder cambiar(String cuerpo) {
        return put(RUTA).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private static String valido() {
        return cuerpo("NIT", "900.123.456", "Tornillo S.A.S.", MOTIVO);
    }

    private static EmpresaEnPlataforma empresa() {
        return new EmpresaEnPlataforma(4, "NIT", "900123456", "Tornillo S.A.S.", null, null, null,
                "PRUEBA", LocalDate.of(2026, 11, 1).atStartOfDay(), "BASICO", LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 12, 1), "PRUEBA", 1);
    }

    @Test
    void elSuperAdministradorCambiaLaIdentidadYRecibeLaEmpresa() throws Exception {
        when(servicio.cambiarIdentidad(eq(4), any())).thenReturn(Optional.of(empresa()));

        mvc.perform(cambiar(valido()).with(como("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.empresaId").value(4))
                .andExpect(jsonPath("$.tipoDocumento").value("NIT"))
                .andExpect(jsonPath("$.nit").value("900123456"));

        ArgumentCaptor<SolicitudIdentidad> pedido = ArgumentCaptor.forClass(SolicitudIdentidad.class);
        verify(servicio).cambiarIdentidad(eq(4), pedido.capture());
        assertEquals(TipoDocumentoEmpresa.NIT, pedido.getValue().tipoDocumento());
        assertEquals("900.123.456", pedido.getValue().numero());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR", "CLIENTE"})
    @NullSource
    void adminRecibe403EnIdentidad(String rol) throws Exception {
        mvc.perform(cambiar(valido()).with(como(rol)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(cambiar(valido())).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    @Test
    void sinMotivoEs400() throws Exception {
        for (String motivo : new String[]{null, "", "          "}) {
            mvc.perform(cambiar(cuerpo("NIT", "900123456", "Tornillo S.A.S.", motivo)).with(como("SUPER_ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("motivo")));
        }

        verifyNoInteractions(servicio);
    }

    /** La columna de la bitácora mide 200: un motivo de 201 se rechaza aquí y no en el INSERT. */
    @Test
    void motivoDe201Es400() throws Exception {
        mvc.perform(cambiar(cuerpo("NIT", "900123456", "Tornillo S.A.S.", "m".repeat(201)))
                        .with(como("SUPER_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("motivo")));

        verifyNoInteractions(servicio);
    }

    @Test
    void unMotivoDeNueveCaracteresEs400YUnoDeDiezOdeDoscientosPasa() throws Exception {
        when(servicio.cambiarIdentidad(eq(4), any())).thenReturn(Optional.of(empresa()));

        mvc.perform(cambiar(cuerpo("NIT", "900123456", "Tornillo S.A.S.", "m".repeat(9))).with(como("SUPER_ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(cambiar(cuerpo("NIT", "900123456", "Tornillo S.A.S.", "m".repeat(10))).with(como("SUPER_ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(cambiar(cuerpo("NIT", "900123456", "Tornillo S.A.S.", "m".repeat(200))).with(como("SUPER_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void faltaElTipoElNumeroOLaRazonSocialEs400() throws Exception {
        for (String c : List.of(
                cuerpo(null, "900123456", "Tornillo S.A.S.", MOTIVO),
                cuerpo("NIT", null, "Tornillo S.A.S.", MOTIVO),
                cuerpo("NIT", "900123456", null, MOTIVO),
                cuerpo("NIT", "900123456", "x".repeat(161), MOTIVO),
                cuerpo("RUT", "900123456", "Tornillo S.A.S.", MOTIVO))) {
            mvc.perform(cambiar(c).with(como("SUPER_ADMIN"))).andExpect(status().isBadRequest());
        }

        verifyNoInteractions(servicio);
    }

    @Test
    void unDocumentoConLetrasLlegaConSuTipo() throws Exception {
        when(servicio.cambiarIdentidad(eq(4), any()))
                .thenThrow(new DocumentoEmpresaInvalidoException());

        mvc.perform(cambiar(valido()).with(como("SUPER_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:sgip:empresa:documento-invalido"));
    }

    @Test
    void unNumeroRepetidoLlegaComoConflictoConSuTipo() throws Exception {
        when(servicio.cambiarIdentidad(eq(4), any()))
                .thenThrow(new NitRepetidoException("Ya hay una empresa con esa identificación."));

        mvc.perform(cambiar(valido()).with(como("SUPER_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:sgip:nit-repetido"));
    }

    @Test
    void unaEmpresaInexistenteEsUn404() throws Exception {
        when(servicio.cambiarIdentidad(eq(4), any())).thenReturn(Optional.empty());

        mvc.perform(cambiar(valido()).with(como("SUPER_ADMIN")))
                .andExpect(status().isNotFound());
    }
}
