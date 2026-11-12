package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.SolicitudApertura;
import co.com.bspstore.sgip.api.dto.Turno;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.ServicioTurnos;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
import co.com.bspstore.sgip.logica.TurnoYaAbiertoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * El turno de caja (RF-45) contra el esquema real: la hora que se guarda, quién es el
 * cajero de cada turno, la carrera de dos aperturas a la vez y el bloqueo compartido que
 * toma {@code exigirTurnoAbierto}.
 *
 * Crea su empresa (con su administrador) y un segundo cajero, y lo borra todo al
 * terminar. Las esperas tienen tiempo máximo y los hilos se cierran siempre.
 */
@Tag("conBase")
@SpringBootTest
class TurnosConBaseTest {

    private static final long ESPERA_MAXIMA_EN_SEGUNDOS = 20;

    @Autowired ServicioTurnos servicio;
    @Autowired RepositorioTurnos repositorio;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired PlatformTransactionManager transacciones;
    @Autowired JdbcClient jdbc;

    private EmpresaDePrueba empresa;
    private Integer segundoCajeroId;

    @BeforeEach
    void prepararDatosPropios() {
        empresa = EmpresaDePrueba.crear("Turnos", plataforma, jdbc, categorias, proveedores);
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash, cambio_clave_req)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = 'VENDEDOR'),
                        'Segundo cajero', :correo, 'no-sirve-para-ingresar', 0)
                """)
                .param("empresa", empresa.empresaId)
                .param("correo", "cajero-" + empresa.marca.toLowerCase() + "@ejemplo.co")
                .update();
        segundoCajeroId = jdbc.sql("SELECT usuario_id FROM usuarios WHERE correo = :correo")
                .param("correo", "cajero-" + empresa.marca.toLowerCase() + "@ejemplo.co")
                .query(Integer.class).single();
        entrarComo(empresa.usuarioId);
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresa != null) {
            empresa.borrar(jdbc);
        }
    }

    private void entrarComo(Integer usuario) {
        var u = new UsuarioAutenticado(usuario, "prueba@ejemplo.co", "VENDEDOR", empresa.empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    private SecurityContext contextoDe(Integer usuario) {
        var u = new UsuarioAutenticado(usuario, "prueba@ejemplo.co", "VENDEDOR", empresa.empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        return c;
    }

    private int turnosAbiertosDe(Integer cajero) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM cierres_caja
                 WHERE empresa_id = :empresa AND usuario_id = :cajero AND cierre IS NULL
                """)
                .param("empresa", empresa.empresaId).param("cajero", cajero)
                .query(Integer.class).single();
    }

    private static SolicitudApertura base(String monto) {
        return new SolicitudApertura(new BigDecimal(monto));
    }

    // ------------------------------------------------------- la hora y el cajero

    @Test
    void abrirGuardaLaHoraDelRelojEnLaHoraDeBogotaAunqueLaJvmEsteEnUtc() {
        Clock utc = Clock.fixed(Instant.parse("2026-11-12T13:30:45.678Z"), ZoneOffset.UTC);
        ServicioTurnos conRelojFijo = new ServicioTurnos(repositorio, utc);

        Turno abierto = conRelojFijo.abrir(base("50000"));

        assertEquals(OffsetDateTime.of(2026, 11, 12, 8, 30, 45, 0, ZoneOffset.ofHours(-5)), abierto.apertura());
        LocalDateTime guardada = jdbc.sql("SELECT apertura FROM cierres_caja WHERE cierre_id = :id")
                .param("id", abierto.id()).query(LocalDateTime.class).single();
        assertEquals(LocalDateTime.of(2026, 11, 12, 8, 30, 45), guardada,
                "el motor guarda la hora de pared de Bogotá, la misma que ve su sesión");
        assertEquals(empresa.usuarioId, abierto.cajeroId());
        assertEquals("Prueba", abierto.cajero());
        assertEquals(0, new BigDecimal("50000").compareTo(abierto.baseEfectivo()));
        assertNull(abierto.cierre());
    }

    @Test
    void elTurnoDeOtroCajeroNoEsElActual() {
        servicio.abrir(base("10000"));

        entrarComo(segundoCajeroId);
        assertEquals(Optional.empty(), servicio.actual(), "el segundo cajero no tiene turno propio");
        Turno suyo = servicio.abrir(base("20000"));

        assertEquals(segundoCajeroId, suyo.cajeroId());
        assertEquals(Optional.of(suyo), servicio.actual());
        entrarComo(empresa.usuarioId);
        assertEquals(empresa.usuarioId, servicio.actual().orElseThrow().cajeroId());
        assertEquals(0, new BigDecimal("10000").compareTo(servicio.actual().orElseThrow().baseEfectivo()));
    }

    @Test
    void unSegundoTurnoDelMismoCajeroEs409YUnoCerradoNoCuenta() {
        Turno primero = servicio.abrir(base("0"));

        assertThrows(TurnoYaAbiertoException.class, () -> servicio.abrir(base("5000")));
        assertEquals(1, turnosAbiertosDe(empresa.usuarioId));

        jdbc.sql("UPDATE cierres_caja SET cierre = apertura WHERE cierre_id = :id")
                .param("id", primero.id()).update();
        assertEquals(Optional.empty(), servicio.actual(), "un turno cerrado no es el actual");
        Turno segundo = servicio.abrir(base("5000"));
        assertEquals(1, turnosAbiertosDe(empresa.usuarioId));
        assertEquals(segundo.id(), servicio.actual().orElseThrow().id());
    }

    // ------------------------------------------------------- la carrera

    /**
     * Dos aperturas del mismo cajero a la vez dejan UN turno abierto, y la otra recibe 409.
     *
     * No se fía de que la carrera ocurra sola: el primer hilo abre dentro de una
     * transacción que mantiene sin confirmar, el segundo pasa su lectura previa (no ve
     * nada, la fila del primero aún no existe para nadie) y su INSERT queda esperando
     * la fila del primero; al confirmar el primero, el segundo choca con el índice
     * único. Sin la unicidad de la base, el segundo no espera y hay dos turnos; sin la
     * traducción del choque, el segundo recibe una excepción de acceso a datos y no un 409.
     */
    @Test
    void dosAperturasSimultaneasDejanUnSoloTurnoAbiertoYLaOtraRecibe409() throws Exception {
        SecurityContext contexto = contextoDe(empresa.usuarioId);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        CountDownLatch primeroInserto = new CountDownLatch(1);
        CountDownLatch soltarAlPrimero = new CountDownLatch(1);
        try {
            Future<Turno> primero = hilos.submit(() -> {
                SecurityContextHolder.setContext(contexto);
                try {
                    return new TransactionTemplate(transacciones).execute(estado -> {
                        Turno abierto = servicio.abrir(base("50000"));
                        primeroInserto.countDown();
                        esperar(soltarAlPrimero);
                        return abierto;
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertTrue(primeroInserto.await(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS),
                    "el primer hilo no llegó a insertar su turno");

            Future<Turno> segundo = hilos.submit(() -> {
                SecurityContextHolder.setContext(contexto);
                try {
                    return servicio.abrir(base("70000"));
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            esperarAQueSeQuedeBloqueado(segundo);

            soltarAlPrimero.countDown();
            Turno ganador = primero.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS);
            ExecutionException fallo = assertThrows(ExecutionException.class,
                    () -> segundo.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS));

            assertInstanceOf(TurnoYaAbiertoException.class, fallo.getCause(),
                    "el choque con la unicidad debe ser un 409 y no un error de la base: " + fallo.getCause());
            assertEquals(1, turnosAbiertosDe(empresa.usuarioId));
            assertEquals(ganador.id(), servicio.actual().orElseThrow().id());
            assertEquals(0, new BigDecimal("50000").compareTo(servicio.actual().orElseThrow().baseEfectivo()),
                    "queda el turno del primero, con su base");
        } finally {
            soltarAlPrimero.countDown();
            hilos.shutdownNow();
        }
    }

    // ------------------------------------------------------- exigirTurnoAbierto

    @Test
    void exigirTurnoAbiertoConElTurnoCerradoEs409() {
        Turno cerrado = servicio.abrir(base("0"));
        jdbc.sql("UPDATE cierres_caja SET cierre = apertura WHERE cierre_id = :id")
                .param("id", cerrado.id()).update();

        assertThrows(SinTurnoAbiertoException.class,
                () -> dentroDeUnaTransaccion(servicio::exigirTurnoAbierto));
    }

    @Test
    void exigirTurnoAbiertoDevuelveElTurnoDelCajeroEnSesion() {
        Turno mio = servicio.abrir(base("0"));
        entrarComo(segundoCajeroId);
        Turno otro = servicio.abrir(base("0"));

        assertEquals(otro.id(), dentroDeUnaTransaccion(servicio::exigirTurnoAbierto));
        entrarComo(empresa.usuarioId);
        assertEquals(mio.id(), dentroDeUnaTransaccion(servicio::exigirTurnoAbierto));
    }

    @Test
    void exigirTurnoAbiertoFueraDeUnaTransaccionFalla() {
        // FOR SHARE fuera de una transacción suelta el bloqueo al terminar la sentencia:
        // el cierre no esperaría a nadie. Por eso el método exige la transacción de la operación.
        servicio.abrir(base("0"));

        assertThrows(IllegalTransactionStateException.class, () -> servicio.exigirTurnoAbierto());
    }

    @Test
    void exigirTurnoAbiertoTomaElBloqueoCompartidoYUnUpdateDelTurnoEspera() throws Exception {
        Turno turno = servicio.abrir(base("0"));
        SecurityContext contexto = contextoDe(empresa.usuarioId);
        ExecutorService hilos = Executors.newSingleThreadExecutor();
        CountDownLatch bloqueoTomado = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        try {
            Future<Long> quienLoTiene = hilos.submit(() -> {
                SecurityContextHolder.setContext(contexto);
                try {
                    return new TransactionTemplate(transacciones).execute(estado -> {
                        Long id = servicio.exigirTurnoAbierto();
                        bloqueoTomado.countDown();
                        esperar(soltar);
                        return id;
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertTrue(bloqueoTomado.await(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS));

            // El cierre (tarea 10) tomará el turno con FOR UPDATE por su clave primaria y se
            // quedará esperando; un UPDATE de la fila espera igual.
            assertThrows(PessimisticLockingFailureException.class, () -> conEsperaCorta(() -> tomarElTurnoComoElCierre(turno.id())),
                    "mientras la operación tiene el turno, un FOR UPDATE del turno debe esperar");
            assertThrows(PessimisticLockingFailureException.class, () -> conEsperaCorta(() ->
                    jdbc.sql("UPDATE cierres_caja SET observacion = 'otro' WHERE empresa_id = :e AND cierre_id = :id")
                            .param("e", empresa.empresaId).param("id", turno.id()).update()),
                    "mientras la operación tiene el turno, un UPDATE del turno debe esperar");

            soltar.countDown();
            assertEquals(turno.id(), quienLoTiene.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS));
            assertEquals(turno.id(), conEsperaCorta(() -> tomarElTurnoComoElCierre(turno.id())),
                    "al terminar la operación el FOR UPDATE ya no espera");
        } finally {
            soltar.countDown();
            hilos.shutdownNow();
        }
    }

    @Test
    void dosOperacionesALaVezNoSeEsperanEntreSi() throws Exception {
        // El bloqueo es COMPARTIDO: dos ventas del mismo cajero (o una venta y un cobro) no
        // se serializan por el turno. Solo el cierre, con FOR UPDATE, espera a todas.
        Turno turno = servicio.abrir(base("0"));
        SecurityContext contexto = contextoDe(empresa.usuarioId);
        ExecutorService hilos = Executors.newSingleThreadExecutor();
        CountDownLatch bloqueoTomado = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        try {
            Future<Long> primera = hilos.submit(() -> {
                SecurityContextHolder.setContext(contexto);
                try {
                    return new TransactionTemplate(transacciones).execute(estado -> {
                        Long id = servicio.exigirTurnoAbierto();
                        bloqueoTomado.countDown();
                        esperar(soltar);
                        return id;
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertTrue(bloqueoTomado.await(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS));

            Long segunda = conEsperaCorta(() -> dentroDeUnaTransaccion(servicio::exigirTurnoAbierto));

            assertEquals(turno.id(), segunda, "la segunda operación no debió esperar a la primera");
            soltar.countDown();
            assertEquals(turno.id(), primera.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS));
        } finally {
            soltar.countDown();
            hilos.shutdownNow();
        }
    }

    /**
     * Una operación que llega cuando el cierre ya tiene el turno recibe el 409 de «sin turno
     * abierto»: ni espera para siempre ni cae como perdedora de un interbloqueo.
     *
     * El cierre (tarea 10) tomará el turno con FOR UPDATE por su clave primaria y después
     * escribirá {@code cierre}, lo que cambia {@code abierto_de} y obliga a tocar el índice
     * único. Si la operación hubiera tomado antes una entrada de ese índice, el cierre
     * esperaría a la operación para tocarla y la operación esperaría al cierre para leer la
     * fila: un interbloqueo, y el motor mata a una de las dos. Aquí el cierre debe terminar
     * y la operación debe enterarse, al recibir el bloqueo, de que el turno ya se cerró.
     */
    @Test
    void unaOperacionQueLlegaDuranteElCierreRecibeSinTurnoYNoUnInterbloqueo() throws Exception {
        Turno turno = servicio.abrir(base("0"));
        SecurityContext contexto = contextoDe(empresa.usuarioId);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        CountDownLatch cierreTomoElTurno = new CountDownLatch(1);
        CountDownLatch seguirConElCierre = new CountDownLatch(1);
        try {
            Future<Integer> cierre = hilos.submit(() -> new TransactionTemplate(transacciones).execute(estado -> {
                tomarElTurnoComoElCierre(turno.id());
                cierreTomoElTurno.countDown();
                esperar(seguirConElCierre);
                return jdbc.sql("""
                                UPDATE cierres_caja SET cierre = apertura
                                 WHERE empresa_id = :e AND cierre_id = :id
                                """)
                        .param("e", empresa.empresaId).param("id", turno.id()).update();
            }));
            assertTrue(cierreTomoElTurno.await(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS));

            Future<Long> operacion = hilos.submit(() -> {
                SecurityContextHolder.setContext(contexto);
                try {
                    return dentroDeUnaTransaccion(servicio::exigirTurnoAbierto);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            esperarAQueSeQuedeBloqueado(operacion);

            seguirConElCierre.countDown();
            assertEquals(1, cierre.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS),
                    "el cierre debe terminar: no puede esperar a la operación");
            ExecutionException fallo = assertThrows(ExecutionException.class,
                    () -> operacion.get(ESPERA_MAXIMA_EN_SEGUNDOS, TimeUnit.SECONDS));
            assertInstanceOf(SinTurnoAbiertoException.class, fallo.getCause(),
                    "la operación que llega durante el cierre recibe el 409 de sin turno: " + fallo.getCause());
        } finally {
            seguirConElCierre.countDown();
            hilos.shutdownNow();
        }
    }

    // ------------------------------------------------------- ayudas

    /** Lo que hará el cierre (tarea 10): leer el turno por su clave primaria con FOR UPDATE. */
    private Long tomarElTurnoComoElCierre(Long turno) {
        return jdbc.sql("SELECT cierre_id FROM cierres_caja WHERE empresa_id = :e AND cierre_id = :id FOR UPDATE")
                .param("e", empresa.empresaId).param("id", turno).query(Long.class).single();
    }

    private <T> T dentroDeUnaTransaccion(java.util.function.Supplier<T> trabajo) {
        return new TransactionTemplate(transacciones).execute(estado -> trabajo.get());
    }

    /**
     * Corre el trabajo en una transacción cuya espera por un bloqueo es de un segundo, y
     * devuelve la espera a su valor al terminar: la conexión vuelve al pool.
     */
    private <T> T conEsperaCorta(java.util.function.Supplier<T> trabajo) {
        return new TransactionTemplate(transacciones).execute(estado -> {
            jdbc.sql("SET SESSION innodb_lock_wait_timeout = 1").update();
            try {
                return trabajo.get();
            } finally {
                jdbc.sql("SET SESSION innodb_lock_wait_timeout = DEFAULT").update();
            }
        });
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

    private void esperarAQueSeQuedeBloqueado(Future<?> enEspera) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(ESPERA_MAXIMA_EN_SEGUNDOS);
        while (System.nanoTime() < limite) {
            if (enEspera.isDone()) {
                fail("Terminó sin esperar a la fila del primero: " + comoTermino(enEspera));
            }
            int esperando = jdbc.sql("SELECT COUNT(*) FROM performance_schema.data_lock_waits")
                    .query(Integer.class).single();
            if (esperando > 0) {
                return;
            }
            Thread.sleep(20);
        }
        fail("Nadie se quedó esperando: la unicidad del turno abierto no está poniendo en fila.");
    }

    private static String comoTermino(Future<?> f) {
        try {
            f.get(1, TimeUnit.SECONDS);
            return "sin error";
        } catch (ExecutionException | TimeoutException | InterruptedException e) {
            return String.valueOf(e);
        }
    }
}
