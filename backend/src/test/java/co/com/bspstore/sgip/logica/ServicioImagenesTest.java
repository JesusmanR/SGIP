package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.EmpresaRepositorio;
import co.com.bspstore.sgip.datos.ProductoImagenRepositorio;
import co.com.bspstore.sgip.datos.ProductoRepositorio;
import co.com.bspstore.sgip.modelo.Producto;
import co.com.bspstore.sgip.modelo.ProductoImagen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServicioImagenesTest {

    @Mock ProductoImagenRepositorio imagenes;
    @Mock ProductoRepositorio productos;
    @Mock EmpresaRepositorio empresas;
    @Mock AlmacenArchivos almacen;
    @InjectMocks ServicioImagenes servicio;

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

    private MultipartFile imagen() {
        return new MockMultipartFile("archivo", "f.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    private void productoPropio() {
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(
                new Producto(3, 4, 9, "ARZ-500", "ARZ-500", "Arroz", new BigDecimal("3100"))));
    }

    @Test
    void laPrimeraImagenQuedaComoPrincipal() {
        comoEmpresa(3);
        productoPropio();
        when(imagenes.countByEmpresaIdAndProductoId(3, 11)).thenReturn(0);
        when(imagenes.mayorOrden(3, 11)).thenReturn((short) 0);
        when(almacen.guardar(any(), eq(3), eq("productos"))).thenReturn("empresa-3/productos/a.jpg");
        when(imagenes.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.subirDeProducto(11, imagen());

        ArgumentCaptor<ProductoImagen> captor = ArgumentCaptor.forClass(ProductoImagen.class);
        verify(imagenes).save(captor.capture());
        assertTrue(captor.getValue().isPrincipal());
        assertEquals(1, captor.getValue().getOrden());
    }

    @Test
    void laSegundaNoDesplazaALaPrincipal() {
        comoEmpresa(3);
        productoPropio();
        when(imagenes.countByEmpresaIdAndProductoId(3, 11)).thenReturn(1);
        when(imagenes.mayorOrden(3, 11)).thenReturn((short) 1);
        when(almacen.guardar(any(), anyInt(), any())).thenReturn("empresa-3/productos/b.jpg");
        when(imagenes.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.subirDeProducto(11, imagen());

        ArgumentCaptor<ProductoImagen> captor = ArgumentCaptor.forClass(ProductoImagen.class);
        verify(imagenes).save(captor.capture());
        assertFalse(captor.getValue().isPrincipal());
        assertEquals(2, captor.getValue().getOrden());
    }

    @Test
    void laSextaSeRechazaAntesDeEscribirElArchivo() {
        // El RF-63 admite cinco. Rechazar antes de guardar evita dejar un
        // archivo en disco que ninguna fila va a referenciar.
        comoEmpresa(3);
        productoPropio();
        when(imagenes.countByEmpresaIdAndProductoId(3, 11)).thenReturn(5);

        assertThrows(ArchivoInvalidoException.class, () -> servicio.subirDeProducto(11, imagen()));
        verify(almacen, never()).guardar(any(), anyInt(), any());
        verify(imagenes, never()).save(any());
    }

    @Test
    void noSeLePuedeColgarUnaImagenAUnProductoAjeno() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.empty());

        assertThrows(ReferenciaAjenaException.class, () -> servicio.subirDeProducto(11, imagen()));
        verify(almacen, never()).guardar(any(), anyInt(), any());
    }

    @Test
    void elArchivoSeGuardaEnLaCarpetaDeLaEmpresaDelContexto() {
        comoEmpresa(3);
        productoPropio();
        when(imagenes.countByEmpresaIdAndProductoId(3, 11)).thenReturn(0);
        when(imagenes.mayorOrden(3, 11)).thenReturn((short) 0);
        when(almacen.guardar(any(), eq(3), eq("productos"))).thenReturn("empresa-3/productos/a.jpg");
        when(imagenes.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.subirDeProducto(11, imagen());

        verify(almacen).guardar(any(), eq(3), eq("productos"));
    }

    @Test
    void elOrdenSaleDelMaximoYNoDelConteo() {
        // Con cuatro imagenes de las que se borro una intermedia, el
        // conteo dice 3 y el maximo 4. Numerar por el conteo chocaria
        // contra uq_producto_imagenes_orden.
        comoEmpresa(3);
        productoPropio();
        when(imagenes.countByEmpresaIdAndProductoId(3, 11)).thenReturn(3);
        when(imagenes.mayorOrden(3, 11)).thenReturn((short) 4);
        when(almacen.guardar(any(), anyInt(), any())).thenReturn("empresa-3/productos/e.jpg");
        when(imagenes.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.subirDeProducto(11, imagen());

        ArgumentCaptor<ProductoImagen> captor = ArgumentCaptor.forClass(ProductoImagen.class);
        verify(imagenes).save(captor.capture());
        assertEquals(5, captor.getValue().getOrden());
    }

    @Test
    void borrarQuitaLaFilaYElArchivo() {
        comoEmpresa(3);
        var imagen = new ProductoImagen(3, 11, "empresa-3/productos/a.jpg", (short) 1, false, 100);
        when(imagenes.findByEmpresaIdAndId(3, 55)).thenReturn(Optional.of(imagen));

        servicio.eliminar(55);

        verify(imagenes).delete(imagen);
        verify(almacen).borrar("empresa-3/productos/a.jpg");
    }

    @Test
    void alBorrarLaPrincipalOtraOcupaSuLugar() {
        // Un producto con imagenes pero sin principal no aparece en el
        // catalogo exportable.
        comoEmpresa(3);
        var principal = new ProductoImagen(3, 11, "a.jpg", (short) 1, true, 100);
        var otra = new ProductoImagen(3, 11, "b.jpg", (short) 2, false, 100);
        when(imagenes.findByEmpresaIdAndId(3, 55)).thenReturn(Optional.of(principal));
        when(imagenes.findByEmpresaIdAndProductoIdOrderByOrden(3, 11)).thenReturn(List.of(otra));

        servicio.eliminar(55);

        assertTrue(otra.isPrincipal());
    }

    @Test
    void noSePuedeBorrarLaImagenDeOtraEmpresa() {
        comoEmpresa(3);
        when(imagenes.findByEmpresaIdAndId(3, 55)).thenReturn(Optional.empty());

        assertThrows(ReferenciaAjenaException.class, () -> servicio.eliminar(55));
        verify(almacen, never()).borrar(any());
    }

    @Test
    void elLogoVaASuPropiaCarpeta() {
        comoEmpresa(3);
        when(empresas.findById(3)).thenReturn(
                Optional.of(mock(co.com.bspstore.sgip.modelo.Empresa.class)));
        when(almacen.guardar(any(), eq(3), eq("logo"))).thenReturn("empresa-3/logo/a.png");

        servicio.subirLogo(imagen());

        verify(almacen).guardar(any(), eq(3), eq("logo"));
    }
}
