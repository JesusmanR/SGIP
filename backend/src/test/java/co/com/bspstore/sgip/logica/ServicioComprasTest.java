package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.MovimientoDeInventario;
import co.com.bspstore.sgip.api.dto.OrdenDeCompra;
import co.com.bspstore.sgip.api.dto.SolicitudOrdenCompra;
import co.com.bspstore.sgip.api.dto.SolicitudRecepcion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioCompras;
import co.com.bspstore.sgip.datos.RepositorioCompras.Cabecera;
import co.com.bspstore.sgip.datos.RepositorioCompras.LineaBloqueada;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServicioComprasTest {

    private static final int EMPRESA = 3;
    private static final int USUARIO = 7;
    private static final long ORDEN = 500L;
    private static final long RECEPCION = 800L;
    private static final int PC = 11;
    private static final int MON = 12;

    @Mock RepositorioCompras compras;
    @Mock ServicioInventario inventario;

    private ServicioCompras servicio;

    @BeforeEach
    void preparar() {
        servicio = new ServicioCompras(compras, inventario);
        var u = new UsuarioAutenticado(USUARIO, "ana@tienda.co", "ADMINISTRADOR", EMPRESA);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);

        when(compras.insertarOrden(anyInt(), anyInt(), anyInt(), any(), any())).thenReturn(ORDEN);
        when(compras.insertarRecepcion(anyInt(), anyLong(), anyInt(), any()))
                .thenReturn(RECEPCION);
        when(compras.buscarCabecera(EMPRESA, ORDEN)).thenReturn(Optional.of(cabecera("ABIERTA")));
        when(compras.lineasDe(EMPRESA, ORDEN)).thenReturn(List.of());
        when(inventario.ingresarPorRecepcion(anyInt(), anyInt(), any(), anyLong()))
                .thenReturn(Optional.of(movimiento()));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private Cabecera cabecera(String estado) {
        return new Cabecera(ORDEN, "OC-001", 2, "Distribuidora", USUARIO, "Ana",
                estado, LocalDateTime.now(), null, new BigDecimal("1000000.00"));
    }

    private MovimientoDeInventario movimiento() {
        return new MovimientoDeInventario(1L, PC, "PC-001", "Portátil", "INGRESO",
                1, 0, 1, null, null, RECEPCION, USUARIO, "Ana", LocalDateTime.now());
    }

    private SolicitudOrdenCompra orden(SolicitudOrdenCompra.LineaOrden... lineas) {
        return new SolicitudOrdenCompra(2, "OC-001", "primera compra", List.of(lineas));
    }

    private SolicitudOrdenCompra.LineaOrden linea(int producto, int cantidad) {
        return new SolicitudOrdenCompra.LineaOrden(producto, cantidad, new BigDecimal("1500.00"));
    }

    private SolicitudRecepcion entrega(int producto, int cantidad) {
        return new SolicitudRecepcion(
                List.of(new SolicitudRecepcion.LineaRecepcion(producto, cantidad)),
                "llegó completa", "REM-001");
    }

    // --------------------------------------------------------- RF-22

    /**
     * El criterio del requisito, tal cual: «se verifica que el inventario
     * disponible no cambia tras registrarla». Aquí se comprueba de la
     * forma más fuerte posible: que el servicio de inventario no recibe
     * ni una llamada.
     */
    @Test
    void registrarUnaOrdenNoTocaElInventario() {
        servicio.registrar(orden(linea(PC, 100), linea(MON, 5)));

        verifyNoInteractions(inventario);
    }

    @Test
    void laOrdenNaceAbiertaConUnaLineaPorProducto() {
        servicio.registrar(orden(linea(PC, 100), linea(MON, 5)));

        verify(compras).insertarOrden(EMPRESA, 2, USUARIO, "OC-001", "primera compra");
        verify(compras).insertarItem(EMPRESA, ORDEN, PC, 100, new BigDecimal("1500.00"));
        verify(compras).insertarItem(EMPRESA, ORDEN, MON, 5, new BigDecimal("1500.00"));
        // El estado no se escribe: la columna lo pone en ABIERTA por omisión
        // y aceptarlo desde fuera permitiría registrar una ya recibida.
        verify(compras, never()).fijarEstado(anyInt(), anyLong(), any());
    }

    @Test
    void unConsecutivoRepetidoSeRechazaAntesDeEscribir() {
        when(compras.existeConsecutivo(EMPRESA, "OC-001")).thenReturn(true);

        var e = assertThrows(RegistroRepetidoException.class,
                () -> servicio.registrar(orden(linea(PC, 100))));

        assertTrue(e.getMessage().contains("OC-001"));
        verify(compras, never()).insertarOrden(anyInt(), anyInt(), anyInt(), any(), any());
        verify(compras, never()).insertarItem(anyInt(), anyLong(), anyInt(), anyInt(), any());
    }

    @Test
    void dosLineasDelMismoProductoSeRechazanDiciendoCual() {
        var e = assertThrows(RegistroRepetidoException.class,
                () -> servicio.registrar(orden(linea(PC, 100), linea(PC, 20))));

        assertTrue(e.getMessage().contains(String.valueOf(PC)));
        verify(compras, never()).insertarOrden(anyInt(), anyInt(), anyInt(), any(), any());
    }

    // --------------------------------------------------------- RF-23

    /** El ejemplo literal del requisito: 100 pedidas, 60 y luego 40. */
    @Test
    void cienPedidasRecibidasEnSesentaYCuarentaCierranLaOrden() {
        when(compras.bloquearOrden(EMPRESA, ORDEN)).thenReturn(Optional.of("ABIERTA"));
        when(compras.bloquearLineas(EMPRESA, ORDEN))
                .thenReturn(List.of(new LineaBloqueada(1L, PC, 100, 0)));

        servicio.recibir(ORDEN, entrega(PC, 60));

        verify(compras).sumarRecibido(EMPRESA, 1L, 60);
        verify(inventario).ingresarPorRecepcion(PC, 60, "REM-001", RECEPCION);
        // Falta algo: la orden queda parcial, no vuelve a abierta.
        verify(compras).fijarEstado(EMPRESA, ORDEN, "PARCIAL");

        // La segunda entrega, ya con sesenta recibidas.
        when(compras.bloquearOrden(EMPRESA, ORDEN)).thenReturn(Optional.of("PARCIAL"));
        when(compras.bloquearLineas(EMPRESA, ORDEN))
                .thenReturn(List.of(new LineaBloqueada(1L, PC, 100, 60)));

        servicio.recibir(ORDEN, entrega(PC, 40));

        verify(compras).sumarRecibido(EMPRESA, 1L, 40);
        verify(inventario).ingresarPorRecepcion(PC, 40, "REM-001", RECEPCION);
        verify(compras).fijarEstado(EMPRESA, ORDEN, "RECIBIDA");
    }

    @Test
    void recibirMasDeLoPedidoLoDiceConLasTresCifras() {
        when(compras.bloquearOrden(EMPRESA, ORDEN)).thenReturn(Optional.of("PARCIAL"));
        when(compras.bloquearLineas(EMPRESA, ORDEN))
                .thenReturn(List.of(new LineaBloqueada(1L, PC, 100, 60)));

        var e = assertThrows(RecepcionInvalidaException.class,
                () -> servicio.recibir(ORDEN, entrega(PC, 50)));

        assertTrue(e.getMessage().contains("100"), "lo pedido");
        assertTrue(e.getMessage().contains("60"), "lo ya recibido");
        assertTrue(e.getMessage().contains("50"), "lo que llega");
        assertTrue(e.getMessage().contains("40"), "lo que cabe");
        // Nada se escribió: ni la recepción, ni el acumulado, ni el movimiento.
        verify(compras, never()).insertarRecepcion(anyInt(), anyLong(), anyInt(), any());
        verify(compras, never()).sumarRecibido(anyInt(), anyLong(), anyInt());
        verifyNoInteractions(inventario);
    }

    @Test
    void unaEntregaDeVariasLineasSeCompruebaEnteraAntesDeEscribirNada() {
        when(compras.bloquearOrden(EMPRESA, ORDEN)).thenReturn(Optional.of("ABIERTA"));
        when(compras.bloquearLineas(EMPRESA, ORDEN)).thenReturn(List.of(
                new LineaBloqueada(1L, PC, 100, 0),
                new LineaBloqueada(2L, MON, 5, 0)));

        // La primera línea cabe y la segunda no.
        var solicitud = new SolicitudRecepcion(List.of(
                new SolicitudRecepcion.LineaRecepcion(PC, 10),
                new SolicitudRecepcion.LineaRecepcion(MON, 50)), null, null);

        assertThrows(RecepcionInvalidaException.class, () -> servicio.recibir(ORDEN, solicitud));

        // Ni la que cabía se escribió: comprobar todo antes de escribir
        // nada es lo que evita media entrega registrada.
        verify(compras, never()).sumarRecibido(anyInt(), anyLong(), anyInt());
        verifyNoInteractions(inventario);
    }

    @Test
    void recibirUnProductoQueLaOrdenNoPideSeRechaza() {
        when(compras.bloquearOrden(EMPRESA, ORDEN)).thenReturn(Optional.of("ABIERTA"));
        when(compras.bloquearLineas(EMPRESA, ORDEN))
                .thenReturn(List.of(new LineaBloqueada(1L, PC, 100, 0)));

        var e = assertThrows(RecepcionInvalidaException.class,
                () -> servicio.recibir(ORDEN, entrega(MON, 1)));

        assertTrue(e.getMessage().contains("no pide"));
        verifyNoInteractions(inventario);
    }

    @Test
    void unaOrdenYaRecibidaNoAdmiteMasEntregas() {
        when(compras.bloquearOrden(EMPRESA, ORDEN)).thenReturn(Optional.of("RECIBIDA"));

        var e = assertThrows(RecepcionInvalidaException.class,
                () -> servicio.recibir(ORDEN, entrega(PC, 1)));

        assertTrue(e.getMessage().contains("ya está recibida"));
        // No se molesta ni en bloquear las líneas.
        verify(compras, never()).bloquearLineas(anyInt(), anyLong());
    }

    @Test
    void unaOrdenAnuladaNoAdmiteEntregas() {
        when(compras.bloquearOrden(EMPRESA, ORDEN)).thenReturn(Optional.of("ANULADA"));

        var e = assertThrows(RecepcionInvalidaException.class,
                () -> servicio.recibir(ORDEN, entrega(PC, 1)));

        assertTrue(e.getMessage().contains("anulada"));
        verifyNoInteractions(inventario);
    }

    @Test
    void unaOrdenDeOtraEmpresaSeResponderaComoInexistente() {
        when(compras.bloquearOrden(EMPRESA, ORDEN)).thenReturn(Optional.empty());

        assertTrue(servicio.recibir(ORDEN, entrega(PC, 1)).isEmpty());

        verify(compras, never()).bloquearLineas(anyInt(), anyLong());
        verifyNoInteractions(inventario);
    }

    /**
     * El movimiento lo pide compras y lo escribe inventario, con el
     * identificador de la recepción. Es lo que mantiene el RF-49 válido
     * sin que este módulo tenga que acordarse de escribir las dos mitades
     * de un cambio de existencia.
     */
    @Test
    void elMovimientoVaAtadoALaRecepcionQueLoOrigino() {
        when(compras.bloquearOrden(EMPRESA, ORDEN)).thenReturn(Optional.of("ABIERTA"));
        when(compras.bloquearLineas(EMPRESA, ORDEN))
                .thenReturn(List.of(new LineaBloqueada(1L, PC, 10, 0)));

        servicio.recibir(ORDEN, entrega(PC, 10));

        verify(compras).insertarRecepcion(EMPRESA, ORDEN, USUARIO, "llegó completa");
        verify(inventario).ingresarPorRecepcion(eq(PC), eq(10), eq("REM-001"), eq(RECEPCION));
        verify(compras).fijarEstado(EMPRESA, ORDEN, "RECIBIDA");
    }

    @Test
    void laOrdenConVariasLineasSoloCierraCuandoNoQuedaNadaPendiente() {
        when(compras.bloquearOrden(EMPRESA, ORDEN)).thenReturn(Optional.of("PARCIAL"));
        when(compras.bloquearLineas(EMPRESA, ORDEN)).thenReturn(List.of(
                new LineaBloqueada(1L, PC, 100, 100),
                new LineaBloqueada(2L, MON, 5, 2)));

        // Llega lo que falta de la segunda línea.
        servicio.recibir(ORDEN, entrega(MON, 3));

        verify(compras).fijarEstado(EMPRESA, ORDEN, "RECIBIDA");
    }

    @Test
    void elListadoYLaBusquedaVanAcotadosALaEmpresaDelContexto() {
        when(compras.listarCabeceras(EMPRESA, "ABIERTA", 50)).thenReturn(List.of());

        servicio.listar("ABIERTA", 50);
        Optional<OrdenDeCompra> una = servicio.buscar(ORDEN);

        verify(compras).listarCabeceras(EMPRESA, "ABIERTA", 50);
        assertTrue(una.isPresent());
        assertEquals("OC-001", una.get().consecutivo());
    }

    @Test
    void elMotivoVacioYLaObservacionVaciaSeGuardanComoNulos() {
        when(compras.bloquearOrden(EMPRESA, ORDEN)).thenReturn(Optional.of("ABIERTA"));
        when(compras.bloquearLineas(EMPRESA, ORDEN))
                .thenReturn(List.of(new LineaBloqueada(1L, PC, 10, 0)));

        servicio.recibir(ORDEN, new SolicitudRecepcion(
                List.of(new SolicitudRecepcion.LineaRecepcion(PC, 10)), "   ", "  "));

        verify(compras).insertarRecepcion(eq(EMPRESA), eq(ORDEN), eq(USUARIO), isNull());
        verify(inventario).ingresarPorRecepcion(eq(PC), eq(10), isNull(), eq(RECEPCION));
    }
}
