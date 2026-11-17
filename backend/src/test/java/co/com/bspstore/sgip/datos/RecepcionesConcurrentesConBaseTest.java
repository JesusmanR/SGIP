package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.SolicitudRecepcion;
import co.com.bspstore.sgip.logica.ServicioCompras;
import co.com.bspstore.sgip.modelo.Producto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

/**
 * Dos recepciones de órdenes distintas con los mismos dos productos, en orden inverso.
 *
 * Si la recepción bloquea el saldo de cada producto uno por uno, en el orden en que
 * llegan las líneas, la primera toma el producto B y la segunda el A, y después cada una
 * pide el que tiene la otra: un ciclo que InnoDB resuelve matando a una (1213). Para que
 * el cruce no dependa de la suerte, cada hilo espera (con tope) a que el otro haya
 * tomado su primer producto. Con los productos bloqueados todos juntos y en orden
 * ascendente de identificador, el segundo hilo se queda esperando el primero antes de
 * tomar nada, el primero se cansa de esperar la barrera y confirma, y las dos
 * recepciones terminan bien, una detrás de la otra.
 *
 * Crea su propia empresa y lo borra todo al terminar.
 */
@Tag("conBase")
@SpringBootTest
class RecepcionesConcurrentesConBaseTest {

    @Autowired ServicioCompras compras;
    @Autowired RepositorioCompras repositorioCompras;
    @MockitoSpyBean RepositorioInventario inventario;
    @Autowired ProductoRepositorio repositorioProductos;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;

    private EmpresaDePrueba empresa;
    private Integer menor;
    private Integer mayor;
    private Long ordenUno;
    private Long ordenDos;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = EmpresaDePrueba.crear("Recepciones simultaneas", plataforma, jdbc, categorias, proveedores);
        empresa.entrar();
        menor = producto("A");
        mayor = producto("B");
        ordenUno = ordenConLosDosProductos("OC-1", 10);
        ordenDos = ordenConLosDosProductos("OC-2", 7);
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresa != null) {
            // Los movimientos apuntan a la recepción: van antes que ella al borrar.
            jdbc.sql("DELETE FROM movimientos_inventario WHERE empresa_id = :e")
                    .param("e", empresa.empresaId).update();
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

    private Long ordenConLosDosProductos(String consecutivo, int cantidad) {
        Long orden = repositorioCompras.insertarOrden(empresa.empresaId, empresa.proveedorId,
                empresa.usuarioId, consecutivo, null);
        repositorioCompras.insertarItem(empresa.empresaId, orden, menor, cantidad, new BigDecimal("10.00"));
        repositorioCompras.insertarItem(empresa.empresaId, orden, mayor, cantidad, new BigDecimal("20.00"));
        return orden;
    }

    private SolicitudRecepcion todoEnEsteOrden(int cantidad, Integer primero, Integer segundo) {
        return new SolicitudRecepcion(List.of(
                new SolicitudRecepcion.LineaRecepcion(primero, cantidad),
                new SolicitudRecepcion.LineaRecepcion(segundo, cantidad)), null, "REM");
    }

    private Callable<Throwable> recibiendo(SecurityContext contexto, CountDownLatch salida,
                                           Long orden, SolicitudRecepcion solicitud) {
        return () -> {
            SecurityContextHolder.setContext(contexto);
            try {
                salida.await();
                compras.recibir(orden, solicitud);
                return null;
            } catch (Throwable t) {
                return t;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    @Test
    void dosRecepcionesConLosMismosProductosEnOrdenInversoTerminanBienSinInterbloqueo() throws Exception {
        // Cada hilo, tras tomar el saldo de su primer producto, espera (con tope) al otro.
        CyclicBarrier ambosTomaronElPrimero = new CyclicBarrier(2);
        Set<Thread> yaEsperaron = ConcurrentHashMap.newKeySet();
        doAnswer(llamada -> {
            Object saldo = llamada.callRealMethod();
            if (yaEsperaron.add(Thread.currentThread())) {
                try {
                    ambosTomaronElPrimero.await(3, TimeUnit.SECONDS);
                } catch (Exception sinElOtro) {
                    // El otro espera un bloqueo: es lo que pasa con los productos bloqueados en orden.
                }
            }
            return saldo;
        }).when(inventario).bloquearSaldo(anyInt(), anyInt());

        SecurityContext contexto = SecurityContextHolder.getContext();
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        Throwable alPrimero;
        Throwable alSegundo;
        try {
            // Una recibe [B, A] y la otra [A, B].
            Future<Throwable> primera = hilos.submit(
                    recibiendo(contexto, salida, ordenUno, todoEnEsteOrden(10, mayor, menor)));
            Future<Throwable> segunda = hilos.submit(
                    recibiendo(contexto, salida, ordenDos, todoEnEsteOrden(7, menor, mayor)));
            salida.countDown();
            alPrimero = primera.get(60, TimeUnit.SECONDS);
            alSegundo = segunda.get(60, TimeUnit.SECONDS);
        } finally {
            hilos.shutdownNow();
            hilos.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertEquals(List.of(), java.util.stream.Stream.of(alPrimero, alSegundo)
                        .filter(r -> r != null).toList(),
                "las dos recepciones terminan bien, una detrás de la otra");
        assertEquals(List.of("RECIBIDA", "RECIBIDA"), jdbc.sql("""
                SELECT estado FROM ordenes_compra WHERE empresa_id = :e ORDER BY orden_id
                """).param("e", empresa.empresaId).query(String.class).list());
        for (Integer producto : List.of(menor, mayor)) {
            assertEquals(17, jdbc.sql("""
                    SELECT existencia FROM productos WHERE empresa_id = :e AND producto_id = :p
                    """).param("e", empresa.empresaId).param("p", producto)
                    .query(Integer.class).single(), "existencia del producto " + producto);
            assertEquals(17, jdbc.sql("""
                    SELECT COALESCE(SUM(cantidad), 0) FROM movimientos_inventario
                     WHERE empresa_id = :e AND producto_id = :p AND tipo = 'INGRESO'
                    """).param("e", empresa.empresaId).param("p", producto)
                    .query(Integer.class).single(), "el kárdex del producto " + producto + " cuadra");
        }
    }
}
