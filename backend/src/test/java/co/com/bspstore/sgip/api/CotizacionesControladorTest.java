package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.CotizacionDetalle;
import co.com.bspstore.sgip.api.dto.CotizacionEnLista;
import co.com.bspstore.sgip.api.dto.PaginaDeCotizaciones;
import co.com.bspstore.sgip.api.dto.LineaDeCotizacion;
import co.com.bspstore.sgip.api.dto.SolicitudCotizacion;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.CotizacionNoAnulableException;
import co.com.bspstore.sgip.logica.FiltroInvalidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioCotizaciones;
import co.com.bspstore.sgip.logica.ServicioSesion;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
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
 * Quién llega a las cotizaciones y qué contesta la API al crear y al leer una (RF-37): el administrador y
 * el vendedor; el cliente y el super administrador reciben 403. Pasa por la cadena de seguridad real, con el
 * servicio simulado.
 */
@WebMvcTest(controllers = CotizacionesControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class CotizacionesControladorTest {

    private static final String RUTA = "/api/v1/cotizaciones";
    private static final String CUERPO = """
            {"clienteId":3,"lineas":[{"productoId":7,"cantidad":2}],"observaciones":"Para el taller"}
            """;

    private static final CotizacionDetalle DETALLE = new CotizacionDetalle(12L, "COT-00000012", "VIGENTE",
            LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 10), 5, 3, "Ferretería Ruiz", "900123456",
            7, "Ana Pérez",
            List.of(new LineaDeCotizacion(7, "A-1", "Disco de corte", 2, new BigDecimal("12000.00"),
                    new BigDecimal("0.1900"), "GRAVADO", new BigDecimal("24000.00"), new BigDecimal("4560.00"),
                    null, true)),
            new BigDecimal("24000.00"), new BigDecimal("4560.00"), new BigDecimal("28560.00"),
            "Para el taller", null, null);

    @Autowired MockMvc mvc;

    @MockitoBean ServicioCotizaciones servicio;
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

    private static MockHttpServletRequestBuilder crear(String cuerpo) {
        return post(RUTA).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    // ------------------------------------------------------- lo que sí pueden

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorCreanYRecibenUn201(String rol) throws Exception {
        when(servicio.crear(eq(4), eq(7), any())).thenReturn(DETALLE);

        mvc.perform(crear(CUERPO).with(como(rol)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.numero").value("COT-00000012"))
                .andExpect(jsonPath("$.estado").value("VIGENTE"))
                .andExpect(jsonPath("$.fechaVencimiento").value("2026-04-10"))
                .andExpect(jsonPath("$.total").value(28560))
                .andExpect(jsonPath("$.lineas[0].valorIva").value(4560))
                .andExpect(jsonPath("$.lineas[0].disponible").value(true));
    }

    @Test
    void laEmpresaYElUsuarioSalenDeLaSesionYNuncaDelCuerpo() throws Exception {
        when(servicio.crear(anyInt(), anyInt(), any())).thenReturn(DETALLE);

        mvc.perform(crear("""
                {"empresaId":99,"usuarioId":99,"lineas":[{"productoId":7,"cantidad":2,"precio":1}]}
                """).with(como("VENDEDOR"))).andExpect(status().isCreated());

        var enviada = forClass(SolicitudCotizacion.class);
        verify(servicio).crear(eq(4), eq(7), enviada.capture());
        assertEquals(7, enviada.getValue().lineas().get(0).productoId());
        assertEquals(2, enviada.getValue().lineas().get(0).cantidad());
    }

    @Test
    void elClienteEsOpcionalYSeEntregaAlServicio() throws Exception {
        when(servicio.crear(anyInt(), anyInt(), any())).thenReturn(DETALLE);

        mvc.perform(crear("{\"lineas\":[{\"productoId\":7,\"cantidad\":2}]}").with(como("ADMINISTRADOR")))
                .andExpect(status().isCreated());

        var enviada = forClass(SolicitudCotizacion.class);
        verify(servicio).crear(eq(4), eq(7), enviada.capture());
        assertNull(enviada.getValue().clienteId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elDetalleSeLeePorIdDentroDeLaEmpresaDeLaSesion(String rol) throws Exception {
        when(servicio.detalle(4, 12L)).thenReturn(DETALLE);

        mvc.perform(get(RUTA + "/12").with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value("COT-00000012"))
                .andExpect(jsonPath("$.clienteNombre").value("Ferretería Ruiz"))
                .andExpect(jsonPath("$.creadaPor").value("Ana Pérez"))
                .andExpect(jsonPath("$.lineas[0].precioUnitario").value(12000));
    }

    // ------------------------------------------------------- lo que no pueden

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void elClienteYElSuperAdministradorRecibenUn403(String rol) throws Exception {
        mvc.perform(crear(CUERPO).with(como(rol)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));
        mvc.perform(get(RUTA + "/12").with(como(rol))).andExpect(status().isForbidden());

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(crear(CUERPO)).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------- los datos inválidos

    @ParameterizedTest
    @ValueSource(strings = {
            // sin líneas, con líneas vacías, con una línea nula
            "{\"clienteId\":3}",
            "{\"clienteId\":3,\"lineas\":[]}",
            "{\"clienteId\":3,\"lineas\":[null]}",
            // cantidad ausente, cero, negativa, de más
            "{\"lineas\":[{\"productoId\":7}]}",
            "{\"lineas\":[{\"productoId\":7,\"cantidad\":0}]}",
            "{\"lineas\":[{\"productoId\":7,\"cantidad\":-3}]}",
            "{\"lineas\":[{\"productoId\":7,\"cantidad\":100000}]}",
            // producto ausente o no positivo; cliente no positivo
            "{\"lineas\":[{\"cantidad\":2}]}",
            "{\"lineas\":[{\"productoId\":0,\"cantidad\":2}]}",
            "{\"clienteId\":-1,\"lineas\":[{\"productoId\":7,\"cantidad\":2}]}",
            // observaciones más largas que su columna
            "{\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"observaciones\":\"%s\"}",
            // cuerpo vacío
            ""})
    void unCuerpoInvalidoEsUn400(String plantilla) throws Exception {
        String cuerpo = plantilla.contains("%s") ? plantilla.formatted("x".repeat(401)) : plantilla;

        mvc.perform(crear(cuerpo).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(servicio);
    }

    @Test
    void masLineasQueElTopeDeLaVentaEsUn400() throws Exception {
        String lineas = String.join(",", Collections.nCopies(201, "{\"productoId\":7,\"cantidad\":1}"));

        mvc.perform(crear("{\"lineas\":[" + lineas + "]}").with(como("VENDEDOR")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------- lo que contesta la regla

    @Test
    void unProductoInactivoOSinPrecioEsUn400() throws Exception {
        when(servicio.crear(anyInt(), anyInt(), any())).thenThrow(new VentaInvalidaException(
                "Este producto no se puede vender: no tiene un precio de venta válido."));

        mvc.perform(crear(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unClienteOUnProductoDeOtraEmpresaEsUn404() throws Exception {
        when(servicio.crear(anyInt(), anyInt(), any())).thenThrow(new RecursoNoEncontradoException("El cliente no existe."));

        mvc.perform(crear(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unIdDeOtraEmpresaEsUn404() throws Exception {
        when(servicio.detalle(eq(4), anyLong())).thenThrow(new RecursoNoEncontradoException("La cotización no existe."));

        mvc.perform(get(RUTA + "/99").with(como("VENDEDOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void unIdQueNoEsUnNumeroEsUn400() throws Exception {
        mvc.perform(get(RUTA + "/abc").with(como("VENDEDOR"))).andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------- lista

    private static final PaginaDeCotizaciones PAGINA = new PaginaDeCotizaciones(
            List.of(new CotizacionEnLista(12L, "COT-00000012", LocalDate.of(2026, 4, 1), "Ferretería Ruiz",
                    new BigDecimal("28560.00"), LocalDate.of(2026, 4, 10), "VIGENTE")), 0, 20, 1);

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void laListaLlevaLosFiltrosYLaPaginaPedidos(String rol) throws Exception {
        when(servicio.listar(4, "VENCIDA", "ruiz", 2, 50)).thenReturn(PAGINA);

        mvc.perform(get(RUTA).param("estado", "VENCIDA").param("q", "ruiz").param("pagina", "2")
                        .param("tamano", "50").with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elementos[0].id").value(12))
                .andExpect(jsonPath("$.elementos[0].numero").value("COT-00000012"))
                .andExpect(jsonPath("$.elementos[0].fechaEmision").value("2026-04-01"))
                .andExpect(jsonPath("$.elementos[0].clienteNombre").value("Ferretería Ruiz"))
                .andExpect(jsonPath("$.elementos[0].total").value(28560))
                .andExpect(jsonPath("$.elementos[0].fechaVencimiento").value("2026-04-10"))
                .andExpect(jsonPath("$.elementos[0].estado").value("VIGENTE"))
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamano").value(20))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void laListaSinFiltrosPideLaPrimeraPaginaDe20() throws Exception {
        when(servicio.listar(eq(4), isNull(), isNull(), eq(0), eq(20))).thenReturn(PAGINA);

        mvc.perform(get(RUTA).with(como("VENDEDOR"))).andExpect(status().isOk());

        verify(servicio).listar(4, null, null, 0, 20);
    }

    @Test
    void unFiltroInvalidoEsUn400() throws Exception {
        when(servicio.listar(anyInt(), anyString(), any(), anyInt(), anyInt()))
                .thenThrow(new FiltroInvalidoException("El estado no es válido."));

        mvc.perform(get(RUTA).param("estado", "PENDIENTE").with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unaPaginaQueNoEsNumeroEsUn400() throws Exception {
        mvc.perform(get(RUTA).param("pagina", "abc").with(como("VENDEDOR"))).andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------- por número

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elDetalleSeLeePorNumeroEscritoComoSea(String rol) throws Exception {
        when(servicio.porNumero(4, "cot-12")).thenReturn(DETALLE);

        mvc.perform(get(RUTA + "/numero/cot-12").with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value("COT-00000012"))
                .andExpect(jsonPath("$.lineas[0].precioUnitario").value(12000));
    }

    @Test
    void unNumeroQueNoExisteEsUn404() throws Exception {
        when(servicio.porNumero(eq(4), anyString())).thenThrow(new RecursoNoEncontradoException("La cotización no existe."));

        mvc.perform(get(RUTA + "/numero/abc").with(como("VENDEDOR")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ------------------------------------------------------- anular

    private static MockHttpServletRequestBuilder anular(String cuerpo) {
        return post(RUTA + "/12/anulacion").contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private static final CotizacionDetalle ANULADA = new CotizacionDetalle(12L, "COT-00000012", "ANULADA",
            LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 10), 5, 3, "Ferretería Ruiz", "900123456",
            7, "Ana Pérez", List.of(), new BigDecimal("24000.00"), new BigDecimal("4560.00"),
            new BigDecimal("28560.00"), null, null, "Ya compró en otra parte");

    @Test
    void elAdministradorAnulaCualquieraYElServicioLoSabe() throws Exception {
        when(servicio.anular(4, 7, true, 12L, "Ya compró en otra parte")).thenReturn(ANULADA);

        mvc.perform(anular("{\"motivo\":\"Ya compró en otra parte\"}").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ANULADA"))
                .andExpect(jsonPath("$.motivoAnulacion").value("Ya compró en otra parte"));

        verify(servicio).anular(4, 7, true, 12L, "Ya compró en otra parte");
    }

    @Test
    void elVendedorQueLaCreoAnula() throws Exception {
        when(servicio.anular(4, 7, false, 12L, "Ya compró en otra parte")).thenReturn(ANULADA);

        mvc.perform(anular("{\"motivo\":\"Ya compró en otra parte\"}").with(como("VENDEDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ANULADA"));

        verify(servicio).anular(4, 7, false, 12L, "Ya compró en otra parte");
    }

    @Test
    void elVendedorQueNoLaCreoRecibeUn403() throws Exception {
        when(servicio.anular(anyInt(), anyInt(), anyBoolean(), anyLong(), anyString()))
                .thenThrow(new AccessDeniedException("Solo el administrador o quien la creó la anula."));

        mvc.perform(anular("{\"motivo\":\"No es mía\"}").with(como("VENDEDOR")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void elMotivoSeRecortaAntesDeValidarYDeEntregarse() throws Exception {
        when(servicio.anular(anyInt(), anyInt(), anyBoolean(), anyLong(), anyString())).thenReturn(ANULADA);
        String justo = "z".repeat(200);

        mvc.perform(anular("{\"motivo\":\"  " + justo + "  \"}").with(como("VENDEDOR")))
                .andExpect(status().isOk());

        verify(servicio).anular(4, 7, false, 12L, justo);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"motivo\":\"\"}",
            "{\"motivo\":\"     \"}",
            "{\"motivo\":null}",
            "{}",
            "",
            "{\"motivo\":\"%s\"}"})
    void unMotivoVacioOMuyLargoEsUn400(String plantilla) throws Exception {
        String cuerpo = plantilla.contains("%s") ? plantilla.formatted("x".repeat(201)) : plantilla;

        mvc.perform(anular(cuerpo).with(como("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(servicio);
    }

    @Test
    void unaQueNoSeAnulaEsUn409ConElTextoFijo() throws Exception {
        when(servicio.anular(anyInt(), anyInt(), anyBoolean(), anyLong(), anyString()))
                .thenThrow(new CotizacionNoAnulableException());

        mvc.perform(anular("{\"motivo\":\"Tarde\"}").with(como("ADMINISTRADOR")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Solo se anula una cotización vigente o vencida."));
    }

    @Test
    void anularUnaDeOtraEmpresaEsUn404() throws Exception {
        when(servicio.anular(anyInt(), anyInt(), anyBoolean(), anyLong(), anyString()))
                .thenThrow(new RecursoNoEncontradoException("La cotización no existe."));

        mvc.perform(anular("{\"motivo\":\"Ajena\"}").with(como("ADMINISTRADOR")))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void lasRutasNuevasSonSoloDelAdministradorYElVendedor(String rol) throws Exception {
        mvc.perform(get(RUTA).with(como(rol))).andExpect(status().isForbidden());
        mvc.perform(get(RUTA + "/numero/12").with(como(rol))).andExpect(status().isForbidden());
        mvc.perform(anular("{\"motivo\":\"x\"}").with(como(rol))).andExpect(status().isForbidden());

        verifyNoInteractions(servicio);
    }
}
