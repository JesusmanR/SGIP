package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.api.dto.VentaRegistrada;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.AutorizacionRequeridaException;
import co.com.bspstore.sgip.logica.ClaveDeIdempotenciaEnUsoException;
import co.com.bspstore.sgip.logica.ExistenciaInsuficienteException;
import co.com.bspstore.sgip.logica.ResultadoDeVenta;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
import co.com.bspstore.sgip.logica.VentaInvalidaException;
import co.com.bspstore.sgip.logica.venta.LineaCalculada;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.logica.DescuentoInvalidoException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Quién llega a la venta del mostrador (RF-41) y qué contesta la API: el administrador y el
 * vendedor registran; el cliente, el super administrador y quien no tiene rol reciben 403.
 * Los datos inválidos del cuerpo son 400 con texto fijo, y los errores de la regla llegan con su
 * estado y su título.
 *
 * Pasa por la cadena de seguridad real. La empresa de la sesión está ACTIVA, de modo que
 * {@code FiltroEmpresaActiva} deja pasar las escrituras y un 403 solo puede venir de
 * {@code @PreAuthorize}; la prueba de la empresa en consulta lo cambia a propósito.
 */
@WebMvcTest(controllers = VentasControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class VentasAutorizacionTest {

    private static final String RUTA = "/api/v1/caja/ventas";
    private static final String CLAVE = "11111111-2222-3333-4444-555555555555";
    private static final String CUERPO = """
            {"claveIdempotencia":"%s",
             "lineas":[{"productoId":7,"cantidad":2}],
             "pagos":[{"medioId":1,"monto":20000,"recibido":50000}]}
            """.formatted(CLAVE);

    private static final VentaRegistrada REGISTRADA = new VentaRegistrada(9L, "V-00000001",
            new BigDecimal("20000.00"), new BigDecimal("30000.00"),
            List.of(new LineaCalculada(7, 2, new BigDecimal("10000.00"), new BigDecimal("20000.00"),
                    BigDecimal.ZERO.setScale(2), new BigDecimal("20000.00"), new BigDecimal("0.0000"), "NO_GRAVADO",
                    BigDecimal.ZERO.setScale(2), new BigDecimal("20000.00"))));

    @Autowired MockMvc mvc;

    @MockitoBean ServicioVentas servicio;
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

    private static MockHttpServletRequestBuilder registrar(String cuerpo) {
        return post(RUTA).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    // ------------------------------------------------------- lo que sí pueden

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorRegistranYRecibenUn201ConLaVenta(String rol) throws Exception {
        when(servicio.procesar(any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        mvc.perform(registrar(CUERPO).with(como(rol)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.consecutivo").value("V-00000001"))
                .andExpect(jsonPath("$.total").value(20000))
                .andExpect(jsonPath("$.cambio").value(30000))
                .andExpect(jsonPath("$.lineas[0].productoId").value(7))
                .andExpect(jsonPath("$.lineas[0].total").value(20000));
    }

    @Test
    void unReintentoConLaMismaClaveRecibeUn200ConLaMismaVenta() throws Exception {
        when(servicio.procesar(any())).thenReturn(new ResultadoDeVenta(REGISTRADA, true));

        mvc.perform(registrar(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.consecutivo").value("V-00000001"));
    }

    @Test
    void laRespuestaNuncaLlevaCostoNiUtilidadNiParaElAdministrador() throws Exception {
        when(servicio.procesar(any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));

        mvc.perform(registrar(CUERPO).with(como("ADMINISTRADOR")))
                .andExpect(status().isCreated())
                .andExpect(content().string(not(containsString("costo"))))
                .andExpect(content().string(not(containsString("utilidad"))))
                .andExpect(content().string(not(containsString("margen"))));
    }

    @Test
    void elPrecioQueManoElClienteEnElJsonSeIgnoraYNoLlegaAlServicio() throws Exception {
        when(servicio.procesar(any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));
        String conPrecioInventado = """
                {"claveIdempotencia":"%s",
                 "lineas":[{"productoId":7,"cantidad":2,"precioUnitario":1,"precio":1,"costo":1}],
                 "precioTotal":1,
                 "pagos":[{"medioId":1,"monto":20000}]}
                """.formatted(CLAVE);

        mvc.perform(registrar(conPrecioInventado).with(como("VENDEDOR"))).andExpect(status().isCreated());

        var enviada = forClass(SolicitudVenta.class);
        verify(servicio).procesar(enviada.capture());
        assertEquals(7, enviada.getValue().lineas().get(0).productoId());
        assertEquals(2, enviada.getValue().lineas().get(0).cantidad());
        assertEquals(UUID.fromString(CLAVE), enviada.getValue().claveIdempotencia());
        assertNull(enviada.getValue().clienteId(), "sin cliente es el Consumidor final");
    }

    @Test
    void laAutorizacionYElMotivoLleganAlServicio() throws Exception {
        when(servicio.procesar(any())).thenReturn(new ResultadoDeVenta(REGISTRADA, false));
        String cuerpo = """
                {"claveIdempotencia":"%s",
                 "lineas":[{"productoId":7,"cantidad":2,"entregaEstimada":"2099-01-05",
                            "descuento":{"tipo":"PORCENTAJE","valor":10}}],
                 "descuentoTotal":{"tipo":"PESOS","valor":500.00},
                 "pagos":[{"medioId":1,"monto":20000,"recibido":20000.00}],
                 "motivoEncargo":"Llega el viernes",
                 "autorizacion":{"administradorId":4,"pin":"483920"}}
                """.formatted(CLAVE);

        mvc.perform(registrar(cuerpo).with(como("VENDEDOR"))).andExpect(status().isCreated());

        var enviada = forClass(SolicitudVenta.class);
        verify(servicio).procesar(enviada.capture());
        assertEquals("Llega el viernes", enviada.getValue().motivoEncargo());
        assertEquals(4, enviada.getValue().autorizacion().administradorId());
        assertEquals("2099-01-05", enviada.getValue().lineas().get(0).entregaEstimada().toString());
    }

    // ------------------------------------------------------ lo que no pueden

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void elClienteYElSuperAdministradorRecibenUn403(String rol) throws Exception {
        mvc.perform(registrar(CUERPO).with(como(rol)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(registrar(CUERPO)).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    @Test
    void unaEmpresaEnConsultaNoRegistraVentas() throws Exception {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));

        mvc.perform(registrar(CUERPO).with(como("VENDEDOR"))).andExpect(status().isForbidden());

        verify(servicio, never()).procesar(any());
    }

    @Test
    void laVentaSoloSeRegistraPorPostYNoSeLee() throws Exception {
        mvc.perform(get(RUTA).with(como("ADMINISTRADOR"))).andExpect(status().is4xxClientError());

        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------ los datos inválidos

    @ParameterizedTest
    @ValueSource(strings = {
            // sin clave de idempotencia
            "{\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            // una clave que no es un UUID
            "{\"claveIdempotencia\":\"hola\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            // sin líneas, con líneas vacías, con una línea nula
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[null],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            // sin pagos o con un pago nulo
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[null]}",
            // cantidad ausente, cero, negativa, de más
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":0}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":-3}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":100000}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            // producto con identificador no positivo; cliente no positivo
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":0,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"clienteId\":-1,\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            // descuento de línea sin tipo, sin valor, cero, negativo, de más cifras de las que caben
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2,\"descuento\":{\"valor\":10}}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2,\"descuento\":{\"tipo\":\"PESOS\"}}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2,\"descuento\":{\"tipo\":\"PESOS\",\"valor\":0}}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2,\"descuento\":{\"tipo\":\"PESOS\",\"valor\":-5}}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2,\"descuento\":{\"tipo\":\"PESOS\",\"valor\":1E+99999999}}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2,\"descuento\":{\"tipo\":\"PESOS\",\"valor\":5.001}}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2,\"descuento\":{\"tipo\":\"INVENTADO\",\"valor\":5}}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            // descuento de toda la compra con un valor imposible
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"descuentoTotal\":{\"tipo\":\"PESOS\",\"valor\":1234567890123},\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            // pago sin medio, sin monto, con monto cero, negativo, desmedido
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"monto\":20000}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":0}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":-1}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":1E+99999999}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":1000000000000}]}",
            // efectivo recibido no positivo o desmedido
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":20000,\"recibido\":0}]}",
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":20000,\"recibido\":1E+99999999}]}",
            // la referencia más larga que su columna
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":20000,\"referencia\":\"%s\"}]}",
            // el motivo más largo que su columna
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":20000}],\"motivoEncargo\":\"%s\"}",
            // fecha de entrega que no es una fecha
            "{\"claveIdempotencia\":\"11111111-2222-3333-4444-555555555555\",\"lineas\":[{\"productoId\":7,\"cantidad\":2,\"entregaEstimada\":\"mañana\"}],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}",
            // cuerpo vacío
            ""})
    void unCuerpoInvalidoEsUn400(String plantilla) throws Exception {
        String cuerpo = plantilla.contains("%s") ? plantilla.formatted("x".repeat(250)) : plantilla;

        mvc.perform(registrar(cuerpo).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(servicio);
    }

    @Test
    void masDeDoscientasLineasEsUn400() throws Exception {
        StringBuilder lineas = new StringBuilder();
        for (int i = 1; i <= 201; i++) {
            lineas.append(i > 1 ? "," : "").append("{\"productoId\":").append(i).append(",\"cantidad\":1}");
        }
        String cuerpo = "{\"claveIdempotencia\":\"%s\",\"lineas\":[%s],\"pagos\":[{\"medioId\":1,\"monto\":20000}]}"
                .formatted(CLAVE, lineas);

        mvc.perform(registrar(cuerpo).with(como("VENDEDOR"))).andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    @Test
    void masDeDiezPagosEsUn400() throws Exception {
        String pagos = String.join(",", java.util.Collections.nCopies(11, "{\"medioId\":1,\"monto\":1000}"));
        String cuerpo = "{\"claveIdempotencia\":\"%s\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[%s]}"
                .formatted(CLAVE, pagos);

        mvc.perform(registrar(cuerpo).with(como("VENDEDOR"))).andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    @Test
    void elDetalleDelDatoInvalidoNoRepiteLoEnviado() throws Exception {
        String cuerpo = "{\"claveIdempotencia\":\"%s\",\"lineas\":[{\"productoId\":7,\"cantidad\":2}],\"pagos\":[{\"medioId\":1,\"monto\":-987654}]}"
                .formatted(CLAVE);

        mvc.perform(registrar(cuerpo).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("987654"))));
    }

    @Test
    void elPinNuncaApareceEnLaRespuestaDeUnError() throws Exception {
        String cuerpo = "{\"claveIdempotencia\":\"hola\",\"autorizacion\":{\"administradorId\":4,\"pin\":\"483920\"}}";

        mvc.perform(registrar(cuerpo).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("483920"))));
    }

    // ----------------------------------------------------- los errores, en español

    @Test
    void unaVentaInvalidaEsUn400ConSuTexto() throws Exception {
        doThrow(new VentaInvalidaException("La suma de los pagos debe ser igual al total de la venta."))
                .when(servicio).procesar(any());

        mvc.perform(registrar(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Venta no válida"))
                .andExpect(jsonPath("$.detail").value("La suma de los pagos debe ser igual al total de la venta."));
    }

    @Test
    void unDescuentoInvalidoDelCalculoEsUn400() throws Exception {
        doThrow(new DescuentoInvalidoException("El descuento no puede ser negativo.")).when(servicio).procesar(any());

        mvc.perform(registrar(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Descuento no válido"));
    }

    @Test
    void laFaltaDeExistenciaEsUn409ConSuTexto() throws Exception {
        doThrow(new ExistenciaInsuficienteException("No hay existencia suficiente de Tornillo."))
                .when(servicio).procesar(any());

        mvc.perform(registrar(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Existencia insuficiente"))
                .andExpect(jsonPath("$.detail").value("No hay existencia suficiente de Tornillo."));
    }

    @Test
    void unaClaveReutilizadaConOtraCargaEsUn409ConSuTexto() throws Exception {
        doThrow(new ClaveDeIdempotenciaEnUsoException()).when(servicio).procesar(any());

        mvc.perform(registrar(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Clave de idempotencia en uso"))
                .andExpect(jsonPath("$.detail").value(
                        "Esa clave de idempotencia ya registró otra venta. Genera una clave nueva para una venta distinta."));
    }

    @Test
    void sinTurnoEsUn409() throws Exception {
        doThrow(new SinTurnoAbiertoException()).when(servicio).procesar(any());

        mvc.perform(registrar(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Sin turno abierto"));
    }

    @Test
    void sinAutorizacionEsUn403() throws Exception {
        doThrow(new AutorizacionRequeridaException()).when(servicio).procesar(any());

        mvc.perform(registrar(CUERPO).with(como("VENDEDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Autorización requerida"));
    }
}
