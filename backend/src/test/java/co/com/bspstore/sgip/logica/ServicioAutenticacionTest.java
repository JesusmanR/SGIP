package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.AuditoriaAccesoRepositorio;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServicioAutenticacionTest {

    private static final String CORREO = "admin@bspstore.com.co";
    private static final String CLAVE = "SgipDev2026";

    @Mock UsuarioRepositorio usuarios;
    @Mock AuditoriaAccesoRepositorio auditoria;

    ServicioAutenticacion servicio;
    Usuario usuario;

    @BeforeEach
    void preparar() {
        servicio = new ServicioAutenticacion(usuarios, auditoria, 5);
        Rol admin = new Rol("ADMINISTRADOR", "Administrador");
        usuario = new Usuario(admin, "Jesús Durán", CORREO, Seguridad.derivar(CLAVE));
    }

    @Test
    void autenticaConCredencialesCorrectasYLoAudita() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));

        assertSame(usuario, servicio.autenticar(CORREO, CLAVE, "prueba"));
        verify(auditoria).save(argThat(a -> a.isExitoso()));
    }

    @Test
    void normalizaElCorreoAntesDeBuscar() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));

        servicio.autenticar("  ADMIN@BSPstore.com.co ", CLAVE, "prueba");
        verify(usuarios).findByCorreo(CORREO);
    }

    @Test
    void rechazaUnCorreoInexistenteYRegistraElMotivo() {
        when(usuarios.findByCorreo(any())).thenReturn(Optional.empty());

        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar("nadie@bspstore.com.co", CLAVE, "prueba"));
        verify(auditoria).save(argThat(a ->
                !a.isExitoso() && "USUARIO_INEXISTENTE".equals(a.getMotivo())));
    }

    @Test
    void unaClaveIncorrectaIncrementaLosIntentos() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));

        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(CORREO, "incorrecta", "prueba"));
        assertEquals(1, usuario.getIntentosFallidos());
        assertFalse(usuario.isBloqueado());
    }

    @Test
    void elQuintoIntentoFallidoBloqueaLaCuenta() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));

        for (int i = 0; i < 5; i++) {
            assertThrows(CredencialesInvalidasException.class,
                    () -> servicio.autenticar(CORREO, "incorrecta", "prueba"));
        }
        assertTrue(usuario.isBloqueado());
    }

    @Test
    void unaCuentaBloqueadaRechazaInclusoLaClaveCorrecta() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));
        for (int i = 0; i < 5; i++) {
            assertThrows(CredencialesInvalidasException.class,
                    () -> servicio.autenticar(CORREO, "incorrecta", "prueba"));
        }

        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(CORREO, CLAVE, "prueba"));
        verify(auditoria).save(argThat(a -> "CUENTA_BLOQUEADA".equals(a.getMotivo())));
    }

    @Test
    void unIngresoExitosoReiniciaLosIntentos() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));
        for (int i = 0; i < 2; i++) {
            assertThrows(CredencialesInvalidasException.class,
                    () -> servicio.autenticar(CORREO, "incorrecta", "prueba"));
        }

        servicio.autenticar(CORREO, CLAVE, "prueba");
        assertEquals(0, usuario.getIntentosFallidos());
    }

    @Test
    void elMensajeNoRevelaLaCausaDelRechazo() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));
        when(usuarios.findByCorreo("nadie@bspstore.com.co")).thenReturn(Optional.empty());

        var porCorreo = assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar("nadie@bspstore.com.co", CLAVE, "prueba"));
        var porClave = assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(CORREO, "incorrecta", "prueba"));

        assertEquals(porCorreo.getMessage(), porClave.getMessage());
    }

    // ------------------------------------------------- desbloqueo (RF-12)

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    private void comoAdministradorDe(Integer empresaId) {
        var u = new UsuarioAutenticado(7, CORREO, "ADMINISTRADOR", empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    @Test
    void desbloqueaUnUsuarioDeSuPropiaEmpresa() {
        comoAdministradorDe(3);
        usuario.registrarIntentoFallido(1);
        assertTrue(usuario.isBloqueado(), "la cuenta llega bloqueada");
        when(usuarios.findByIdAndEmpresaId(20, 3)).thenReturn(Optional.of(usuario));

        assertTrue(servicio.desbloquear(20));
        assertFalse(usuario.isBloqueado());
        assertEquals(0, usuario.getIntentosFallidos());
    }

    /**
     * El identificador de usuario es único en toda la plataforma y la
     * anotación de rol del controlador solo comprueba que quien pide sea
     * administrador, no de qué empresa. Sin acotar la búsqueda, el
     * administrador de un comercio desbloqueaba cuentas de otro.
     */
    @Test
    void noDesbloqueaUnUsuarioDeOtraEmpresa() {
        comoAdministradorDe(3);
        when(usuarios.findByIdAndEmpresaId(20, 3)).thenReturn(Optional.empty());

        assertFalse(servicio.desbloquear(20), "para lo ajeno responde como para lo inexistente");
        verify(usuarios, never()).findById(any());
    }

    @Test
    void elDesbloqueoNuncaResuelvePorIdentificadorSuelto() {
        comoAdministradorDe(3);
        when(usuarios.findByIdAndEmpresaId(any(), any())).thenReturn(Optional.empty());

        servicio.desbloquear(20);

        // La empresa sale del contexto, no de la llamada: es lo que impide
        // que un número en la ruta alcance a otro comercio.
        verify(usuarios).findByIdAndEmpresaId(20, 3);
    }
}
