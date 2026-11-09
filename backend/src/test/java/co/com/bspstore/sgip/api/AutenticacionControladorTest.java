package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.SolicitudCambioClave;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.ClaveActualIncorrectaException;
import co.com.bspstore.sgip.logica.ServicioAutenticacion;
import co.com.bspstore.sgip.logica.ServicioSesion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AutenticacionControladorTest {

    @Mock ServicioAutenticacion servicio;
    @Mock ServicioSesion sesiones;

    AutenticacionControlador controlador;
    MockHttpServletRequest peticion = new MockHttpServletRequest();

    @BeforeEach
    void preparar() {
        controlador = new AutenticacionControlador(servicio, sesiones);
        peticion.setRemoteAddr("10.0.0.5");
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "VENDEDOR", 4, true);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cambiaLaClaveDeQuienPideEnUnaSolaLlamadaDelServicioConElTokenDeLaSesionActual() {
        var respuesta = controlador.cambiarClave(
                new SolicitudCambioClave("Inicial2026", "Nueva2026xyz"), "Bearer token-actual", peticion);

        assertEquals(204, respuesta.getStatusCode().value());
        // El identificador sale de la sesión autenticada, no de la solicitud. El cierre de las
        // demás sesiones lo hace el servicio, en la misma transacción: el controlador no lo toca.
        verify(servicio).cambiarClave(7, "Inicial2026", "Nueva2026xyz", "10.0.0.5", "token-actual");
        verifyNoInteractions(sesiones);
    }

    @Test
    void sinCabeceraDeAutorizacionElTokenViajaNulo() {
        controlador.cambiarClave(new SolicitudCambioClave("Inicial2026", "Nueva2026xyz"), null, peticion);

        verify(servicio).cambiarClave(7, "Inicial2026", "Nueva2026xyz", "10.0.0.5", null);
    }

    @Test
    void siLaClaveActualEstaMalElErrorSubeSinTocarLasSesiones() {
        doThrow(new ClaveActualIncorrectaException()).when(servicio)
                .cambiarClave(7, "mala", "Nueva2026xyz", "10.0.0.5", "token-actual");

        assertThrows(ClaveActualIncorrectaException.class, () -> controlador.cambiarClave(
                new SolicitudCambioClave("mala", "Nueva2026xyz"), "Bearer token-actual", peticion));

        verifyNoInteractions(sesiones);
    }

    @Test
    void laSolicitudNoImprimeLasContrasenas() {
        String texto = new SolicitudCambioClave("Inicial2026", "Nueva2026xyz").toString();

        assertFalse(texto.contains("Inicial2026"));
        assertFalse(texto.contains("Nueva2026xyz"));
    }
}
