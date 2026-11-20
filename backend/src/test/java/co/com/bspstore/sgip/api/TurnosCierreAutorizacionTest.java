package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ResumenDeTurno;
import co.com.bspstore.sgip.api.dto.SolicitudCierre;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.DatoDeTurnoInvalidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.logica.ServicioTurnos;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Quién llega al cierre y a la lista de turnos (RF-45): el administrador y el vendedor. El cliente, el
 * super administrador y quien no tiene rol reciben 403 en las tres rutas.
 *
 * Pasa por la cadena de seguridad real, con la empresa ACTIVA, de modo que un 403 solo puede venir de
 * {@code @PreAuthorize}; la prueba de la empresa en consulta lo cambia a propósito. Cada denegación comprueba
 * que el servicio ni se enteró. También fija la forma del JSON que el web ya espera: las horas con su
 * desplazamiento y {@code porMedio} como un objeto de números.
 */
@WebMvcTest(controllers = TurnosControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class TurnosCierreAutorizacionTest {

    private static final String RUTA = "/api/v1/caja/turnos";
    private static final String CUERPO = "{\"efectivoContado\":165000,\"observacion\":null}";

    private static final OffsetDateTime APERTURA =
            OffsetDateTime.of(LocalDateTime.of(2026, 11, 12, 6, 0, 0), ZoneOffset.ofHours(-5));
    private static final OffsetDateTime CIERRE =
            OffsetDateTime.of(LocalDateTime.of(2026, 11, 12, 14, 5, 30), ZoneOffset.ofHours(-5));

    private static ResumenDeTurno resumen(OffsetDateTime cierre, BigDecimal contado, BigDecimal diferencia) {
        Map<TipoMedio, BigDecimal> porMedio = new EnumMap<>(TipoMedio.class);
        porMedio.put(TipoMedio.EFECTIVO, new BigDecimal("70000"));
        porMedio.put(TipoMedio.TARJETA, new BigDecimal("30000"));
        return new ResumenDeTurno(11L, "Ana Ruiz", APERTURA, cierre, new BigDecimal("100000"),
                new BigDecimal("165000"), contado, diferencia, porMedio, null);
    }

    private static final ResumenDeTurno CERRADO =
            resumen(CIERRE, new BigDecimal("165000"), BigDecimal.ZERO);
    private static final ResumenDeTurno ABIERTO = resumen(null, null, null);

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

    private static MockHttpServletRequestBuilder cerrar(String cuerpo) {
        return post(RUTA + "/actual/cierre").contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private static MockHttpServletRequestBuilder resumen() {
        return get(RUTA + "/actual/resumen");
    }

    private static MockHttpServletRequestBuilder lista() {
        return get(RUTA).param("desde", "2026-11-01").param("hasta", "2026-11-12");
    }

    // ------------------------------------------------------- lo que sí pueden

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorCierranSuTurnoYRecibenElResumen(String rol) throws Exception {
        when(servicio.cerrar(any())).thenReturn(CERRADO);

        mvc.perform(cerrar(CUERPO).with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.cajero").value("Ana Ruiz"))
                .andExpect(jsonPath("$.apertura").value("2026-11-12T06:00:00-05:00"))
                .andExpect(jsonPath("$.cierre").value("2026-11-12T14:05:30-05:00"))
                .andExpect(jsonPath("$.baseEfectivo").value(100000))
                .andExpect(jsonPath("$.efectivoEsperado").value(165000))
                .andExpect(jsonPath("$.efectivoContado").value(165000))
                .andExpect(jsonPath("$.diferencia").value(0))
                // Un objeto con el nombre del tipo de medio por clave y números por valor.
                .andExpect(jsonPath("$.porMedio.EFECTIVO").value(70000))
                .andExpect(jsonPath("$.porMedio.TARJETA").value(30000))
                .andExpect(jsonPath("$.observacion").doesNotExist());
    }

    @Test
    void elContadoYLaObservacionLlegaAlServicioTalComoSeEnviaron() throws Exception {
        when(servicio.cerrar(any())).thenReturn(CERRADO);

        mvc.perform(cerrar("{\"efectivoContado\":160000,\"observacion\":\"Falta un billete\"}")
                        .with(como("VENDEDOR")))
                .andExpect(status().isOk());

        var enviada = org.mockito.ArgumentCaptor.forClass(SolicitudCierre.class);
        verify(servicio).cerrar(enviada.capture());
        assertEquals(0, new BigDecimal("160000").compareTo(enviada.getValue().efectivoContado()));
        assertEquals("Falta un billete", enviada.getValue().observacion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"165000.00", "165000.0", "1.65E+5", "0"})
    void unContadoEnPesosEnterosConCerosDecimalesSeAcepta(String monto) throws Exception {
        when(servicio.cerrar(any())).thenReturn(CERRADO);

        mvc.perform(cerrar("{\"efectivoContado\":" + monto + "}").with(como("VENDEDOR")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elResumenDelTurnoAbiertoNoTieneContadoNiCierre(String rol) throws Exception {
        when(servicio.resumenActual()).thenReturn(ABIERTO);

        mvc.perform(resumen().with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.efectivoEsperado").value(165000))
                .andExpect(jsonPath("$.efectivoContado").doesNotExist())
                .andExpect(jsonPath("$.diferencia").doesNotExist())
                .andExpect(jsonPath("$.cierre").doesNotExist())
                .andExpect(jsonPath("$.porMedio.EFECTIVO").value(70000));
    }

    @Test
    void sinTurnoAbiertoElResumenEs404ConSuProblema() throws Exception {
        when(servicio.resumenActual()).thenThrow(new RecursoNoEncontradoException("No tienes un turno de caja abierto."));

        mvc.perform(resumen().with(como("VENDEDOR")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("No tienes un turno de caja abierto."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void laListaLlegaConLasFechasYElWebNoMandaCajero(String rol) throws Exception {
        when(servicio.listar(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 12))).thenReturn(List.of(CERRADO, ABIERTO));

        mvc.perform(lista().with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(11))
                .andExpect(jsonPath("$[1].cierre").doesNotExist());

        verify(servicio).listar(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 12));
    }

    // ------------------------------------------------------ lo que no pueden

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void elClienteYElSuperAdministradorRecibenUn403(String rol) throws Exception {
        for (MockHttpServletRequestBuilder llamada : List.of(cerrar(CUERPO), resumen(), lista())) {
            mvc.perform(llamada.with(como(rol)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));
        }

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(cerrar(CUERPO)).andExpect(status().isUnauthorized());
        mvc.perform(resumen()).andExpect(status().isUnauthorized());
        mvc.perform(lista()).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    @Test
    void unaEmpresaEnConsultaVeSuResumenYSusTurnosYCierraSuTurnoPeroNoAbreOtro() throws Exception {
        // Decisión del usuario: cerrar no trae dinero nuevo, solo cuadra el cajón (ADR-0015). Abrir sigue bloqueado.
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));
        when(servicio.resumenActual()).thenReturn(ABIERTO);
        when(servicio.listar(any(), any())).thenReturn(List.of());
        when(servicio.cerrar(any())).thenReturn(CERRADO);

        mvc.perform(resumen().with(como("VENDEDOR"))).andExpect(status().isOk());
        mvc.perform(lista().with(como("ADMINISTRADOR"))).andExpect(status().isOk());
        mvc.perform(cerrar(CUERPO).with(como("VENDEDOR"))).andExpect(status().isOk());
        mvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content("{\"baseEfectivo\":50000}")
                        .with(como("VENDEDOR")))
                .andExpect(status().isForbidden());

        verify(servicio).cerrar(any());
        verify(servicio, never()).abrir(any());
    }

    @Test
    void unaPruebaVencidaTambienDejaCerrarElTurno() throws Exception {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.PRUEBA));
        when(plataforma.finDePruebaDe(4)).thenReturn(Optional.of(LocalDate.of(2020, 1, 1)));
        when(servicio.cerrar(any())).thenReturn(CERRADO);

        mvc.perform(cerrar(CUERPO).with(como("ADMINISTRADOR"))).andExpect(status().isOk());
    }

    // ------------------------------------------------ la observación con espacios

    @Test
    void laObservacionConEspaciosAlrededorSeRecortaAntesDeMedirla() throws Exception {
        when(servicio.cerrar(any())).thenReturn(CERRADO);
        // 200 caracteres de verdad, con 10 espacios de relleno a cada lado: la columna guarda 200, no 220.
        String observacion = " ".repeat(10) + "x".repeat(200) + " ".repeat(10);

        mvc.perform(cerrar("{\"efectivoContado\":0,\"observacion\":\"" + observacion + "\"}").with(como("VENDEDOR")))
                .andExpect(status().isOk());

        var enviada = org.mockito.ArgumentCaptor.forClass(SolicitudCierre.class);
        verify(servicio).cerrar(enviada.capture());
        assertEquals("x".repeat(200), enviada.getValue().observacion());
    }

    @Test
    void laObservacionSoloDeEspaciosLlegaVaciaYNoNula() throws Exception {
        when(servicio.cerrar(any())).thenReturn(CERRADO);

        mvc.perform(cerrar("{\"efectivoContado\":0,\"observacion\":\"      \"}").with(como("VENDEDOR")))
                .andExpect(status().isOk());

        var enviada = org.mockito.ArgumentCaptor.forClass(SolicitudCierre.class);
        verify(servicio).cerrar(enviada.capture());
        assertEquals("", enviada.getValue().observacion());
    }

    // ------------------------------------------------------ los datos inválidos

    @ParameterizedTest
    @ValueSource(strings = {
            // falta el contado
            "{}",
            "{\"observacion\":\"algo\"}",
            // contado nulo
            "{\"efectivoContado\":null}",
            // negativo
            "{\"efectivoContado\":-1}",
            // más de dos decimales: no cabe en la columna
            "{\"efectivoContado\":50000.001}",
            // más grande que la columna
            "{\"efectivoContado\":1000000000000}",
            "{\"efectivoContado\":1E+20}",
            // no es un número
            "{\"efectivoContado\":\"mucho\"}",
            // la observación no cabe en la columna
            "{\"efectivoContado\":0,\"observacion\":\"__LARGA__\"}",
            // cuerpo vacío
            ""})
    void unCuerpoInvalidoEsUn400(String cuerpo) throws Exception {
        mvc.perform(cerrar(cuerpo.replace("__LARGA__", "x".repeat(201))).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    @Test
    void elDetalleDelContadoInvalidoNoRepiteLoEnviado() throws Exception {
        mvc.perform(cerrar("{\"efectivoContado\":-987654}").with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("987654"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // sin fechas
            "",
            "desde=2026-11-01",
            "hasta=2026-11-12",
            // fechas que no son aaaa-mm-dd
            "desde=01/11/2026&hasta=12/11/2026",
            "desde=2026-13-01&hasta=2026-11-12",
            "desde=ayer&hasta=hoy"})
    void laListaSinFechasOConFechasMalEscritasEsUn400(String consulta) throws Exception {
        mvc.perform(get(RUTA + (consulta.isEmpty() ? "" : "?" + consulta)).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    // ----------------------------------------------------- los errores, en español

    @Test
    void siElServicioRechazaElCierreEsUn400ConSuTexto() throws Exception {
        doThrow(new DatoDeTurnoInvalidoException("Explica la diferencia entre lo contado y lo esperado."))
                .when(servicio).cerrar(any());

        mvc.perform(cerrar(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Turno no válido"))
                .andExpect(jsonPath("$.detail").value("Explica la diferencia entre lo contado y lo esperado."));
    }

    @Test
    void cerrarSinTurnoAbiertoEsUn409() throws Exception {
        doThrow(new SinTurnoAbiertoException()).when(servicio).cerrar(any());

        mvc.perform(cerrar(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Sin turno abierto"));
    }
}
