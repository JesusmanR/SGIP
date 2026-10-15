package co.com.bspstore.sgip.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextoEmpresaTest {

    @AfterEach
    void limpiar() {
        // El contexto es de hilo: sin esto, una prueba contamina a la siguiente.
        SecurityContextHolder.clearContext();
    }

    private void autenticar(UsuarioAutenticado u) {
        var token = new UsernamePasswordAuthenticationToken(
                u, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.rol())));
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(token);
        SecurityContextHolder.setContext(c);
    }

    @Test
    void devuelveLaEmpresaDeQuienHizoLaPeticion() {
        autenticar(new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", 3));

        assertEquals(3, ContextoEmpresa.empresaActual());
    }

    @Test
    void elUsuarioDePlataformaNoTieneEmpresa() {
        autenticar(new UsuarioAutenticado(1, "bsp@bspstore.com.co", "SUPER_ADMIN", null));

        assertThrows(SinEmpresaException.class, ContextoEmpresa::empresaActual);
    }

    @Test
    void sinAutenticacionEsUnErrorDeConfiguracionNoDeAutorizacion() {
        // Si un endpoint llega hasta aquí sin autenticar, lo que está mal es
        // la cadena de seguridad, no la petición. Por eso no es SinEmpresa.
        assertThrows(IllegalStateException.class, ContextoEmpresa::empresaActual);
    }

    @Test
    void unPrincipalQueNoEsNuestroSeIgnora() {
        // Así quedaba antes: el correo suelto como principal.
        var token = new UsernamePasswordAuthenticationToken("ana@tienda.co", null, List.of());
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(token);
        SecurityContextHolder.setContext(c);

        assertTrue(ContextoEmpresa.actual().isEmpty());
        assertThrows(IllegalStateException.class, ContextoEmpresa::empresaActual);
    }

    @Test
    void distingueAlUsuarioDePlataformaDelDeEmpresa() {
        assertTrue(new UsuarioAutenticado(1, "bsp@bspstore.com.co", "SUPER_ADMIN", null)
                .esDePlataforma());
        assertFalse(new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", 3)
                .esDePlataforma());
    }

    @Test
    void alRegistrarseEnUnLogSoloApareceElCorreo() {
        assertEquals("ana@tienda.co",
                new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", 3).toString());
    }
}
