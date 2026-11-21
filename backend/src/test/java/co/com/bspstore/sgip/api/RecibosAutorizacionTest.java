package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.Recibo;
import co.com.bspstore.sgip.api.dto.Recibo.LineaDeRecibo;
import co.com.bspstore.sgip.api.dto.Recibo.PagoDeRecibo;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.ReciboPdf;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioConsultaDeVentas;
import co.com.bspstore.sgip.logica.ServicioRecibos;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.logica.ServicioVentas;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién llega al recibo de una venta (RF-70) y qué contesta la API: el administrador y el vendedor de la
 * empresa lo reciben, en JSON y en PDF; el cliente y el super administrador reciben 403 en las dos rutas, y la
 * venta que no es de la empresa de la sesión es un 404 con el mismo cuerpo de siempre. Pasa por la cadena de
 * seguridad real, como {@code VentasAutorizacionTest}.
 */
@WebMvcTest(controllers = VentasControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class RecibosAutorizacionTest {

    private static final String JSON = "/api/v1/caja/ventas/9/recibo";
    private static final String PDF = "/api/v1/caja/ventas/9/recibo.pdf";

    private static final Recibo RECIBO = new Recibo("Black Steel Parts S.A.S.", "900123456-7", "V-00000012",
            OffsetDateTime.of(2026, 11, 21, 15, 30, 5, 0, ZoneOffset.ofHours(-5)), "Ana Gómez", "Carlos Ruiz",
            "CC 79123456",
            List.of(new LineaDeRecibo("Celular económico", 2, new BigDecimal("10000.00"), BigDecimal.ZERO,
                    new BigDecimal("3800.00"), new BigDecimal("23800.00"))),
            new BigDecimal("20000.00"), BigDecimal.ZERO, new BigDecimal("3800.00"), new BigDecimal("23800.00"),
            List.of(new PagoDeRecibo("Efectivo", TipoMedio.EFECTIVO, new BigDecimal("23800.00"),
                    new BigDecimal("25000.00"), null)),
            new BigDecimal("1200.00"), List.of("Domicilio"));

    @Autowired MockMvc mvc;

    @MockitoBean ServicioRecibos recibos;
    @MockitoBean ReciboPdf pdf;
    @MockitoBean ServicioVentas ventas;
    @MockitoBean ServicioConsultaDeVentas consulta;
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    @BeforeEach
    void empresaActiva() {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
    }

    private static RequestPostProcessor como(String rol) {
        Integer empresa = "SUPER_ADMIN".equals(rol) ? null : 4;
        var u = new UsuarioAutenticado(7, "ana@tienda.co", rol, empresa);
        return authentication(new UsernamePasswordAuthenticationToken(u, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + rol))));
    }

    // ------------------------------------------------------- lo que sí pueden

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorRecibenElReciboEnJson(String rol) throws Exception {
        when(recibos.recibo(9L)).thenReturn(RECIBO);

        mvc.perform(get(JSON).with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.empresa").value("Black Steel Parts S.A.S."))
                .andExpect(jsonPath("$.nitEmpresa").value("900123456-7"))
                .andExpect(jsonPath("$.consecutivo").value("V-00000012"))
                .andExpect(jsonPath("$.cajero").value("Ana Gómez"))
                .andExpect(jsonPath("$.lineas[0].nombre").value("Celular económico"))
                .andExpect(jsonPath("$.total").value(23800))
                .andExpect(jsonPath("$.pagos[0].tipo").value("EFECTIVO"))
                .andExpect(jsonPath("$.cambio").value(1200))
                .andExpect(jsonPath("$.marcas[0]").value("Domicilio"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorRecibenElReciboEnPdf(String rol) throws Exception {
        byte[] contenido = "%PDF-1.7 de prueba".getBytes(StandardCharsets.US_ASCII);
        when(recibos.recibo(9L)).thenReturn(RECIBO);
        when(pdf.generar(RECIBO)).thenReturn(contenido);

        byte[] respuesta = mvc.perform(get(PDF).with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/pdf")))
                .andReturn().getResponse().getContentAsByteArray();

        assertEquals(new String(contenido, StandardCharsets.US_ASCII), new String(respuesta, StandardCharsets.US_ASCII));
    }

    @Test
    void elPdfNoSeGuardaEnLaCache() throws Exception {
        when(recibos.recibo(9L)).thenReturn(RECIBO);
        when(pdf.generar(RECIBO)).thenReturn(new byte[]{'%', 'P', 'D', 'F'});

        mvc.perform(get(PDF).with(como("VENDEDOR")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void elReciboNuncaLlevaCostoNiUtilidadNiParaElAdministrador() throws Exception {
        when(recibos.recibo(9L)).thenReturn(RECIBO);

        mvc.perform(get(JSON).with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("costo"))))
                .andExpect(content().string(not(containsString("utilidad"))))
                .andExpect(content().string(not(containsString("margen"))));
    }

    // ------------------------------------------------------- lo que no pueden

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    void elClienteYElSuperAdministradorRecibenUn403(String rol) throws Exception {
        mvc.perform(get(JSON).with(como(rol))).andExpect(status().isForbidden());
        mvc.perform(get(PDF).with(como(rol))).andExpect(status().isForbidden());
        mvc.perform(get(PDF).accept(MediaType.APPLICATION_PDF).with(como(rol))).andExpect(status().isForbidden());

        verifyNoInteractions(recibos);
    }

    @Test
    void sinSesionEsUn401EnLasDosRutas() throws Exception {
        mvc.perform(get(JSON)).andExpect(status().isUnauthorized());
        mvc.perform(get(PDF)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------- la venta de otra empresa

    @Test
    void laVentaQueNoEsDeLaEmpresaEsUn404EnLasDosRutas() throws Exception {
        when(recibos.recibo(55L)).thenThrow(new RecursoNoEncontradoException("La venta no existe."));

        mvc.perform(get("/api/v1/caja/ventas/55/recibo").with(como("VENDEDOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La venta no existe."));
        mvc.perform(get("/api/v1/caja/ventas/55/recibo.pdf").with(como("VENDEDOR")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/caja/ventas/55/recibo.pdf").accept(MediaType.APPLICATION_PDF).with(como("VENDEDOR")))
                .andExpect(status().isNotFound());
        verify(recibos, times(3)).recibo(55L);
        verifyNoInteractions(pdf);
    }

    @Test
    void unIdentificadorQueNoEsUnNumeroEsUn400() throws Exception {
        mvc.perform(get("/api/v1/caja/ventas/abc/recibo").with(como("VENDEDOR")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(recibos);
    }
}
