package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.LineaSolicitada;
import co.com.bspstore.sgip.api.dto.PagoSolicitado;
import co.com.bspstore.sgip.api.dto.ProductoNuevo;
import co.com.bspstore.sgip.api.dto.SolicitudVenta;
import co.com.bspstore.sgip.logica.ResultadoDeVenta;
import co.com.bspstore.sgip.logica.ServicioVentas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * El producto nuevo contra dos hilos. Cada prueba fuerza el cruce: sostiene desde la prueba el
 * bloqueo del consecutivo de códigos rápidos, deja que las dos ventas lleguen a esperarlo
 * ({@code performance_schema} dice que de verdad esperan) y las suelta juntas.
 *
 * Crea su empresa y la borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class ProductoNuevoConcurrenteConBaseTest {

    private static final long ESPERA_MAXIMA_EN_SEGUNDOS = 30;
    private static final String MOTIVO = "Producto que el cliente pidió y no está en el catálogo";

    @Autowired ServicioVentas ventas;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioClientes clientes;
    @Autowired PlatformTransactionManager transacciones;
    @Autowired JdbcClient jdbc;

    private CajaDePrueba caja;
    private ExecutorService hilos;

    @BeforeEach
    void prepararDatosPropios() {
        caja = CajaDePrueba.crear("Producto nuevo concurrente", jdbc, plataforma, categorias, proveedores, clientes);
        caja.abrirTurno(caja.administradorId);
        caja.abrirTurno(caja.vendedorId);
        hilos = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void borrarLoQueCreo() throws InterruptedException {
        if (hilos != null) {
            hilos.shutdownNow();
            assertTrue(hilos.awaitTermination(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS), "un hilo de la prueba no terminó");
        }
        SecurityContextHolder.clearContext();
        if (caja != null) {
            caja.borrar();
        }
    }

    // ------------------------------------------------------- ayudas

    private SolicitudVenta venta(UUID clave, String nombre, String precio, String barras, Integer delCatalogo) {
        List<LineaSolicitada> lineas = new java.util.ArrayList<>();
        BigDecimal total = new BigDecimal(precio);
        lineas.add(new LineaSolicitada(null, new ProductoNuevo(nombre, new BigDecimal(precio), barras), 1, null,
                LocalDate.now().plusDays(7)));
        if (delCatalogo != null) {
            lineas.add(new LineaSolicitada(delCatalogo, null, 1, null, null));
            total = total.add(new BigDecimal("10000"));
        }
        return new SolicitudVenta(clave, null, lineas, null,
                List.of(new PagoSolicitado(caja.efectivoId, total, null, null)), MOTIVO, null, null);
    }

    private <T> Future<T> comoAdministrador(Callable<T> trabajo) {
        return como(caja.contextoDe(caja.administradorId, "ADMINISTRADOR"), trabajo);
    }

    private <T> Future<T> como(SecurityContext contexto, Callable<T> trabajo) {
        return hilos.submit(() -> {
            SecurityContextHolder.setContext(contexto);
            try {
                return trabajo.call();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    private int esperasEn(String tabla) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                  JOIN performance_schema.data_locks l ON l.engine_lock_id = w.requesting_engine_lock_id
                 WHERE l.object_schema = DATABASE() AND l.object_name = :tabla
                """).param("tabla", tabla).query(Integer.class).single();
    }

    private void esperarAQueEsperen(String tabla, int cuantas, Future<?>... enMarcha) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(ESPERA_MAXIMA_EN_SEGUNDOS);
        while (System.nanoTime() < limite) {
            if (esperasEn(tabla) >= cuantas) {
                return;
            }
            for (Future<?> f : enMarcha) {
                if (f.isDone()) {
                    fail("Un hilo terminó sin quedarse esperando en " + tabla + ": " + comoTermino(f));
                }
            }
            Thread.sleep(20);
        }
        fail("Nadie esperaba en " + tabla + ": " + esperasEn(tabla) + " de " + cuantas);
    }

    private static String comoTermino(Future<?> f) {
        try {
            f.get(1, TimeUnit.SECONDS);
            return "sin error";
        } catch (ExecutionException | TimeoutException | InterruptedException e) {
            return String.valueOf(e);
        }
    }

    /** Una transacción abierta desde la prueba que mantiene un bloqueo hasta que se la suelte. */
    private final class Bloqueo implements AutoCloseable {
        private final CountDownLatch soltar = new CountDownLatch(1);
        private final CountDownLatch tomado = new CountDownLatch(1);
        private final Future<?> transaccion;

        Bloqueo(Runnable tomar) {
            transaccion = hilos.submit(() -> new TransactionTemplate(transacciones).execute(estado -> {
                tomar.run();
                tomado.countDown();
                esperar(soltar);
                return null;
            }));
            try {
                long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(ESPERA_MAXIMA_EN_SEGUNDOS);
                while (!tomado.await(20, TimeUnit.MILLISECONDS)) {
                    if (transaccion.isDone() || System.nanoTime() > limite) {
                        fail("no se tomó el bloqueo: " + comoTermino(transaccion));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() throws Exception {
            soltar.countDown();
            transaccion.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS);
        }
    }

    private static void esperar(CountDownLatch cerrojo) {
        try {
            if (!cerrojo.await(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("nadie soltó al hilo a tiempo");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** El consecutivo de los códigos rápidos, tomado como lo toma la venta; la fila se crea si falta. */
    private Bloqueo bloquearElConsecutivoDeCodigosRapidos() {
        jdbc.sql("INSERT INTO consecutivos (empresa_id, tipo) VALUES (:e, 'PRODUCTO_RAPIDO') ON DUPLICATE KEY UPDATE siguiente = siguiente")
                .param("e", caja.empresa.empresaId).update();
        return new Bloqueo(() -> jdbc.sql("""
                SELECT siguiente FROM consecutivos WHERE empresa_id = :e AND tipo = 'PRODUCTO_RAPIDO' FOR UPDATE
                """).param("e", caja.empresa.empresaId).query(Long.class).single());
    }

    private static <T> T resultado(Future<T> f) throws Exception {
        return f.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------- el mismo envío por dos hilos

    @Test
    void elMismoEnvioPorDosHilosConProductoNuevoYCodigoDeBarrasEsUnaSolaVentaYUnSoloProducto() throws Exception {
        // Dos peticiones con la misma clave y solo un producto nuevo no comparten ningún producto
        // bloqueado: las dos pasan la comprobación de la clave sin ver nada. Se juntan en el
        // consecutivo de los códigos rápidos; la segunda, al despertar, tiene que ver la venta de
        // la primera y devolverla, no chocar con el código de barras que la primera acaba de crear.
        SolicitudVenta solicitud = venta(UUID.randomUUID(), "Tornillo hexagonal", "7500", "7701234567890", null);
        Future<ResultadoDeVenta> uno;
        Future<ResultadoDeVenta> otro;
        try (Bloqueo bloqueo = bloquearElConsecutivoDeCodigosRapidos()) {
            uno = comoAdministrador(() -> ventas.procesar(solicitud));
            otro = comoAdministrador(() -> ventas.procesar(solicitud));
            esperarAQueEsperen("consecutivos", 2, uno, otro);
        }

        ResultadoDeVenta a = resultado(uno);
        ResultadoDeVenta b = resultado(otro);

        assertNotEquals(a.yaExistia(), b.yaExistia(), "uno recibe el 201 y el otro el 200");
        assertEquals(a.venta().id(), b.venta().id(), "los dos reciben la misma venta");
        assertEquals(1, caja.cuantas("ventas"));
        assertEquals(1, caja.cuantas("productos"), "un solo producto nuevo");
        assertEquals(2, caja.cuantas("reglas_margen"));
        assertEquals(2, jdbc.sql("SELECT siguiente FROM consecutivos WHERE empresa_id = :e AND tipo = 'PRODUCTO_RAPIDO'")
                .param("e", caja.empresa.empresaId).query(Integer.class).single(), "solo se consumió un código");
    }

    // ------------------------------------------------------- dos ventas distintas

    @Test
    void dosVentasConProductosNuevosDistintosNumeranSusCodigosSinRepetirYSinEsperarseEnCirculo() throws Exception {
        Future<ResultadoDeVenta> uno;
        Future<ResultadoDeVenta> otro;
        try (Bloqueo bloqueo = bloquearElConsecutivoDeCodigosRapidos()) {
            uno = comoAdministrador(() -> ventas.procesar(venta(UUID.randomUUID(), "Tornillo", "7500", null, null)));
            otro = comoAdministrador(() -> ventas.procesar(venta(UUID.randomUUID(), "Tuerca", "800", null, null)));
            esperarAQueEsperen("consecutivos", 2, uno, otro);
        }

        resultado(uno);
        resultado(otro);

        assertEquals(2, caja.cuantas("productos"));
        assertEquals(List.of("RAP-1", "RAP-2"), jdbc.sql("""
                SELECT codigo FROM productos WHERE empresa_id = :e ORDER BY codigo
                """).param("e", caja.empresa.empresaId).query(String.class).list(), "dos códigos distintos y seguidos");
        assertEquals(2, caja.cuantas("ventas"));
        assertEquals(3, jdbc.sql("SELECT siguiente FROM consecutivos WHERE empresa_id = :e AND tipo = 'PRODUCTO_RAPIDO'")
                .param("e", caja.empresa.empresaId).query(Integer.class).single());
    }

    @Test
    void dosVentasQueComparteUnProductoDelCatalogoYCreanProductosNuevosNoSeInterbloquean() throws Exception {
        // Orden de bloqueos: el producto del catálogo (por id) antes que el consecutivo de códigos
        // rápidos, en las dos. Con las dos esperándose el producto, la que entra segunda espera
        // hasta que la primera termina, y no hay ciclo.
        Integer compartido = caja.producto("A", 5, "10000");
        Future<ResultadoDeVenta> uno;
        Future<ResultadoDeVenta> otro;
        try (Bloqueo bloqueo = new Bloqueo(() -> jdbc.sql("""
                SELECT existencia FROM productos WHERE empresa_id = :e AND producto_id = :p FOR UPDATE
                """).param("e", caja.empresa.empresaId).param("p", compartido).query(Integer.class).single())) {
            uno = comoAdministrador(() -> ventas.procesar(venta(UUID.randomUUID(), "Tornillo", "7500", null, compartido)));
            otro = comoAdministrador(() -> ventas.procesar(venta(UUID.randomUUID(), "Tuerca", "800", null, compartido)));
            esperarAQueEsperen("productos", 2, uno, otro);
        }

        resultado(uno);
        resultado(otro);

        assertEquals(3, caja.cuantas("productos"));
        assertEquals(3, caja.existencia(compartido), "las dos descontaron una unidad");
        assertEquals(2, caja.cuantas("ventas"));
    }
}
