package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.AdministradorParaAutorizar;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.ExcepcionAutorizable;
import co.com.bspstore.sgip.logica.PinBloqueadoException;
import co.com.bspstore.sgip.logica.PinInvalidoException;
import co.com.bspstore.sgip.logica.ServicioAutorizacion;
import co.com.bspstore.sgip.util.Seguridad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La autorización con PIN contra el esquema real (ADR-0032): que los intentos y el
 * bloqueo sobrevivan a la reversión de la operación que autoriza, que dos intentos
 * simultáneos no se salten el contador, que el bloqueo de la fila del administrador
 * no toque la de {@code roles} y que lo ajeno no se mezcle.
 *
 * Necesita la base con el esquema aplicado: se excluye sin ella con
 * {@code -DexcludedGroups=conBase}. Crea su propia empresa y sus usuarios, y los
 * borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class PinConBaseTest {

    private static final String PIN = "483920";
    private static final String ERRADO = "000000";
    private static final Set<ExcepcionAutorizable> DESCUENTO = Set.of(ExcepcionAutorizable.DESCUENTO);

    /**
     * La operación que llama a {@code autorizar} dentro de su propia transacción y
     * falla después, como una venta que se revierte. Es un bean para que el proxy
     * de Spring le aplique la transacción: una llamada dentro de la misma clase la
     * ignoraría. Antes de autorizar escribe una fila marcadora en una tabla que la
     * autorización no toca, para comprobar que la suya SÍ se revirtió y la de la
     * autorización, no. La tarea 7 repite esto por {@code POST /caja/ventas}.
     */
    static class OperacionQueFalla {
        private final ServicioAutorizacion autorizacion;
        private final JdbcClient jdbc;

        OperacionQueFalla(ServicioAutorizacion autorizacion, JdbcClient jdbc) {
            this.autorizacion = autorizacion;
            this.jdbc = jdbc;
        }

        @Transactional
        public void autorizarYFallar(SolicitudAutorizacion s, String correoMarcador) {
            jdbc.sql("""
                    INSERT INTO auditoria_accesos (usuario_id, correo, exitoso, motivo)
                    VALUES (NULL, :correo, 0, 'OPERACION_QUE_FALLA')
                    """).param("correo", correoMarcador).update();
            autorizacion.autorizar(s, DESCUENTO);
            throw new IllegalStateException("la operación falla después de autorizar");
        }
    }

    @TestConfiguration
    static class Configuracion {
        @Bean
        OperacionQueFalla operacionQueFalla(ServicioAutorizacion autorizacion, JdbcClient jdbc) {
            return new OperacionQueFalla(autorizacion, jdbc);
        }
    }

    @Autowired ServicioAutorizacion servicio;
    @Autowired OperacionQueFalla operacion;
    @Autowired RepositorioPin pines;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired PlatformTransactionManager transacciones;
    @Autowired JdbcClient jdbc;

    private String marca;
    private Integer empresaId;
    private Integer otraEmpresaId;
    private Integer adminId;
    private Integer ajenoId;
    private Integer vendedorId;

    @BeforeEach
    void prepararDatosPropios() {
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase();
        empresaId = plataforma.insertarEmpresa("Q" + marca, "Prueba PIN " + marca, null, null, null);
        otraEmpresaId = plataforma.insertarEmpresa("R" + marca, "Prueba PIN B " + marca, null, null, null);
        String hash = Seguridad.derivar(PIN);
        adminId = insertarUsuario(empresaId, "ADMINISTRADOR", "admin", true, hash);
        ajenoId = insertarUsuario(otraEmpresaId, "ADMINISTRADOR", "ajeno", true, hash);
        vendedorId = insertarUsuario(empresaId, "VENDEDOR", "vendedor", true, null);
    }

    @AfterEach
    void borrarLoQueCreo() {
        SecurityContextHolder.clearContext();
        if (marca == null) {
            return;
        }
        jdbc.sql("DELETE FROM auditoria_accesos WHERE correo LIKE :patron")
                .param("patron", "%" + marca + "%").update();
        jdbc.sql("DELETE FROM usuarios WHERE correo LIKE :patron")
                .param("patron", "%" + marca + "%").update();
        for (Integer empresa : new Integer[]{empresaId, otraEmpresaId}) {
            if (empresa != null) {
                jdbc.sql("DELETE FROM empresas WHERE empresa_id = :empresa")
                        .param("empresa", empresa).update();
            }
        }
    }

    private int contador = 0;

    private Integer insertarUsuario(Integer empresa, String rol, String nombre, boolean activo, String pinHash) {
        String correo = nombre + (contador++) + "-" + marca + "@ejemplo.co";
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash, activo,
                                      cambio_clave_req, pin_hash)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = :rol),
                        :nombre, :correo, 'no-sirve-para-ingresar', :activo, 0, :pin)
                """)
                .param("empresa", empresa)
                .param("rol", rol)
                .param("nombre", nombre)
                .param("correo", correo)
                .param("activo", activo)
                .param("pin", pinHash)
                .update();
        return jdbc.sql("SELECT usuario_id FROM usuarios WHERE correo = :correo")
                .param("correo", correo).query(Integer.class).single();
    }

    private void como(Integer usuarioId, String rol, Integer empresa) {
        var u = new UsuarioAutenticado(usuarioId, "prueba@ejemplo.co", rol, empresa);
        SecurityContext contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(contexto);
    }

    private void comoVendedor() {
        como(vendedorId, "VENDEDOR", empresaId);
    }

    private int entero(String columna, Integer usuario) {
        return jdbc.sql("SELECT " + columna + " FROM usuarios WHERE usuario_id = :id")
                .param("id", usuario).query(Integer.class).single();
    }

    private LocalDateTime instante(String columna, Integer usuario) {
        return jdbc.sql("SELECT " + columna + " FROM usuarios WHERE usuario_id = :id")
                .param("id", usuario).query(LocalDateTime.class).optional().orElse(null);
    }

    private int rechazos(Integer usuario, String motivo) {
        return jdbc.sql("SELECT COUNT(*) FROM auditoria_accesos WHERE usuario_id = :id AND motivo = :motivo")
                .param("id", usuario).param("motivo", motivo).query(Integer.class).single();
    }

    private int marcadores() {
        return jdbc.sql("SELECT COUNT(*) FROM auditoria_accesos WHERE correo = :correo")
                .param("correo", "marcador-" + marca + "@ejemplo.co").query(Integer.class).single();
    }

    private void operacionConFallo(String pin) {
        operacion.autorizarYFallar(new SolicitudAutorizacion(adminId, pin), "marcador-" + marca + "@ejemplo.co");
    }

    // --------------------------------------- los intentos sobreviven a la reversión

    @Test
    void losIntentosPersistenAunqueLaOperacionFalle() {
        comoVendedor();

        for (int i = 0; i < 5; i++) {
            assertThrows(PinInvalidoException.class, () -> operacionConFallo(ERRADO));
        }

        // La operación se revirtió cada vez (el marcador no está) y la autorización no.
        assertEquals(0, marcadores(), "la transacción de la operación se revirtió");
        assertEquals(5, entero("pin_intentos", adminId));
        assertNotNull(instante("pin_bloqueado_en", adminId));
        assertEquals(5, rechazos(vendedorId, "PIN_INCORRECTO"));
        assertEquals(1, rechazos(adminId, "PIN_BLOQUEADO"), "el bloqueo queda en la bitácora");

        // Y el bloqueo también: el sexto, con el PIN correcto, se rechaza.
        assertThrows(PinBloqueadoException.class, () -> operacionConFallo(PIN));
        assertEquals(0, marcadores());
        assertEquals(5, entero("pin_intentos", adminId), "bloqueado no suma");
        assertEquals(1, rechazos(vendedorId, "PIN_BLOQUEADO"));
    }

    @Test
    void unAciertoTambienSobreviveALaReversionDeLaOperacion() {
        comoVendedor();
        for (int i = 0; i < 3; i++) {
            assertThrows(PinInvalidoException.class, () -> operacionConFallo(ERRADO));
        }
        assertEquals(3, entero("pin_intentos", adminId));

        // Autoriza bien y la operación falla después: lo que sigue de pie es el acierto.
        assertThrows(IllegalStateException.class, () -> operacionConFallo(PIN));

        assertEquals(0, entero("pin_intentos", adminId));
        assertEquals(0, marcadores());
    }

    @Test
    void elObjetivoInvalidoCuentaContraElSolicitanteYNoTocaAlAjeno() {
        comoVendedor();

        for (int i = 0; i < 5; i++) {
            // Aunque el PIN sea el correcto del administrador de la otra empresa.
            assertThrows(PinInvalidoException.class,
                    () -> servicio.autorizar(new SolicitudAutorizacion(ajenoId, PIN), DESCUENTO));
        }

        assertEquals(5, entero("pin_errados_emitidos", vendedorId));
        assertNotNull(instante("pin_emision_bloqueada_en", vendedorId));
        assertEquals(0, entero("pin_intentos", ajenoId));
        assertNull(instante("pin_bloqueado_en", ajenoId));
        // Bloqueado como solicitante, ni con el administrador y el PIN buenos.
        assertThrows(PinBloqueadoException.class,
                () -> servicio.autorizar(new SolicitudAutorizacion(adminId, PIN), DESCUENTO));
        assertEquals(0, entero("pin_intentos", adminId));
    }

    @Test
    void elInstanteDelBloqueoEsElDelRelojDeLaAplicacionSinDesfaseDeZona() {
        comoVendedor();
        for (int i = 0; i < 5; i++) {
            assertThrows(PinInvalidoException.class,
                    () -> servicio.autorizar(new SolicitudAutorizacion(adminId, ERRADO), DESCUENTO));
        }

        LocalDateTime leido = instante("pin_bloqueado_en", adminId);

        // Lo que la aplicación escribe es lo que lee: ni cinco horas de más ni de menos.
        assertTrue(Duration.between(leido, LocalDateTime.now()).abs().toMinutes() < 2,
                "el bloqueo debería ser de hace instantes y es " + leido);
    }

    // ---------------------------------------------------- lo que hace de verdad

    @Test
    void unAdministradorFijaSuPinYQuedaDerivado() {
        Integer nuevo = insertarUsuario(empresaId, "ADMINISTRADOR", "nuevo", true, null);
        como(nuevo, "ADMINISTRADOR", empresaId);
        assertFalse(servicio.estadoDelPin().configurado());

        servicio.fijarPin(null, "13579");

        String guardado = jdbc.sql("SELECT pin_hash FROM usuarios WHERE usuario_id = :id")
                .param("id", nuevo).query(String.class).single();
        assertTrue(guardado.startsWith("pbkdf2$"));
        assertFalse(guardado.contains("13579"));
        assertTrue(Seguridad.verificar("13579", guardado));
        assertTrue(servicio.estadoDelPin().configurado());
    }

    @Test
    void administradoresListaSoloLosQueTienenPinYSonDeLaEmpresa() {
        insertarUsuario(empresaId, "ADMINISTRADOR", "sinpin", true, null);
        insertarUsuario(empresaId, "ADMINISTRADOR", "inactivo", false, Seguridad.derivar(PIN));
        insertarUsuario(empresaId, "VENDEDOR", "vendedor2", true, Seguridad.derivar(PIN));
        comoVendedor();

        List<AdministradorParaAutorizar> lista = servicio.administradores();

        assertEquals(List.of(new AdministradorParaAutorizar(adminId, "admin")), lista);
    }

    // -------------------------------------------------------------- concurrencia

    /** Cada hilo es un vendedor distinto: solo comparten la fila del administrador. */
    private List<Object> intentarEnParalelo(int hilos, String pin) throws Exception {
        List<Integer> vendedores = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            vendedores.add(insertarUsuario(empresaId, "VENDEDOR", "paralelo", true, null));
        }
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Object>> futuros = new ArrayList<>();
        for (Integer vendedor : vendedores) {
            futuros.add(pool.submit(() -> {
                como(vendedor, "VENDEDOR", empresaId);
                try {
                    salida.await();
                    return servicio.autorizar(new SolicitudAutorizacion(adminId, pin), DESCUENTO);
                } catch (RuntimeException e) {
                    return e;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        salida.countDown();
        List<Object> resultados = new ArrayList<>();
        for (Future<Object> f : futuros) {
            resultados.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return resultados;
    }

    @Test
    void dosIntentosSimultaneosNoSeSaltanElContador() throws Exception {
        List<Object> resultados = intentarEnParalelo(4, ERRADO);

        assertEquals(4, resultados.stream().filter(r -> r instanceof PinInvalidoException).count());
        assertEquals(4, entero("pin_intentos", adminId), "ningún intento se perdió");
        assertNull(instante("pin_bloqueado_en", adminId));
    }

    @Test
    void seisIntentosSimultaneosDejanCincoContadosYUnoBloqueado() throws Exception {
        List<Object> resultados = intentarEnParalelo(6, ERRADO);

        assertEquals(5, resultados.stream().filter(r -> r instanceof PinInvalidoException).count());
        assertEquals(1, resultados.stream().filter(r -> r instanceof PinBloqueadoException).count());
        assertEquals(5, entero("pin_intentos", adminId));
        assertNotNull(instante("pin_bloqueado_en", adminId));
    }

    @Test
    void bloquearAUnAdministradorNoBloqueaLaFilaCompartidaDeRoles() throws Exception {
        // Si el FOR UPDATE tomara también la fila de roles, que comparten todas las
        // empresas, el administrador de la otra empresa esperaría al primero.
        var plantilla = new TransactionTemplate(transacciones);
        CountDownLatch tomado = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> primero = pool.submit(() -> plantilla.executeWithoutResult(t -> {
                assertTrue(pines.bloquearAdministradorConPin(adminId, empresaId).isPresent());
                tomado.countDown();
                try {
                    soltar.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(tomado.await(30, TimeUnit.SECONDS));

            Future<Boolean> segundo = pool.submit(() -> plantilla.execute(t ->
                    pines.bloquearAdministradorConPin(ajenoId, otraEmpresaId).isPresent()));

            try {
                assertTrue(segundo.get(10, TimeUnit.SECONDS), "no debería esperar al otro administrador");
            } catch (TimeoutException e) {
                throw new AssertionError("el bloqueo de un administrador frenó a otro de otra empresa", e);
            } finally {
                soltar.countDown();
            }
            primero.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError(e.getCause());
        } finally {
            soltar.countDown();
            pool.shutdownNow();
        }
    }
}
