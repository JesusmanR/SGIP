package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.SolicitudApertura;
import co.com.bspstore.sgip.api.dto.Turno;
import co.com.bspstore.sgip.config.SinEmpresaException;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioTurnos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * El turno de caja (RF-45): abrirlo, saber cuál es el actual y exigir que haya uno.
 *
 * La empresa y el cajero salen del contexto y no de un parámetro. La hora de
 * apertura es la del reloj de la aplicación en la hora de Bogotá aunque la JVM
 * corra en otra zona (ADR-0030): el reloj de estas pruebas va en UTC a propósito.
 * Ningún mensaje repite el dato enviado.
 */
@ExtendWith(MockitoExtension.class)
class ServicioTurnosTest {

    private static final int EMPRESA = 3;
    private static final int CAJERO = 7;
    /** 13:30:45.678 en UTC son las 08:30:45 en Bogotá. */
    private static final Instant AHORA = Instant.parse("2026-11-12T13:30:45.678Z");
    private static final LocalDateTime APERTURA_EN_BOGOTA = LocalDateTime.of(2026, 11, 12, 8, 30, 45);
    private static final OffsetDateTime APERTURA_CON_DESPLAZAMIENTO =
            OffsetDateTime.of(APERTURA_EN_BOGOTA, ZoneOffset.ofHours(-5));

    private static final Turno ABIERTO = new Turno(11L, CAJERO, "Ana Ruiz", APERTURA_CON_DESPLAZAMIENTO,
            new BigDecimal("50000"), null);

    @Mock RepositorioTurnos turnos;

    private ServicioTurnos servicio;

