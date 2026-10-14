package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.datos.SesionRepositorio;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.Sesion;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServicioSesionTest {

    private static final ZoneId ZONA = ZoneId.of("America/Bogota");
    private static final Instant ARRANQUE = Instant.parse("2026-10-14T13:00:00Z");
    private static final int MINUTOS = 30;

    @Mock SesionRepositorio sesiones;

    Usuario usuario;

    @BeforeEach
    void preparar() {
        Rol admin = new Rol("ADMINISTRADOR", "Administrador");
        usuario = new Usuario(admin, "Jesús Durán", "admin@bspstore.com.co",
                Seguridad.derivar("SgipDev2026"));
    }

    /** Un reloj detenido: la expiración se comprueba moviéndolo, no esperando. */
    private ServicioSesion servicioEn(Instant momento) {
        return new ServicioSesion(sesiones, MINUTOS, Clock.fixed(momento, ZONA));
    }

    private Sesion capturarGuardada() {
        ArgumentCaptor<Sesion> captor = ArgumentCaptor.forClass(Sesion.class);
        verify(sesiones).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void abrirDevuelveUnTokenYGuardaSoloSuHash() {
        String token = servicioEn(ARRANQUE).abrir(usuario, "127.0.0.1", "JUnit");

        Sesion guardada = capturarGuardada();
        assertNotEquals(token, guardada.getTokenHash());
        assertEquals(Seguridad.hashToken(token), guardada.getTokenHash());
    }

    @Test
    void cadaIngresoAbreUnaSesionDistinta() {
        assertNotEquals(servicioEn(ARRANQUE).abrir(usuario, null, null),
                servicioEn(ARRANQUE).abrir(usuario, null, null));
    }

    @Test
    void laSesionExpiraALosMinutosConfigurados() {
        servicioEn(ARRANQUE).abrir(usuario, null, null);

        Sesion guardada = capturarGuardada();
        assertEquals(guardada.getInicio().plusMinutes(MINUTOS), guardada.getExpira());
        // La migración V1 exige expira > inicio mediante un CHECK
        assertTrue(guardada.getExpira().isAfter(guardada.getInicio()));
    }

    @Test
    void validarDevuelveLaSesionVigente() {
        String token = "token-de-prueba";
        Sesion sesion = sesionDe(token, ARRANQUE);
        when(sesiones.findByTokenHash(Seguridad.hashToken(token))).thenReturn(Optional.of(sesion));

        Optional<Sesion> hallada = servicioEn(ARRANQUE.plusSeconds(60)).validar(token);

        assertTrue(hallada.isPresent());
        assertSame(usuario, hallada.get().getUsuario());
    }

    @Test
    void cadaUsoCorreLaExpiracionHaciaAdelante() {
        String token = "token-de-prueba";
        Sesion sesion = sesionDe(token, ARRANQUE);
        when(sesiones.findByTokenHash(Seguridad.hashToken(token))).thenReturn(Optional.of(sesion));

        LocalDateTime expiraAntes = sesion.getExpira();
        Instant despues = ARRANQUE.plusSeconds(600);
        servicioEn(despues).validar(token);

        assertTrue(sesion.getExpira().isAfter(expiraAntes));
        assertEquals(LocalDateTime.ofInstant(despues, ZONA), sesion.getUltimaActividad());
    }

    @Test
    void unaSesionCaducadaNoVale() {
        String token = "token-de-prueba";
        when(sesiones.findByTokenHash(Seguridad.hashToken(token)))
                .thenReturn(Optional.of(sesionDe(token, ARRANQUE)));

        // Un minuto después de la expiración
        assertTrue(servicioEn(ARRANQUE.plusSeconds((MINUTOS + 1) * 60L)).validar(token).isEmpty());
    }

    @Test
    void unaSesionCerradaNoVale() {
        String token = "token-de-prueba";
        Sesion sesion = sesionDe(token, ARRANQUE);
        sesion.cerrar();
        when(sesiones.findByTokenHash(Seguridad.hashToken(token))).thenReturn(Optional.of(sesion));

        assertTrue(servicioEn(ARRANQUE).validar(token).isEmpty());
    }

    @Test
    void unTokenDesconocidoNoVale() {
        when(sesiones.findByTokenHash(Seguridad.hashToken("inventado"))).thenReturn(Optional.empty());

        assertTrue(servicioEn(ARRANQUE).validar("inventado").isEmpty());
    }

    @Test
    void unTokenVacioNiSiquieraConsultaLaBase() {
        assertTrue(servicioEn(ARRANQUE).validar("   ").isEmpty());
        assertTrue(servicioEn(ARRANQUE).validar(null).isEmpty());
        verify(sesiones, never()).findByTokenHash(any());
    }

    @Test
    void cerrarMarcaLaSesionYLaDejaInvalida() {
        String token = "token-de-prueba";
        Sesion sesion = sesionDe(token, ARRANQUE);
        when(sesiones.findByTokenHash(Seguridad.hashToken(token))).thenReturn(Optional.of(sesion));

        ServicioSesion servicio = servicioEn(ARRANQUE);
        assertTrue(servicio.cerrar(token));
        assertTrue(sesion.isCerrada());
        assertTrue(servicio.validar(token).isEmpty());
    }

    @Test
    void cerrarDosVecesNoFalla() {
        String token = "token-de-prueba";
        Sesion sesion = sesionDe(token, ARRANQUE);
        when(sesiones.findByTokenHash(Seguridad.hashToken(token))).thenReturn(Optional.of(sesion));

        ServicioSesion servicio = servicioEn(ARRANQUE);
        assertTrue(servicio.cerrar(token));
        assertFalse(servicio.cerrar(token));
    }

    private Sesion sesionDe(String token, Instant momento) {
        LocalDateTime inicio = LocalDateTime.ofInstant(momento, ZONA);
        return new Sesion(usuario, Seguridad.hashToken(token), inicio,
                inicio.plusMinutes(MINUTOS), "127.0.0.1", "JUnit");
    }
}
