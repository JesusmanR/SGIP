package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.datos.AuditoriaAccesoRepositorio;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.datos.SesionRepositorio;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServicioAutenticacionTest {

    private static final String CORREO = "admin@bspstore.com.co";
    private static final String CLAVE = "SgipDev2026";

    @Mock UsuarioRepositorio usuarios;
    @Mock AuditoriaAccesoRepositorio auditoria;
    @Mock SesionRepositorio sesiones;

    ServicioAutenticacion servicio;
    Usuario usuario;

    @BeforeEach
    void preparar() {
        servicio = new ServicioAutenticacion(usuarios, auditoria, sesiones, 5, 30);
        Rol admin = new Rol("ADMINISTRADOR", "Administrador");
        usuario = new Usuario(admin, "Jesús Durán", CORREO, Seguridad.derivar(CLAVE));
    }

    @Test
    void autenticaConCredencialesCorrectasYLoAudita() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));

        var emitida = servicio.autenticar(CORREO, CLAVE, "curl", "prueba");

        assertSame(usuario, emitida.usuario());
        assertNotNull(emitida.token());
        verify(auditoria).save(argThat(a -> a.isExitoso()));
        verify(sesiones).save(any());
    }

    @Test
    void normalizaElCorreoAntesDeBuscar() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));

        servicio.autenticar("  ADMIN@BSPstore.com.co ", CLAVE, "curl", "prueba");
        verify(usuarios).findByCorreo(CORREO);
    }

    @Test
    void rechazaUnCorreoInexistenteYRegistraElMotivo() {
        when(usuarios.findByCorreo(any())).thenReturn(Optional.empty());

        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar("nadie@bspstore.com.co", CLAVE, "curl", "prueba"));
        verify(auditoria).save(argThat(a ->
                !a.isExitoso() && "USUARIO_INEXISTENTE".equals(a.getMotivo())));
    }

    @Test
    void unaClaveIncorrectaIncrementaLosIntentos() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));

        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(CORREO, "incorrecta", "curl", "prueba"));
        assertEquals(1, usuario.getIntentosFallidos());
        assertFalse(usuario.isBloqueado());
    }

    @Test
    void elQuintoIntentoFallidoBloqueaLaCuenta() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));

        for (int i = 0; i < 5; i++) {
            assertThrows(CredencialesInvalidasException.class,
                    () -> servicio.autenticar(CORREO, "incorrecta", "curl", "prueba"));
        }
        assertTrue(usuario.isBloqueado());
    }

    @Test
    void unaCuentaBloqueadaRechazaInclusoLaClaveCorrecta() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));
        for (int i = 0; i < 5; i++) {
            assertThrows(CredencialesInvalidasException.class,
                    () -> servicio.autenticar(CORREO, "incorrecta", "curl", "prueba"));
        }

        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(CORREO, CLAVE, "curl", "prueba"));
        verify(auditoria).save(argThat(a -> "CUENTA_BLOQUEADA".equals(a.getMotivo())));
    }

    @Test
    void unIngresoExitosoReiniciaLosIntentos() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));
        for (int i = 0; i < 2; i++) {
            assertThrows(CredencialesInvalidasException.class,
                    () -> servicio.autenticar(CORREO, "incorrecta", "curl", "prueba"));
        }

        servicio.autenticar(CORREO, CLAVE, "curl", "prueba");
        assertEquals(0, usuario.getIntentosFallidos());
    }

    @Test
    void elMensajeNoRevelaLaCausaDelRechazo() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));
        when(usuarios.findByCorreo("nadie@bspstore.com.co")).thenReturn(Optional.empty());

        var porCorreo = assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar("nadie@bspstore.com.co", CLAVE, "curl", "prueba"));
        var porClave = assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(CORREO, "incorrecta", "curl", "prueba"));

        assertEquals(porCorreo.getMessage(), porClave.getMessage());
    }

    @Test
    void laSesionGuardaLaHuellaYNoElToken() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));

        var emitida = servicio.autenticar(CORREO, CLAVE, "curl", "prueba");

        verify(sesiones).save(argThat(s -> s.vigente()));
        assertEquals(64, Seguridad.huella(emitida.token()).length());
    }
}
