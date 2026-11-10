package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.MedioDePago;
import co.com.bspstore.sgip.api.dto.SolicitudMedioPago;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioMediosPago;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Los medios de pago de la empresa (RF-43). La empresa sale del contexto y no
 * de un parámetro, y el medio de sistema «Saldo a favor» lo gestiona el
 * sistema: ni se crea, ni se activa, ni se desactiva a mano.
 */
@ExtendWith(MockitoExtension.class)
class ServicioMediosPagoTest {

    private static final int EMPRESA = 3;

    private static final MedioDePago EFECTIVO = new MedioDePago(10, "Efectivo", TipoMedio.EFECTIVO, true);
    private static final MedioDePago TARJETA = new MedioDePago(11, "Tarjeta", TipoMedio.TARJETA, true);
    private static final MedioDePago TRANSFERENCIA =
            new MedioDePago(12, "Transferencia", TipoMedio.TRANSFERENCIA, false);
    private static final MedioDePago SALDO =
            new MedioDePago(13, "Saldo a favor", TipoMedio.SALDO_A_FAVOR, false);

    @Mock RepositorioMediosPago medios;
    @InjectMocks ServicioMediosPago servicio;

    @BeforeEach
    void comoEmpresa() {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", EMPRESA);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------- lectura

    @Test
    void losActivosSonLosDeLaEmpresaDelContexto() {
        when(medios.listarActivos(EMPRESA)).thenReturn(List.of(EFECTIVO, TARJETA));

        assertEquals(List.of(EFECTIVO, TARJETA), servicio.activos());
    }

    @Test
    void todosIncluyeElMedioDeSistema() {
        when(medios.listar(EMPRESA)).thenReturn(List.of(EFECTIVO, TARJETA, TRANSFERENCIA, SALDO));

        assertTrue(servicio.todos().contains(SALDO));
    }

    // ---------------------------------------------------------------- crear

    @Test
    void creaUnMedioDeLaEmpresaConElNombreRecortado() {
        when(medios.existeNombre(EMPRESA, "Nequi")).thenReturn(false);
        when(medios.insertar(EMPRESA, "Nequi", TipoMedio.TRANSFERENCIA)).thenReturn(20);
        MedioDePago creado = new MedioDePago(20, "Nequi", TipoMedio.TRANSFERENCIA, true);
        when(medios.buscar(EMPRESA, 20)).thenReturn(Optional.of(creado));

        assertEquals(creado, servicio.crear(new SolicitudMedioPago("  Nequi ", TipoMedio.TRANSFERENCIA)));
    }

    @Test
    void unNombreRepetidoEnLaEmpresaEsUnConflicto() {
        when(medios.existeNombre(EMPRESA, "Efectivo")).thenReturn(true);

        NombreRepetidoException e = assertThrows(NombreRepetidoException.class,
                () -> servicio.crear(new SolicitudMedioPago("Efectivo", TipoMedio.EFECTIVO)));
        // Sin eco del dato enviado.
        assertEquals("Ya existe un medio de pago con ese nombre.", e.getMessage());
        verify(medios, never()).insertar(anyInt(), any(), any());
    }

    @Test
    void elSaldoAFavorNoSePuedeCrearAMano() {
        assertThrows(MedioDePagoInvalidoException.class,
                () -> servicio.crear(new SolicitudMedioPago("Saldo", TipoMedio.SALDO_A_FAVOR)));
        verify(medios, never()).insertar(anyInt(), any(), any());
    }

    // -------------------------------------------------------- cambiarActivo

    @Test
    void desactivaUnMedioSiQuedaOtroActivo() {
        when(medios.bloquearDeLaEmpresa(EMPRESA)).thenReturn(List.of(EFECTIVO, TARJETA, SALDO));
        when(medios.buscar(EMPRESA, 10)).thenReturn(Optional.of(
                new MedioDePago(10, "Efectivo", TipoMedio.EFECTIVO, false)));

        MedioDePago resultado = servicio.cambiarActivo(10, false);

        verify(medios).actualizarActivo(EMPRESA, 10, false);
        assertFalse(resultado.activo());
    }

    @Test
    void desactivarElUnicoMedioActivoEs409() {
        // El medio de sistema no cuenta como activo, aunque estuviera marcado así.
        MedioDePago saldoActivo = new MedioDePago(13, "Saldo a favor", TipoMedio.SALDO_A_FAVOR, true);
        when(medios.bloquearDeLaEmpresa(EMPRESA))
                .thenReturn(List.of(EFECTIVO, TRANSFERENCIA, saldoActivo));

        assertThrows(UltimoMedioActivoException.class, () -> servicio.cambiarActivo(10, false));
        verify(medios, never()).actualizarActivo(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void activaUnMedioInactivo() {
        when(medios.bloquearDeLaEmpresa(EMPRESA)).thenReturn(List.of(EFECTIVO, TRANSFERENCIA, SALDO));
        when(medios.buscar(EMPRESA, 12)).thenReturn(Optional.of(
                new MedioDePago(12, "Transferencia", TipoMedio.TRANSFERENCIA, true)));

        assertTrue(servicio.cambiarActivo(12, true).activo());
        verify(medios).actualizarActivo(EMPRESA, 12, true);
    }

    @Test
    void ponerElEstadoQueYaTieneNoEscribeNada() {
        when(medios.bloquearDeLaEmpresa(EMPRESA)).thenReturn(List.of(EFECTIVO, TARJETA, SALDO));

        assertEquals(EFECTIVO, servicio.cambiarActivo(10, true));
        verify(medios, never()).actualizarActivo(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void unMedioDeOtraEmpresaEs404() {
        // El bloqueo trae solo los medios de la empresa 3: el 99 es de otra, o no existe.
        when(medios.bloquearDeLaEmpresa(EMPRESA)).thenReturn(List.of(EFECTIVO, TARJETA, SALDO));

        assertThrows(RecursoNoEncontradoException.class, () -> servicio.cambiarActivo(99, false));
        verify(medios).bloquearDeLaEmpresa(EMPRESA);
        verify(medios, never()).actualizarActivo(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void elSaldoAFavorNoSePuedeActivarNiDesactivarAMano() {
        when(medios.bloquearDeLaEmpresa(EMPRESA)).thenReturn(List.of(EFECTIVO, TARJETA, SALDO));

        assertThrows(MedioDePagoInvalidoException.class, () -> servicio.cambiarActivo(13, true));
        assertThrows(MedioDePagoInvalidoException.class, () -> servicio.cambiarActivo(13, false));
        verify(medios, never()).actualizarActivo(anyInt(), anyInt(), anyBoolean());
    }

    // ---------------------------------------------------------------- el alta

    @Test
    void losMediosDelAltaSonTresActivosYElSaldoAFavorInactivo() {
        assertEquals(List.of(
                        new MedioDePago(null, "Efectivo", TipoMedio.EFECTIVO, true),
                        new MedioDePago(null, "Tarjeta", TipoMedio.TARJETA, true),
                        new MedioDePago(null, "Transferencia", TipoMedio.TRANSFERENCIA, true),
                        new MedioDePago(null, "Saldo a favor", TipoMedio.SALDO_A_FAVOR, false)),
                ServicioMediosPago.MEDIOS_DEL_ALTA);
    }
}
