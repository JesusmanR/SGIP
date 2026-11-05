package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.ProductoImagenRepositorio;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.AlmacenArchivos;
import co.com.bspstore.sgip.logica.ServicioImagenes;
import co.com.bspstore.sgip.logica.ServicioImportacion;
import co.com.bspstore.sgip.logica.ServicioProducto;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Todo error de la API es un {@code ProblemDetail} con un texto en español
 * (restricción global del plan), venga de donde venga: de un recurso que no
 * existe, de un JSON roto o de un parámetro mal escrito.
 *
 * Pasa por la cadena de seguridad y el manejador reales. No depende de
 * {@code spring.mvc.problemdetails}: la clase hermana
 * {@link ErroresDeLaApiConProblemDetailsTest} repite todo con esa propiedad
 * encendida, porque con ella Spring registra su propio manejador, en inglés.
 */
@WebMvcTest(controllers = {ProductoControlador.class, ImportacionControlador.class,
        ImagenControlador.class})
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class ErroresDeLaApiTest {

    @Autowired MockMvc mvc;

    @MockitoBean ServicioProducto productos;
    @MockitoBean ServicioImportacion importaciones;
    @MockitoBean ServicioImagenes imagenes;
    @MockitoBean ProductoImagenRepositorio repositorioImagenes;
    @MockitoBean AlmacenArchivos almacen;
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    @BeforeEach
    void empresaActiva() {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
        when(productos.buscar(99)).thenReturn(Optional.empty());
        when(productos.porCodigoBarras("0000")).thenReturn(Optional.empty());
        when(importaciones.rechazar(99L)).thenReturn(Optional.empty());
        when(repositorioImagenes.findByEmpresaIdAndProductoIdAndPrincipalTrue(4, 99))
                .thenReturn(Optional.empty());
    }

    private static RequestPostProcessor administrador() {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", 4);
        return authentication(new UsernamePasswordAuthenticationToken(u, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))));
    }

    private static ResultActions esProblema(ResultActions r, int estado) throws Exception {
        return r.andExpect(status().is(estado))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(estado))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    // ---- 404 con cuerpo --------------------------------------------------

    @Test
    void unProductoQueNoExisteEsUn404ConMotivo() throws Exception {
        esProblema(mvc.perform(get("/api/v1/productos/99").with(administrador())), 404)
                .andExpect(jsonPath("$.detail").value("El producto no existe."));
    }

    @Test
    void unCodigoDeBarrasSinProductoEsUn404ConMotivo() throws Exception {
        esProblema(mvc.perform(get("/api/v1/productos/barras/0000").with(administrador())), 404)
                .andExpect(jsonPath("$.detail").value("No hay ningún producto con ese código de barras."));
    }

    @Test
    void unaCargaQueNoExisteEsUn404ConMotivo() throws Exception {
        esProblema(mvc.perform(post("/api/v1/importaciones/99/rechazar").with(administrador())), 404)
                .andExpect(jsonPath("$.detail").value("La carga no existe."));
    }

    /**
     * El navegador pide las imágenes con {@code Accept: image/*}. Un cuerpo
     * {@code application/problem+json} no encaja con eso, y sin cuidado el
     * 404 se convertiría en un 406.
     */
    @Test
    void laImagenQueNoExisteSigueSiendoUn404AunqueSeLaPidaComoImagen() throws Exception {
        esProblema(mvc.perform(get("/api/v1/productos/99/imagen")
                .accept(MediaType.parseMediaType("image/*")).with(administrador())), 404)
                .andExpect(jsonPath("$.detail").value("El producto no tiene imagen."));
    }

    // ---- 400 con texto propio -------------------------------------------

    @Test
    void unJsonMalFormadoEsUn400SinEcoDeLoEscrito() throws Exception {
        esProblema(mvc.perform(post("/api/v1/productos").with(administrador())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\": \"secreto-del-cliente\", ")), 400)
                .andExpect(jsonPath("$.title").value("Cuerpo no válido"))
                .andExpect(jsonPath("$.detail")
                        .value("El cuerpo de la petición no es un JSON válido o tiene datos de un tipo equivocado."));
    }

    @Test
    void unTipoErroneoEnElCuerpoEsUn400SinEcoDeLoEscrito() throws Exception {
        esProblema(mvc.perform(put("/api/v1/productos/9").with(administrador())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoriaId\": \"secreto-del-cliente\"}")), 400)
                .andExpect(jsonPath("$.title").value("Cuerpo no válido"))
                .andExpect(content().string(not(containsString("secreto-del-cliente"))));
    }

    @Test
    void unTipoErroneoEnLaRutaEsUn400SinEcoDeLoEscrito() throws Exception {
        esProblema(mvc.perform(get("/api/v1/productos/abc-secreto").with(administrador())), 400)
                .andExpect(jsonPath("$.title").value("Valor no válido"))
                // `instance` lleva la dirección tal cual la pidió el cliente; el eco
                // que se evita es el del título y el detalle.
                .andExpect(jsonPath("$.detail").value("El valor de «productoId» no es válido."))
                .andExpect(jsonPath("$.title").value(not(containsString("abc-secreto"))));
    }

    @Test
    void unTipoErroneoEnUnParametroEsUn400SinEcoDeLoEscrito() throws Exception {
        esProblema(mvc.perform(get("/api/v1/productos").param("categoria", "abc-secreto")
                .with(administrador())), 400)
                .andExpect(jsonPath("$.detail").value("El valor de «categoria» no es válido."))
                .andExpect(content().string(not(containsString("abc-secreto"))));
    }

    @Test
    void unParametroObligatorioAusenteEsUn400ConSuNombre() throws Exception {
        esProblema(mvc.perform(multipart("/api/v1/importaciones")
                .file(new MockMultipartFile("archivo", "lista.xlsx", "application/octet-stream",
                        new byte[] {1}))
                .with(administrador())), 400)
                .andExpect(jsonPath("$.title").value("Falta un dato"))
                .andExpect(jsonPath("$.detail").value("Falta el dato obligatorio «proveedor»."));
    }
}
