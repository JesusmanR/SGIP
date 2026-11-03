package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.Sesion;
import co.com.bspstore.sgip.modelo.Usuario;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El cambio de contraseña inicial como puerta: quien lo tiene pendiente
 * no hace nada más que cambiarla o salir.
 *
 * Igual que con el modo de consulta, cada prueba mira las dos mitades:
 * si la cadena siguió y qué quedó en la respuesta.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FiltroCambioClaveTest {

    @Mock FilterChain cadena;
    @Mock ServicioSesion sesiones;

    private final MockHttpServletResponse respuesta = new MockHttpServletResponse();

    private final FiltroCambioClave filtro = new FiltroCambioClave();

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void comoUsuario(boolean debeCambiarClave) {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "VENDEDOR", 4, debeCambiarClave);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    private MockHttpServletRequest peticion(String metodo, String uri) {
        var p = new MockHttpServletRequest(metodo, uri);
        p.setRequestURI(uri);
        return p;
    }

    private void verificarRechazo(MockHttpServletRequest peticion) throws Exception {
        var r = new MockHttpServletResponse();
        var c = mock(FilterChain.class);

        filtro.doFilter(peticion, r, c);

        verify(c, never()).doFilter(any(), any());
        assertEquals(403, r.getStatus(),
                peticion.getMethod() + " " + peticion.getRequestURI() + " debería quedar rechazado");
    }

    // ------------------------------------------------------ lo que se bloquea

    @Test
    void conElCambioPendienteNoSeHaceNadaMas() throws Exception {
        comoUsuario(true);

        filtro.doFilter(peticion("GET", "/api/v1/productos"), respuesta, cadena);

        verify(cadena, never()).doFilter(any(), any());
        assertEquals(403, respuesta.getStatus());
        String cuerpo = respuesta.getContentAsString();
        assertTrue(cuerpo.contains("\"title\":\"Cambio de contraseña obligatorio\""), cuerpo);
        assertTrue(cuerpo.contains("\"status\":403"), cuerpo);
        assertTrue(cuerpo.contains("\"codigo\":\"CAMBIO_CLAVE_REQUERIDO\""), cuerpo);
        assertTrue(respuesta.getContentType().startsWith("application/problem+json"),
                "el error debe venir en el mismo formato que el resto de la API");
        assertEquals("UTF-8", respuesta.getCharacterEncoding(),
                "el mensaje lleva acentos y el cliente tiene que saber cómo leerlos");
    }

    @Test
    void tambienSeBloqueanLasEscriturasDeCualquierMetodo() throws Exception {
        for (String metodo : List.of("POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
            comoUsuario(true);
            verificarRechazo(peticion(metodo, "/api/v1/productos"));
        }
    }

    @Test
    void elRestoDeLaRutaDeAutenticacionNoEstaExento() throws Exception {
        comoUsuario(true);
        // Ni la misma ruta con otro verbo, ni otra ruta con el mismo verbo.
        verificarRechazo(peticion("GET", "/api/v1/auth/clave"));
        verificarRechazo(peticion("GET", "/api/v1/auth/logout"));
        verificarRechazo(peticion("POST", "/api/v1/auth/otra"));
    }

    // --------------------------------------------------- lo que queda abierto

    @Test
    void cambiarLaClaveSigue() throws Exception {
        comoUsuario(true);

        filtro.doFilter(peticion("POST", "/api/v1/auth/clave"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
        assertEquals(200, respuesta.getStatus());
    }

    @Test
    void cerrarSesionSigue() throws Exception {
        comoUsuario(true);

        filtro.doFilter(peticion("POST", "/api/v1/auth/logout"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
        assertEquals(200, respuesta.getStatus());
    }

    @Test
    void sinCambioPendienteSigueTodo() throws Exception {
        comoUsuario(false);

        filtro.doFilter(peticion("GET", "/api/v1/productos"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
        assertEquals(200, respuesta.getStatus());
    }

    /**
     * El ingreso tiene que seguir funcionando sin sesión: es la única
     * forma de llegar a tener una.
     */
    @Test
    void sinAutenticarSigueYElIngresoNoSeToca() throws Exception {
        filtro.doFilter(peticion("POST", "/api/v1/auth/login"), respuesta, cadena);

        verify(cadena).doFilter(any(), any());
        assertEquals(200, respuesta.getStatus());
    }

    // --------------------------------------- la ruta no se puede esquivar

    /**
     * La comparación es exacta sobre la ruta cruda. Cualquier variante
     * —barra final, doble barra, un carácter codificado, un parámetro de
     * ruta, el verbo en minúsculas— no coincide y cae del lado que
     * rechaza: la lista de excepciones nunca crece por accidente.
     */
    @Test
    void lasVariantesDeLaRutaNoSeEsquivan() throws Exception {
        for (String uri : List.of(
                "/api/v1/auth/clave/",
                "//api/v1/auth/clave",
                "/api/v1//auth/clave",
                "/api/v1/auth/%63lave",
                "/api/v1/auth/clave;x=1",
                "/api/v1/auth/clave%2F",
                "/api/v1/auth/./clave",
                "/api/v1/productos/../auth/clave",
                "/API/V1/AUTH/CLAVE",
                "/api/v1/auth/logout/",
                "/api/v1/auth/logout;jsessionid=1")) {
            comoUsuario(true);
            verificarRechazo(peticion("POST", uri));
        }
        comoUsuario(true);
        verificarRechazo(peticion("post", "/api/v1/auth/clave"));
    }

    @Test
    void conRutaDeContextoSeComparaRelativoAlContexto() throws Exception {
        comoUsuario(true);
        var conContexto = peticion("POST", "/sgip/api/v1/auth/clave");
        conContexto.setContextPath("/sgip");

        filtro.doFilter(conContexto, respuesta, cadena);

        verify(cadena).doFilter(any(), any());
    }

    @Test
    void unContextoAjenoNoHaceCoincidirLaRuta() throws Exception {
        // Contexto «/api»: la ruta relativa es «/v1/auth/clave», que no es
        // la exenta aunque la cruda termine igual.
        comoUsuario(true);
        var p = peticion("POST", "/api/v1/auth/clave");
        p.setContextPath("/api");
        verificarRechazo(p);

        // Y sin contexto, una ruta con el prefijo de otro contexto tampoco.
        comoUsuario(true);
        verificarRechazo(peticion("POST", "/sgip/api/v1/auth/clave"));
    }

    // ------------------------ el estado sale del servidor en cada petición

    /**
     * La bandera no se guarda en la sesión: FiltroSesion reconstruye el
     * principal desde el usuario de la base en cada petición. De modo que
     * el mismo token deja de estar bloqueado en cuanto la clave cambia,
     * sin volver a entrar.
     */
    @Test
    void trasCambiarLaClaveElMismoTokenFuncionaDeInmediato() throws Exception {
        Usuario usuario = new Usuario(new Rol("VENDEDOR", "Vendedor"), "Ana",
                "ana@tienda.co", "hash-inicial");
        assertTrue(usuario.isCambioClaveRequerido(), "el usuario nace con el cambio pendiente");
        var sesion = new Sesion(usuario, "hash-del-token", LocalDateTime.now(),
                LocalDateTime.now().plusMinutes(30), null, null);
        when(sesiones.validar("token")).thenReturn(Optional.of(sesion));
        var filtroSesion = new FiltroSesion(sesiones);

        // Primera petición: bloqueada.
        var primera = pedirConToken(filtroSesion, "/api/v1/productos");
        assertEquals(403, primera.getStatus());

        // El usuario cambia su clave (lo que hace ServicioAutenticacion).
        usuario.cambiarClave("hash-nuevo");

        // Segunda petición, mismo token: pasa.
        SecurityContextHolder.clearContext();
        var segunda = pedirConToken(filtroSesion, "/api/v1/productos");
        assertEquals(200, segunda.getStatus());
        verify(sesiones, times(2)).validar("token");
    }

    private MockHttpServletResponse pedirConToken(FiltroSesion filtroSesion, String uri)
            throws Exception {
        var p = peticion("GET", uri);
        p.addHeader("Authorization", "Bearer token");
        var r = new MockHttpServletResponse();
        // La cadena real: sesión primero y después este filtro.
        FilterChain despues = (rq, rs) -> filtro.doFilter(rq, rs, cadena);
        filtroSesion.doFilter(p, r, despues);
        return r;
    }
}
