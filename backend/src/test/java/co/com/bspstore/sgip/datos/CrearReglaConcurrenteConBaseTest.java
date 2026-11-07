package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.SolicitudReglaMargen;
import co.com.bspstore.sgip.logica.RegistroRepetidoException;
import co.com.bspstore.sgip.logica.ServicioPrecios;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Un doble envío del formulario de reglas de ganancia no puede dejar dos reglas
 * iguales (M14).
 *
 * La comprobación del solape y la escritura son dos pasos; sin nada que los
 * ponga en fila, dos peticiones a la vez ven las dos «no hay solape» y las dos
 * escriben. Para que el entrelazado no dependa de la suerte, el primer hilo que
 * termina de comprobar el solape espera (con tope) a que el segundo llegue a
 * comprobarlo también. Sin bloqueo, el segundo llega enseguida y las dos reglas
 * entran. Con la empresa bloqueada, el segundo se queda esperando el bloqueo, el
 * primero se cansa de esperar, escribe y confirma, y el segundo ve su regla.
 *
 * Crea su propia empresa y lo borra todo al terminar.
 */
@Tag("conBase")
@SpringBootTest
class CrearReglaConcurrenteConBaseTest {

    private static final LocalDate DESDE = LocalDate.of(2040, 1, 1);

    @Autowired ServicioPrecios precios;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean RepositorioPrecios repositorio;

    private EmpresaDePrueba empresa;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = EmpresaDePrueba.crear("Reglas simultaneas", plataforma, jdbc, categorias, proveedores);
        empresa.entrar();
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresa != null) {
            empresa.borrar(jdbc);
        }
    }

    @Test
    void dosCreacionesSimultaneasDejanUnaSolaReglaYLaOtraSeRechaza() throws Exception {
        AtomicInteger comprobaciones = new AtomicInteger();
        CountDownLatch segundoComprobo = new CountDownLatch(1);
        doAnswer(llamada -> {
            Object solapa = llamada.callRealMethod();
            if (comprobaciones.incrementAndGet() == 1) {
                segundoComprobo.await(3, TimeUnit.SECONDS);
            } else {
                segundoComprobo.countDown();
            }
            return solapa;
        }).when(repositorio).haySolape(anyInt(), any(), any(), anyString(), any(), any(), any());

        var solicitud = new SolicitudReglaMargen(empresa.categoriaId, null, "CONTADO", "SOBRE_COSTO",
                new BigDecimal("1.2000"), DESDE, null);
        SecurityContext contexto = SecurityContextHolder.getContext();
        CountDownLatch salida = new CountDownLatch(1);
        Callable<Throwable> creacion = () -> {
            SecurityContextHolder.setContext(contexto);
            try {
                salida.await();
                precios.crear(solicitud);
                return null;
            } catch (Throwable t) {
                return t;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        ExecutorService hilos = Executors.newFixedThreadPool(2);
        List<Throwable> resultados = new ArrayList<>();
        try {
            Future<Throwable> uno = hilos.submit(creacion);
            Future<Throwable> otro = hilos.submit(creacion);
            salida.countDown();
            resultados.add(uno.get(60, TimeUnit.SECONDS));
            resultados.add(otro.get(60, TimeUnit.SECONDS));
        } finally {
            hilos.shutdownNow();
        }

        long creadas = resultados.stream().filter(r -> r == null).count();
        assertEquals(1, creadas, "una sola creación debe salir bien: " + resultados);
        resultados.stream().filter(r -> r != null)
                .forEach(r -> assertInstanceOf(RegistroRepetidoException.class, r));
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM reglas_margen
                 WHERE empresa_id = :empresa AND producto_id IS NULL
                """).param("empresa", empresa.empresaId).query(Integer.class).single(),
                "la base no puede quedarse con dos reglas iguales");
    }
}
