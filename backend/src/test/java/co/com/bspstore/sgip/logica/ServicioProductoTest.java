package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.MovimientoDeInventario;
import co.com.bspstore.sgip.api.dto.SolicitudEdicionProducto;
import co.com.bspstore.sgip.api.dto.SolicitudPrecio;
import co.com.bspstore.sgip.api.dto.SolicitudProducto;
import co.com.bspstore.sgip.config.SinEmpresaException;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.CategoriaRepositorio;
import co.com.bspstore.sgip.datos.ConsultaCatalogo;
import co.com.bspstore.sgip.datos.ProductoRepositorio;
import co.com.bspstore.sgip.datos.ProveedorRepositorio;
import co.com.bspstore.sgip.datos.RepositorioCostos;
import co.com.bspstore.sgip.datos.RepositorioPrecios;
import co.com.bspstore.sgip.modelo.Categoria;
import co.com.bspstore.sgip.modelo.Producto;
import co.com.bspstore.sgip.modelo.Proveedor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServicioProductoTest {

    @Mock ProductoRepositorio productos;
    @Mock CategoriaRepositorio categorias;
    @Mock ProveedorRepositorio proveedores;
    @Mock ConsultaCatalogo catalogo;
    @Mock ServicioInventario inventario;
    @Mock RepositorioPrecios precios;
    @Mock RepositorioCostos costos;

    /** Con el ServicioPrecios de verdad: la regla de qué se escribe y cuándo vive allí. */
    private ServicioProducto servicio;

    private static final LocalDate HOY = LocalDate.of(2026, 11, 7);
    private static final int ID_GUARDADO = 55;
    private static final SolicitudPrecio VEINTE = new SolicitudPrecio(new BigDecimal("20"), null);

    @BeforeEach
    void elInventarioAceptaElSaldoInicial() {
        Clock reloj = Clock.fixed(HOY.atTime(9, 0).atZone(ZoneId.of("America/Bogota")).toInstant(),
                ZoneId.of("America/Bogota"));
        servicio = new ServicioProducto(productos, categorias, proveedores, catalogo, inventario,
                new ServicioPrecios(precios, reloj), costos, reloj);
        // Tolerante porque solo las pruebas de alta con existencia llegan
        // a pedirlo; las de edición y las de rechazo no lo tocan.
        lenient().when(inventario.registrarSaldoInicial(any(), anyInt())).thenReturn(
                Optional.of(new MovimientoDeInventario(900L, ID_GUARDADO, "ARZ-500", "Arroz Diana",
                        "INGRESO", 120, 0, 120, null, "Saldo inicial del producto", null,
                        7, "Ana", LocalDateTime.now())));
    }

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
                "Arroz Diana", "500 g", null, new BigDecimal("3100.00"), 120, 20, VEINTE, null);
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
    /** Lo que hace la base al guardar: asigna el identificador. */
    private void elGuardadoAsignaElIdentificador() {
        when(productos.save(any())).thenAnswer(i -> {
            Producto p = i.getArgument(0);
            ReflectionTestUtils.setField(p, "id", ID_GUARDADO);
            return p;
        });
    }

    @Test
    void elAltaNaceEnCeroYPideElSaldoInicialAlInventario() {
        comoEmpresa(3);
        referenciasPropias();
        elGuardadoAsignaElIdentificador();

        servicio.crear(solicitud("ARZ-500", "ARZ-500"));

        ArgumentCaptor<Producto> captor = ArgumentCaptor.forClass(Producto.class);
        verify(productos).save(captor.capture());
        // Nace en cero y es el kárdex quien lo sube: así los 120 llevan su
        // movimiento y la existencia nunca se explica a medias (RF-49).
        assertEquals(0, captor.getValue().getExistencia());
        assertEquals(20, captor.getValue().getExistenciaMinima());
        verify(inventario).registrarSaldoInicial(ID_GUARDADO, 120);
    }

    @Test
    void unAltaConExistenciaCeroNoEscribeMovimientos() {
        comoEmpresa(3);
        referenciasPropias();
        elGuardadoAsignaElIdentificador();

        servicio.crear(new SolicitudProducto(4, 9, "ARZ-500", "ARZ-500", null,
                "Arroz Diana", "500 g", null, new BigDecimal("3100.00"), 0, 20, VEINTE, null));

        verify(inventario, never()).registrarSaldoInicial(anyInt(), anyInt());
    }

    @Test
    void unAltaSinExistenciaNoEscribeMovimientos() {
        comoEmpresa(3);
        referenciasPropias();
        elGuardadoAsignaElIdentificador();

        servicio.crear(new SolicitudProducto(4, 9, "ARZ-500", "ARZ-500", null,
                "Arroz Diana", "500 g", null, new BigDecimal("3100.00"), null, null, VEINTE, null));

        verify(inventario, never()).registrarSaldoInicial(anyInt(), anyInt());
    }

    @Test
    void siElInventarioNoEncuentraElProductoRecienGuardadoElAltaFalla() {
        comoEmpresa(3);
        referenciasPropias();
        elGuardadoAsignaElIdentificador();
        when(inventario.registrarSaldoInicial(ID_GUARDADO, 120)).thenReturn(Optional.empty());

        // Lanzar es lo que deshace la transacción del alta: dejar pasar el
        // producto sin su movimiento sería la inconsistencia que esto evita.
        assertThrows(IllegalStateException.class,
                () -> servicio.crear(solicitud("ARZ-500", "ARZ-500")));
    }

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
                "Arroz Diana", "500 g", null, new BigDecimal("3400.00"), 20, null, null, null, 0);
    }

    private Producto productoGuardado() {
        Producto p = new Producto(3, 4, 9, "ARZ-500", "ARZ-500",
                "Arroz Diana", new BigDecimal("3100.00"));
        p.fijarExistenciaMinima(20);
        // Como si viniera de la base: la entidad no tiene forma de fijar la
        // existencia, porque solo la cambia el kárdex.
        ReflectionTestUtils.setField(p, "existencia", 120);
        return p;
    }

    @Test
    void editarNoTocaLaExistencia() {
        comoEmpresa(3);
        Producto guardado = productoGuardado();
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(guardado));
        referenciasPropias();
        when(productos.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

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
        when(productos.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

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
        when(productos.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        servicio.actualizar(11, edicion("  arz-700  ", "   "));

        assertEquals("ARZ-700", guardado.getCodigo());
        assertEquals("ARZ-700", guardado.getSku());
    }

    // ── Datos viejos (I1) ────────────────────────────────────────────

    private SolicitudEdicionProducto edicionConVersion(Integer version) {
        return new SolicitudEdicionProducto(4, 9, "ARZ-500", "ARZ-500", "7701234567890",
                "Arroz Diana", "500 g", null, new BigDecimal("3400.00"), 20, null, null, null,
                version);
    }

    private Producto productoEnLaVersion(int version) {
        Producto p = productoGuardado();
        ReflectionTestUtils.setField(p, "version", version);
        return p;
    }

    /**
     * La edición lleva la versión que traía el formulario al abrirse. Si el
     * producto ya cambió (una carga de precios, otra edición) se rechaza antes de
     * escribir nada: guardar lo de antes deshacía el cambio sin aviso.
     */
    @Test
    void editarConLaVersionDeAntesDeUnCambioSeRechazaSinEscribirNada() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoEnLaVersion(3)));

        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> servicio.actualizar(11, edicionConVersion(2)));

        verify(productos, never()).save(any());
        verify(productos, never()).saveAndFlush(any());
        verifyNoInteractions(precios);
    }

    @Test
    void unaVersionDeUnProductoMasNuevoQueElDelFormularioTambienSeRechaza() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoEnLaVersion(3)));

        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> servicio.actualizar(11, edicionConVersion(4)));
    }

    @Test
    void editarConLaVersionActualGuarda() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoEnLaVersion(3)));
        referenciasPropias();
        when(productos.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        assertTrue(servicio.actualizar(11, edicionConVersion(3)).isPresent());

        verify(productos).saveAndFlush(any());
    }

    // ── Costo en el histórico (M15) ──────────────────────────────────

    /** El costo guardado es 3100.00 (ver productoGuardado). */
    private SolicitudEdicionProducto edicionConCosto(String costo) {
        return new SolicitudEdicionProducto(4, 9, "ARZ-500", "ARZ-500", "7701234567890",
                "Arroz Diana", "500 g", null, new BigDecimal(costo), 20, null, null, null, 0);
    }

    private void unaEdicionLista() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoGuardado()));
        referenciasPropias();
        when(productos.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void cambiarElCostoLoRegistraEnElHistoricoConElDiaDeBogota() {
        unaEdicionLista();

        servicio.actualizar(11, edicionConCosto("3400.00"));

        verify(costos).registrarCambio(3, 11, new BigDecimal("3400.00"), HOY);
    }

    @Test
    void elHistoricoSeEscribeDespuesDeGuardarElProducto() {
        unaEdicionLista();

        servicio.actualizar(11, edicionConCosto("3400.00"));

        InOrder orden = inOrder(productos, costos);
        orden.verify(productos).saveAndFlush(any());
        orden.verify(costos).registrarCambio(3, 11, new BigDecimal("3400.00"), HOY);
    }

    /** La base devuelve 3100.00 y el formulario puede mandar 3100: es el mismo costo. */
    @Test
    void editarSinCambiarElCostoNoEscribeNadaEnElHistorico() {
        unaEdicionLista();

        servicio.actualizar(11, edicionConCosto("3100"));

        verifyNoInteractions(costos);
    }

    @Test
    void siLaEdicionSeRechazaNoQuedaNadaEnElHistorico() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoEnLaVersion(3)));

        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                () -> servicio.actualizar(11, edicionConVersion(2)));

        verifyNoInteractions(costos);
    }

    // ── Cambio de categoría (M3) ─────────────────────────────────────

    /**
     * Los precios se escriben leyendo la vista, que junta el producto con la
     * regla de su categoría. Con el cambio sin vaciar a la base, la vista aún ve
     * la categoría vieja.
     */
    @Test
    void elCambioSeVaciaALaBaseAntesDeEscribirLosPrecios() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoGuardado()));
        referenciasPropias();
        when(productos.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        var conPrecio = new SolicitudEdicionProducto(4, 9, "ARZ-500", "ARZ-500", null,
                "Arroz Diana", "500 g", null, new BigDecimal("3400.00"), 20,
                new SolicitudPrecio(new BigDecimal("20"), null), null, null, 0);

        servicio.actualizar(11, conPrecio);

        InOrder orden = inOrder(productos, precios);
        orden.verify(productos).saveAndFlush(any());
        orden.verify(precios).bloquearProducto(3, 11);
    }

    // ── Un código de barras por empresa (V33) ───────────────────────
    private SolicitudProducto conBarras(String barras) {
        return new SolicitudProducto(4, 9, "ARZ-500", "ARZ-500", barras,
                "Arroz Diana", "500 g", null, new BigDecimal("3100.00"), 120, 20, VEINTE, null);
    }

    private SolicitudEdicionProducto edicionConBarras(String barras) {
        return new SolicitudEdicionProducto(4, 9, "ARZ-500", "ARZ-500", barras,
                "Arroz Diana", "500 g", null, new BigDecimal("3400.00"), 20, null, null, null, 0);
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
        when(productos.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

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
        when(productos.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

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
        when(productos.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        servicio.actualizar(11, edicionConBarras(" 7709999999999 "));

        assertEquals("7709999999999", guardado.getCodigoBarras());
        verify(productos).existsByEmpresaIdAndCodigoBarrasAndIdNot(3, "7709999999999", 11);
    }

    // ── El porcentaje y el precio de venta (ADR-0025) ───────────────
    private static final String CONTADO = "CONTADO";
    private static final String CREDITO = "CREDITO";

    private static SolicitudPrecio porcentaje(String valor) {
        return new SolicitudPrecio(new BigDecimal(valor), null);
    }

    private static SolicitudPrecio precio(String valor) {
        return new SolicitudPrecio(null, new BigDecimal(valor));
    }

    private static ReglaDePrecio reglaDeFactor(String factor) {
        return new ReglaDePrecio(new BigDecimal(factor), null, "SOBRE_COSTO");
    }

    private static ReglaDePrecio reglaDeCifra(String cifra) {
        return new ReglaDePrecio(null, new BigDecimal(cifra), "SOBRE_COSTO");
    }

    private SolicitudProducto alta(String costo, SolicitudPrecio contado, SolicitudPrecio credito) {
        return new SolicitudProducto(4, 9, "ARZ-500", "ARZ-500", null, "Arroz Diana", "500 g", null,
                new BigDecimal(costo), 0, 20, contado, credito);
    }

    private SolicitudEdicionProducto edicionDePrecio(SolicitudPrecio contado, SolicitudPrecio credito,
                                                     Boolean creditoIgualAlContado) {
        return new SolicitudEdicionProducto(4, 9, "ARZ-500", "ARZ-500", null, "Arroz Diana", "500 g",
                null, new BigDecimal("1000.00"), 20, contado, credito, creditoIgualAlContado, 0);
    }

    private void prepararAlta() {
        comoEmpresa(3);
        referenciasPropias();
        elGuardadoAsignaElIdentificador();
    }

    private void prepararEdicion() {
        comoEmpresa(3);
        Producto guardado = productoGuardado();
        ReflectionTestUtils.setField(guardado, "id", 11);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(guardado));
        referenciasPropias();
        when(productos.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    private void altaRechazada(SolicitudProducto solicitud, String pedazoDelMotivo) {
        comoEmpresa(3);
        referenciasPropias();
        ReglaInvalidaException e = assertThrows(ReglaInvalidaException.class,
                () -> servicio.crear(solicitud));
        assertTrue(e.getMessage().contains(pedazoDelMotivo), e.getMessage());
        verify(productos, never()).save(any());
        verifyNoInteractions(precios);
    }

    @Test
    void elAltaExigePorcentajeOPrecioDeContado() {
        altaRechazada(alta("1000.00", null, null), "de contado");
    }

    @Test
    void unPrecioDeContadoSinNingunValorTambienSeRechaza() {
        altaRechazada(alta("1000.00", new SolicitudPrecio(null, null), null), "de contado");
    }

    @Test
    void mandarPorcentajeYPrecioALaVezSeRechaza() {
        altaRechazada(alta("1000.00",
                new SolicitudPrecio(new BigDecimal("20"), new BigDecimal("1200")), null), "no los dos");
    }

    @Test
    void mandarPorcentajeYPrecioALaVezEnElCreditoTambienSeRechaza() {
        altaRechazada(alta("1000.00", porcentaje("20"),
                new SolicitudPrecio(new BigDecimal("20"), new BigDecimal("1200"))), "a crédito");
    }

    @Test
    void unPorcentajeDeCeroSeRechazaYSeDiceComoHacerlo() {
        altaRechazada(alta("1000.00", porcentaje("0"), null), "escribe el precio");
    }

    @Test
    void unPorcentajeNegativoSeRechaza() {
        altaRechazada(alta("1000.00", porcentaje("-5"), null), "escribe el precio");
    }

    @Test
    void unPrecioDeCeroSeRechaza() {
        altaRechazada(alta("1000.00", precio("0"), null), "mayor que cero");
    }

    @Test
    void unPrecioNegativoSeRechaza() {
        altaRechazada(alta("1000.00", precio("-1"), null), "mayor que cero");
    }

    @Test
    void unPrecioConCentavosSeRechazaEnVezDeRedondearloEnSilencio() {
        altaRechazada(alta("1000.00", precio("1250.50"), null), "pesos enteros");
    }

    @Test
    void unPorcentajeConMasDeDosDecimalesSeRechaza() {
        altaRechazada(alta("1000.00", porcentaje("13.645"), null), "dos decimales");
    }

    @Test
    void unPorcentajeQueNoCabeEnElFactorSeRechazaConSuMotivo() {
        // Con 9900 el factor sería 100,0000 y la columna es DECIMAL(6,4): sin
        // esto, el error sería del motor y no le diría nada a la tienda.
        altaRechazada(alta("1000.00", porcentaje("9900"), null), "9.899,99");
    }

    @Test
    void unPrecioQueNoCabeEnLaColumnaSeRechazaConSuMotivo() {
        altaRechazada(alta("1000.00", precio("1234567890123"), null), "12 cifras");
    }

    @Test
    void unPrecioDeDoceCifrasEsElMayorQueSePuedeVender() {
        comoEmpresa(3);
        referenciasPropias();
        when(productos.save(any(Producto.class))).thenAnswer(i -> {
            Producto p = i.getArgument(0);
            ReflectionTestUtils.setField(p, "id", ID_GUARDADO);
            return p;
        });

        servicio.crear(alta("1000.00", precio("123456789012"), null));

        verify(precios).abrirReglaPropia(3, ID_GUARDADO, CONTADO, reglaDeCifra("123456789012"), HOY);
    }

    @Test
    void unPorcentajeCuyoPrecioNoSePodriaVenderSeRechaza() {
        // 900 mil millones al 200 % son 13 cifras: la regla cabe en su factor y
        // la venta no cabría en `precio_unitario`.
        altaRechazada(alta("900000000000.00", porcentaje("200"), null), "12 cifras");
    }

    @Test
    void unPorcentajeQueDejaElPrecioEnCeroPesosSeRechaza() {
        // Costo 0,40 al 20 % son 0,48, que redondea a cero. La regla sería
        // válida y el producto se vendería regalado.
        altaRechazada(alta("0.40", porcentaje("20"), null), "cero pesos");
    }

    @Test
    void elPorcentajeSeGuardaComoFactorSobreElCosto() {
        prepararAlta();

        servicio.crear(alta("1000.00", porcentaje("20"), null));

        verify(precios).abrirReglaPropia(3, ID_GUARDADO, CONTADO, reglaDeFactor("1.2000"), HOY);
    }

    @Test
    void elPrecioSeGuardaComoCifraSinFactor() {
        prepararAlta();

        servicio.crear(alta("1000.00", precio("1250"), null));

        verify(precios).abrirReglaPropia(3, ID_GUARDADO, CONTADO, reglaDeCifra("1250"), HOY);
    }

    @Test
    void unPrecioEscritoConCerosDecimalesEsUnPrecioEntero() {
        prepararAlta();

        servicio.crear(alta("1000.00", precio("1250.00"), null));

        verify(precios).abrirReglaPropia(3, ID_GUARDADO, CONTADO, reglaDeCifra("1250"), HOY);
    }

    @Test
    void unPrecioPorDebajoDelCostoSeAdmite() {
        prepararAlta();

        servicio.crear(alta("1000.00", precio("900"), null));

        verify(precios).abrirReglaPropia(3, ID_GUARDADO, CONTADO, reglaDeCifra("900"), HOY);
    }

    @Test
    void sinCreditoSeGuardaIgualAlContado() {
        prepararAlta();

        servicio.crear(alta("1000.00", precio("1200"), null));

        verify(precios).abrirReglaPropia(3, ID_GUARDADO, CREDITO, reglaDeCifra("1200"), HOY);
    }

    @Test
    void unCreditoEscritoSeGuardaAparte() {
        prepararAlta();

        servicio.crear(alta("1000.00", porcentaje("20"), porcentaje("25")));

        verify(precios).abrirReglaPropia(3, ID_GUARDADO, CONTADO, reglaDeFactor("1.2000"), HOY);
        verify(precios).abrirReglaPropia(3, ID_GUARDADO, CREDITO, reglaDeFactor("1.2500"), HOY);
    }

    @Test
    void elAltaConPrecioYExistenciaSigueEscribiendoElMovimientoInicial() {
        prepararAlta();

        servicio.crear(new SolicitudProducto(4, 9, "ARZ-500", "ARZ-500", null, "Arroz Diana", "500 g",
                null, new BigDecimal("1000.00"), 120, 20, porcentaje("20"), null));

        verify(inventario).registrarSaldoInicial(ID_GUARDADO, 120);
    }

    @Test
    void cambiarElPrecioCierraLaReglaAnteriorYAbreOtra() {
        prepararEdicion();
        when(precios.reglaPropiaVigente(3, 11, CONTADO, HOY)).thenReturn(Optional.of(reglaDeCifra("1250")));

        servicio.actualizar(11, edicionDePrecio(precio("1300"), null, false));

        InOrder orden = inOrder(precios);
        orden.verify(precios).corregirReglaPropiaDeHoy(3, 11, CONTADO, reglaDeCifra("1300"), HOY);
        orden.verify(precios).cerrarReglaPropiaAyer(3, 11, CONTADO, HOY);
        orden.verify(precios).abrirReglaPropia(3, 11, CONTADO, reglaDeCifra("1300"), HOY);
    }

    @Test
    void dosCambiosElMismoDiaCorrigenLaMismaRegla() {
        prepararEdicion();
        when(precios.reglaPropiaVigente(3, 11, CONTADO, HOY)).thenReturn(Optional.of(reglaDeCifra("1250")));
        when(precios.corregirReglaPropiaDeHoy(3, 11, CONTADO, reglaDeCifra("1300"), HOY)).thenReturn(1);

        servicio.actualizar(11, edicionDePrecio(precio("1300"), null, false));

        verify(precios, never()).cerrarReglaPropiaAyer(anyInt(), anyInt(), any(), any());
        verify(precios, never()).abrirReglaPropia(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void volverAEscribirLaMismaCifraNoDejaHistoria() {
        prepararEdicion();
        when(precios.reglaPropiaVigente(3, 11, CONTADO, HOY)).thenReturn(Optional.of(reglaDeFactor("1.2000")));

        servicio.actualizar(11, edicionDePrecio(porcentaje("20.00"), null, false));

        verify(precios, never()).corregirReglaPropiaDeHoy(anyInt(), anyInt(), any(), any(), any());
        verify(precios, never()).cerrarReglaPropiaAyer(anyInt(), anyInt(), any(), any());
        verify(precios, never()).abrirReglaPropia(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void pasarDePorcentajeAPrecioEsUnCambioAunqueDeLaMismaCifra() {
        prepararEdicion();
        when(precios.reglaPropiaVigente(3, 11, CONTADO, HOY)).thenReturn(Optional.of(reglaDeFactor("1.2000")));

        servicio.actualizar(11, edicionDePrecio(precio("1200"), null, false));

        verify(precios).abrirReglaPropia(3, 11, CONTADO, reglaDeCifra("1200"), HOY);
    }

    @Test
    void editarSinEscribirElPrecioNoTocaNingunaRegla() {
        prepararEdicion();

        servicio.actualizar(11, edicionDePrecio(null, null, null));

        // Un producto que sigue la regla general no se queda con una regla
        // propia porque alguien le corrigió el nombre.
        verifyNoInteractions(precios);
    }

    @Test
    void unProductoDeOtraEmpresaNoRecibeRegla() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.empty());

        assertTrue(servicio.actualizar(11, edicionDePrecio(precio("1300"), null, null)).isEmpty());

        verifyNoInteractions(precios);
    }

    @Test
    void unPrecioRechazadoAlEditarNoGuardaNada() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoGuardado()));
        referenciasPropias();

        assertThrows(ReglaInvalidaException.class,
                () -> servicio.actualizar(11, edicionDePrecio(precio("0"), null, null)));

        verify(productos, never()).save(any());
        verifyNoInteractions(precios);
    }

    @Test
    void alCambiarElContadoElCreditoQueEraIgualLoSigue() {
        prepararEdicion();

        servicio.actualizar(11, edicionDePrecio(precio("1300"), null, null));

        verify(precios).abrirReglaPropia(3, 11, CONTADO, reglaDeCifra("1300"), HOY);
        verify(precios).abrirReglaPropia(3, 11, CREDITO, reglaDeCifra("1300"), HOY);
    }

    @Test
    void unCreditoQueEsOtroPrecioNoSigueAlContado() {
        prepararEdicion();

        servicio.actualizar(11, edicionDePrecio(precio("1300"), null, false));

        verify(precios).abrirReglaPropia(3, 11, CONTADO, reglaDeCifra("1300"), HOY);
        verify(precios, never()).abrirReglaPropia(eq(3), eq(11), eq(CREDITO), any(), any());
    }

    @Test
    void volverAUsarElPrecioDeContadoCopiaLaReglaVigente() {
        prepararEdicion();
        when(precios.reglaVigente(3, 11, CONTADO))
                .thenReturn(Optional.of(new ReglaDePrecio(new BigDecimal("1.2000"), null, "SOBRE_PRECIO")));

        servicio.actualizar(11, edicionDePrecio(null, null, true));

        verify(precios).abrirReglaPropia(3, 11, CREDITO,
                new ReglaDePrecio(new BigDecimal("1.2000"), null, "SOBRE_PRECIO"), HOY);
        verify(precios, never()).abrirReglaPropia(eq(3), eq(11), eq(CONTADO), any(), any());
    }

    @Test
    void volverAUsarElContadoCopiaLaReglaPropiaLeidaConBloqueoYNoLaDeLaVista() {
        // La vista es una lectura normal: tras esperar el bloqueo del producto,
        // aún ve la foto de antes y diría el contado viejo.
        prepararEdicion();
        ReglaDePrecio actual = new ReglaDePrecio(new BigDecimal("1.3000"), null, "SOBRE_COSTO");
        when(precios.reglaPropiaVigente(3, 11, CONTADO, HOY)).thenReturn(Optional.of(actual));

        servicio.actualizar(11, edicionDePrecio(null, null, true));

        verify(precios).abrirReglaPropia(3, 11, CREDITO, actual, HOY);
        verify(precios, never()).reglaVigente(anyInt(), anyInt(), any());
        InOrder orden = inOrder(precios);
        orden.verify(precios).bloquearProducto(3, 11);
        orden.verify(precios).reglaPropiaVigente(3, 11, CONTADO, HOY);
    }

    @Test
    void noSePuedeIgualarElCreditoAUnContadoQueNoExiste() {
        prepararEdicion();

        ReglaInvalidaException e = assertThrows(ReglaInvalidaException.class,
                () -> servicio.actualizar(11, edicionDePrecio(null, null, true)));

        assertTrue(e.getMessage().contains("de contado"), e.getMessage());
        verify(precios, never()).abrirReglaPropia(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void decirQueElCreditoEsIgualYMandarOtroEsContradictorio() {
        comoEmpresa(3);
        when(productos.findByEmpresaIdAndId(3, 11)).thenReturn(Optional.of(productoGuardado()));
        referenciasPropias();

        assertThrows(ReglaInvalidaException.class,
                () -> servicio.actualizar(11, edicionDePrecio(precio("1300"), precio("1400"), true)));

        verify(productos, never()).save(any());
    }

    @Test
    void cambiarSoloElCreditoNoTocaElContado() {
        prepararEdicion();

        servicio.actualizar(11, edicionDePrecio(null, precio("1400"), null));

        verify(precios).abrirReglaPropia(3, 11, CREDITO, reglaDeCifra("1400"), HOY);
        verify(precios, never()).abrirReglaPropia(eq(3), eq(11), eq(CONTADO), any(), any());
    }
}
