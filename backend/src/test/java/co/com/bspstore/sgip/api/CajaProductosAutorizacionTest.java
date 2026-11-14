package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.ConsultaCatalogo;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.ServicioCajaProductos;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién busca productos desde la caja: el administrador y el vendedor. El cliente, el super
 * administrador y quien no tiene rol reciben 403 en las dos formas de la ruta.
 *
 * Pasa por la cadena de seguridad real y por el servicio real, con el catálogo simulado: así
 * {@code elVendedorNoRecibeCosto} lee el JSON que de verdad sale (el producto del catálogo sí
 * trae costo, margen y proveedor), para los dos roles que venden. La empresa de la sesión está
 * ACTIVA, de modo que un 403 solo puede venir de {@code @PreAuthorize}.
 */
@WebMvcTest(controllers = CajaProductosControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class, ServicioCajaProductos.class})
class CajaProductosAutorizacionTest {

    private static final String RUTA = "/api/v1/caja/productos";
    private static final int EMPRESA = 4;

    @Autowired MockMvc mvc;

    @MockitoBean ConsultaCatalogo catalogo;
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    @BeforeEach
    void empresaActiva() {
        when(plataforma.estadoDe(EMPRESA)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
    }

    private static RequestPostProcessor como(String rol) {
        Integer empresa = "SUPER_ADMIN".equals(rol) ? null : EMPRESA;
        var u = new UsuarioAutenticado(7, "ana@tienda.co", rol, empresa);
        var autoridades = rol == null
                ? List.<SimpleGrantedAuthority>of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + rol));
        return authentication(new UsernamePasswordAuthenticationToken(u, null, autoridades));
    }

    /** Un producto del catálogo con costo 2500, margen 24 % y proveedor: lo que la caja nunca enseña. */
    private static ProductoEnCatalogo conCostoYMargen() {
        return new ProductoEnCatalogo(11, "ARZ-500", "ARZ-500", "Arroz Diana", "500 g", null,
                "7701234567890", 4, "Granos", 9, "Distribuidora La Esperanza",
                new BigDecimal("2500.00"), 120, 20, "ACTIVO",
                new BigDecimal("3100"), new BigDecimal("3400"), "GRAVADO", new BigDecimal("0.19"),
                new BigDecimal("3689"), new BigDecimal("4046"), "SOBRE_COSTO", "SOBRE_COSTO",
                0, 0, 0, 0, null, 0,
                new BigDecimal("24.00"), new BigDecimal("36.00"),
                null, null, new BigDecimal("24.00"), new BigDecimal("36.00"), 5);
    }

    private static MockHttpServletRequestBuilder porCodigo(String codigo) {
        return get(RUTA).param("codigo", codigo);
    }

    private static MockHttpServletRequestBuilder porTexto(String q) {
        return get(RUTA).param("q", q);
    }

    // ------------------------------------------------------- lo que sí pueden

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorBuscanPorCodigoYRecibenLaFichaDeLaCaja(String rol) throws Exception {
        when(catalogo.porCodigoBarras(EMPRESA, "7701234567890")).thenReturn(Optional.of(conCostoYMargen()));

        mvc.perform(porCodigo("7701234567890").with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.codigo").value("ARZ-500"))
                .andExpect(jsonPath("$.nombre").value("Arroz Diana"))
                .andExpect(jsonPath("$.presentacion").value("500 g"))
                .andExpect(jsonPath("$.codigoBarras").value("7701234567890"))
                .andExpect(jsonPath("$.existencia").value(120))
                .andExpect(jsonPath("$.precio").value(3100))
                .andExpect(jsonPath("$.tarifaIva").value(0.19))
                .andExpect(jsonPath("$.tratamientoIva").value("GRAVADO"))
                .andExpect(jsonPath("$.vendible").value(true))
                .andExpect(jsonPath("$.motivoNoVendible").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elAdministradorYElVendedorBuscanPorTexto(String rol) throws Exception {
        when(catalogo.listar(eq(EMPRESA), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(conCostoYMargen()));

        mvc.perform(porTexto("arroz").with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].nombre").value("Arroz Diana"))
                .andExpect(jsonPath("$[0].precio").value(3100));
    }

    @Test
    void sinCoincidenciasPorTextoEsUnaListaVaciaYNoUnError() throws Exception {
        when(catalogo.listar(eq(EMPRESA), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        mvc.perform(porTexto("nada").with(como("VENDEDOR")))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void laBusquedaPorTextoNuncaPideMasDeVeinte() throws Exception {
        when(catalogo.listar(eq(EMPRESA), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        mvc.perform(porTexto("arroz").param("limite", "500").with(como("VENDEDOR"))).andExpect(status().isOk());
        mvc.perform(porTexto("arroz").with(como("VENDEDOR"))).andExpect(status().isOk());
        mvc.perform(porTexto("arroz").param("limite", "5").with(como("VENDEDOR"))).andExpect(status().isOk());

        verify(catalogo, org.mockito.Mockito.times(2)).listar(EMPRESA, "arroz", null, null, null, 20);
        verify(catalogo).listar(EMPRESA, "arroz", null, null, null, 5);
    }

    /**
     * La caja no enseña el costo a nadie, ni siquiera al administrador (decisión del 2026-11-09):
     * el JSON no tiene las claves {@code costo}, {@code margen} ni {@code utilidad}, ni ninguna
     * que las contenga, ni el proveedor, y las cifras del costo no aparecen en ningún valor.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR", "VENDEDOR"})
    void elVendedorNoRecibeCosto(String rol) throws Exception {
        when(catalogo.porCodigoBarras(EMPRESA, "7701234567890")).thenReturn(Optional.of(conCostoYMargen()));
        when(catalogo.listar(eq(EMPRESA), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(conCostoYMargen()));

        for (MockHttpServletRequestBuilder llamada : List.of(porCodigo("7701234567890"), porTexto("arroz"))) {
            MvcResult resultado = mvc.perform(llamada.with(como(rol)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$..costo").isEmpty())
                    .andExpect(jsonPath("$..margen").isEmpty())
                    .andExpect(jsonPath("$..utilidad").isEmpty())
                    .andReturn();

            String json = resultado.getResponse().getContentAsString().toLowerCase();
            for (String prohibido : List.of("costo", "margen", "utilidad", "proveedor", "esperanza",
                    "2500", "24.00", "\"24\"")) {
                assertFalse(json.contains(prohibido), "la respuesta de la caja no puede contener «"
                        + prohibido + "»: " + json);
            }
        }
    }

    @Test
    void unaEmpresaEnConsultaTambienPuedeBuscar() throws Exception {
        when(plataforma.estadoDe(EMPRESA)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));
        when(catalogo.porCodigoBarras(EMPRESA, "7701234567890")).thenReturn(Optional.of(conCostoYMargen()));

        mvc.perform(porCodigo("7701234567890").with(como("VENDEDOR"))).andExpect(status().isOk());
    }

    // ------------------------------------------------------ lo que no pueden

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void elClienteYElSuperAdministradorRecibenUn403(String rol) throws Exception {
        for (MockHttpServletRequestBuilder llamada : List.of(porCodigo("7701234567890"), porTexto("arroz"))) {
            mvc.perform(llamada.with(como(rol)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));
        }

        verifyNoInteractions(catalogo);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(porCodigo("7701234567890")).andExpect(status().isUnauthorized());
        mvc.perform(porTexto("arroz")).andExpect(status().isUnauthorized());

        verifyNoInteractions(catalogo);
    }

    // ------------------------------------------------------ los errores, en español y sin eco

    @Test
    void unCodigoQueNoExisteEsUn404ConTextoFijo() throws Exception {
        when(catalogo.porCodigoBarras(EMPRESA, "8800000000099")).thenReturn(Optional.empty());
        when(catalogo.porCodigo(EMPRESA, "8800000000099")).thenReturn(Optional.empty());

        mvc.perform(porCodigo("8800000000099").with(como("VENDEDOR")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("No hay ningún producto con ese código."))
                .andExpect(content().string(not(containsString("8800000000099"))));
    }

    @Test
    void elCodigoDeOtraEmpresaEsUn404IgualQueUnoInexistente() throws Exception {
        // El catálogo siempre se pregunta con la empresa de la sesión (4); el producto que existe
        // solo en la empresa 5 no se devuelve nunca.
        when(catalogo.porCodigoBarras(5, "7701234567890")).thenReturn(Optional.of(conCostoYMargen()));

        mvc.perform(porCodigo("7701234567890").with(como("VENDEDOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No hay ningún producto con ese código."));

        verify(catalogo, never()).porCodigoBarras(eq(5), any());
        verify(catalogo, never()).porCodigo(eq(5), any());
    }

    @Test
    void codigoYTextoALaVezSonUn400() throws Exception {
        mvc.perform(get(RUTA).param("codigo", "A1").param("q", "arroz987").with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail")
                        .value("Indica el código del producto o el texto a buscar, solo uno de los dos."))
                .andExpect(content().string(not(containsString("arroz987"))));

        verifyNoInteractions(catalogo);
    }

    @Test
    void sinCodigoNiTextoEsUn400() throws Exception {
        mvc.perform(get(RUTA).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("Indica el código del producto o el texto a buscar, solo uno de los dos."));

        verifyNoInteractions(catalogo);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void unTextoEnBlancoEsUn400(String q) throws Exception {
        mvc.perform(porTexto(q).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El texto a buscar no puede estar vacío."));

        verifyNoInteractions(catalogo);
    }

    @Test
    void unTextoDeMasDeCienCaracteresEsUn400() throws Exception {
        mvc.perform(porTexto("a".repeat(101)).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El texto a buscar admite hasta 100 caracteres."))
                .andExpect(content().string(not(containsString("aaaaaaaaaa"))));

        verifyNoInteractions(catalogo);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void unCodigoEnBlancoEsUn400(String codigo) throws Exception {
        mvc.perform(porCodigo(codigo).with(como("VENDEDOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El código del producto no puede estar vacío."));

        verifyNoInteractions(catalogo);
    }
}
