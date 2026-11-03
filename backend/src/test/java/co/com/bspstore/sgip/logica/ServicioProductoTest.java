package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.SolicitudEdicionProducto;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
        when(catalogo.listar(eq(3), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        servicio.listar("arroz", null, null, null, 10);

        verify(catalogo).listar(3, "arroz", null, null, null, 10);
    }

    @Test
    void unListadoSinTopeNoTraeElInventarioEntero() {
        comoEmpresa(3);
        when(catalogo.listar(eq(3), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        servicio.listar(null, null, null, null, null);
        verify(catalogo).listar(3, null, null, null, null, 50);

        servicio.listar(null, null, null, null, 5000);
        verify(catalogo).listar(3, null, null, null, null, 200);
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

        assertThrows(SinEmpresaException.class,
                () -> servicio.listar(null, null, null, null, null));
        assertThrows(SinEmpresaException.class,
                () -> servicio.crear(solicitud("ARZ-500", "ARZ-500")));
        verify(productos, never()).save(any());
    }

    // ── Filtros del catálogo (RF-16) ─────────────────────────────────
    @Test
    void losFiltrosLleganTalCualALaConsulta() {
        comoEmpresa(3);
        when(catalogo.listar(eq(3), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        servicio.listar("arroz", 4, 9, "critico", 25);

        // El estado viaja en mayúsculas: la columna es un ENUM y la
        // pantalla no tiene por qué saberlo.
        verify(catalogo).listar(3, "arroz", 4, 9, "CRITICO", 25);
    }

    @Test
    void unEstadoDeExistenciaInventadoSeRechaza() {
        comoEmpresa(3);

        // Devolver el catálogo entero haría leer «no hay agotados» a
        // quien escribió AGOTADOS en plural.
        FiltroInvalidoException e = assertThrows(FiltroInvalidoException.class,
                () -> servicio.listar(null, null, null, "AGOTADOS", null));
        assertTrue(e.getMessage().contains("AGOTADOS"));
        verify(catalogo, never()).listar(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void unEstadoEnBlancoNoEsUnFiltro() {
        comoEmpresa(3);
        when(catalogo.listar(eq(3), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        servicio.listar(null, null, null, "   ", null);

        verify(catalogo).listar(3, null, null, null, null, 50);
    }

    // ── Edición (RF-15) ──────────────────────────────────────────────
    private SolicitudEdicionProducto edicion(String codigo, String sku) {
        return new SolicitudEdicionProducto(4, 9, codigo, sku, "7701234567890",
                "Arroz Diana", "500 g", null, new BigDecimal("3400.00"), 20);
    }

    private Producto productoGuardado() {
        Producto p = new Producto(3, 4, 9, "ARZ-500", "ARZ-500",
                "Arroz Diana", new BigDecimal("3100.00"));
        p.fijarExistencias(120, 20);
        return p;
    }

    @Test
    void editarNoTocaLaExistencia() {
        comoEmpresa(3);
        Producto guardado = productoGuardado();
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(guardado));
        referenciasPropias();
        when(productos.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.actualizar(11, edicion("ARZ-500", "ARZ-500"));

        // La existencia solo cambia por movimientos de inventario, que
        // dejan el valor anterior y el posterior (RF-49).
        assertEquals(120, guardado.getExistencia());
        assertEquals(new BigDecimal("3400.00"), guardado.getCosto());
    }

    @Test
    void editarUnProductoDeOtraEmpresaNoEncuentraNada() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.empty());

        assertTrue(servicio.actualizar(11, edicion("ARZ-500", null)).isEmpty());
        verify(productos, never()).save(any());
    }

    @Test
    void noSePuedeMoverUnProductoAUnaCategoriaAjena() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoGuardado()));
        when(categorias.findByEmpresaIdAndId(3, 4)).thenReturn(Optional.empty());

        assertThrows(ReferenciaAjenaException.class,
                () -> servicio.actualizar(11, edicion("ARZ-500", null)));
        verify(productos, never()).save(any());
    }

    @Test
    void noSePuedeMoverUnProductoAUnProveedorAjeno() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoGuardado()));
        when(categorias.findByEmpresaIdAndId(3, 4))
                .thenReturn(Optional.of(new Categoria(3, "Granos", null)));
        when(proveedores.findByEmpresaIdAndId(3, 9)).thenReturn(Optional.empty());

        assertThrows(ReferenciaAjenaException.class,
                () -> servicio.actualizar(11, edicion("ARZ-500", null)));
        verify(productos, never()).save(any());
    }

    @Test
    void guardarSinCambiarElCodigoNoLoCuentaComoRepetido() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoGuardado()));
        referenciasPropias();
        // La comprobación excluye al propio producto: si no lo hiciera,
        // guardar sin tocar el código fallaría contra sí mismo.
        when(productos.existsByEmpresaIdAndCodigoAndIdNot(3, "ARZ-500", 11)).thenReturn(false);
        when(productos.existsByEmpresaIdAndSkuAndIdNot(3, "ARZ-500", 11)).thenReturn(false);
        when(productos.save(any())).thenAnswer(i -> i.getArgument(0));

        assertTrue(servicio.actualizar(11, edicion("ARZ-500", "ARZ-500")).isPresent());
    }

    @Test
    void elCodigoDeOtroProductoSigueSiendoRepetido() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoGuardado()));
        referenciasPropias();
        when(productos.existsByEmpresaIdAndCodigoAndIdNot(3, "ARZ-900", 11)).thenReturn(true);

        assertThrows(NombreRepetidoException.class,
                () -> servicio.actualizar(11, edicion("ARZ-900", "ARZ-900")));
        verify(productos, never()).save(any());
    }

    @Test
    void alEditarSinSkuSeUsaElCodigo() {
        comoEmpresa(3);
        Producto guardado = productoGuardado();
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(guardado));
        referenciasPropias();
        when(productos.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.actualizar(11, edicion("  arz-700  ", "   "));

        assertEquals("ARZ-700", guardado.getCodigo());
        assertEquals("ARZ-700", guardado.getSku());
    }

    // ── Un código de barras por empresa (V33) ───────────────────────
    private SolicitudProducto conBarras(String barras) {
        return new SolicitudProducto(4, 9, "ARZ-500", "ARZ-500", barras,
                "Arroz Diana", "500 g", null, new BigDecimal("3100.00"), 120, 20);
    }

    private SolicitudEdicionProducto edicionConBarras(String barras) {
        return new SolicitudEdicionProducto(4, 9, "ARZ-500", "ARZ-500", barras,
                "Arroz Diana", "500 g", null, new BigDecimal("3400.00"), 20);
    }

    @Test
    void unCodigoDeBarrasRepetidoSeRechazaAlCrear() {
        comoEmpresa(3);
        referenciasPropias();
        when(productos.existsByEmpresaIdAndCodigoBarras(3, "7701234567890")).thenReturn(true);

        NombreRepetidoException e = assertThrows(NombreRepetidoException.class,
                () -> servicio.crear(conBarras("7701234567890")));

        assertTrue(e.getMessage().contains("7701234567890"));
        verify(productos, never()).save(any());
    }

    @Test
    void unCodigoDeBarrasRepetidoSeRechazaAlEditar() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoGuardado()));
        referenciasPropias();
        when(productos.existsByEmpresaIdAndCodigoBarrasAndIdNot(3, "7709999999999", 11))
                .thenReturn(true);

        NombreRepetidoException e = assertThrows(NombreRepetidoException.class,
                () -> servicio.actualizar(11, edicionConBarras("7709999999999")));

        assertTrue(e.getMessage().contains("7709999999999"));
        verify(productos, never()).save(any());
    }

    @Test
    void editarSinCambiarSuCodigoDeBarrasNoChocaConsigoMismo() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoGuardado()));
        referenciasPropias();
        // La comprobación de la edición excluye al propio producto. Si el
        // servicio usara la de la creación, este «true» (el producto
        // mismo, que ya tiene ese código) lo haría fallar contra sí mismo.
        lenient().when(productos.existsByEmpresaIdAndCodigoBarras(3, "7701234567890"))
                .thenReturn(true);
        when(productos.existsByEmpresaIdAndCodigoBarrasAndIdNot(3, "7701234567890", 11))
                .thenReturn(false);
        when(productos.save(any())).thenAnswer(i -> i.getArgument(0));

        assertTrue(servicio.actualizar(11, edicionConBarras("7701234567890")).isPresent());
        verify(productos).existsByEmpresaIdAndCodigoBarrasAndIdNot(3, "7701234567890", 11);
    }

    @Test
    void unCodigoDeBarrasEnBlancoSeGuardaComoNulo() {
        comoEmpresa(3);
        referenciasPropias();
        when(productos.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.crear(conBarras("   "));

        ArgumentCaptor<Producto> captor = ArgumentCaptor.forClass(Producto.class);
        verify(productos).save(captor.capture());
        assertNull(captor.getValue().getCodigoBarras());
        // Un nulo no compite con nadie: la clave deja pasar todos los que
        // haga falta, y consultarlo diría «repetido» contra otro sin código.
        verify(productos, never()).existsByEmpresaIdAndCodigoBarras(any(), any());
    }

    @Test
    void editarConElCodigoDeBarrasEnBlancoLoDejaNuloSinConsultar() {
        comoEmpresa(3);
        Producto guardado = productoGuardado();
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(guardado));
        referenciasPropias();
        when(productos.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.actualizar(11, edicionConBarras(""));

        assertNull(guardado.getCodigoBarras());
        verify(productos, never()).existsByEmpresaIdAndCodigoBarrasAndIdNot(any(), any(), any());
    }

    @Test
    void elCodigoDeBarrasSeGuardaSinEspaciosAlBorde() {
        comoEmpresa(3);
        referenciasPropias();
        when(productos.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.crear(conBarras("  7701234567890  "));

        ArgumentCaptor<Producto> captor = ArgumentCaptor.forClass(Producto.class);
        verify(productos).save(captor.capture());
        assertEquals("7701234567890", captor.getValue().getCodigoBarras());
        // Se consulta ya recortado: «  77…  » no puede pasar por distinto
        // de «77…» y chocar después contra la clave del motor.
        verify(productos).existsByEmpresaIdAndCodigoBarras(3, "7701234567890");
    }

    @Test
    void elCodigoDeBarrasEditadoTambienSeGuardaSinEspaciosAlBorde() {
        comoEmpresa(3);
        Producto guardado = productoGuardado();
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(guardado));
        referenciasPropias();
        when(productos.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.actualizar(11, edicionConBarras(" 7709999999999 "));

        assertEquals("7709999999999", guardado.getCodigoBarras());
        verify(productos).existsByEmpresaIdAndCodigoBarrasAndIdNot(3, "7709999999999", 11);
    }
}