    @BeforeEach
    void comoCajero() {
        entrar(new UsuarioAutenticado(CAJERO, "ana@tienda.co", "VENDEDOR", EMPRESA));
        servicio = new ServicioTurnos(turnos, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private static void entrar(UsuarioAutenticado u) {
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    // ---------------------------------------------------------------- abrir

    @Test
    void abrirRegistraCajeroHoraYBase() {
        when(turnos.abiertoDe(EMPRESA, CAJERO)).thenReturn(Optional.empty());
        when(turnos.insertar(eq(EMPRESA), eq(CAJERO), eq(APERTURA_EN_BOGOTA), eq(new BigDecimal("50000"))))
                .thenReturn(11L);
        when(turnos.buscar(EMPRESA, 11L)).thenReturn(Optional.of(ABIERTO));

        Turno abierto = servicio.abrir(new SolicitudApertura(new BigDecimal("50000")));

        assertSame(ABIERTO, abierto);
        // La hora sale del reloj y se guarda como hora de Bogotá, al segundo: la JVM de
        // la prueba está en UTC y el motor guarda la hora de pared de Bogotá (ADR-0030).
        verify(turnos).insertar(EMPRESA, CAJERO, APERTURA_EN_BOGOTA, new BigDecimal("50000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "50000", "999999999999", "50000.00"})
    void abrirAdmiteUnaBaseEnPesosEnterosDeCeroEnAdelante(String base) {
        when(turnos.abiertoDe(EMPRESA, CAJERO)).thenReturn(Optional.empty());
        when(turnos.insertar(eq(EMPRESA), eq(CAJERO), any(), any())).thenReturn(11L);
        when(turnos.buscar(EMPRESA, 11L)).thenReturn(Optional.of(ABIERTO));

        servicio.abrir(new SolicitudApertura(new BigDecimal(base)));

        verify(turnos).insertar(eq(EMPRESA), eq(CAJERO), any(), any());
    }

    @Test
    void unaBaseNegativaEs400() {
        DatoDeTurnoInvalidoException e = assertThrows(DatoDeTurnoInvalidoException.class,
                () -> servicio.abrir(new SolicitudApertura(new BigDecimal("-1"))));

        assertFalse(e.getMessage().contains("-1"), "sin eco del dato enviado");
        verifyNoInteractions(turnos);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "-50000", "0.5", "50000.01", "1000000000000", "1E+20"})
    void unaBaseQueElMotorNoGuardariaEs400(String base) {
        // Cada una de estas llegaría a una restricción o a un desbordamiento de la columna
        // DECIMAL(14,2) y saldría como un 500: se rechaza antes de escribir.
        assertThrows(DatoDeTurnoInvalidoException.class,
                () -> servicio.abrir(new SolicitudApertura(new BigDecimal(base))));

        verifyNoInteractions(turnos);
    }

    @Test
    void sinBaseEs400() {
        assertThrows(DatoDeTurnoInvalidoException.class, () -> servicio.abrir(new SolicitudApertura(null)));
        assertThrows(DatoDeTurnoInvalidoException.class, () -> servicio.abrir(null));

        verifyNoInteractions(turnos);
    }

    @Test
    void unSegundoTurnoAbiertoDelMismoCajeroEs409() {
        when(turnos.abiertoDe(EMPRESA, CAJERO)).thenReturn(Optional.of(ABIERTO));

        assertThrows(TurnoYaAbiertoException.class,
                () -> servicio.abrir(new SolicitudApertura(new BigDecimal("50000"))));

        verify(turnos, never()).insertar(any(), any(), any(), any());
    }

    @Test
    void siLaCarreraPasaLaLecturaPreviaLaUnicidadDeLaBaseTambienEs409() {
        // Dos aperturas a la vez: ninguna ve a la otra en la lectura previa y la
        // segunda choca con el índice único. Es un 409, no un 500.
        when(turnos.abiertoDe(EMPRESA, CAJERO)).thenReturn(Optional.empty());
        when(turnos.insertar(eq(EMPRESA), eq(CAJERO), any(), any()))
                .thenThrow(new DuplicateKeyException("Duplicate entry '3-7' for key 'uq_cierres_abierto'"));

        TurnoYaAbiertoException e = assertThrows(TurnoYaAbiertoException.class,
                () -> servicio.abrir(new SolicitudApertura(new BigDecimal("50000"))));

        assertFalse(e.getMessage().contains("uq_cierres_abierto"), "el mensaje no expone la base");
    }

    @Test
    void otraViolacionDeIntegridadNoSeDisfrazaDeTurnoAbierto() {
        // Una clave foránea rota, por ejemplo, es un error de verdad y no «ya hay un turno».
        when(turnos.abiertoDe(EMPRESA, CAJERO)).thenReturn(Optional.empty());
        DataIntegrityViolationException otra = new DataIntegrityViolationException("fk_cierre_usuario_emp");
        when(turnos.insertar(eq(EMPRESA), eq(CAJERO), any(), any())).thenThrow(otra);

        DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
                () -> servicio.abrir(new SolicitudApertura(new BigDecimal("50000"))));

        assertSame(otra, e);
    }

    @Test
    void unUsuarioDePlataformaNoAbreTurnos() {
        entrar(new UsuarioAutenticado(1, "plataforma@sgip.co", "SUPER_ADMIN", null));

        assertThrows(SinEmpresaException.class,
                () -> servicio.abrir(new SolicitudApertura(new BigDecimal("50000"))));

        verifyNoInteractions(turnos);
    }

    // ---------------------------------------------------------------- actual

    @Test
    void actualEsElTurnoAbiertoDelUsuarioEnSesion() {
        when(turnos.abiertoDe(EMPRESA, CAJERO)).thenReturn(Optional.of(ABIERTO));

        assertEquals(Optional.of(ABIERTO), servicio.actual());
    }

    @Test
    void elTurnoDeOtroCajeroNoEsElActual() {
        // Otro cajero de la misma empresa tiene su turno abierto; el repositorio solo
        // responde por el cajero que se le pregunta, y se le pregunta por quien está en sesión.
        when(turnos.abiertoDe(EMPRESA, CAJERO)).thenReturn(Optional.empty());

        assertEquals(Optional.empty(), servicio.actual());

        verify(turnos).abiertoDe(EMPRESA, CAJERO);
        verify(turnos, never()).abiertoDe(eq(EMPRESA), eq(99));
    }

    // ---------------------------------------------------------------- exigirTurnoAbierto

    @Test
    void exigirTurnoAbiertoDevuelveElIdentificadorDelTurno() {
        when(turnos.bloquearAbiertoDe(EMPRESA, CAJERO)).thenReturn(Optional.of(11L));

        assertEquals(11L, servicio.exigirTurnoAbierto());
    }

    @Test
    void exigirTurnoAbiertoSinTurnoEs409() {
        when(turnos.bloquearAbiertoDe(EMPRESA, CAJERO)).thenReturn(Optional.empty());

        SinTurnoAbiertoException e = assertThrows(SinTurnoAbiertoException.class,
                () -> servicio.exigirTurnoAbierto());

        assertFalse(e.getMessage().isBlank());
    }
}
