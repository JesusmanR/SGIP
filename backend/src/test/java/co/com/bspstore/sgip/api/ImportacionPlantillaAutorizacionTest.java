package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.ResumenCarga;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.GeneradorPlantillaPrecios;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.ServicioImportacion;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
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
 * La ruta de la plantilla de precios: quién la descarga, qué responde y que no choca con
 * la ruta de una carga ({@code /{cargaId}}).
 *
 * Pasa por la cadena de seguridad real, con la empresa de la sesión ACTIVA para que un 403
 * solo pueda venir de la anotación del controlador.
 */
@WebMvcTest(controllers = ImportacionControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class ImportacionPlantillaAutorizacionTest {

    private static final String RUTA = "/api/v1/importaciones/plantilla";
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired MockMvc mvc;

    @MockitoBean ServicioImportacion servicio;
    @MockitoBean GeneradorPlantillaPrecios generador;
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    @BeforeEach
    void empresaActiva() throws Exception {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
        when(generador.nombreDeArchivo(4, 3)).thenReturn("plantilla-precios-ferreteria-nandu.xlsx");
        doAnswer(llamada -> {
            OutputStream salida = llamada.getArgument(2);
            salida.write("XLSX-FALSO".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(generador).escribir(eq(4), eq(3), any());
    }

    private static RequestPostProcessor como(String rol) {
        Integer empresa = "SUPER_ADMIN".equals(rol) ? null : 4;
        var u = new UsuarioAutenticado(7, "ana@tienda.co", rol, empresa);
        var autoridades = rol == null
                ? List.<SimpleGrantedAuthority>of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + rol));
        return authentication(new UsernamePasswordAuthenticationToken(u, null, autoridades));
    }

    @Test
    void elAdministradorDescargaElXlsxConSuNombreDeArchivo() throws Exception {
        mvc.perform(get(RUTA).param("proveedorId", "3").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"plantilla-precios-ferreteria-nandu.xlsx\""))
                .andExpect(content().string("XLSX-FALSO"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VENDEDOR", "CLIENTE", "SUPER_ADMIN"})
    @NullSource
    void vendedorRecibe403(String rol) throws Exception {
        mvc.perform(get(RUTA).param("proveedorId", "3").with(como(rol)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(generador);
    }

    @Test
    void sinAutenticarEsUn401() throws Exception {
        mvc.perform(get(RUTA).param("proveedorId", "3")).andExpect(status().isUnauthorized());
        verifyNoInteractions(generador);
    }

    @Test
    void proveedorIdAusenteEs400() throws Exception {
        mvc.perform(get(RUTA).with(como("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(generador);
    }

    @Test
    void proveedorIdNoNumericoEs400() throws Exception {
        mvc.perform(get(RUTA).param("proveedorId", "abc").with(como("ADMINISTRADOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(generador);
    }

    @Test
    void proveedorAjenoEs404SinCabecerasDeDescarga() throws Exception {
        when(generador.nombreDeArchivo(4, 9))
                .thenThrow(new RecursoNoEncontradoException("El proveedor no existe."));

        mvc.perform(get(RUTA).param("proveedorId", "9").with(como("ADMINISTRADOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(header().doesNotExist("Content-Disposition"));
        verify(generador, never()).escribir(any(), any(), any());
    }

    @Test
    void rutaPlantillaNoChocaConCargaId() throws Exception {
        // «plantilla» no es un identificador de carga: la ruta literal gana a /{cargaId}.
        mvc.perform(get(RUTA).param("proveedorId", "3").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk());
        verify(servicio, never()).buscar(any());

        // Y la ruta de una carga sigue siendo la de una carga.
        when(servicio.buscar(55L)).thenReturn(Optional.of(new ResumenCarga(55L, 3, "Proveedor",
                "lista.xlsx", "VALIDADA", null, null, 0, 0, 0, List.of(), List.of())));
        mvc.perform(get("/api/v1/importaciones/55").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cargaId").value(55));
        verify(servicio).buscar(55L);
    }
}
