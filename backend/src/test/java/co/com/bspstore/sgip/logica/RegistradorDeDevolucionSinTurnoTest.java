package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.DevolucionRegistrada;
import co.com.bspstore.sgip.api.dto.MedioDePago;
import co.com.bspstore.sgip.api.dto.Reembolso;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioClientes;
import co.com.bspstore.sgip.datos.RepositorioConsecutivos;
import co.com.bspstore.sgip.datos.RepositorioDevoluciones;
import co.com.bspstore.sgip.datos.RepositorioDevoluciones.DineroSinSaldo;
import co.com.bspstore.sgip.datos.RepositorioDevoluciones.NuevaDevolucion;
import co.com.bspstore.sgip.datos.RepositorioDevoluciones.VentaADevolver;
import co.com.bspstore.sgip.datos.RepositorioInventario;
import co.com.bspstore.sgip.datos.RepositorioVentas;
import co.com.bspstore.sgip.datos.RepositorioVentas.ProductoBloqueado;
import co.com.bspstore.sgip.logica.devolucion.CalculoDeDevolucion.LineaVendida;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La devolución del administrador sin turno abierto (mejoras posteriores a la muestra, M4): se registra con
 * {@code cierre_id} nulo si ningún reembolso es en efectivo; el efectivo exige turno; el vendedor siempre
 * necesita turno aunque traiga el PIN de un administrador.
 *
 * Con los repositorios simulados: lo que decide el registrador, sin base. El comportamiento contra el esquema
 * real está en {@code DevolucionSinTurnoConBaseTest}.
 */
@ExtendWith(MockitoExtension.class)
class RegistradorDeDevolucionSinTurnoTest {

    private static final Integer EMPRESA = 3;
    private static final Integer ADMINISTRADOR = 7;
    private static final Integer CAJERO_VENDEDOR = 8;
    private static final Long VENTA = 90L;
    private static final Integer CLIENTE = 21;
    private static final Integer PRODUCTO = 5;
    private static final Integer EFECTIVO = 1;
    private static final Integer TARJETA = 2;
    private static final Integer SALDO = 4;
    private static final Long TURNO = 400L;

    @Mock ServicioTurnos turnos;
    @Mock RepositorioDevoluciones devoluciones;
    @Mock RepositorioVentas ventas;
    @Mock RepositorioConsecutivos consecutivos;
    @Mock RepositorioInventario inventario;
    @Mock RepositorioClientes clientes;
    @Mock ServicioSaldoAFavor saldos;

    private RegistradorDeDevolucion registrador;

