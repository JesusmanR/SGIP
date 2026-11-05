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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ServicioAutenticacionTest {

    private static final String CORREO = "admin@bspstore.com.co";
    private static final String CLAVE = "SgipDev2026";

    @Mock UsuarioRepositorio usuarios;
    @Mock AuditoriaAccesoRepositorio auditoria;

    ServicioAutenticacion servicio;
    Usuario usuario;
    RelojAjustable reloj;

    /** Un reloj que la prueba adelanta a mano; un mock devolvería nulo en instant(). */
    private static final class RelojAjustable extends Clock {
        private Instant ahora = Instant.parse("2026-11-05T15:00:00Z");

        void adelantarMinutos(long minutos) {
            ahora = ahora.plusSeconds(minutos * 60);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    private static final int MINUTOS_DE_BLOQUEO = 15;
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 5, 15, 0);

    @BeforeEach
    void preparar() {
        reloj = new RelojAjustable();
        servicio = new ServicioAutenticacion(usuarios, auditoria, reloj, 5, MINUTOS_DE_BLOQUEO);
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

    // ------------------------------------------- el bloqueo caduca (I2)

    private void bloquearConCincoErrores() {
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));
        for (int i = 0; i < 5; i++) {
            assertThrows(CredencialesInvalidasException.class,
                    () -> servicio.autenticar(CORREO, "incorrecta", "prueba"));
        }
        assertTrue(usuario.isBloqueado(), "la cuenta llega bloqueada");
    }

    @Test
    void elBloqueoGuardaElInstanteEnQueOcurrio() {
        bloquearConCincoErrores();

        assertEquals(AHORA, usuario.getBloqueadoEn());
    }

    @Test
    void dentroDelPlazoLaClaveCorrectaSigueRechazada() {
        bloquearConCincoErrores();
        reloj.adelantarMinutos(MINUTOS_DE_BLOQUEO - 1);

        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(CORREO, CLAVE, "prueba"));
        assertTrue(usuario.isBloqueado());
    }

    @Test
    void alVencerElPlazoLaClaveCorrectaEntraYLaCuentaQuedaLimpia() {
        bloquearConCincoErrores();
        reloj.adelantarMinutos(MINUTOS_DE_BLOQUEO);

        assertSame(usuario, servicio.autenticar(CORREO, CLAVE, "prueba"));

        assertFalse(usuario.isBloqueado());
        assertEquals(0, usuario.getIntentosFallidos());
        assertNull(usuario.getBloqueadoEn());
    }

    /** Al vencer, los intentos vuelven a cero: un error no vuelve a bloquear de inmediato. */
    @Test
    void alVencerElPlazoUnaClaveErradaEmpiezaLaCuentaDeNuevo() {
        bloquearConCincoErrores();
        reloj.adelantarMinutos(MINUTOS_DE_BLOQUEO);

        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(CORREO, "incorrecta", "prueba"));

        assertFalse(usuario.isBloqueado());
        assertEquals(1, usuario.getIntentosFallidos());
        assertNull(usuario.getBloqueadoEn());
    }

    @Test
    void elPlazoSeConfiguraYNoEstaEscritoEnElCodigo() {
        servicio = new ServicioAutenticacion(usuarios, auditoria, reloj, 5, 30);
        bloquearConCincoErrores();
        reloj.adelantarMinutos(29);
        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(CORREO, CLAVE, "prueba"));

        reloj.adelantarMinutos(1);

        assertSame(usuario, servicio.autenticar(CORREO, CLAVE, "prueba"));
    }

    /**
     * Un bloqueo anterior a la V37 no tiene instante. No hay desde cuando
     * contar, y dejarlo bloqueado para siempre es justo lo que esto corrige.
     */
    @Test
    void unBloqueoSinInstanteSeTomaPorVencido() {
        ReflectionTestUtils.setField(usuario, "bloqueado", true);
        ReflectionTestUtils.setField(usuario, "intentosFallidos", 5);
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));

        assertSame(usuario, servicio.autenticar(CORREO, CLAVE, "prueba"));
        assertFalse(usuario.isBloqueado());
    }

    /**
     * El 401 es el mismo con la clave errada, la cuenta bloqueada o el usuario
     * inexistente, y lleva el plazo configurado: quien se equivoca y no entra
     * necesita saber que esperar sirve, sin que eso diga que le pasó.
     */
    @Test
    void elRechazoEsIgualConClaveErradaCuentaBloqueadaOUsuarioInexistente() {
        when(usuarios.findByCorreo("nadie@bspstore.com.co")).thenReturn(Optional.empty());
        var inexistente = assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar("nadie@bspstore.com.co", CLAVE, "prueba"));
        when(usuarios.findByCorreo(CORREO)).thenReturn(Optional.of(usuario));
        var claveErrada = assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(CORREO, "incorrecta", "prueba"));
        for (int i = 0; i < 4; i++) {
            assertThrows(CredencialesInvalidasException.class,
                    () -> servicio.autenticar(CORREO, "incorrecta", "prueba"));
        }
        var bloqueada = assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(CORREO, CLAVE, "prueba"));

        assertTrue(usuario.isBloqueado());
        assertEquals(MINUTOS_DE_BLOQUEO, inexistente.getMinutosDeBloqueo());
        assertEquals(MINUTOS_DE_BLOQUEO, claveErrada.getMinutosDeBloqueo());
        assertEquals(MINUTOS_DE_BLOQUEO, bloqueada.getMinutosDeBloqueo());
        assertEquals(inexistente.getMessage(), claveErrada.getMessage());
        assertEquals(inexistente.getMessage(), bloqueada.getMessage());
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
        usuario.registrarIntentoFallido(1, AHORA);
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

    // ------------------------------------------ cambio de contraseña inicial

    @Test
    void cambiarLaClaveGuardaLaNuevaYQuitaLaObligacion() {
        when(usuarios.findById(20)).thenReturn(Optional.of(usuario));
        assertTrue(usuario.isCambioClaveRequerido(), "el usuario nace con el cambio pendiente");

        servicio.cambiarClave(20, CLAVE, "NuevaClave2026", "10.0.0.5");

        assertFalse(usuario.isCambioClaveRequerido());
        assertTrue(Seguridad.verificar("NuevaClave2026", usuario.getClaveHash()));
        assertFalse(Seguridad.verificar(CLAVE, usuario.getClaveHash()),
                "la contraseña anterior deja de servir");
        // Acertar la actual vuelve a empezar la cuenta de intentos.
        assertEquals(0, usuario.getIntentosFallidos());
    }

    @Test
    void laClaveActualIncorrectaSeRechazaYCuentaComoIntento() {
        when(usuarios.findById(20)).thenReturn(Optional.of(usuario));
        String hashAntes = usuario.getClaveHash();

        assertThrows(ClaveActualIncorrectaException.class,
                () -> servicio.cambiarClave(20, "incorrecta", "NuevaClave2026", "10.0.0.5"));

        assertEquals(1, usuario.getIntentosFallidos(),
                "adivinar la actual desde una sesión robada no puede salir gratis");
        assertEquals(hashAntes, usuario.getClaveHash());
        assertTrue(usuario.isCambioClaveRequerido());
    }

    /** M22: el error al cambiar la clave deja el mismo rastro que el del ingreso. */
    @Test
    void laClaveActualIncorrectaQuedaEnLaAuditoriaDeAccesos() {
        when(usuarios.findById(20)).thenReturn(Optional.of(usuario));

        assertThrows(ClaveActualIncorrectaException.class,
                () -> servicio.cambiarClave(20, "incorrecta-secreta", "NuevaClave2026", "10.0.0.5"));

        verify(auditoria).save(argThat(a -> !a.isExitoso()
                && "CLAVE_INCORRECTA".equals(a.getMotivo())
                && CORREO.equals(a.getCorreo())
                && "10.0.0.5".equals(a.getOrigen())
                && !a.toString().contains("incorrecta-secreta")));
    }

    @Test
    void unaClaveNuevaRechazadaNoCuentaComoAccesoFallido() {
        when(usuarios.findById(20)).thenReturn(Optional.of(usuario));

        assertThrows(ClaveNuevaInvalidaException.class,
                () -> servicio.cambiarClave(20, CLAVE, "corta", "10.0.0.5"));

        verify(auditoria, never()).save(any());
    }

    @Test
    void elQuintoIntentoConLaActualIncorrectaBloqueaLaCuenta() {
        when(usuarios.findById(20)).thenReturn(Optional.of(usuario));

        for (int i = 0; i < 5; i++) {
            assertThrows(ClaveActualIncorrectaException.class,
                    () -> servicio.cambiarClave(20, "incorrecta", "NuevaClave2026", "10.0.0.5"));
        }

        assertTrue(usuario.isBloqueado());
    }

    @Test
    void laClaveNuevaIgualALaActualSeRechaza() {
        when(usuarios.findById(20)).thenReturn(Optional.of(usuario));
        String hashAntes = usuario.getClaveHash();

        assertThrows(ClaveNuevaInvalidaException.class,
                () -> servicio.cambiarClave(20, CLAVE, CLAVE, "10.0.0.5"));

        assertEquals(hashAntes, usuario.getClaveHash());
        assertTrue(usuario.isCambioClaveRequerido(),
                "si la nueva es la inicial, el cambio sigue pendiente");
    }

    @Test
    void unaClaveNuevaDemasiadoCortaSeRechazaAunqueElDtoNoLaHayaFiltrado() {
        when(usuarios.findById(20)).thenReturn(Optional.of(usuario));

        assertThrows(ClaveNuevaInvalidaException.class,
                () -> servicio.cambiarClave(20, CLAVE, "corta", "10.0.0.5"));
        assertTrue(usuario.isCambioClaveRequerido());
    }

    @Test
    void cambiarLaClaveDeUnUsuarioInexistenteEsUnRecursoNoEncontrado() {
        when(usuarios.findById(99)).thenReturn(Optional.empty());

        assertThrows(RecursoNoEncontradoException.class,
                () -> servicio.cambiarClave(99, CLAVE, "NuevaClave2026", "10.0.0.5"));
    }
}
