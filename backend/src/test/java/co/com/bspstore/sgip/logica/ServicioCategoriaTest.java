package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.config.SinEmpresaException;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.CategoriaRepositorio;
import co.com.bspstore.sgip.datos.ProductoRepositorio;
import co.com.bspstore.sgip.modelo.Categoria;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServicioCategoriaTest {

    @Mock CategoriaRepositorio categorias;
    @Mock ProductoRepositorio productos;
    @InjectMocks ServicioCategoria servicio;

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void comoEmpresa(Integer empresaId) {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    @Test
    void listaSoloLasCategoriasDeSuEmpresa() {
        comoEmpresa(3);
        when(categorias.findByEmpresaIdAndActivaTrueOrderByNombre(3))
                .thenReturn(List.of(new Categoria(3, "Bebidas", null)));

        assertEquals(1, servicio.listar().size());
        verify(categorias).findByEmpresaIdAndActivaTrueOrderByNombre(3);
    }

    @Test
    void dosEmpresasConsultanConFiltrosDistintos() {
        comoEmpresa(3);
        when(categorias.findByEmpresaIdAndActivaTrueOrderByNombre(any())).thenReturn(List.of());
        servicio.listar();

        comoEmpresa(8);
        servicio.listar();

        verify(categorias).findByEmpresaIdAndActivaTrueOrderByNombre(3);
        verify(categorias).findByEmpresaIdAndActivaTrueOrderByNombre(8);
    }

    @Test
    void buscarPorIdTambienAcotaPorEmpresa() {
        // Pedir el identificador de una categoría ajena no debe devolverla.
        comoEmpresa(3);
        when(categorias.findByEmpresaIdAndId(3, 99)).thenReturn(Optional.empty());

        assertEquals(Optional.empty(), servicio.buscar(99));
        verify(categorias).findByEmpresaIdAndId(3, 99);
    }

    @Test
    void creaConLaEmpresaDelContextoYNoConUnaRecibida() {
        comoEmpresa(3);
        when(categorias.existsByEmpresaIdAndNombreIgnoreCase(3, "Bebidas")).thenReturn(false);
        when(categorias.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.crear("  Bebidas  ", "Gaseosas y jugos");

        ArgumentCaptor<Categoria> captor = ArgumentCaptor.forClass(Categoria.class);
        verify(categorias).save(captor.capture());
        assertEquals(3, captor.getValue().getEmpresaId());
        assertEquals("Bebidas", captor.getValue().getNombre());
    }

    @Test
    void rechazaUnNombreQueYaExisteEnEsaEmpresa() {
        comoEmpresa(3);
        when(categorias.existsByEmpresaIdAndNombreIgnoreCase(3, "Bebidas")).thenReturn(true);

        assertThrows(NombreRepetidoException.class, () -> servicio.crear("Bebidas", null));
        verify(categorias, never()).save(any());
    }

    @Test
    void elUsuarioDePlataformaNoPuedeTocarElCatalogoDeNadie() {
        comoEmpresa(null);

        assertThrows(SinEmpresaException.class, () -> servicio.listar());
        assertThrows(SinEmpresaException.class, () -> servicio.crear("Bebidas", null));
        verify(categorias, never()).save(any());
    }

    // ── Edición y desactivación (RF-19) ──────────────────────────────
    @Test
    void editarUnaCategoriaDeOtraEmpresaNoEncuentraNada() {
        comoEmpresa(3);
        when(categorias.findByEmpresaIdAndId(3, 8)).thenReturn(Optional.empty());

        assertTrue(servicio.actualizar(8, "Granos", null).isEmpty());
        verify(categorias, never()).save(any());
    }

    @Test
    void guardarSinCambiarElNombreNoLoCuentaComoRepetido() {
        comoEmpresa(3);
        when(categorias.findByEmpresaIdAndId(3, 8))
                .thenReturn(Optional.of(new Categoria(3, "Bebidas", null)));
        // La comprobación excluye a la propia categoría.
        when(categorias.existsByEmpresaIdAndNombreIgnoreCaseAndIdNot(3, "Bebidas", 8))
                .thenReturn(false);
        when(categorias.save(any())).thenAnswer(i -> i.getArgument(0));

        assertTrue(servicio.actualizar(8, "  Bebidas  ", "Gaseosas y jugos").isPresent());
    }

    @Test
    void elNombreDeOtraCategoriaSigueSiendoRepetido() {
        comoEmpresa(3);
        when(categorias.findByEmpresaIdAndId(3, 8))
                .thenReturn(Optional.of(new Categoria(3, "Bebidas", null)));
        when(categorias.existsByEmpresaIdAndNombreIgnoreCaseAndIdNot(3, "Granos", 8))
                .thenReturn(true);

        assertThrows(NombreRepetidoException.class,
                () -> servicio.actualizar(8, "Granos", null));
        verify(categorias, never()).save(any());
    }

    @Test
    void desactivaUnaCategoriaQueNoSostieneProductos() {
        comoEmpresa(3);
        Categoria c = new Categoria(3, "Bebidas", null);
        when(categorias.findByEmpresaIdAndId(3, 8)).thenReturn(Optional.of(c));
        when(productos.countByEmpresaIdAndCategoriaIdAndActivoTrue(3, 8)).thenReturn(0L);
        when(categorias.save(any())).thenAnswer(i -> i.getArgument(0));

        assertTrue(servicio.desactivar(8).isPresent());
        assertFalse(c.isActiva());
    }

    @Test
    void seNiegaADesactivarUnaCategoriaConProductosActivos() {
        comoEmpresa(3);
        when(categorias.findByEmpresaIdAndId(3, 8))
                .thenReturn(Optional.of(new Categoria(3, "Bebidas", null)));
        when(productos.countByEmpresaIdAndCategoriaIdAndActivoTrue(3, 8)).thenReturn(4L);

        // Dejarla desactivar dejaría cuatro productos mostrando una
        // categoría que ya no aparece en ninguna lista.
        CategoriaEnUsoException e = assertThrows(CategoriaEnUsoException.class,
                () -> servicio.desactivar(8));
        assertTrue(e.getMessage().contains("4"));
        verify(categorias, never()).save(any());
    }

    @Test
    void desactivarUnaCategoriaDeOtraEmpresaNoEncuentraNada() {
        comoEmpresa(3);
        when(categorias.findByEmpresaIdAndId(3, 8)).thenReturn(Optional.empty());

        assertTrue(servicio.desactivar(8).isEmpty());
        verify(productos, never()).countByEmpresaIdAndCategoriaIdAndActivoTrue(any(), any());
    }
}
