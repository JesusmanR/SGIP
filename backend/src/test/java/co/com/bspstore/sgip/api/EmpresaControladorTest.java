package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.EmpresaResumen;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.ServicioEmpresa;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.modelo.TipoDocumentoEmpresa;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/v1/empresa: el administrador edita los datos de contacto de SU empresa.
 *
 * Pasa por la cadena de seguridad real. La empresa de la sesión está ACTIVA, de modo que
 * {@code FiltroEmpresaActiva} deja pasar la escritura y un 403 solo puede venir de
 * {@code @PreAuthorize}. Cada rechazo comprueba que el servicio ni se enteró: lo que se rechaza aquí
 * no llega a escribir nada. Lo que sí escribe, contra el esquema real, está en
 * {@code EmpresaConBaseTest}.
 */
@WebMvcTest(controllers = EmpresaControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class EmpresaControladorTest {

    private static final String RUTA = "/api/v1/empresa";
    private static final String CUERPO = """
            {"nombreComercial":"Ferretería El Tornillo","direccion":"Calle 5 # 6-7",
             "ciudad":"Bucaramanga","telefono":"3001234567","correoContacto":"contacto@tornillo.co"}""";

    @Autowired MockMvc mvc;

    @MockitoBean ServicioEmpresa servicio;
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    @BeforeEach
    void empresaActiva() {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
        when(servicio.actualizarDatos(any())).thenReturn(new EmpresaResumen(4, "900123456", "Tornillo S.A.S.",
                null, TipoDocumentoEmpresa.NIT, null, null, null, null, null));
    }

    private static RequestPostProcessor como(String rol) {
        Integer empresa = "SUPER_ADMIN".equals(rol) ? null : 4;
        var u = new UsuarioAutenticado(7, "ana@tienda.co", rol, empresa);
        var autoridades = rol == null
                ? List.<SimpleGrantedAuthority>of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + rol));
        return authentication(new UsernamePasswordAuthenticationToken(u, null, autoridades));
    }

    private static MockHttpServletRequestBuilder editar(String cuerpo) {
        return put(RUTA).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VENDEDOR", "CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void vendedorClienteYSuperAdminReciben403EnPutEmpresa(String rol) throws Exception {
        mvc.perform(editar(CUERPO).with(como(rol)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Sin permiso para esta operación"));

        verifyNoInteractions(servicio);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(editar(CUERPO)).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    @Test
    void adminQueMandaNitRecibe400NombrandoElCampo() throws Exception {
        mvc.perform(editar("""
                        {"nombreComercial":"Ferretería","nit":"900123456"}""").with(como("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:sgip:campo-no-permitido"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("nit")));

        verifyNoInteractions(servicio);
    }

    @Test
    void adminQueMandaRazonSocialRecibe400() throws Exception {
        mvc.perform(editar("""
                        {"razonSocial":"Otra razón S.A.S.","telefono":"3001234567"}""").with(como("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("razonSocial")));

        verifyNoInteractions(servicio);
    }

    /**
     * Un campo desconocido con valor null también se nombra: antes el null reventaba al copiar el mapa y el
     * cliente recibía el «Cuerpo no válido» genérico, sin saber cuál campo sobraba.
     */
    @Test
    void unCampoDesconocidoConValorNullTambienSeNombra() throws Exception {
        mvc.perform(editar("{\"nit\":null,\"logoUrl\":null}").with(como("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:sgip:campo-no-permitido"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("nit")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("logoUrl")));

        verifyNoInteractions(servicio);
    }

    @Test
    void correoInvalidoEs400() throws Exception {
        mvc.perform(editar("""
                        {"correoContacto":"esto-no-es-un-correo"}""").with(como("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("correoContacto")));

        verifyNoInteractions(servicio);
    }

    @Test
    void telefonoDe21CaracteresEs400() throws Exception {
        mvc.perform(editar("{\"telefono\":\"" + "3".repeat(21) + "\"}").with(como("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("telefono")));

        verifyNoInteractions(servicio);
    }

    /** 20 caracteres es el tope de la columna y entra; 21 no. */
    @Test
    void telefonoDe20CaracteresPasaLaValidacion() throws Exception {
        mvc.perform(editar("{\"telefono\":\"" + "3".repeat(20) + "\"}").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk());
    }

    /** El tope se mide sobre el texto recortado: los espacios de los bordes no cuentan. */
    @Test
    void losEspaciosDeLosBordesNoCuentanParaElTope() throws Exception {
        mvc.perform(editar("{\"telefono\":\"  " + "3".repeat(20) + "  \"}").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk());
    }

    /** Un correo de solo espacios se trata como vacío (null), no como un correo mal escrito. */
    @Test
    void unCorreoDeSoloEspaciosNoEsUnCorreoInvalido() throws Exception {
        mvc.perform(editar("{\"correoContacto\":\"   \"}").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk());
    }
}
