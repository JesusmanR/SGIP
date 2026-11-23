package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.ResumenDeTurno;
import co.com.bspstore.sgip.api.dto.SolicitudCierre;
import co.com.bspstore.sgip.api.dto.Turno;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioTurnos;
import co.com.bspstore.sgip.datos.RepositorioTurnos.TurnoDeLista;
import co.com.bspstore.sgip.datos.RepositorioTurnos.TurnoPorCerrar;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/**
 * Cerrar el turno de caja (RF-45), sus reglas sin base: el efectivo esperado, la diferencia que exige
 * explicación, la hora de Bogotá, el resumen del turno abierto y qué turnos ve cada rol.
 *
 * Contra un repositorio simulado. Lo que solo se ve con el motor (los bloqueos, qué pagos cuenta cada
 * turno, la carrera con una venta) está en {@code CierreDeTurnoConBaseTest}.
 */
@ExtendWith(MockitoExtension.class)
class ServicioTurnosCierreTest {

    private static final int EMPRESA = 3;
    private static final int CAJERO = 7;
    private static final long TURNO = 11L;
    /** 13:30:45.678 en UTC son las 08:30:45 en Bogotá. */
    private static final Instant AHORA = Instant.parse("2026-11-12T13:30:45.678Z");
    private static final LocalDateTime CIERRE_EN_BOGOTA = LocalDateTime.of(2026, 11, 12, 8, 30, 45);
    private static final OffsetDateTime APERTURA =
            OffsetDateTime.of(LocalDateTime.of(2026, 11, 12, 6, 0, 0), ZoneOffset.ofHours(-5));
    private static final OffsetDateTime CIERRE =
            OffsetDateTime.of(CIERRE_EN_BOGOTA, ZoneOffset.ofHours(-5));

    private static final Turno ABIERTO = new Turno(TURNO, CAJERO, "Ana Ruiz", APERTURA, new BigDecimal("100000"), null);
    private static final Turno CERRADO = new Turno(TURNO, CAJERO, "Ana Ruiz", APERTURA, new BigDecimal("100000"), CIERRE);

    @Mock RepositorioTurnos turnos;

    private ServicioTurnos servicio;