    @BeforeEach
    void preparar() {
        registrador = new RegistradorDeDevolucion(turnos, devoluciones, ventas, consecutivos, inventario, clientes,
                saldos, Clock.fixed(Instant.parse("2026-11-23T15:00:00Z"), ZoneId.of("America/Bogota")));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------- ayudas

    private static void entrar(Integer usuario, String rol) {
        var u = new UsuarioAutenticado(usuario, "prueba@tienda.co", rol, EMPRESA);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    private static Autorizacion autorizadaPorElAdministrador() {
        return new Autorizacion(ADMINISTRADOR, Set.of(ExcepcionAutorizable.DEVOLUCION));
    }

    private static Reembolso efectivo(String monto) {
        return new Reembolso(EFECTIVO, new BigDecimal(monto), null);
    }

    private static Reembolso tarjeta(String monto) {
        return new Reembolso(TARJETA, new BigDecimal(monto), "DATAFONO-1");
    }

    private static Reembolso saldo(String monto) {
        return new Reembolso(SALDO, new BigDecimal(monto), null);
    }

    /** Anula la venta entera, de 10 000, con esos reembolsos. */
    private static SolicitudDevolucion anulacion(Reembolso... reembolsos) {
        return new SolicitudDevolucion(UUID.randomUUID(), VENTA, List.of(), "El cliente se arrepintió",
                List.of(reembolsos), null);
    }

    /** Una venta pagada de 10 000 con una sola línea, con lo que el registrador le pide al resto. */
    private void unaVentaPagada() {
        when(devoluciones.bloquearVenta(EMPRESA, VENTA)).thenReturn(Optional.of(
                new VentaADevolver(VENTA, CLIENTE, new BigDecimal("10000.00"), "REGISTRADA", "PAGADA")));
        when(devoluciones.buscarPorClave(eq(EMPRESA), anyString())).thenReturn(Optional.empty());
        when(devoluciones.lineasDeLaVenta(EMPRESA, VENTA)).thenReturn(List.of(
                new LineaVendida(PRODUCTO, 1, new BigDecimal("10000.00"), 0, BigDecimal.ZERO)));
        when(ventas.mediosPorId(eq(EMPRESA), anyCollection())).thenReturn(List.of(
                new MedioDePago(EFECTIVO, "Efectivo", TipoMedio.EFECTIVO, true),
                new MedioDePago(TARJETA, "Tarjeta", TipoMedio.TARJETA, true),
                new MedioDePago(SALDO, "Saldo a favor", TipoMedio.SALDO_A_FAVOR, false)));
        lenient().when(devoluciones.dineroSinSaldo(EMPRESA, VENTA)).thenReturn(
                new DineroSinSaldo(new BigDecimal("10000.00"), BigDecimal.ZERO));
        lenient().when(clientes.buscar(EMPRESA, CLIENTE)).thenReturn(Optional.of(new Cliente(CLIENTE, "Ana Pérez",
                TipoDocumento.CC, "123456", null, null, null, null, false)));
        lenient().when(ventas.bloquearProductos(eq(EMPRESA), anyCollection())).thenReturn(List.of(
                new ProductoBloqueado(PRODUCTO, "A", 4, new BigDecimal("1000.00"))));
        lenient().when(consecutivos.tomarSiguiente(eq(EMPRESA), any())).thenReturn(1L);
        lenient().when(devoluciones.insertarDevolucion(any())).thenReturn(55L);
    }

    private NuevaDevolucion laInsertada() {
        ArgumentCaptor<NuevaDevolucion> insertada = ArgumentCaptor.forClass(NuevaDevolucion.class);
        verify(devoluciones).insertarDevolucion(insertada.capture());
        return insertada.getValue();
    }

    private void nadaSeGuardo() {
        verify(devoluciones, never()).insertarDevolucion(any());
        verify(devoluciones, never()).insertarItems(anyInt(), anyLong(), any());
        verify(devoluciones, never()).insertarReembolsos(anyInt(), anyLong(), any());
        verify(saldos, never()).abonar(anyInt(), anyInt(), any());
        verify(inventario, never()).registrarReingresos(anyInt(), anyInt(), anyString(), any());
    }

    // ------------------------------------------------------- el administrador sin turno

    @Test
    void adminSinTurnoConTarjetaRegistra() {
        entrar(ADMINISTRADOR, "ADMINISTRADOR");
        unaVentaPagada();
        when(turnos.intentarTurnoAbierto()).thenReturn(Optional.empty());

        DevolucionRegistrada r = registrador.registrar(anulacion(tarjeta("10000")), autorizadaPorElAdministrador())
                .devolucion();

        assertEquals(55L, r.id());
        assertNull(laInsertada().cierreId(), "sin turno la devolución queda sin cierre");
        verify(devoluciones).insertarReembolsos(eq(EMPRESA), eq(55L), any());
    }

    @Test
    void adminSinTurnoConSaldoRegistra() {
        entrar(ADMINISTRADOR, "ADMINISTRADOR");
        unaVentaPagada();
        when(turnos.intentarTurnoAbierto()).thenReturn(Optional.empty());

        registrador.registrar(anulacion(saldo("10000")), autorizadaPorElAdministrador());

        assertNull(laInsertada().cierreId());
        verify(saldos).abonar(eq(EMPRESA), eq(CLIENTE), eq(new BigDecimal("10000.00")));
    }

    @Test
    void adminSinTurnoTarjetaYSaldoRegistra() {
        entrar(ADMINISTRADOR, "ADMINISTRADOR");
        unaVentaPagada();
        when(turnos.intentarTurnoAbierto()).thenReturn(Optional.empty());

        registrador.registrar(anulacion(tarjeta("6000"), saldo("4000")), autorizadaPorElAdministrador());

        assertNull(laInsertada().cierreId());
        verify(saldos).abonar(eq(EMPRESA), eq(CLIENTE), eq(new BigDecimal("4000.00")));
    }

    @Test
    void adminSinTurnoConEfectivoEs409TurnoRequerido() {
        entrar(ADMINISTRADOR, "ADMINISTRADOR");
        unaVentaPagada();
        when(turnos.intentarTurnoAbierto()).thenReturn(Optional.empty());

        TurnoRequeridoParaEfectivoException e = assertThrows(TurnoRequeridoParaEfectivoException.class,
                () -> registrador.registrar(anulacion(tarjeta("6000"), efectivo("4000")), autorizadaPorElAdministrador()));

        assertEquals("Abre un turno de caja para devolver efectivo.", e.getMessage());
        nadaSeGuardo();
    }

    // ------------------------------------------------------- el vendedor sin turno

    @Test
    void vendedorSinTurnoConPinDeAdminYTarjetaEs409SinTurno() {
        entrar(CAJERO_VENDEDOR, "VENDEDOR");
        unaVentaPagada();
        when(turnos.intentarTurnoAbierto()).thenReturn(Optional.empty());

        assertThrows(SinTurnoAbiertoException.class,
                () -> registrador.registrar(anulacion(tarjeta("10000")), autorizadaPorElAdministrador()));

        nadaSeGuardo();
    }

    // ------------------------------------------------------- el orden de los errores

    @Test
    void ventaDeOtraEmpresaEs404AntesQueElTurno() {
        entrar(ADMINISTRADOR, "ADMINISTRADOR");
        when(turnos.intentarTurnoAbierto()).thenReturn(Optional.empty());
        when(devoluciones.bloquearVenta(EMPRESA, VENTA)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class,
                () -> registrador.registrar(anulacion(efectivo("10000")), autorizadaPorElAdministrador()));

        nadaSeGuardo();
    }

    // ------------------------------------------------------- con turno, como hoy

    @Test
    void adminConTurnoYTarjetaSigueAsociandoElTurno() {
        entrar(ADMINISTRADOR, "ADMINISTRADOR");
        unaVentaPagada();
        when(turnos.intentarTurnoAbierto()).thenReturn(Optional.of(TURNO));

        registrador.registrar(anulacion(tarjeta("10000")), autorizadaPorElAdministrador());

        assertEquals(TURNO, laInsertada().cierreId());
    }
}
