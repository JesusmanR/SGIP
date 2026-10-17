package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.SolicitudEdicionUsuario;
import co.com.bspstore.sgip.api.dto.SolicitudUsuario;
import co.com.bspstore.sgip.config.SinEmpresaException;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RolRepositorio;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServicioUsuariosTest {

    private static final int EMPRESA = 3;
    private static final int QUIEN_PREGUNTA = 7;

    @Mock UsuarioRepositorio usuarios;
    @Mock RolRepositorio roles;

    private ServicioUsuarios servicio;

    @BeforeEach
    void preparar() {
        servicio = new ServicioUsuarios(usuarios, roles);
        comoAdministradorDe(EMPRESA);
        when(roles.findByNombre(anyString()))
                .thenAnswer(i -> Optional.of(new Rol(i.getArgument(0), "rol de prueba")));
        when(usuarios.save(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void comoAdministradorDe(Integer empresaId) {
        var u = new UsuarioAutenticado(QUIEN_PREGUNTA, "ana@tienda.co", "ADMINISTRADOR", empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    private Usuario usuarioCon(String rol) {
        Usuario u = new Usuario(new Rol(rol, "rol"), "Pedro", "pedro@tienda.co",
                Seguridad.derivar("clave-de-prueba"));
        u.asignarEmpresa(EMPRESA);
        return u;
    }

    private SolicitudUsuario solicitud(String rol) {
        return new SolicitudUsuario("Pedro Gómez", "Pedro@Tienda.CO", "clave-de-prueba", rol);
    }

    // --------------------------------------------------------- crear

    @Test
    void elUsuarioCreadoQuedaLigadoALaEmpresaDeQuienLoCreo() {
        when(usuarios.existsByCorreo(anyString())).thenReturn(false);

        servicio.crear(solicitud("VENDEDOR"));

        ArgumentCaptor<Usuario> capturado = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarios).save(capturado.capture());
        Usuario creado = capturado.getValue();
        assertEquals(Integer.valueOf(EMPRESA), creado.getEmpresaId());
        assertEquals("pedro@tienda.co", creado.getCorreo(), "el correo se normaliza");
        assertEquals("VENDEDOR", creado.getRol().getNombre());
        assertTrue(creado.isCambioClaveRequerido(), "la clave del alta sirve una vez");
        assertTrue(Seguridad.verificar("clave-de-prueba", creado.getClaveHash()));
    }

    /**
     * La línea que más importa del requisito. Si un administrador pudiera
     * asignar SUPER_ADMIN, se daría el mando de la plataforma entera
     * desde la pantalla de usuarios de su propia tienda.
     */
    @Test
    void unAdministradorNoPuedeRepartirElRolDeSuperAdministrador() {
        when(usuarios.existsByCorreo(anyString())).thenReturn(false);

        var e = assertThrows(RolNoAsignableException.class,
                () -> servicio.crear(solicitud("SUPER_ADMIN")));

        assertTrue(e.getMessage().contains("SUPER_ADMIN"));
        verify(usuarios, never()).save(any());
    }

    @Test
    void unRolInventadoTampocoSeAsigna() {
        when(usuarios.existsByCorreo(anyString())).thenReturn(false);

        assertThrows(RolNoAsignableException.class, () -> servicio.crear(solicitud("DIOS")));
        verify(usuarios, never()).save(any());
    }

    @Test
    void elRolSeNormalizaAMayusculas() {
        when(usuarios.existsByCorreo(anyString())).thenReturn(false);

        servicio.crear(solicitud("  vendedor "));

        verify(roles).findByNombre("VENDEDOR");
    }

    @Test
    void unCorreoYaRegistradoEnLaPlataformaSeRechaza() {
        when(usuarios.existsByCorreo("pedro@tienda.co")).thenReturn(true);

        var e = assertThrows(RegistroRepetidoException.class,
                () -> servicio.crear(solicitud("VENDEDOR")));

        assertTrue(e.getMessage().contains("pedro@tienda.co"));
        verify(usuarios, never()).save(any());
    }

    // ------------------------------------------------- lo ajeno es 404

    @Test
    void loDeOtraEmpresaSeResponderaComoLoInexistente() {
        when(usuarios.findByIdAndEmpresaId(20, EMPRESA)).thenReturn(Optional.empty());

        assertTrue(servicio.buscar(20).isEmpty());
        assertTrue(servicio.actualizar(20,
                new SolicitudEdicionUsuario("Otro", "VENDEDOR")).isEmpty());
        assertTrue(servicio.desactivar(20).isEmpty());
        assertTrue(servicio.activar(20).isEmpty());

        verify(usuarios, never()).save(any());
        // Nunca por identificador suelto: es lo que permitiría alcanzar otro comercio.
        verify(usuarios, never()).findById(any());
    }

    @Test
    void elListadoVaAcotadoALaEmpresaDelContexto() {
        when(usuarios.findByEmpresaIdOrderByNombre(EMPRESA)).thenReturn(List.of());

        servicio.listar();

        verify(usuarios).findByEmpresaIdOrderByNombre(EMPRESA);
    }

    /** Un usuario de plataforma no tiene empresa, de modo que no administra personal. */
    @Test
    void unUsuarioDePlataformaNoAdministraUsuariosDeNadie() {
        comoAdministradorDe(null);

        assertThrows(SinEmpresaException.class, () -> servicio.listar());
        assertThrows(SinEmpresaException.class, () -> servicio.crear(solicitud("VENDEDOR")));
    }

    // ------------------------------------- no dejar la empresa sin mando

    @Test
    void noSeDesactivaAlUnicoAdministradorActivo() {
        when(usuarios.findByIdAndEmpresaId(20, EMPRESA))
                .thenReturn(Optional.of(usuarioCon("ADMINISTRADOR")));
        when(usuarios.countByEmpresaIdAndRol_NombreAndActivoTrue(EMPRESA, "ADMINISTRADOR"))
                .thenReturn(1L);

        var e = assertThrows(UltimoAdministradorException.class, () -> servicio.desactivar(20));

        assertTrue(e.getMessage().contains("único administrador"));
        verify(usuarios, never()).save(any());
    }

    @Test
    void conOtroAdministradorActivoSiSeDesactiva() {
        when(usuarios.findByIdAndEmpresaId(20, EMPRESA))
                .thenReturn(Optional.of(usuarioCon("ADMINISTRADOR")));
        when(usuarios.countByEmpresaIdAndRol_NombreAndActivoTrue(EMPRESA, "ADMINISTRADOR"))
                .thenReturn(2L);

        Optional<Usuario> resultado = servicio.desactivar(20);

        assertTrue(resultado.isPresent());
        assertFalse(resultado.get().isActivo());
    }

    @Test
    void nadieSeDesactivaASiMismo() {
        when(usuarios.findByIdAndEmpresaId(QUIEN_PREGUNTA, EMPRESA))
                .thenReturn(Optional.of(usuarioCon("ADMINISTRADOR")));

        var e = assertThrows(UltimoAdministradorException.class,
                () -> servicio.desactivar(QUIEN_PREGUNTA));

        assertTrue(e.getMessage().contains("tu propia cuenta"));
        verify(usuarios, never()).save(any());
        // Ni se molesta en contar administradores: la razón es otra.
        verify(usuarios, never()).countByEmpresaIdAndRol_NombreAndActivoTrue(anyInt(), anyString());
    }

    @Test
    void noSeBajaDeRolAlUnicoAdministradorActivo() {
        when(usuarios.findByIdAndEmpresaId(20, EMPRESA))
                .thenReturn(Optional.of(usuarioCon("ADMINISTRADOR")));
        when(usuarios.countByEmpresaIdAndRol_NombreAndActivoTrue(EMPRESA, "ADMINISTRADOR"))
                .thenReturn(1L);

        var e = assertThrows(UltimoAdministradorException.class,
                () -> servicio.actualizar(20, new SolicitudEdicionUsuario("Pedro", "VENDEDOR")));

        assertTrue(e.getMessage().contains("rol"));
        verify(usuarios, never()).save(any());
    }

    @Test
    void alUnicoAdministradorSiSeLePuedeCambiarElNombre() {
        when(usuarios.findByIdAndEmpresaId(20, EMPRESA))
                .thenReturn(Optional.of(usuarioCon("ADMINISTRADOR")));
        when(usuarios.countByEmpresaIdAndRol_NombreAndActivoTrue(EMPRESA, "ADMINISTRADOR"))
                .thenReturn(1L);

        Optional<Usuario> resultado = servicio.actualizar(20,
                new SolicitudEdicionUsuario("Pedro Gómez", "ADMINISTRADOR"));

        assertTrue(resultado.isPresent(), "sigue siendo administrador, no hay nada que proteger");
        assertEquals("Pedro Gómez", resultado.get().getNombre());
    }

    @Test
    void desactivarAUnVendedorNoCuentaAdministradores() {
        when(usuarios.findByIdAndEmpresaId(20, EMPRESA))
                .thenReturn(Optional.of(usuarioCon("VENDEDOR")));

        assertTrue(servicio.desactivar(20).isPresent());

        verify(usuarios, never()).countByEmpresaIdAndRol_NombreAndActivoTrue(anyInt(), anyString());
    }

    @Test
    void activarVuelveAHabilitarLaCuenta() {
        Usuario desactivado = usuarioCon("VENDEDOR");
        desactivado.desactivar();
        when(usuarios.findByIdAndEmpresaId(20, EMPRESA)).thenReturn(Optional.of(desactivado));

        Optional<Usuario> resultado = servicio.activar(20);

        assertTrue(resultado.isPresent());
        assertTrue(resultado.get().isActivo());
    }
}
