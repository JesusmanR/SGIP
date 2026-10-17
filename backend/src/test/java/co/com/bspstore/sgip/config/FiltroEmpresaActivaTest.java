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

    private FiltroEmpresaActiva filtro() {
        return new FiltroEmpresaActiva(plataforma);
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

    // ------------------------------------------------ quién sí puede escribir

    @Test
    void laEmpresaEnPruebaEscribe() throws Exception {
        comoUsuarioDe(4);
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.PRUEBA));

        filtro().doFilter(peticion("POST", "/api/v1/productos"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
        assertEquals(200, respuesta.getStatus());
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