    @BeforeEach
    void comoCajero() {
        entrar("VENDEDOR");
        servicio = new ServicioTurnos(turnos, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private static void entrar(String rol) {
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(
                new UsuarioAutenticado(CAJERO, "ana@tienda.co", rol, EMPRESA), null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    private static Map<TipoMedio, BigDecimal> pagos(String efectivo, String tarjeta) {
        Map<TipoMedio, BigDecimal> m = new EnumMap<>(TipoMedio.class);
        if (efectivo != null) {
            m.put(TipoMedio.EFECTIVO, new BigDecimal(efectivo));
        }
        if (tarjeta != null) {
            m.put(TipoMedio.TARJETA, new BigDecimal(tarjeta));
        }
        return m;
    }

    /** El turno de la base de 100 000 con 70 000 en efectivo, 30 000 en tarjeta y 5 000 reembolsados en efectivo. */
    private void turnoConMovimientos() {
        when(turnos.bloquearParaCerrar(EMPRESA, CAJERO))
                .thenReturn(Optional.of(new TurnoPorCerrar(TURNO, new BigDecimal("100000"))));
        when(turnos.pagosPorMedio(EMPRESA, TURNO)).thenReturn(pagos("70000", "30000"));
        when(turnos.reembolsosEnEfectivo(EMPRESA, TURNO)).thenReturn(new BigDecimal("5000"));
    }

    /** Lo que lee el cierre que llega a buen término; los casos que terminan en 400 no llegan a leerlo. */
    private void elTurnoQuedaCerrado() {
        when(turnos.buscar(EMPRESA, TURNO)).thenReturn(Optional.of(CERRADO));
    }

    private static SolicitudCierre contado(String monto, String observacion) {
        return new SolicitudCierre(new BigDecimal(monto), observacion);
    }

    private static void igual(String esperado, BigDecimal real) {
        assertEquals(0, new BigDecimal(esperado).compareTo(real), "se esperaba " + esperado + " y fue " + real);
    }

    // ---------------------------------------------------------------- cerrar

    @Test
    void elEsperadoEsLaBaseMasElEfectivoMenosLosReembolsosEnEfectivo() {
        turnoConMovimientos();
        elTurnoQuedaCerrado();

        ResumenDeTurno r = servicio.cerrar(contado("165000", null));

        igual("165000", r.efectivoEsperado());
        igual("165000", r.efectivoContado());
        igual("0", r.diferencia());
        igual("100000", r.baseEfectivo());
        assertEquals(TURNO, r.id());
        assertEquals("Ana Ruiz", r.cajero());
        assertEquals(APERTURA, r.apertura());
        assertEquals(CIERRE, r.cierre());
    }

    @Test
    void elCierreGuardaLoEsperadoLoContadoYLaHoraDeBogotaAunqueLaJvmEsteEnUtc() {
        turnoConMovimientos();
        elTurnoQuedaCerrado();

        servicio.cerrar(contado("165000", null));

        ArgumentCaptor<BigDecimal> esperado = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> contado = ArgumentCaptor.forClass(BigDecimal.class);
        verify(turnos).registrarCierre(eq(EMPRESA), eq(TURNO), eq(CIERRE_EN_BOGOTA), esperado.capture(),
                contado.capture(), isNull());
        igual("165000", esperado.getValue());
        igual("165000", contado.getValue());
    }

    @Test
    void sinPagosNiReembolsosElEsperadoEsLaBase() {
        when(turnos.bloquearParaCerrar(EMPRESA, CAJERO))
                .thenReturn(Optional.of(new TurnoPorCerrar(TURNO, new BigDecimal("100000"))));
        when(turnos.pagosPorMedio(EMPRESA, TURNO)).thenReturn(new EnumMap<>(TipoMedio.class));
        when(turnos.reembolsosEnEfectivo(EMPRESA, TURNO)).thenReturn(BigDecimal.ZERO);
        elTurnoQuedaCerrado();

        ResumenDeTurno r = servicio.cerrar(contado("100000", null));

        igual("100000", r.efectivoEsperado());
        assertTrue(r.porMedio().isEmpty(), "nada cobrado, nada por medio");
    }

    @Test
    void porMedioSoloTraeLoCobradoYNoRestaLosReembolsos() {
        turnoConMovimientos();
        elTurnoQuedaCerrado();

        ResumenDeTurno r = servicio.cerrar(contado("165000", null));

        // El reembolso de 5 000 baja el efectivo esperado, no el efectivo cobrado.
        assertEquals(2, r.porMedio().size());
        igual("70000", r.porMedio().get(TipoMedio.EFECTIVO));
        igual("30000", r.porMedio().get(TipoMedio.TARJETA));
        assertFalse(r.porMedio().containsKey(TipoMedio.TRANSFERENCIA));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void unaDiferenciaSinObservacionEs400YElTurnoQuedaAbierto(String observacion) {
        turnoConMovimientos();

        DatoDeTurnoInvalidoException e = assertThrows(DatoDeTurnoInvalidoException.class,
                () -> servicio.cerrar(contado("160000", observacion)));

        assertFalse(e.getMessage().contains("160000"), "sin eco del dato enviado");
        verify(turnos, never()).registrarCierre(anyInt(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void tambienSobrarEfectivoExigeObservacion() {
        turnoConMovimientos();

        assertThrows(DatoDeTurnoInvalidoException.class, () -> servicio.cerrar(contado("167000", null)));

        verify(turnos, never()).registrarCierre(anyInt(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void conDiferenciaYObservacionCierraYLaGuardaSinEspaciosSobrantes() {
        turnoConMovimientos();
        elTurnoQuedaCerrado();

        ResumenDeTurno r = servicio.cerrar(contado("160000", "  Faltan 5 000, un billete roto  "));

        igual("-5000", r.diferencia());
        assertEquals("Faltan 5 000, un billete roto", r.observacion());
        verify(turnos).registrarCierre(eq(EMPRESA), eq(TURNO), eq(CIERRE_EN_BOGOTA), any(), any(),
                eq("Faltan 5 000, un billete roto"));
    }

    @Test
    void conLaDiferenciaEnCeroLaObservacionNoSeExigeYUnaEnBlancoSeGuardaNula() {
        turnoConMovimientos();
        elTurnoQuedaCerrado();

        ResumenDeTurno r = servicio.cerrar(contado("165000", "   "));

        assertNull(r.observacion());
        verify(turnos).registrarCierre(eq(EMPRESA), eq(TURNO), eq(CIERRE_EN_BOGOTA), any(), any(), isNull());
    }

    @Test
    void sinTurnoAbiertoCerrarEs409() {
        when(turnos.bloquearParaCerrar(EMPRESA, CAJERO)).thenReturn(Optional.empty());

        SinTurnoAbiertoException e = assertThrows(SinTurnoAbiertoException.class,
                () -> servicio.cerrar(contado("0", null)));

        // Cerrar tiene su propio texto: el de las ventas dice «abre un turno», que aquí no ayuda.
        assertEquals("No tienes un turno abierto para cerrar.", e.getMessage());
        verify(turnos, never()).registrarCierre(anyInt(), anyLong(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "100.5", "50000.01", "1000000000000", "1E+20"})
    void unContadoQueNoSonPesosEnterosEs400SinTocarLaBase(String monto) {
        DatoDeTurnoInvalidoException e = assertThrows(DatoDeTurnoInvalidoException.class,
                () -> servicio.cerrar(contado(monto, "Explicación")));

        assertFalse(e.getMessage().contains(monto), "sin eco del dato enviado");
        verifyNoInteractions(turnos);
    }

    @Test
    void sinContadoOSinSolicitudEs400() {
        assertThrows(DatoDeTurnoInvalidoException.class, () -> servicio.cerrar(new SolicitudCierre(null, null)));
        assertThrows(DatoDeTurnoInvalidoException.class, () -> servicio.cerrar(null));
        verifyNoInteractions(turnos);
    }

    @Test
    void unContadoConCerosDecimalesSeAcepta() {
        turnoConMovimientos();
        elTurnoQuedaCerrado();

        ResumenDeTurno r = servicio.cerrar(contado("165000.00", null));

        igual("165000", r.efectivoContado());
    }

    @Test
    void unaObservacionDeMasDe200CaracteresEs400() {
        DatoDeTurnoInvalidoException e = assertThrows(DatoDeTurnoInvalidoException.class,
                () -> servicio.cerrar(contado("165000", "x".repeat(201))));

        assertFalse(e.getMessage().contains("xxxx"), "sin eco del dato enviado");
        verifyNoInteractions(turnos);
    }

    @Test
    void unaObservacionDeExactamente200CaracteresSeAcepta() {
        turnoConMovimientos();
        elTurnoQuedaCerrado();

        servicio.cerrar(contado("165000", "x".repeat(200)));

        verify(turnos).registrarCierre(eq(EMPRESA), eq(TURNO), any(), any(), any(), eq("x".repeat(200)));
    }

    // -------------------------------------------------------- resumen actual

    @Test
    void elResumenDelTurnoAbiertoCalculaElEsperadoSinCerrarNada() {
        when(turnos.abiertoDe(EMPRESA, CAJERO)).thenReturn(Optional.of(ABIERTO));
        when(turnos.pagosPorMedio(EMPRESA, TURNO)).thenReturn(pagos("70000", "30000"));
        when(turnos.reembolsosEnEfectivo(EMPRESA, TURNO)).thenReturn(new BigDecimal("5000"));

        ResumenDeTurno r = servicio.resumenActual();

        igual("165000", r.efectivoEsperado());
        igual("100000", r.baseEfectivo());
        assertNull(r.efectivoContado());
        assertNull(r.diferencia());
        assertNull(r.cierre());
        assertNull(r.observacion());
        assertEquals(CAJERO, ABIERTO.cajeroId());
        assertEquals("Ana Ruiz", r.cajero());
        igual("30000", r.porMedio().get(TipoMedio.TARJETA));
        verify(turnos, never()).registrarCierre(anyInt(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void sinTurnoAbiertoElResumenEs404() {
        when(turnos.abiertoDe(EMPRESA, CAJERO)).thenReturn(Optional.empty());

        RecursoNoEncontradoException e = assertThrows(RecursoNoEncontradoException.class, () -> servicio.resumenActual());

        assertTrue(e.getMessage().toLowerCase().contains("turno"));
    }

    // ---------------------------------------------------------------- listar

    private static TurnoDeLista cerradoConEsperado(long id, int cajero, String esperado, String contado,
                                                   String diferencia, String observacion) {
        return new TurnoDeLista(new Turno(id, cajero, "Cajero " + cajero, APERTURA, new BigDecimal("100000"), CIERRE),
                new BigDecimal(esperado), new BigDecimal(contado), new BigDecimal(diferencia), observacion);
    }

    private static TurnoDeLista abierto(long id, int cajero) {
        return new TurnoDeLista(new Turno(id, cajero, "Cajero " + cajero, APERTURA, new BigDecimal("50000"), null),
                BigDecimal.ZERO, null, null, null);
    }

    @Test
    void elAdministradorListaLosTurnosDeTodosLosCajerosDentroDeLasFechas() {
        entrar("ADMINISTRADOR");
        LocalDateTime desde = LocalDateTime.of(2026, 11, 1, 0, 0);
        LocalDateTime hastaExclusivo = LocalDateTime.of(2026, 11, 13, 0, 0);
        when(turnos.turnosDelPeriodo(EMPRESA, desde, hastaExclusivo, null))
                .thenReturn(List.of(abierto(12L, 8), cerradoConEsperado(11L, 7, "165000", "160000", "-5000", "Faltan 5 000")));
        when(turnos.pagosPorMedioDelPeriodo(EMPRESA, desde, hastaExclusivo, null)).thenReturn(Map.of(
                11L, pagos("70000", "30000"), 12L, pagos("20000", null)));
        when(turnos.reembolsosEnEfectivoDelPeriodo(EMPRESA, desde, hastaExclusivo, null))
                .thenReturn(Map.of(11L, new BigDecimal("5000"), 12L, new BigDecimal("2000")));

        List<ResumenDeTurno> lista = servicio.listar(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 12));

        assertEquals(2, lista.size());
        // El abierto: el esperado vivo (base 50 000 + 20 000 - 2 000), sin contado ni diferencia.
        ResumenDeTurno vivo = lista.get(0);
        assertEquals(12L, vivo.id());
        igual("68000", vivo.efectivoEsperado());
        assertNull(vivo.efectivoContado());
        assertNull(vivo.diferencia());
        assertNull(vivo.cierre());
        // El cerrado: lo que quedó guardado al cerrar, con su diferencia.
        ResumenDeTurno cerrado = lista.get(1);
        igual("165000", cerrado.efectivoEsperado());
        igual("160000", cerrado.efectivoContado());
        igual("-5000", cerrado.diferencia());
        assertEquals("Faltan 5 000", cerrado.observacion());
        igual("30000", cerrado.porMedio().get(TipoMedio.TARJETA));
    }

    @Test
    void laDiferenciaDeUnTurnoCerradoSeLeeDeLaColumnaYNoSeCalculaEnJava() {
        entrar("ADMINISTRADOR");
        LocalDateTime desde = LocalDateTime.of(2026, 11, 12, 0, 0);
        LocalDateTime hastaExclusivo = LocalDateTime.of(2026, 11, 13, 0, 0);
        // Los valores no cuadran a propósito: contado - esperado sería -5 000 y la columna dice otra cosa.
        when(turnos.turnosDelPeriodo(EMPRESA, desde, hastaExclusivo, null))
                .thenReturn(List.of(cerradoConEsperado(11L, 7, "165000", "160000", "-4321", "Explicación")));
        when(turnos.pagosPorMedioDelPeriodo(EMPRESA, desde, hastaExclusivo, null)).thenReturn(Map.of());
        when(turnos.reembolsosEnEfectivoDelPeriodo(EMPRESA, desde, hastaExclusivo, null)).thenReturn(Map.of());

        List<ResumenDeTurno> lista = servicio.listar(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12));

        igual("-4321", lista.get(0).diferencia());
    }

    @Test
    void unTurnoCerradoSinContadoNoRevientaLaLista() {
        entrar("ADMINISTRADOR");
        LocalDateTime desde = LocalDateTime.of(2026, 11, 12, 0, 0);
        LocalDateTime hastaExclusivo = LocalDateTime.of(2026, 11, 13, 0, 0);
        // Un cierre sin contado no debería existir, pero una fila rara no tumba la lista de todos los turnos.
        TurnoDeLista raro = new TurnoDeLista(new Turno(11L, 7, "Cajero 7", APERTURA, new BigDecimal("100000"), CIERRE),
                new BigDecimal("100000"), null, null, null);
        when(turnos.turnosDelPeriodo(EMPRESA, desde, hastaExclusivo, null)).thenReturn(List.of(raro));
        when(turnos.pagosPorMedioDelPeriodo(EMPRESA, desde, hastaExclusivo, null)).thenReturn(Map.of());
        when(turnos.reembolsosEnEfectivoDelPeriodo(EMPRESA, desde, hastaExclusivo, null)).thenReturn(Map.of());

        List<ResumenDeTurno> lista = servicio.listar(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12));

        assertNull(lista.get(0).efectivoContado());
        assertNull(lista.get(0).diferencia());
    }

    @Test
    void elVendedorSoloListaLosSuyosAunqueElWebNoMandeCajero() {
        entrar("VENDEDOR");
        LocalDateTime desde = LocalDateTime.of(2026, 11, 12, 0, 0);
        LocalDateTime hastaExclusivo = LocalDateTime.of(2026, 11, 13, 0, 0);
        when(turnos.turnosDelPeriodo(EMPRESA, desde, hastaExclusivo, CAJERO))
                .thenReturn(List.of(cerradoConEsperado(11L, CAJERO, "100000", "100000", "0", null)));
        when(turnos.pagosPorMedioDelPeriodo(EMPRESA, desde, hastaExclusivo, CAJERO)).thenReturn(Map.of());
        when(turnos.reembolsosEnEfectivoDelPeriodo(EMPRESA, desde, hastaExclusivo, CAJERO)).thenReturn(Map.of());

        List<ResumenDeTurno> lista = servicio.listar(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 12));

        assertEquals(1, lista.size());
        assertTrue(lista.get(0).porMedio().isEmpty());
        verify(turnos, never()).turnosDelPeriodo(eq(EMPRESA), any(), any(), isNull());
    }

    @Test
    void unRangoInvertidoOSinFechasEs400() {
        assertThrows(DatoDeTurnoInvalidoException.class,
                () -> servicio.listar(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 11)));
        assertThrows(DatoDeTurnoInvalidoException.class, () -> servicio.listar(null, LocalDate.of(2026, 11, 11)));
        assertThrows(DatoDeTurnoInvalidoException.class, () -> servicio.listar(LocalDate.of(2026, 11, 11), null));
        verifyNoInteractions(turnos);
    }

    @Test
    void unRangoDeMasDeUnAnioEs400() {
        assertThrows(DatoDeTurnoInvalidoException.class,
                () -> servicio.listar(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 11, 12)));
        verifyNoInteractions(turnos);
    }
}
