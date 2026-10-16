package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.datos.RolRepositorio;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InicializadorAdministradorTest {

    @Mock UsuarioRepositorio usuarios;
    @Mock RolRepositorio roles;

    private InicializadorAdministrador inicializador(String correo, String clave) {
        return new InicializadorAdministrador(usuarios, roles, correo, clave, 1);
    }

    private void existeElRol() {
        when(roles.findByNombre("ADMINISTRADOR"))
                .thenReturn(Optional.of(new Rol("ADMINISTRADOR", "Administrador")));
    }

    @Test
    void creaElAdministradorCuandoEseCorreoNoExiste() {
        when(usuarios.existsByCorreo("admin@bsp.co")).thenReturn(false);
        existeElRol();

        inicializador("Admin@BSP.co", "ClaveSegura1").run();

        verify(usuarios).save(argThat(u ->
                u.getCorreo().equals("admin@bsp.co")
                        && Seguridad.verificar("ClaveSegura1", u.getClaveHash())
                        && u.isCambioClaveRequerido()));
    }

    @Test
    void leAsignaLaEmpresaConfigurada() {
        // Sin empresa, ContextoEmpresa lo tomaría por usuario de plataforma
        // y le respondería 403 en todo el catálogo.
        when(usuarios.existsByCorreo(any())).thenReturn(false);
        existeElRol();

        new InicializadorAdministrador(usuarios, roles, "a@bsp.co", "ClaveSegura1", 4).run();

        verify(usuarios).save(argThat(u -> u.getEmpresaId() == 4));
    }

    @Test
    void loCreaAunqueLaTablaYaTengaOtrosUsuarios() {
        // Es el caso de una base con los datos de demostración de la V8:
        // tiene usuarios, pero con hashes que no verifican nada.
        when(usuarios.existsByCorreo("real@bsp.co")).thenReturn(false);
        existeElRol();

        inicializador("real@bsp.co", "ClaveSegura1").run();

        verify(usuarios).save(any());
    }

    @Test
    void comparaElCorreoYaNormalizado() {
        // Usuario guarda el correo en minúsculas. Si la comprobación usara
        // la forma tal cual la escribió el operador, no encontraría la fila
        // y el alta chocaría contra uq_usuarios_correo.
        when(usuarios.existsByCorreo("admin@bsp.co")).thenReturn(true);

        inicializador("  Admin@BSP.co  ", "ClaveSegura1").run();

        verify(usuarios, never()).save(any());
    }

    @Test
    void noLoDuplicaSiEseCorreoYaExiste() {
        when(usuarios.existsByCorreo("a@bsp.co")).thenReturn(true);

        inicializador("a@bsp.co", "ClaveSegura1").run();

        verify(usuarios, never()).save(any());
    }

    @Test
    void noCreaNadieSinVariablesDeEntorno() {
        inicializador("", "").run();

        verify(usuarios, never()).save(any());
    }

    @Test
    void seNiegaAArrancarConUnaClaveCorta() {
        when(usuarios.existsByCorreo("a@bsp.co")).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> inicializador("a@bsp.co", "corta").run());
    }
}
