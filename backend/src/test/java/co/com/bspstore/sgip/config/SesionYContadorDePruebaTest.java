package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.datos.SesionRepositorio;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.Sesion;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * El contador de la prueba no mantiene viva la sesión (RNF-09, ADR-0031).
 *
 * La sesión caduca por inactividad: cada petición autenticada corre su
 * expiración. El contador del encabezado pregunta solo, cada cinco minutos, sin
 * que nadie toque nada; si eso contara como actividad, una pestaña olvidada en
 * la caja no caducaría nunca. Por eso {@code GET /api/v1/empresa/prueba} valida
 * la sesión (caducada da 401) pero no la alarga, y cualquier otra ruta la sigue
 * alargando como siempre.
 *
 * Pasa por {@link FiltroSesion} y {@link ServicioSesion} de verdad, con un reloj
 * que la prueba mueve; solo el repositorio es simulado, y devuelve siempre la
 * misma sesión, como haría la unidad de persistencia.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SesionYContadorDePruebaTest {

    private static final String TOKEN = "token-de-prueba";
    private static final int MINUTOS = 30;
    private static final LocalDateTime INICIO = LocalDateTime.of(2026, 11, 5, 15, 0);

    @Mock SesionRepositorio repositorio;
    @Mock FilterChain cadena;

    private final RelojMovible reloj = new RelojMovible();
    private Sesion sesion;
    private FiltroSesion filtro;

    /** Un reloj que la prueba adelanta a mano. */
    private static final class RelojMovible extends Clock {
        private Instant instante = INICIO.toInstant(ZoneOffset.UTC);

        void a(LocalDateTime momento) { instante = momento.toInstant(ZoneOffset.UTC); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return instante; }
    }

    @BeforeEach
    void preparar() {
        Usuario usuario = new Usuario(new Rol("VENDEDOR", "Vendedor"), "Ana",
                "ana@tienda.co", Seguridad.derivar("SgipDev2026"));
        sesion = new Sesion(usuario, Seguridad.hashToken(TOKEN), INICIO,
                INICIO.plusMinutes(MINUTOS), "origen", "dispositivo");
        when(repositorio.findByTokenHash(Seguridad.hashToken(TOKEN))).thenReturn(Optional.of(sesion));
        filtro = new FiltroSesion(new ServicioSesion(repositorio, MINUTOS, reloj));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    /** Pasa una petición por el filtro en {@code momento} y dice si quedó autenticada. */
    private boolean pedir(String metodo, String ruta, LocalDateTime momento) throws Exception {
        SecurityContextHolder.clearContext();
        reloj.a(momento);
        var peticion = new MockHttpServletRequest(metodo, ruta);
        peticion.addHeader("Authorization", "Bearer " + TOKEN);
        filtro.doFilter(peticion, new MockHttpServletResponse(), cadena);
        boolean autenticada = SecurityContextHolder.getContext().getAuthentication() != null;
        SecurityContextHolder.clearContext();
        return autenticada;
    }

    @Test
    void conSoloElContadorLaSesionCaducaCuandoCaducariaSinEl() throws Exception {
        for (int minuto = 5; minuto < MINUTOS; minuto += 5) {
            assertTrue(pedir("GET", "/api/v1/empresa/prueba", INICIO.plusMinutes(minuto)),
                    "la sesión sigue viva y el contador responde en el minuto " + minuto);
        }
        assertEquals(INICIO.plusMinutes(MINUTOS), sesion.getExpira(), "el contador no corrió la expiración");

        assertTrue(pedir("GET", "/api/v1/empresa/prueba", INICIO.plusMinutes(MINUTOS).minusSeconds(1)));
        assertFalse(pedir("GET", "/api/v1/empresa/prueba", INICIO.plusMinutes(MINUTOS)),
                "en el instante en que caducaría sin el contador, el contador ya recibe un 401");
        assertFalse(pedir("GET", "/api/v1/productos", INICIO.plusMinutes(MINUTOS)),
                "y la sesión está caducada para todo lo demás");
    }

    @Test
    void otraRutaSiRenuevaLaSesion() throws Exception {
        assertTrue(pedir("GET", "/api/v1/productos", INICIO.plusMinutes(10)));

        assertEquals(INICIO.plusMinutes(10 + MINUTOS), sesion.getExpira());
        assertTrue(pedir("GET", "/api/v1/productos", INICIO.plusMinutes(MINUTOS + 5)),
                "la actividad de verdad alarga la sesión como siempre");
    }

    /** Solo el GET exacto queda fuera: un POST o una ruta parecida renuevan. */
    @Test
    void laExencionEsSoloElGetExactoDelContador() throws Exception {
        for (String[] peticion : new String[][] {
                {"POST", "/api/v1/empresa/prueba"},
                {"GET", "/api/v1/empresa/prueba/otra"},
                {"GET", "/api/v1/empresa"}}) {
            LocalDateTime momento = INICIO.plusMinutes(1);
            sesion.renovar(INICIO, MINUTOS);

            pedir(peticion[0], peticion[1], momento);

            assertEquals(momento.plusMinutes(MINUTOS), sesion.getExpira(),
                    peticion[0] + " " + peticion[1] + " debería renovar");
        }
    }
}
