package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.api.dto.SolicitudEdicionProducto;
import co.com.bspstore.sgip.logica.ServicioProducto;
import co.com.bspstore.sgip.modelo.Producto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

/**
 * Dos personas editan a la vez el costo del mismo producto, que no tiene histórico
 * (N1).
 *
 * Sin nada que las ponga en fila, las dos escriben su periodo en
 * {@code historico_costos} antes de guardar el producto, y los bloqueos que cada una
 * toma en esa escritura (por ejemplo, los de hueco del DELETE y del UPDATE sobre
 * filas que aún no existen, y el compartido de la clave foránea sobre el producto)
 * hacen que se esperen entre sí. InnoDB resuelve el ciclo matando a una (1213), que
 * llegaría al cliente como un 500; en la prueba, la víctima fue el INSERT en el
 * histórico. Para que el entrelazado no dependa de la suerte, la primera que termina
 * de escribir el histórico espera (con tope) a que la otra también lo haya escrito.
 * Con el producto bloqueado antes del histórico, la segunda se queda esperando ese
 * bloqueo, la primera se cansa de esperar y confirma, y la segunda se rechaza por la
 * versión (409), sin interbloqueo.
 *
 * Crea su propia empresa y lo borra todo al terminar.
 */
@Tag("conBase")
@SpringBootTest
class EdicionDeCostoConcurrenteConBaseTest {

    @Autowired ServicioProducto productos;
    @Autowired ProductoRepositorio repositorioProductos;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean RepositorioCostos costos;

    private EmpresaDePrueba empresa;
    private Integer productoId;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = EmpresaDePrueba.crear("Costos simultaneos", plataforma, jdbc, categorias, proveedores);
        empresa.entrar();
        String codigo = "CC-" + empresa.marca;
        Producto p = new Producto(empresa.empresaId, empresa.categoriaId, empresa.proveedorId,
                codigo, codigo, "Producto " + codigo, new BigDecimal("1000.00"));
        p.fijarExistenciaMinima(0);
        productoId = repositorioProductos.save(p).getId();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresa != null) {
            empresa.borrar(jdbc);
        }
    }

    private SolicitudEdicionProducto editando(ProductoEnCatalogo p, String costo) {
        return new SolicitudEdicionProducto(empresa.categoriaId, empresa.proveedorId, p.codigo(),
                p.sku(), null, p.nombre(), null, null, new BigDecimal(costo), 0, null, null, null,
                p.version());
    }

    @Test
    void dosEdicionesSimultaneasDelCostoNoSeInterbloqueanYElHistoricoQuedaCoherente() throws Exception {
        ProductoEnCatalogo abierto = productos.buscar(productoId).orElseThrow();
        var unaEdicion = editando(abierto, "1200.00");
        var otraEdicion = editando(abierto, "1300.00");

        CyclicBarrier ambasEscribieron = new CyclicBarrier(2);
        doAnswer(llamada -> {
            Object resultado = llamada.callRealMethod();
            try {
                ambasEscribieron.await(3, TimeUnit.SECONDS);
            } catch (Exception sinLaOtra) {
                // La otra espera un bloqueo: es lo que pasa con el producto bloqueado primero.
            }
            return resultado;
        }).when(costos).registrarCambio(anyInt(), anyInt(), any(), any());

        SecurityContext contexto = SecurityContextHolder.getContext();
        CountDownLatch salida = new CountDownLatch(1);
        Callable<Throwable> edicion1 = edicion(contexto, salida, unaEdicion);
        Callable<Throwable> edicion2 = edicion(contexto, salida, otraEdicion);

        ExecutorService hilos = Executors.newFixedThreadPool(2);
        List<Throwable> resultados = new ArrayList<>();
        try {
            Future<Throwable> uno = hilos.submit(edicion1);
            Future<Throwable> otro = hilos.submit(edicion2);
            salida.countDown();
            resultados.add(uno.get(60, TimeUnit.SECONDS));
            resultados.add(otro.get(60, TimeUnit.SECONDS));
        } finally {
            hilos.shutdownNow();
            hilos.awaitTermination(30, TimeUnit.SECONDS);
        }

        long guardadas = resultados.stream().filter(r -> r == null).count();
        assertTrue(guardadas >= 1, "al menos una edición se guarda: " + resultados);
        resultados.stream().filter(r -> r != null).forEach(r -> assertInstanceOf(
                ObjectOptimisticLockingFailureException.class, r,
                "la que pierde se rechaza por la versión (409), no por un interbloqueo: " + r));

        BigDecimal costoDelProducto = jdbc.sql("""
                SELECT costo FROM productos WHERE empresa_id = :e AND producto_id = :p
                """).param("e", empresa.empresaId).param("p", productoId)
                .query(BigDecimal.class).single();
        List<BigDecimal> abiertos = jdbc.sql("""
                SELECT costo FROM historico_costos
                 WHERE empresa_id = :e AND producto_id = :p AND vigente_hasta IS NULL
                """).param("e", empresa.empresaId).param("p", productoId)
                .query(BigDecimal.class).list();
        assertEquals(1, abiertos.size(), "un solo periodo abierto");
        assertEquals(0, costoDelProducto.compareTo(abiertos.get(0)),
                "el histórico dice lo mismo que el producto");
        assertEquals(LocalDate.now(java.time.ZoneId.of("America/Bogota")), jdbc.sql("""
                SELECT vigente_desde FROM historico_costos
                 WHERE empresa_id = :e AND producto_id = :p AND vigente_hasta IS NULL
                """).param("e", empresa.empresaId).param("p", productoId)
                .query(LocalDate.class).single());
    }

    private Callable<Throwable> edicion(SecurityContext contexto, CountDownLatch salida,
                                        SolicitudEdicionProducto solicitud) {
        return () -> {
            SecurityContextHolder.setContext(contexto);
            try {
                salida.await();
                productos.actualizar(productoId, solicitud);
                return null;
            } catch (Throwable t) {
                return t;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }
}
