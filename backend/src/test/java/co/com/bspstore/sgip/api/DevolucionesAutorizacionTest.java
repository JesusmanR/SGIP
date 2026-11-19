package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.DevolucionRegistrada;
import co.com.bspstore.sgip.api.dto.Reembolso;
import co.com.bspstore.sgip.api.dto.SaldoDeCliente;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.AutorizacionRequeridaException;
import co.com.bspstore.sgip.logica.ClaveDeIdempotenciaEnUsoException;
import co.com.bspstore.sgip.logica.EstadoNoAdmiteLaOperacionException;
import co.com.bspstore.sgip.logica.PinInvalidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ResultadoDeDevolucion;
import co.com.bspstore.sgip.logica.ServicioDevoluciones;
import co.com.bspstore.sgip.logica.ServicioSaldoAFavor;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
import co.com.bspstore.sgip.logica.VentaInvalidaException;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * Quién llega a las devoluciones y al saldo a favor (RF-68) y qué contesta la API: el administrador y el
 * vendedor devuelven y consultan el saldo; el cliente, el super administrador y quien no tiene rol
 * reciben 403 en las dos rutas. Los errores de la regla llegan con su estado.
 *
 * Pasa por la cadena de seguridad real. La empresa de la sesión está ACTIVA, de modo que
 * {@code FiltroEmpresaActiva} deja pasar las escrituras y un 403 solo puede venir de
 * {@code @PreAuthorize}; la prueba de la empresa en consulta lo cambia a propósito.
 */
