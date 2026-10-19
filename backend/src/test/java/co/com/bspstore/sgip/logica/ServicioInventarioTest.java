package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.MovimientoDeInventario;
import co.com.bspstore.sgip.api.dto.SolicitudAjuste;
import co.com.bspstore.sgip.api.dto.SolicitudIngreso;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioInventario;
import co.com.bspstore.sgip.modelo.TipoMovimiento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServicioInventarioTest {

    private static final int EMPRESA = 3;
    private static final int USUARIO = 7;
    private static final int PRODUCTO = 42;
    private static final long MOVIMIENTO = 900L;

    @Mock RepositorioInventario inventario;

    private ServicioInventario servicio;

    @BeforeEach
    void preparar() {
        servicio = new ServicioInventario(inventario);
        var u = new UsuarioAutenticado(USUARIO, "ana@tienda.co", "ADMINISTRADOR", EMPRESA);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);

        when(inventario.insertarMovimiento(anyInt(), anyInt(), anyInt(), any(), anyInt(),
                anyInt(), anyInt(), any(), any(), any())).thenReturn(MOVIMIENTO);
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    /** El movimiento que el repositorio devolverá tras escribirlo. */
    private void elKardexDevuelve(int anterior, int posterior) {
        when(inventario.kardex(EMPRESA, PRODUCTO, 1)).thenReturn(List.of(
                new MovimientoDeInventario(MOVIMIENTO, PRODUCTO, "PC-001", "Portátil",
                        "INGRESO", Math.abs(posterior - anterior), anterior, posterior,
                        null, null, null, USUARIO, "Ana", LocalDateTime.now())));
    }

    // ------------------------------------------------------- RF-48

    @Test
    void elIngresoSubeLaExistenciaYProduceExactamenteUnMovimiento() {
        when(inventario.bloquearSaldo(EMPRESA, PRODUCTO)).thenReturn(Optional.of(10));
        elKardexDevuelve(10, 15);

        var resultado = servicio.ingresar(new SolicitudIngreso(PRODUCTO, 5, "FAC-001", null));

        assertTrue(resultado.isPresent());
        verify(inventario).insertarMovimiento(EMPRESA, PRODUCTO, USUARIO,
                TipoMovimiento.INGRESO, 5, 10, 15, "FAC-001", null, null);
        // El saldo que se fija es el posterior que el movimiento declara,
        // no una suma hecha aparte: así las dos cifras salen del mismo
        // cálculo y no pueden discrepar.
        verify(inventario).fijarSaldo(EMPRESA, PRODUCTO, 15);
    }

    /**
     * El orden es lo que hace correcta la operación. Sin el bloqueo
     * primero, dos ingresos simultáneos leen el mismo saldo anterior y
     * escriben dos filas que arrancan del mismo sitio: cada una coherente
     * por separado, y la suma dejando de dar la existencia.
     */
    @Test
    void seBloqueaElSaldoAntesDeEscribirYSeFijaDespues() {
        when(inventario.bloquearSaldo(EMPRESA, PRODUCTO)).thenReturn(Optional.of(10));
        elKardexDevuelve(10, 15);

        servicio.ingresar(new SolicitudIngreso(PRODUCTO, 5, null, null));

        InOrder orden = inOrder(inventario);
        orden.verify(inventario).bloquearSaldo(EMPRESA, PRODUCTO);
        orden.verify(inventario).insertarMovimiento(anyInt(), anyInt(), anyInt(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), any());
        orden.verify(inventario).fijarSaldo(EMPRESA, PRODUCTO, 15);
    }

    @Test
    void unProductoDeOtraEmpresaNoSeMueveYSeResponderaComoInexistente() {
        when(inventario.bloquearSaldo(EMPRESA, PRODUCTO)).thenReturn(Optional.empty());

        assertTrue(servicio.ingresar(new SolicitudIngreso(PRODUCTO, 5, null, null)).isEmpty());

        verify(inventario, never()).insertarMovimiento(anyInt(), anyInt(), anyInt(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), any());
        verify(inventario, never()).fijarSaldo(anyInt(), anyInt(), anyInt());
    }

    @Test
    void elIngresoSobreUnSaldoNegativoLoAcercaACeroSinSaltarselo() {
        // Una referencia que se debe: la venta sin existencia del RF-42.
        when(inventario.bloquearSaldo(EMPRESA, PRODUCTO)).thenReturn(Optional.of(-4));
        elKardexDevuelve(-4, -1);

        servicio.ingresar(new SolicitudIngreso(PRODUCTO, 3, null, null));

        verify(inventario).insertarMovimiento(EMPRESA, PRODUCTO, USUARIO,
                TipoMovimiento.INGRESO, 3, -4, -1, null, null, null);
        verify(inventario).fijarSaldo(EMPRESA, PRODUCTO, -1);
    }

    // ------------------------------------------------------- RF-49

    @Test
    void elAjusteAlAlzaRegistraLaDiferenciaComoCantidad() {
        when(inventario.bloquearSaldo(EMPRESA, PRODUCTO)).thenReturn(Optional.of(10));
        elKardexDevuelve(10, 13);

        var resultado = servicio.ajustar(
                new SolicitudAjuste(PRODUCTO, 13, "conteo físico de octubre", null));

        assertTrue(resultado.existe());
        assertTrue(resultado.huboCambio());
        verify(inventario).insertarMovimiento(EMPRESA, PRODUCTO, USUARIO,
                TipoMovimiento.AJUSTE, 3, 10, 13, null, "conteo físico de octubre", null);
        verify(inventario).fijarSaldo(EMPRESA, PRODUCTO, 13);
    }

    @Test
    void elAjusteALaBajaTambienRegistraUnaCantidadPositiva() {
        when(inventario.bloquearSaldo(EMPRESA, PRODUCTO)).thenReturn(Optional.of(10));
        elKardexDevuelve(10, 6);

        servicio.ajustar(new SolicitudAjuste(PRODUCTO, 6, "merma por humedad", null));

        // La cantidad es una magnitud: la dirección la lleva el salto,
        // que es lo que la V23 fijó para el ajuste.
        verify(inventario).insertarMovimiento(EMPRESA, PRODUCTO, USUARIO,
                TipoMovimiento.AJUSTE, 4, 10, 6, null, "merma por humedad", null);
    }

    /**
     * Un conteo que coincide no es un error y tampoco es un cambio. No se
     * registra nada porque no pasó nada, y la tabla no admite movimientos
     * de cantidad cero, con razón: una fila que dice que no pasó nada no
     * es un movimiento.
     */
    @Test
    void unConteoQueCoincideNoRegistraNadaYNoEsUnError() {
        when(inventario.bloquearSaldo(EMPRESA, PRODUCTO)).thenReturn(Optional.of(10));

        var resultado = servicio.ajustar(new SolicitudAjuste(PRODUCTO, 10, "conteo", null));

        assertTrue(resultado.existe(), "el producto existe: no es un 404");
        assertFalse(resultado.huboCambio(), "y no hay movimiento que devolver: es un 204");
        verify(inventario, never()).insertarMovimiento(anyInt(), anyInt(), anyInt(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), any());
        verify(inventario, never()).fijarSaldo(anyInt(), anyInt(), anyInt());
    }

    @Test
    void elAjusteDeUnProductoAjenoSeDistingueDelConteoQueCoincide() {
        when(inventario.bloquearSaldo(EMPRESA, PRODUCTO)).thenReturn(Optional.empty());

        var resultado = servicio.ajustar(new SolicitudAjuste(PRODUCTO, 10, "conteo", null));

        assertFalse(resultado.existe());
        assertFalse(resultado.huboCambio());
    }

    /**
     * La validación del cuerpo ya exige el motivo, de modo que esto no
     * debería llegar nunca desde la web. Se comprueba igual porque el
     * módulo de ventas entrará por el mismo sitio sin pasar por ese
     * cuerpo, y entonces esta será la única red.
     */
    @Test
    void unAjusteSinMotivoSeRechazaAunqueLaValidacionDelCuerpoNoLoHayaVisto() {
        when(inventario.bloquearSaldo(EMPRESA, PRODUCTO)).thenReturn(Optional.of(10));

        var e = assertThrows(MovimientoInvalidoException.class,
                () -> servicio.ajustar(new SolicitudAjuste(PRODUCTO, 13, "   ", null)));

        assertTrue(e.getMessage().contains("motivo"));
        verify(inventario, never()).insertarMovimiento(anyInt(), anyInt(), anyInt(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void noSeDejaElSaldoPorDebajoDelPisoDeCordura() {
        when(inventario.bloquearSaldo(EMPRESA, PRODUCTO)).thenReturn(Optional.of(0));

        // La anotación @Min(0) del cuerpo veta un conteo negativo cuando
        // la petición llega por la web, pero construir el registro a mano
        // la esquiva —las anotaciones no se evalúan en el constructor— y
        // eso es justo lo que hace falta aquí: comprobar la red del
        // servicio, que es la única que protegerá a la venta sin
        // existencia cuando entre por este mismo sitio sin cuerpo que
        // validar.
        var e = assertThrows(MovimientoInvalidoException.class,
                () -> servicio.ajustar(new SolicitudAjuste(PRODUCTO, -10000, "dedazo", null)));

        assertTrue(e.getMessage().contains("-9999"));
        verify(inventario, never()).fijarSaldo(anyInt(), anyInt(), anyInt());
    }

    // ------------------------------------------------- lectura y cuadre

    @Test
    void elCuadreUsaLaMismaExpresionQueElGuionDeVerificacion() {
        when(inventario.sumaDeMovimientos(EMPRESA, PRODUCTO)).thenReturn(17);

        assertEquals(17, servicio.sumaDeMovimientos(PRODUCTO));
        verify(inventario).sumaDeMovimientos(EMPRESA, PRODUCTO);
    }

    @Test
    void elKardexYLosUltimosVanAcotadosALaEmpresaDelContexto() {
        when(inventario.kardex(EMPRESA, PRODUCTO, 50)).thenReturn(List.of());
        when(inventario.ultimos(EMPRESA, 50)).thenReturn(List.of());

        servicio.kardex(PRODUCTO, 50);
        servicio.ultimos(50);

        verify(inventario).kardex(EMPRESA, PRODUCTO, 50);
        verify(inventario).ultimos(EMPRESA, 50);
    }

    // ------------------------------------------------ el tipo y su dirección

    @Test
    void laDireccionDeCadaTipoEsLaDeLaRestriccionDeLaV23() {
        assertEquals(15, TipoMovimiento.INGRESO.saldoTras(10, 5));
        assertEquals(15, TipoMovimiento.DEVOLUCION.saldoTras(10, 5));
        assertEquals(5, TipoMovimiento.EGRESO.saldoTras(10, 5));
        // El egreso puede dejar el saldo en negativo: es el RF-42.
        assertEquals(-3, TipoMovimiento.EGRESO.saldoTras(0, 3));
    }

    @Test
    void elAjusteNoCalculaSuSaldoDesdeLaCantidad() {
        // Llamarlo así es un error de programación, no de datos, y falla
        // en el acto en lugar de dejar que la base rechace una fila
        // incoherente más tarde.
        assertThrows(IllegalStateException.class, () -> TipoMovimiento.AJUSTE.saldoTras(10, 2));
    }

    @Test
    void soloElAjusteExigeMotivo() {
        assertTrue(TipoMovimiento.AJUSTE.exigeMotivo());
        assertFalse(TipoMovimiento.INGRESO.exigeMotivo());
        assertFalse(TipoMovimiento.EGRESO.exigeMotivo());
        assertFalse(TipoMovimiento.DEVOLUCION.exigeMotivo());
    }
}
