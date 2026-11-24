package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.ClienteEnLista;
import co.com.bspstore.sgip.api.dto.PaginaDeClientes;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioClientes;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * La lista paginada de clientes: lo que el servicio decide antes de preguntarle a la base (la empresa
 * sale de la sesión, la página y el tamaño se validan o se recortan, y el texto buscado se limpia y se
 * escapa). Lo que dice el SQL se prueba con base en {@code ClientesListaConBaseTest}.
 */
@ExtendWith(MockitoExtension.class)
class ServicioClientesListaTest {

    private static final int EMPRESA = 3;

    @Mock RepositorioClientes clientes;

    private ServicioClientes servicio;

    @BeforeEach
    void comoEmpresa() {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "VENDEDOR", EMPRESA);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
        servicio = new ServicioClientes(clientes, Clock.systemUTC());
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private static ClienteEnLista fila(int id) {
        return new ClienteEnLista(id, TipoDocumento.CC, "100" + id, "Cliente " + id, null, null, null,
                "Cali", true, new BigDecimal("0.00"), true);
    }

    @Test
    void listaConLaEmpresaDeLaSesionYSinBusqueda() {
        when(clientes.listar(EMPRESA, null, null, 20, 0L)).thenReturn(List.of(fila(1)));
        when(clientes.contar(EMPRESA, null, null)).thenReturn(1L);

        PaginaDeClientes pagina = servicio.listar(null, 0, 20);

        assertEquals(List.of(fila(1)), pagina.elementos());
        assertEquals(0, pagina.pagina());
        assertEquals(20, pagina.tamano());
        assertEquals(1L, pagina.total());
    }

    @Test
    void paginaYTotalCorrectos() {
        when(clientes.listar(EMPRESA, null, null, 10, 30L)).thenReturn(List.of(fila(31), fila(32)));
        when(clientes.contar(EMPRESA, null, null)).thenReturn(32L);

        PaginaDeClientes pagina = servicio.listar(null, 3, 10);

        assertEquals(2, pagina.elementos().size());
        assertEquals(3, pagina.pagina());
        assertEquals(10, pagina.tamano());
        assertEquals(32L, pagina.total());
    }

    @Test
    void paginaNegativaEs400() {
        assertThrows(FiltroInvalidoException.class, () -> servicio.listar(null, -1, 20));
        verifyNoInteractions(clientes);
    }

    @Test
    void tamanoMenorQue1Es400() {
        assertThrows(FiltroInvalidoException.class, () -> servicio.listar(null, 0, 0));
        assertThrows(FiltroInvalidoException.class, () -> servicio.listar(null, 0, -5));
        verifyNoInteractions(clientes);
    }

    @Test
    void tamanoMayorQue100SeLimita() {
        when(clientes.listar(EMPRESA, null, null, 100, 200L)).thenReturn(List.of());
        when(clientes.contar(EMPRESA, null, null)).thenReturn(0L);

        PaginaDeClientes pagina = servicio.listar(null, 2, 5000);

        assertEquals(100, pagina.tamano());
        verify(clientes).listar(EMPRESA, null, null, 100, 200L);
    }

    @Test
    void buscarSeRecortaYSeConvierteEnPrefijoDeDocumentoOContenidoDeNombre() {
        when(clientes.listar(eq(EMPRESA), any(), any(), anyInt(), anyLong())).thenReturn(List.of());
        when(clientes.contar(eq(EMPRESA), any(), any())).thenReturn(0L);

        servicio.listar("  Mar  ", 0, 20);

        verify(clientes).listar(EMPRESA, "Mar%", "%Mar%", 20, 0L);
        verify(clientes).contar(EMPRESA, "Mar%", "%Mar%");
    }

    @Test
    void buscarConComodinesEsLiteral() {
        when(clientes.listar(eq(EMPRESA), any(), any(), anyInt(), anyLong())).thenReturn(List.of());
        when(clientes.contar(eq(EMPRESA), any(), any())).thenReturn(0L);

        servicio.listar("50%_\\x", 0, 20);

        verify(clientes).listar(EMPRESA, "50\\%\\_\\\\x%", "%50\\%\\_\\\\x%", 20, 0L);
    }

    @Test
    void buscarVacioOSoloEspaciosEsSinBusqueda() {
        when(clientes.listar(EMPRESA, null, null, 20, 0L)).thenReturn(List.of());
        when(clientes.contar(EMPRESA, null, null)).thenReturn(0L);

        servicio.listar("   ", 0, 20);

        verify(clientes).listar(EMPRESA, null, null, 20, 0L);
    }
}
