package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.OrdenDeCompra;
import co.com.bspstore.sgip.logica.ServicioCompras;
import co.com.bspstore.sgip.modelo.Producto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El listado de órdenes de compra no hace una consulta por orden.
 *
 * Presupuesto: el número de sentencias que lanza {@code GET /compras/ordenes}
 * no depende de cuántas órdenes haya. Hibernate Statistics no sirve aquí porque
 * el repositorio usa {@code JdbcClient}; se cuenta en su propia puerta, con un
 * espía sobre el bean: cada sentencia nace de una llamada a {@code sql(...)}.
 * También se comprueba que las líneas salgan en su orden y cada una en su orden
 * de compra, que es lo que podría romperse al juntarlas en una sola consulta.
 *
 * Crea su propia empresa y lo borra todo al terminar.
 */
@Tag("conBase")
@SpringBootTest
class ListadoDeOrdenesConsultasConBaseTest {

    @Autowired ServicioCompras compras;
    @Autowired RepositorioCompras repositorioCompras;
    @Autowired ProductoRepositorio repositorioProductos;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @MockitoSpyBean JdbcClient jdbc;

    private EmpresaDePrueba empresa;
    private Integer productoA;
    private Integer productoB;
    private int ordenes;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = EmpresaDePrueba.crear("Listado de ordenes", plataforma, jdbc, categorias, proveedores);
        empresa.entrar();
        productoA = producto("A");
        productoB = producto("B");
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresa != null) {
            empresa.borrar(jdbc);
        }
    }

    private Integer producto(String letra) {
        String codigo = letra + "-" + empresa.marca;
        Producto p = new Producto(empresa.empresaId, empresa.categoriaId, empresa.proveedorId,
                codigo, codigo, "Producto " + letra, new BigDecimal("1000.00"));
        p.fijarExistenciaMinima(0);
        return repositorioProductos.save(p).getId();
    }

    /** Órdenes de dos líneas cada una, con una cantidad distinta por orden. */
    private void ordenesConDosLineas(int cuantas) {
        for (int i = 0; i < cuantas; i++) {
            ordenes++;
            Long orden = repositorioCompras.insertarOrden(empresa.empresaId, empresa.proveedorId,
                    empresa.usuarioId, "OC-" + ordenes, null);
            repositorioCompras.insertarItem(empresa.empresaId, orden, productoA, ordenes, new BigDecimal("10.00"));
            repositorioCompras.insertarItem(empresa.empresaId, orden, productoB, ordenes + 100, new BigDecimal("20.00"));
        }
    }

    private long sentencias() {
        return Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(llamada -> llamada.getMethod().getName().equals("sql"))
                .count();
    }

    private long sentenciasDelListado(int esperadas) {
        Mockito.clearInvocations(jdbc);
        List<OrdenDeCompra> listado = compras.listar(null, 50);
        long lanzadas = sentencias();
        assertEquals(esperadas, listado.size());
        return lanzadas;
    }

    @Test
    void elNumeroDeConsultasNoCrecePorCadaOrden() {
        ordenesConDosLineas(2);
        long conDos = sentenciasDelListado(2);

        ordenesConDosLineas(18);
        long conVeinte = sentenciasDelListado(20);

        assertEquals(conDos, conVeinte,
                "con 2 órdenes se lanzaron " + conDos + " consultas y con 20, " + conVeinte);
        assertTrue(conVeinte <= 2, "una por las cabeceras y otra por todas las líneas, y fueron " + conVeinte);
    }

    @Test
    void cadaOrdenConservaSusLineasEnOrdenAlfabeticoDeProducto() {
        ordenesConDosLineas(3);

        List<OrdenDeCompra> listado = compras.listar(null, 50);

        assertEquals(3, listado.size());
        for (OrdenDeCompra orden : listado) {
            int numero = Integer.parseInt(orden.consecutivo().substring("OC-".length()));
            assertEquals(List.of("Producto A", "Producto B"),
                    orden.items().stream().map(OrdenDeCompra.LineaDeOrden::producto).toList());
            assertEquals(List.of(numero, numero + 100),
                    orden.items().stream().map(OrdenDeCompra.LineaDeOrden::cantidad).toList(),
                    "las líneas de la " + orden.consecutivo() + " no pueden ser las de otra orden");
        }
    }
}
