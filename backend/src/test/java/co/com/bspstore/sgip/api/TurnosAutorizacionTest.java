package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.SolicitudApertura;
import co.com.bspstore.sgip.api.dto.Turno;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.DatoDeTurnoInvalidoException;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.logica.ServicioTurnos;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
import co.com.bspstore.sgip.logica.TurnoYaAbiertoException;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
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
 * Quién llega al turno de caja (RF-45): el administrador y el vendedor. El cliente, el
 * super administrador y quien no tiene rol reciben 403 en las dos rutas.
 *
 * Pasa por la cadena de seguridad real. La empresa de la sesión está ACTIVA, de modo
 * que {@code FiltroEmpresaActiva} deja pasar las escrituras y un 403 solo puede venir
 * de {@code @PreAuthorize}; la prueba de la empresa en consulta lo cambia a propósito.
 * Cada denegación comprueba que el servicio ni se enteró.
 */
@WebMvcTest(controllers = TurnosControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class TurnosAutorizacionTest {

    private static final String RUTA = "/api/v1/caja/turnos";
    private static final String CUERPO = "{\"baseEfectivo\":50000}";

    private static final Turno ABIERTO = new Turno(11L, 7, "Ana Ruiz",
            OffsetDateTime.of(LocalDateTime.of(2026, 11, 12, 8, 30, 45), ZoneOffset.ofHours(-5)),
            new BigDecimal("50000"), null);

    @Autowired MockMvc mvc;

    @MockitoBean ServicioTurnos servicio;
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

    private static MockHttpServletRequestBuilder abrir(String cuerpo) {
        return post(RUTA).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private static MockHttpServletRequestBuilder actual() {
        return get(RUTA + "/actual");
    }

    // ------------------------------------------------------- lo que sí pueden

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorAbrenSuTurnoYRecibenUn201(String rol) throws Exception {
        when(servicio.abrir(any())).thenReturn(ABIERTO);

        mvc.perform(abrir(CUERPO).with(como(rol)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.cajeroId").value(7))
                .andExpect(jsonPath("$.cajero").value("Ana Ruiz"))
                // El desplazamiento de Bogotá va en el texto: el cliente lee la hora tal cual.
                .andExpect(jsonPath("$.apertura").value("2026-11-12T08:30:45-05:00"))
                .andExpect(jsonPath("$.baseEfectivo").value(50000))
                .andExpect(jsonPath("$.cierre").doesNotExist());
    }

    @Test
    void laBaseLlegaAlServicioTalComoSeEnvio() throws Exception {
        when(servicio.abrir(any())).thenReturn(ABIERTO);

        mvc.perform(abrir("{\"baseEfectivo\":120000}").with(como("VENDEDOR"))).andExpect(status().isCreated());

        var enviada = forClass(SolicitudApertura.class);
        verify(servicio).abrir(enviada.capture());
        assertEquals(0,
                new BigDecimal("120000").compareTo(enviada.getValue().baseEfectivo()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"50000.00", "50000.0", "5E+4", "0.00"})
    void unaBaseEnPesosEnterosConCerosDecimalesSeAcepta(String base) throws Exception {
        // Lo que manda un formulario que formatea el monto con dos decimales: son pesos enteros.
        when(servicio.abrir(any())).thenReturn(ABIERTO);

        mvc.perform(abrir("{\"baseEfectivo\":" + base + "}").with(como("VENDEDOR")))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorConsultanSuTurnoActual(String rol) throws Exception {
        when(servicio.actual()).thenReturn(Optional.of(ABIERTO));

        mvc.perform(actual().with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.apertura").value("2026-11-12T08:30:45-05:00"));
    }

    @Test
    void sinTurnoElActualResponde204() throws Exception {
        when(servicio.actual()).thenReturn(Optional.empty());

        mvc.perform(actual().with(como("VENDEDOR")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    // ------------------------------------------------------ lo que no pueden

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void elClienteYElSuperAdministradorRecibenUn403(String rol) throws Exception {
        for (MockHttpServletRequestBuilder llamada : List.of(abrir(CUERPO), actual())) {
            mvc.perform(llamada.with(como(rol)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));
        }

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(abrir(CUERPO)).andExpect(status().isUnauthorized());
        mvc.perform(actual()).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    @Test
    void unaEmpresaEnConsultaVeSuTurnoPeroNoAbreOtro() throws Exception {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));
        when(servicio.actual()).thenReturn(Optional.of(ABIERTO));

        mvc.perform(actual().with(como("VENDEDOR"))).andExpect(status().isOk());
        mvc.perform(abrir(CUERPO).with(como("VENDEDOR"))).andExpect(status().isForbidden());

        verify(servicio, never()).abrir(any());
    }

    // ------------------------------------------------------ los datos inválidos

    @ParameterizedTest
    @ValueSource(strings = {
            // falta la base
            "{}",
            // base nula
            "{\"baseEfectivo\":null}",
            // negativa
            "{\"baseEfectivo\":-1}",
            // con centavos: el dinero va en pesos enteros
            "{\"baseEfectivo\":50000.5}",
            // más grande que la columna
            "{\"baseEfectivo\":1000000000000}",
            // no es un número
            "{\"baseEfectivo\":\"mucho\"}",
            // cuerpo vacío
            ""})
    void unaBaseInvalidaEsUn400(String cuerpo) throws Exception {
        mvc.perform(abrir(cuerpo).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    @Test
    void elDetalleDeLaBaseInvalidaNoRepiteLoEnviado() throws Exception {
        mvc.perform(abrir("{\"baseEfectivo\":-987654}").with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("987654"))));
    }

    // ----------------------------------------------------- los errores, en español

    @Test
    void siElServicioRechazaLaBaseEsUn400ConSuTexto() throws Exception {
        doThrow(new DatoDeTurnoInvalidoException("La base de efectivo debe ser un monto en pesos enteros."))
                .when(servicio).abrir(any());

        mvc.perform(abrir(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Turno no válido"))
                .andExpect(jsonPath("$.detail").value("La base de efectivo debe ser un monto en pesos enteros."));
    }

    @Test
    void abrirConUnTurnoYaAbiertoEsUn409() throws Exception {
        doThrow(new TurnoYaAbiertoException()).when(servicio).abrir(any());

        mvc.perform(abrir(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Ya hay un turno abierto"))
                .andExpect(jsonPath("$.detail")
                        .value("Ya tienes un turno de caja abierto. Ciérralo antes de abrir otro."));
    }

    @Test
    void elManejadorTraduceLaFaltaDeTurnoA409ConSuTexto() throws Exception {
        doThrow(new SinTurnoAbiertoException()).when(servicio).actual();

        mvc.perform(actual().with(como("VENDEDOR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Sin turno abierto"))
                .andExpect(jsonPath("$.detail")
                        .value("Abre un turno de caja antes de registrar esta operación."));
    }
}
