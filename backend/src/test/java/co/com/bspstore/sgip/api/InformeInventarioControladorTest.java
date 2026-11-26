package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.InformeInventario;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.logica.informes.ServicioInformeInventario;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo que el controlador del inventario valorizado pasa al servicio y cómo contesta: los parámetros y sus
 * valores por omisión, la forma del JSON y las cabeceras de la descarga. Quién entra lo prueba
 * {@code InformesRolesTest}; las cifras, {@code InformeInventarioConBaseTest}.
 */
@WebMvcTest(controllers = InformesControlador.class)
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class InformeInventarioControladorTest {

    @Autowired MockMvc mvc;

    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;
    @MockitoBean ServicioInformeInventario servicio;

    private static RequestPostProcessor administrador() {
        var usuario = new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", 4);
        return authentication(new UsernamePasswordAuthenticationToken(usuario, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))));
    }

    @BeforeEach
    void empresaActiva() {
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
    }

    @Test
    void sinParametrosPideLaPrimeraPaginaDe50() throws Exception {
        var totales = new InformeInventario.Totales(2, 14, new BigDecimal("12000"), new BigDecimal("14400"),
                0, 1, 3, new BigDecimal("3000"));
        var fila = new InformeInventario.Fila(9, "A-1", "Arroz", "Granos", true, 10, new BigDecimal("1000.00"),
                null, new BigDecimal("10000.00"), null);
        when(servicio.pagina(null, null, 0, 50)).thenReturn(new InformeInventario(totales, List.of(fila), 1));

        mvc.perform(get("/api/v1/informes/inventario-valorizado").with(administrador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.referencias").value(2))
                .andExpect(jsonPath("$.totales.valorAlCosto").value(12000))
                .andExpect(jsonPath("$.totales.unidadesBajoCero").value(3))
                .andExpect(jsonPath("$.totalFilas").value(1))
                .andExpect(jsonPath("$.filas[0].codigo").value("A-1"))
                .andExpect(jsonPath("$.filas[0].activo").value(true))
                .andExpect(jsonPath("$.filas[0].precio").doesNotExist());
    }

    @Test
    void losFiltrosYLaPaginaLlegaranAlServicio() throws Exception {
        when(servicio.pagina(any(), any(), anyInt(), anyInt()))
                .thenReturn(new InformeInventario(null, List.of(), 0));

        mvc.perform(get("/api/v1/informes/inventario-valorizado").with(administrador())
                        .param("categoriaId", "12").param("q", "arroz").param("pagina", "2").param("tamano", "500"))
                .andExpect(status().isOk());

        verify(servicio).pagina(12, "arroz", 2, 500);
    }

    @Test
    void unaPaginaQueNoEsUnNumeroEsUn400() throws Exception {
        mvc.perform(get("/api/v1/informes/inventario-valorizado").with(administrador()).param("pagina", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void elExcelSeDescargaComoAdjuntoConElNombreDelServicio() throws Exception {
        when(servicio.nombreDeArchivo()).thenReturn("informe-inventario-valorizado-2026-11-26.xlsx");
        doAnswer(llamada -> {
            OutputStream salida = llamada.getArgument(2);
            salida.write(new byte[]{1, 2, 3});
            return null;
        }).when(servicio).escribirExcel(any(), any(), any());

        var resultado = mvc.perform(get("/api/v1/informes/inventario-valorizado/excel").with(administrador())
                        .param("categoriaId", "12").param("q", "arroz"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"informe-inventario-valorizado-2026-11-26.xlsx\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();

        assertEquals(3, resultado.getResponse().getContentAsByteArray().length);
        verify(servicio).escribirExcel(eq(12), eq("arroz"), any());
    }

    @Test
    void sinFiltrosElExcelPideTodo() throws Exception {
        when(servicio.nombreDeArchivo()).thenReturn("informe-inventario-valorizado-2026-11-26.xlsx");

        mvc.perform(get("/api/v1/informes/inventario-valorizado/excel").with(administrador()))
                .andExpect(status().isOk());

        verify(servicio).escribirExcel(isNull(), isNull(), any());
    }
}
