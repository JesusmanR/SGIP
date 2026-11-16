package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.CobroRegistrado;
import co.com.bspstore.sgip.api.dto.DomicilioPendiente;
import co.com.bspstore.sgip.api.dto.EntregaRegistrada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.EstadoNoAdmiteLaOperacionException;
import co.com.bspstore.sgip.logica.FiltroInvalidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioDomicilios;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
 * Quién llega a los domicilios (RF-44, RF-69) y qué contesta la API: el administrador y el vendedor
 * ven las listas, marcan la entrega y cobran; el cliente, el super administrador y quien no tiene
 * rol reciben 403 en todas las rutas. Los errores de la regla llegan con su estado.
 *
 * Pasa por la cadena de seguridad real. La empresa de la sesión está ACTIVA, de modo que
 * {@code FiltroEmpresaActiva} deja pasar las escrituras y un 403 solo puede venir de
 * {@code @PreAuthorize}; la prueba de la empresa en consulta lo cambia a propósito.
 */
@WebMvcTest(controllers = DomiciliosControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class DomiciliosAutorizacionTest {

    private static final String RUTA = "/api/v1/caja/domicilios";
    private static final String COBRO = "[{\"medioId\":1,\"monto\":23800,\"recibido\":50000}]";

    private static final DomicilioPendiente PENDIENTE = new DomicilioPendiente(9L, "V-00000001",
            OffsetDateTime.parse("2026-11-09T09:00:00-05:00"), "Ana Pérez", "Calle 1 # 2-3", "Cali", "3001112233",
            "Luis", true, "PENDIENTE", new BigDecimal("23800.00"), new BigDecimal("23800.00"), 3);

    @Autowired MockMvc mvc;

    @MockitoBean ServicioDomicilios servicio;
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

    private ResultActions cobrar(String rol, String cuerpo) throws Exception {
        return mvc.perform(post(RUTA + "/9/cobro").contentType(MediaType.APPLICATION_JSON).content(cuerpo).with(como(rol)));
    }

    // ------------------------------------------------------- lo que sí pueden

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorVenLasListas(String rol) throws Exception {
        when(servicio.listar("POR_COBRAR")).thenReturn(List.of(PENDIENTE));

        mvc.perform(get(RUTA).param("estado", "POR_COBRAR").with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ventaId").value(9))
                .andExpect(jsonPath("$[0].consecutivo").value("V-00000001"))
                .andExpect(jsonPath("$[0].cliente").value("Ana Pérez"))
                .andExpect(jsonPath("$[0].direccion").value("Calle 1 # 2-3"))
                .andExpect(jsonPath("$[0].telefono").value("3001112233"))
                .andExpect(jsonPath("$[0].recibe").value("Luis"))
                .andExpect(jsonPath("$[0].contraEntrega").value(true))
                .andExpect(jsonPath("$[0].valorPorCobrar").value(23800))
                .andExpect(jsonPath("$[0].antiguedadDias").value(3));
        verify(servicio).listar("POR_COBRAR");
    }

    @Test
    void laListaNuncaTraeCostoNiUtilidad() throws Exception {
        when(servicio.listar("POR_DESPACHAR")).thenReturn(List.of(PENDIENTE));

        String json = mvc.perform(get(RUTA).param("estado", "POR_DESPACHAR").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString().toLowerCase();

        for (String prohibida : List.of("costo", "utilidad", "margen")) {
            assertEquals(false, json.contains(prohibida), prohibida + " en " + json);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void marcarLaEntregaDevuelveQuienYCuando(String rol) throws Exception {
        when(servicio.entregar(9L)).thenReturn(new EntregaRegistrada(9L,
                OffsetDateTime.parse("2026-11-12T08:30:45-05:00"), 7));

        mvc.perform(post(RUTA + "/9/entrega").with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.entregadoPor").value(7))
                .andExpect(jsonPath("$.entregadoEn").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void cobrarRecibeLaListaDePagosYResponde201ConElCobro(String rol) throws Exception {
        when(servicio.cobrar(anyLong(), any())).thenReturn(
                new CobroRegistrado(9L, "V-00000001", new BigDecimal("23800.00"), new BigDecimal("26200.00")));

        cobrar(rol, COBRO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ventaId").value(9))
                .andExpect(jsonPath("$.cobrado").value(23800))
                .andExpect(jsonPath("$.cambio").value(26200));

        var pagos = forClass(List.class);
        verify(servicio).cobrar(eq(9L), pagos.capture());
        @SuppressWarnings("unchecked")
        List<PagoSolicitado> recibidos = pagos.getValue();
        assertEquals(1, recibidos.size());
        assertEquals(1, recibidos.get(0).medioId());
        assertEquals(0, new BigDecimal("23800").compareTo(recibidos.get(0).monto()));
        assertEquals(0, new BigDecimal("50000").compareTo(recibidos.get(0).recibido()));
        assertNull(recibidos.get(0).referencia());
    }

    // ------------------------------------------------------- lo que contesta la regla

    @Test
    void elEstadoEsObligatorioYUnoQueNoExisteEs400() throws Exception {
        mvc.perform(get(RUTA).with(como("VENDEDOR"))).andExpect(status().isBadRequest());
        doThrow(new FiltroInvalidoException("El estado no existe. Los valores admitidos son POR_DESPACHAR y POR_COBRAR."))
                .when(servicio).listar("OTRO");

        mvc.perform(get(RUTA).param("estado", "OTRO").with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void losErroresDeLaReglaLleganConSuEstado() throws Exception {
        doThrow(new RecursoNoEncontradoException("El domicilio no existe.")).when(servicio).entregar(404L);
        doThrow(new EstadoNoAdmiteLaOperacionException("Este domicilio ya fue entregado.")).when(servicio).entregar(409L);
        doThrow(new SinTurnoAbiertoException()).when(servicio).cobrar(eq(10L), any());
        doThrow(new EstadoNoAdmiteLaOperacionException("Esta venta ya está pagada.")).when(servicio).cobrar(eq(11L), any());
        doThrow(new VentaInvalidaException("La suma de los pagos debe ser igual al saldo por cobrar."))
                .when(servicio).cobrar(eq(12L), any());

        mvc.perform(post(RUTA + "/404/entrega").with(como("VENDEDOR"))).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post(RUTA + "/409/entrega").with(como("VENDEDOR"))).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Este domicilio ya fue entregado."));
        mvc.perform(post(RUTA + "/10/cobro").contentType(MediaType.APPLICATION_JSON).content(COBRO).with(como("VENDEDOR")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Sin turno abierto"));
        mvc.perform(post(RUTA + "/11/cobro").contentType(MediaType.APPLICATION_JSON).content(COBRO).with(como("VENDEDOR")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Esta venta ya está pagada."));
        mvc.perform(post(RUTA + "/12/cobro").contentType(MediaType.APPLICATION_JSON).content(COBRO).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unCuerpoQueNoEsUnaListaDePagosOUnIdentificadorQueNoEsUnNumeroEsUn400() throws Exception {
        for (String cuerpo : new String[]{"", "{\"medioId\":1}", "hola"}) {
            cobrar("VENDEDOR", cuerpo).andExpect(status().isBadRequest());
        }
        mvc.perform(post(RUTA + "/abc/cobro").contentType(MediaType.APPLICATION_JSON).content(COBRO).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(RUTA + "/abc/entrega").with(como("VENDEDOR"))).andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------- lo que no pueden

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void elClienteYElSuperAdministradorRecibenUn403EnTodasLasRutas(String rol) throws Exception {
        for (ResultActions r : List.of(
                mvc.perform(get(RUTA).param("estado", "POR_COBRAR").with(como(rol))),
                mvc.perform(post(RUTA + "/9/entrega").with(como(rol))),
                cobrar(rol, COBRO))) {
            r.andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));
        }

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(get(RUTA).param("estado", "POR_COBRAR")).andExpect(status().isUnauthorized());
        mvc.perform(post(RUTA + "/9/cobro").contentType(MediaType.APPLICATION_JSON).content(COBRO))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    @Test
    void unaEmpresaEnConsultaVeLasListasPeroNoEntregaNiCobra() throws Exception {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));
        when(servicio.listar("POR_COBRAR")).thenReturn(List.of(PENDIENTE));

        mvc.perform(get(RUTA).param("estado", "POR_COBRAR").with(como("VENDEDOR"))).andExpect(status().isOk());
        mvc.perform(post(RUTA + "/9/entrega").with(como("VENDEDOR"))).andExpect(status().isForbidden());
        cobrar("VENDEDOR", COBRO).andExpect(status().isForbidden());

        verify(servicio, never()).entregar(anyLong());
        verify(servicio, never()).cobrar(anyLong(), any());
    }
}