@WebMvcTest(controllers = DevolucionesControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class DevolucionesAutorizacionTest {

    private static final String DEVOLUCIONES = "/api/v1/caja/devoluciones";
    private static final String SALDO = "/api/v1/caja/clientes/8/saldo";
    private static final String CUERPO = """
            {"clave":"11111111-2222-3333-4444-555555555555","ventaId":9,"unidades":[{"productoId":3,"cantidad":2}],"motivo":"El cliente se arrepintió",
             "reembolsos":[{"medioId":5,"monto":10000},{"medioId":6,"monto":2000,"referencia":"TX-1"}],
             "autorizacion":{"administradorId":2,"pin":"483920"}}
            """;
    private static final String ANULACION = """
            {"clave":"11111111-2222-3333-4444-555555555555","ventaId":9,"unidades":[],"motivo":"Error de digitación","reembolsos":[]}
            """;

    private static final DevolucionRegistrada REGISTRADA = new DevolucionRegistrada(4L, "D-00000001",
            new BigDecimal("12000.00"), new BigDecimal("12000.00"), BigDecimal.ZERO, "DEVUELTA_PARCIAL",
            List.of(new Reembolso(5, new BigDecimal("10000.00"), null),
                    new Reembolso(6, new BigDecimal("2000.00"), "TX-1")));

    @Autowired MockMvc mvc;

    @MockitoBean ServicioDevoluciones devoluciones;
    @MockitoBean ServicioSaldoAFavor saldos;
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

    private ResultActions devolver(String rol, String cuerpo) throws Exception {
        return mvc.perform(post(DEVOLUCIONES).contentType(MediaType.APPLICATION_JSON).content(cuerpo).with(como(rol)));
    }

    // ------------------------------------------------------- lo que sí pueden

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void devolverRecibeLaSolicitudYResponde201ConLaDevolucion(String rol) throws Exception {
        when(devoluciones.procesar(any())).thenReturn(new ResultadoDeDevolucion(REGISTRADA, false));

        devolver(rol, CUERPO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.consecutivo").value("D-00000001"))
                .andExpect(jsonPath("$.valorDevuelto").value(12000))
                .andExpect(jsonPath("$.reembolsado").value(12000))
                .andExpect(jsonPath("$.saldoPorCobrar").value(0))
                .andExpect(jsonPath("$.estadoVenta").value("DEVUELTA_PARCIAL"))
                .andExpect(jsonPath("$.reembolsos[1].referencia").value("TX-1"));

        var captor = forClass(SolicitudDevolucion.class);
        verify(devoluciones).procesar(captor.capture());
        SolicitudDevolucion s = captor.getValue();
        assertEquals(9L, s.ventaId());
        assertEquals(1, s.unidades().size());
        assertEquals(3, s.unidades().get(0).productoId());
        assertEquals(2, s.unidades().get(0).cantidad());
        assertEquals("El cliente se arrepintió", s.motivo());
        assertEquals(2, s.reembolsos().size());
        assertNull(s.reembolsos().get(0).referencia());
        assertEquals(2, s.autorizacion().administradorId());
        assertEquals("483920", s.autorizacion().pin());
    }

    @Test
    void unaAnulacionLlevaLaListaDeUnidadesVacia() throws Exception {
        when(devoluciones.procesar(any())).thenReturn(new ResultadoDeDevolucion(REGISTRADA, false));

        devolver("ADMINISTRADOR", ANULACION).andExpect(status().isCreated());

        var captor = forClass(SolicitudDevolucion.class);
        verify(devoluciones).procesar(captor.capture());
        assertEquals(List.of(), captor.getValue().unidades());
    }

    @Test
    void laRespuestaNuncaTraeCostoNiUtilidad() throws Exception {
        when(devoluciones.procesar(any())).thenReturn(new ResultadoDeDevolucion(REGISTRADA, false));

        String json = devolver("ADMINISTRADOR", CUERPO).andReturn().getResponse().getContentAsString().toLowerCase();

        for (String prohibida : List.of("costo", "utilidad", "margen")) {
            assertEquals(false, json.contains(prohibida), prohibida + " en " + json);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elSaldoDelClienteSeConsultaConElMedioParaPagarConEl(String rol) throws Exception {
        when(saldos.consultar(8)).thenReturn(new SaldoDeCliente(new BigDecimal("15000.00"), 13));

        mvc.perform(get(SALDO).with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(15000))
                .andExpect(jsonPath("$.medioId").value(13));

        verify(saldos).consultar(8);
    }

    // ------------------------------------------------------- lo que contesta la regla

    @Test
    void losErroresDeLaReglaLleganConSuEstado() throws Exception {
        doThrow(new AutorizacionRequeridaException()).when(devoluciones).registrar(any());
        devolver("VENDEDOR", CUERPO).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        doThrow(new PinInvalidoException(15)).when(devoluciones).registrar(any());
        devolver("VENDEDOR", CUERPO).andExpect(status().isForbidden());

        doThrow(new RecursoNoEncontradoException("La venta no existe.")).when(devoluciones).registrar(any());
        devolver("VENDEDOR", CUERPO).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La venta no existe."));

        doThrow(new EstadoNoAdmiteLaOperacionException("La venta ya está anulada.")).when(devoluciones).registrar(any());
        devolver("VENDEDOR", CUERPO).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La venta ya está anulada."));

        doThrow(new SinTurnoAbiertoException()).when(devoluciones).registrar(any());
        devolver("VENDEDOR", CUERPO).andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Sin turno abierto"));

        doThrow(new VentaInvalidaException("No se puede devolver más de lo vendido.")).when(devoluciones).registrar(any());
        devolver("VENDEDOR", CUERPO).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unClienteDeOtraEmpresaEs404() throws Exception {
        doThrow(new RecursoNoEncontradoException("El cliente no existe.")).when(saldos).consultar(8);

        mvc.perform(get(SALDO).with(como("VENDEDOR"))).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unCuerpoMalFormadoOUnIdentificadorQueNoEsUnNumeroEsUn400() throws Exception {
        for (String cuerpo : new String[]{"", "hola", "[]",
                "{\"unidades\":[],\"motivo\":\"x\"}",
                "{\"ventaId\":9,\"unidades\":[],\"motivo\":\"x\"}",
                "{\"clave\":\"no-es-un-uuid\",\"ventaId\":9,\"unidades\":[],\"motivo\":\"x\"}",
                "{\"clave\":\"11111111-2222-3333-4444-555555555555\",\"ventaId\":9,\"motivo\":\"x\"}",
                "{\"clave\":\"11111111-2222-3333-4444-555555555555\",\"ventaId\":9,\"unidades\":[{\"productoId\":3,\"cantidad\":0}],\"motivo\":\"x\"}",
                "{\"clave\":\"11111111-2222-3333-4444-555555555555\",\"ventaId\":9,\"unidades\":[],\"motivo\":\"x\",\"reembolsos\":[{\"medioId\":5,\"monto\":0}]}"}) {
            devolver("VENDEDOR", cuerpo).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/caja/clientes/abc/saldo").with(como("VENDEDOR"))).andExpect(status().isBadRequest());

        verifyNoInteractions(devoluciones, saldos);
    }

    // ------------------------------------------------------- lo que no pueden

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void elClienteYElSuperAdministradorRecibenUn403(String rol) throws Exception {
        for (ResultActions r : List.of(devolver(rol, CUERPO), mvc.perform(get(SALDO).with(como(rol))))) {
            r.andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));
        }

        verifyNoInteractions(devoluciones, saldos);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(post(DEVOLUCIONES).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(SALDO)).andExpect(status().isUnauthorized());

        verifyNoInteractions(devoluciones, saldos);
    }

    @Test
    void unaEmpresaEnConsultaVeElSaldoPeroNoDevuelve() throws Exception {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));
        when(saldos.consultar(anyInt())).thenReturn(new SaldoDeCliente(BigDecimal.ZERO, 13));

        mvc.perform(get(SALDO).with(como("VENDEDOR"))).andExpect(status().isOk());
        devolver("VENDEDOR", CUERPO).andExpect(status().isForbidden());

        verify(devoluciones, never()).procesar(any());
    }

    // ------------------------------------------------------- la clave de idempotencia

    @Test
    void unReintentoConLaMismaClaveRecibeUn200ConLaMismaDevolucion() throws Exception {
        when(devoluciones.procesar(any())).thenReturn(new ResultadoDeDevolucion(REGISTRADA, true));

        devolver("VENDEDOR", CUERPO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.consecutivo").value("D-00000001"));
    }

    @Test
    void laClaveReutilizadaParaOtraDevolucionEs409ConElTipoDeLaClaveEnUso() throws Exception {
        doThrow(new ClaveDeIdempotenciaEnUsoException()).when(devoluciones).procesar(any());

        devolver("VENDEDOR", CUERPO)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:sgip:caja:clave-en-uso"));
    }

    @Test
    void laSolicitudLlevaLaClaveQueEnvioElMostrador() throws Exception {
        when(devoluciones.procesar(any())).thenReturn(new ResultadoDeDevolucion(REGISTRADA, false));

        devolver("ADMINISTRADOR", CUERPO).andExpect(status().isCreated());

        var captor = forClass(SolicitudDevolucion.class);
        verify(devoluciones).procesar(captor.capture());
        assertEquals("11111111-2222-3333-4444-555555555555", captor.getValue().clave().toString());
    }
}
