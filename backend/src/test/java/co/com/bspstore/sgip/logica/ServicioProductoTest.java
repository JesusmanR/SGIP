package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.config.SinEmpresaException;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.CategoriaRepositorio;
import co.com.bspstore.sgip.datos.ConsultaCatalogo;
import co.com.bspstore.sgip.datos.ProductoRepositorio;
import co.com.bspstore.sgip.datos.ProveedorRepositorio;
import co.com.bspstore.sgip.modelo.Categoria;
import co.com.bspstore.sgip.modelo.Producto;
import co.com.bspstore.sgip.modelo.Proveedor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServicioProductoTest {

    @Mock ProductoRepositorio productos;
    @Mock CategoriaRepositorio categorias;
    @Mock ProveedorRepositorio proveedores;
    @Mock ConsultaCatalogo catalogo;
    @InjectMocks ServicioProducto servicio;

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void comoEmpresa(Integer empresaId) {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    private SolicitudProducto solicitud(String codigo, String sku) {
        return new SolicitudProducto(4, 9, codigo, sku, "7701234567890",
                "Arroz Diana", "500 g", null, new BigDecimal("3100.00"), 120, 20);
    }

    /** Categoría y proveedor que sí pertenecen a la empresa 3. */
    private void referenciasPropias() {
        when(categorias.findByEmpresaIdAndId(3, 4))
                .thenReturn(Optional.of(new Categoria(3, "Granos", null)));
        when(proveedores.findByEmpresaIdAndId(3, 9))
                .thenReturn(Optional.of(new Proveedor(3, "Distribuidora La Esperanza", "9001234567")));
    }

    // ── Lectura ──────────────────────────────────────────────────────
    @Test
    void elListadoSiempreVaAcotadoALaEmpresa() {
        comoEmpresa(3);
        when(catalogo.listar(eq(3), any(), anyInt())).thenReturn(List.of());

        servicio.listar("arroz", 10);

        verify(catalogo).listar(3, "arroz", 10);
    }

    @Test
    void unListadoSinTopeNoTraeElInventarioEntero() {
        comoEmpresa(3);
        when(catalogo.listar(eq(3), any(), anyInt())).thenReturn(List.of());

        servicio.listar(null, null);
        verify(catalogo).listar(3, null, 50);

        servicio.listar(null, 5000);
        verify(catalogo).listar(3, null, 200);
    }

    @Test
    void unCodigoDeBarrasVacioNiSiquieraConsulta() {
        comoEmpresa(3);

        assertTrue(servicio.porCodigoBarras("  ").isEmpty());
        assertTrue(servicio.porCodigoBarras(null).isEmpty());
        verify(catalogo, never()).porCodigoBarras(any(), any());
    }

    // ── Alta ─────────────────────────────────────────────────────────
    @Test
    void guardaConLaEmpresaDelContexto() {
        comoEmpresa(3);
        referenciasPropias();
        when(productos.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.crear(solicitud("ARZ-500", "ARZ-500"));

        ArgumentCaptor<Producto> captor = ArgumentCaptor.forClass(Producto.class);
        verify(productos).save(captor.capture());
        assertEquals(3, captor.getValue().getEmpresaId());
    }

    @Test
    void normalizaElCodigoYElSku() {
        comoEmpresa(3);
        referenciasPropias();
        when(productos.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.crear(solicitud("  arz-500  ", "  arz-a  "));

        ArgumentCaptor<Producto> captor = ArgumentCaptor.forClass(Producto.class);
        verify(productos).save(captor.capture());
        assertEquals("ARZ-500", captor.getValue().getCodigo());
        assertEquals("ARZ-A", captor.getValue().getSku());
    }

    @Test
    void sinSkuSeUsaElCodigo() {
        comoEmpresa(3);
        referenciasPropias();
        when(productos.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.crear(solicitud("ARZ-500", "   "));

        ArgumentCaptor<Producto> captor = ArgumentCaptor.forClass(Producto.class);
        verify(productos).save(captor.capture());
        assertEquals("ARZ-500", captor.getValue().getSku());
    }

    @Test
    void rechazaUnaCategoriaDeOtraEmpresa() {
        comoEmpresa(3);
        // La categoría existe, pero no en esta empresa.
        when(categorias.findByEmpresaIdAndId(3, 4)).thenReturn(Optional.empty());

        assertThrows(ReferenciaAjenaException.class,
                () -> servicio.crear(solicitud("ARZ-500", "ARZ-500")));
        verify(productos, never()).save(any());
    }

    @Test
    void rechazaUnProveedorDeOtraEmpresa() {
        comoEmpresa(3);
        when(categorias.findByEmpresaIdAndId(3, 4))
                .thenReturn(Optional.of(new Categoria(3, "Granos", null)));
        // El proveedor existe, pero es de otro comercio.
        when(proveedores.findByEmpresaIdAndId(3, 9)).thenReturn(Optional.empty());

        assertThrows(ReferenciaAjenaException.class,
                () -> servicio.crear(solicitud("ARZ-500", "ARZ-500")));
        verify(productos, never()).save(any());
    }

    @Test
    void rechazaUnCodigoRepetidoEnLaMismaEmpresa() {
        comoEmpresa(3);
        referenciasPropias();
        when(productos.existsByEmpresaIdAndCodigo(3, "ARZ-500")).thenReturn(true);

        assertThrows(NombreRepetidoException.class,
                () -> servicio.crear(solicitud("ARZ-500", "ARZ-500")));
        verify(productos, never()).save(any());
    }

    @Test
    void rechazaUnSkuRepetidoAunqueElCodigoSeaNuevo() {
        comoEmpresa(3);
        referenciasPropias();
        when(productos.existsByEmpresaIdAndCodigo(3, "ARZ-600")).thenReturn(false);
        when(productos.existsByEmpresaIdAndSku(3, "ARZ-A")).thenReturn(true);

        assertThrows(NombreRepetidoException.class,
                () -> servicio.crear(solicitud("ARZ-600", "ARZ-A")));
        verify(productos, never()).save(any());
    }

    @Test
    void elUsuarioDePlataformaNoTieneCatalogo() {
        comoEmpresa(null);

        assertThrows(SinEmpresaException.class, () -> servicio.listar(null, null));
        assertThrows(SinEmpresaException.class,
                () -> servicio.crear(solicitud("ARZ-500", "ARZ-500")));
        verify(productos, never()).save(any());
    }
}
