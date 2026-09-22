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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InicializadorAdministradorTest {

    @Mock UsuarioRepositorio usuarios;
    @Mock RolRepositorio roles;

    @Test
    void creaElAdministradorCuandoLaTablaEstaVacia() {
        when(usuarios.count()).thenReturn(0L);
        when(roles.findByNombre("ADMINISTRADOR"))
                .thenReturn(Optional.of(new Rol("ADMINISTRADOR", "Administrador")));

        new InicializadorAdministrador(usuarios, roles, "Admin@BSP.co", "ClaveSegura1").run();

        verify(usuarios).save(argThat(u ->
                u.getCorreo().equals("admin@bsp.co")
                        && Seguridad.verificar("ClaveSegura1", u.getClaveHash())
                        && u.isCambioClaveRequerido()));
    }

    @Test
    void noHaceNadaSiYaExistenUsuarios() {
        when(usuarios.count()).thenReturn(3L);

        new InicializadorAdministrador(usuarios, roles, "a@bsp.co", "ClaveSegura1").run();

        verify(usuarios, never()).save(any());
    }

    @Test
    void noCreaNadieSinVariablesDeEntorno() {
        when(usuarios.count()).thenReturn(0L);

        new InicializadorAdministrador(usuarios, roles, "", "").run();

        verify(usuarios, never()).save(any());
    }

    @Test
    void seNiegaAArrancarConUnaClaveCorta() {
        when(usuarios.count()).thenReturn(0L);

        assertThrows(IllegalStateException.class,
                () -> new InicializadorAdministrador(usuarios, roles, "a@bsp.co", "corta").run());
    }
}