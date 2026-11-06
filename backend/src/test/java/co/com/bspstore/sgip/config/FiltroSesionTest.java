package co.com.bspstore.sgip.config;

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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Qué cuentas conservan su sesión abierta (ADR-0028).
 *
 * El bloqueo por intentos fallidos solo impide entrar de nuevo: quien ya
 * entró sigue dentro. Si lo expulsara, cualquiera que conozca un correo
 * podría sacar a su dueño de la sesión equivocándose cinco veces. Desactivar
 * la cuenta sí la corta, porque es una decisión de la empresa y no un efecto
 * de los errores de quien teclea.
 */
@ExtendWith(MockitoExtension.class)
class FiltroSesionTest {

    private static final String TOKEN = "token-de-prueba";
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 5, 15, 0);

    @Mock ServicioSesion sesiones;
    @Mock FilterChain cadena;

    private Usuario usuario;

    @BeforeEach
    void preparar() {
        usuario = new Usuario(new Rol("ADMINISTRADOR", "Administrador"), "Ana",
                "ana@tienda.co", Seguridad.derivar("SgipDev2026"));
        when(sesiones.validar(TOKEN)).thenAnswer(i -> Optional.of(
                new Sesion(usuario, "hash", AHORA, AHORA.plusMinutes(30), "origen", "dispositivo")));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void pasarPeticionConToken() throws Exception {
        var peticion = new MockHttpServletRequest("GET", "/api/v1/productos");
        peticion.addHeader("Authorization", "Bearer " + TOKEN);
        var respuesta = new MockHttpServletResponse();
        new FiltroSesion(sesiones).doFilter(peticion, respuesta, cadena);
        verify(cadena).doFilter(peticion, respuesta);
    }

    @Test
    void unaCuentaActivaQuedaAutenticada() throws Exception {
        pasarPeticionConToken();

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void unaCuentaBloqueadaConLaSesionAbiertaSigueDentro() throws Exception {
        usuario.registrarIntentoFallido(1, AHORA);

        pasarPeticionConToken();

        assertNotNull(SecurityContextHolder.getContext().getAuthentication(),
                "el bloqueo solo impide entrar de nuevo; no expulsa a quien ya entró");
    }

    @Test
    void unaCuentaDesactivadaPierdeLaSesion() throws Exception {
        usuario.desactivar();

        pasarPeticionConToken();

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
