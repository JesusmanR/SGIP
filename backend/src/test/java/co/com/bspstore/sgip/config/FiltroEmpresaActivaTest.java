package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La RN-15 vista desde la única puerta por la que pasa.
 *
 * Cada prueba comprueba las dos mitades de la misma afirmación: que la
 * cadena siguió o no siguió, y que la respuesta es la que el cliente
 * necesita. Un filtro que rechaza sin continuar la cadena pero deja un
 * 200 en la respuesta sería peor que no tenerlo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FiltroEmpresaActivaTest {

    @Mock RepositorioPlataforma plataforma;
    @Mock FilterChain cadena;

    private final MockHttpServletResponse respuesta = new MockHttpServletResponse();

    /** Último día de la prueba de la empresa 4 en las pruebas de vencimiento. */
    private static final LocalDate FIN_PRUEBA = LocalDate.of(2026, 2, 28);

    /** El reloj va en UTC, como la JVM de la CI: el día lo decide Bogotá. */
    private Clock reloj = enBogota("2026-02-20T10:00:00-05:00");

    private static Clock enBogota(String instante) {
        return Clock.fixed(OffsetDateTime.parse(instante).toInstant(), ZoneOffset.UTC);
    }

    private FiltroEmpresaActiva filtro() {
        return new FiltroEmpresaActiva(plataforma, reloj);
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void comoUsuarioDe(Integer empresaId) {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    private MockHttpServletRequest peticion(String metodo, String ruta) {
        return new MockHttpServletRequest(metodo, ruta);
    }

    // --------------------------------------------- la empresa desactivada

    @Test
    void laEmpresaDesactivadaConsultaTodo() throws Exception {
        comoUsuarioDe(4);
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));

        filtro().doFilter(peticion("GET", "/api/v1/productos"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
        assertEquals(200, respuesta.getStatus());
        // Una lectura no debe costar ni una consulta de estado.
        verify(plataforma, never()).estadoDe(anyInt());
    }

    @Test
    void laEmpresaDesactivadaNoRegistraNada() throws Exception {
        comoUsuarioDe(4);
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));

        filtro().doFilter(peticion("POST", "/api/v1/productos"), respuesta, cadena);

        verify(cadena, never()).doFilter(any(), any());
        assertEquals(403, respuesta.getStatus());
        assertTrue(respuesta.getContentAsString().contains("modo de consulta"),
                "el cuerpo debe decir por qué, no solo que no");
        // El tipo se comprueba por su prefijo porque la respuesta declara
        // además el juego de caracteres —«application/problem+json;charset=UTF-8»—
        // y eso es deliberado: el mensaje lleva acentos y sin la
        // declaración el cliente los lee mal. Lo que el cliente negocia es
        // el tipo; el parámetro va aparte.
        assertTrue(respuesta.getContentType().startsWith("application/problem+json"),
                "el error debe venir en el mismo formato que el resto de la API");
        assertEquals("UTF-8", respuesta.getCharacterEncoding(),
                "el mensaje lleva acentos y el cliente tiene que saber cómo leerlos");
    }

    @Test
    void elRechazoCubreTodosLosMetodosQueEscriben() throws Exception {
        for (String metodo : List.of("POST", "PUT", "PATCH", "DELETE")) {
            SecurityContextHolder.clearContext();
            comoUsuarioDe(4);
            when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));
            var r = new MockHttpServletResponse();
            var c = org.mockito.Mockito.mock(FilterChain.class);

            filtro().doFilter(peticion(metodo, "/api/v1/productos"), r, c);

            assertEquals(403, r.getStatus(), metodo + " debería quedar rechazado");
            verify(c, never()).doFilter(any(), any());
        }
    }

    // ------------------------- lo que una empresa en consulta sí puede hacer

    /**
     * Cambiar la contraseña y salir no escriben datos de la empresa: son
     * del usuario. Si el modo de consulta los bloqueara, el usuario de
     * una empresa desactivada que aún tiene su contraseña inicial
     * quedaría atrapado: el filtro del cambio le exige cambiarla y este
     * le impide hacerlo.
     */
    @Test
    void laEmpresaDesactivadaDejaCambiarLaClaveYSalir() throws Exception {
        for (String ruta : List.of("/api/v1/auth/clave", "/api/v1/auth/logout")) {
            comoUsuarioDe(4);
            when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));
            var r = new MockHttpServletResponse();
            var c = org.mockito.Mockito.mock(FilterChain.class);

            filtro().doFilter(peticion("POST", ruta), r, c);

            verify(c).doFilter(any(), any());
            assertEquals(200, r.getStatus(), ruta);
        }
    }

    @Test
    void laExencionNoSeExtiendeAOtrasRutasNiAVariantes() throws Exception {
        for (String ruta : List.of("/api/v1/productos", "/api/v1/auth/clave/",
                "/api/v1/auth/otra", "/api/v1/auth/%63lave")) {
            comoUsuarioDe(4);
            when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.DESACTIVADA));
            var r = new MockHttpServletResponse();
            var c = org.mockito.Mockito.mock(FilterChain.class);

            filtro().doFilter(peticion("POST", ruta), r, c);

            verify(c, never()).doFilter(any(), any());
            assertEquals(403, r.getStatus(), ruta);
        }
    }

    // ------------------------------------------------ quién sí puede escribir

    @Test
    void laEmpresaEnPruebaEscribe() throws Exception {
        comoUsuarioDe(4);
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.PRUEBA));
        when(plataforma.finDePruebaDe(4)).thenReturn(Optional.of(FIN_PRUEBA));

        filtro().doFilter(peticion("POST", "/api/v1/productos"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
        assertEquals(200, respuesta.getStatus());
    }

    // ------------------------------------- la prueba vencida (RF-04, ADR-0031)

    @Test
    void elUltimoDiaDeLaPruebaALas235959TodaviaEscribe() throws Exception {
        reloj = enBogota("2026-02-28T23:59:59-05:00");
        comoUsuarioDe(4);
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.PRUEBA));
        when(plataforma.finDePruebaDe(4)).thenReturn(Optional.of(FIN_PRUEBA));

        filtro().doFilter(peticion("POST", "/api/v1/productos"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
        assertEquals(200, respuesta.getStatus());
    }

    /**
     * Sin tarea programada: el estado en la base sigue siendo PRUEBA, y es este
     * filtro el que, con el reloj, la trata como sin escritura.
     */
    @Test
    void unSegundoDespuesDelVencimientoLaPruebaNoRegistraNada() throws Exception {
        reloj = enBogota("2026-03-01T00:00:00-05:00");
        comoUsuarioDe(4);
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.PRUEBA));
        when(plataforma.finDePruebaDe(4)).thenReturn(Optional.of(FIN_PRUEBA));

        filtro().doFilter(peticion("POST", "/api/v1/productos"), respuesta, cadena);

        verify(cadena, never()).doFilter(any(), any());
        assertEquals(403, respuesta.getStatus());
        assertTrue(respuesta.getContentType().startsWith("application/problem+json"));
        assertTrue(respuesta.getContentAsString().contains(
                        "\"detail\":\"La prueba gratuita terminó. Puedes consultar toda tu información, "
                                + "pero no registrar cambios.\""),
                "el detalle dice que terminó la prueba: " + respuesta.getContentAsString());
        verify(plataforma, never()).cambiarEstado(anyInt(), any());
    }

    @Test
    void laPruebaVencidaSigueConsultandoSinPreguntarNada() throws Exception {
        reloj = enBogota("2026-03-10T10:00:00-05:00");
        comoUsuarioDe(4);

        filtro().doFilter(peticion("GET", "/api/v1/productos"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
        assertEquals(200, respuesta.getStatus());
        verify(plataforma, never()).finDePruebaDe(anyInt());
    }

    @Test
    void laPruebaVencidaPuedeCambiarLaClaveYSalir() throws Exception {
        reloj = enBogota("2026-03-10T10:00:00-05:00");
        comoUsuarioDe(4);
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.PRUEBA));
        when(plataforma.finDePruebaDe(4)).thenReturn(Optional.of(FIN_PRUEBA));

        filtro().doFilter(peticion("POST", "/api/v1/auth/clave"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
    }

    /** Una empresa activa no paga la segunda consulta. */
    @Test
    void laEmpresaActivaNoPreguntaPorElFinDeLaPrueba() throws Exception {
        comoUsuarioDe(4);
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));

        filtro().doFilter(peticion("POST", "/api/v1/productos"), respuesta, cadena);

        verify(plataforma, never()).finDePruebaDe(anyInt());
    }

    /** Una prueba sin suscripción no es un caso que convenga dejar escribir. */
    @Test
    void unaPruebaSinSuscripcionNoEscribe() throws Exception {
        comoUsuarioDe(4);
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.PRUEBA));
        when(plataforma.finDePruebaDe(4)).thenReturn(Optional.empty());

        filtro().doFilter(peticion("POST", "/api/v1/productos"), respuesta, cadena);

        verify(cadena, never()).doFilter(any(), any());
        assertEquals(403, respuesta.getStatus());
    }

    @Test
    void laEmpresaActivaEscribe() throws Exception {
        comoUsuarioDe(4);
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));

        filtro().doFilter(peticion("POST", "/api/v1/productos"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
    }

    /**
     * El ingreso es un POST sin autenticar. Si este filtro lo bloqueara,
     * el usuario de una empresa desactivada no podría entrar, y entonces
     * no podría consultar nada: justo lo que la regla le conserva.
     */
    @Test
    void elIngresoSigueAbiertoPorqueTodaviaNoHayNadieEnElContexto() throws Exception {
        filtro().doFilter(peticion("POST", "/api/v1/auth/login"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
        verify(plataforma, never()).estadoDe(anyInt());
    }

    /**
     * El super administrador no tiene empresa (RN-17) y además es quien
     * reactiva. Un filtro que lo bloqueara dejaría a la empresa
     * desactivada para siempre.
     */
    @Test
    void elSuperAdministradorNoPasaPorEstaComprobacion() throws Exception {
        comoUsuarioDe(null);

        filtro().doFilter(
                peticion("POST", "/api/v1/plataforma/empresas/4/activar"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
        verify(plataforma, never()).estadoDe(anyInt());
    }

    // ------------------------------------------------------------ fallar cerrado

    @Test
    void unaSesionQueApuntaAUnaEmpresaInexistenteNoEscribe() throws Exception {
        comoUsuarioDe(404);
        when(plataforma.estadoDe(404)).thenReturn(Optional.empty());

        filtro().doFilter(peticion("POST", "/api/v1/productos"), respuesta, cadena);

        verify(cadena, never()).doFilter(any(), any());
        assertEquals(403, respuesta.getStatus());
    }
}
