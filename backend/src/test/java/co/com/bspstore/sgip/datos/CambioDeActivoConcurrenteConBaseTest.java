package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.MedioDePago;
import co.com.bspstore.sgip.api.dto.SolicitudAltaEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.PoliticaDeBloqueo;
import co.com.bspstore.sgip.logica.ServicioMediosPago;
import co.com.bspstore.sgip.logica.ServicioPlataforma;
import co.com.bspstore.sgip.logica.UltimoMedioActivoException;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dos administradores desactivan a la vez, cada uno, uno de los dos últimos medios
 * activos de la empresa (RF-43).
 *
 * Sin el bloqueo de las filas de la empresa, los dos leerían «el otro sigue
 * activo», los dos escribirían, y la empresa se quedaría sin ningún medio para
 * cobrar. Con él, el segundo espera al primero, ve el medio ya inactivo y recibe
 * el 409. Se repite varias veces porque una barrera junta los hilos pero no
 * garantiza que las dos transacciones se solapen en cada intento.
 *
 * Crea su empresa y su super administrador, y lo borra todo al terminar.
 */
@Tag("conBase")
@SpringBootTest
class CambioDeActivoConcurrenteConBaseTest {

    private static final int RONDAS = 8;

    @Autowired ServicioMediosPago servicio;
    @Autowired RepositorioMediosPago medios;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired UsuarioRepositorio usuarios;
    @Autowired RolRepositorio roles;
    @Autowired JdbcClient jdbc;

    private String marca;
    private Integer superAdminId;

    @BeforeEach
    void prepararSuperAdministrador() {
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase();
        String correo = "plataforma-" + marca + "@ejemplo.co";
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash)
                VALUES (NULL, (SELECT rol_id FROM roles WHERE nombre = 'SUPER_ADMIN'),
                        'Plataforma', :correo, 'no-sirve-para-ingresar')
                """)
                .param("correo", correo)
                .update();
        superAdminId = jdbc.sql("SELECT usuario_id FROM usuarios WHERE correo = :correo")
                .param("correo", correo).query(Integer.class).single();
        SecurityContextHolder.setContext(contexto(new UsuarioAutenticado(superAdminId, correo, "SUPER_ADMIN", null)));
    }

    @AfterEach
    void borrarLoQueCreo() {
        SecurityContextHolder.clearContext();
        List<Integer> empresas = jdbc.sql("SELECT empresa_id FROM empresas WHERE nit = :nit")
                .param("nit", nit()).query(Integer.class).list();
        for (Integer empresa : empresas) {
            for (String tabla : List.of("auditoria_plataforma", "medios_pago", "empresa_config",
                    "suscripciones", "usuarios", "empresas")) {
                jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                        .param("empresa", empresa).update();
            }
        }
        jdbc.sql("DELETE FROM usuarios WHERE usuario_id = :id").param("id", superAdminId).update();
    }

    private String nit() {
        return "C" + marca.toUpperCase();
    }

    private static SecurityContext contexto(UsuarioAutenticado u) {
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        return c;
    }

    @Test
    void dosDesactivacionesALaVezDejanUnMedioActivoYUnSoloExito() throws Exception {
        new ServicioPlataforma(plataforma, usuarios, roles, Clock.systemUTC(), new PoliticaDeBloqueo(15))
                .darDeAlta(new SolicitudAltaEmpresa(nit(), "Medios concurrentes " + marca, null, null, null,
                        null, "Administración", "admin-" + marca + "@ejemplo.co", "ClaveDePrueba2026"));
        Integer empresa = jdbc.sql("SELECT empresa_id FROM empresas WHERE nit = :nit")
                .param("nit", nit()).query(Integer.class).single();
        Integer efectivo = idDe(empresa, TipoMedio.EFECTIVO);
        Integer tarjeta = idDe(empresa, TipoMedio.TARJETA);
        // El alta deja tres activos; con uno inactivo quedan exactamente dos.
        medios.actualizarActivo(empresa, idDe(empresa, TipoMedio.TRANSFERENCIA), false);
        UsuarioAutenticado administrador = new UsuarioAutenticado(superAdminId, "ana@tienda.co", "ADMINISTRADOR", empresa);

        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            for (int ronda = 1; ronda <= RONDAS; ronda++) {
                medios.actualizarActivo(empresa, efectivo, true);
                medios.actualizarActivo(empresa, tarjeta, true);
                CyclicBarrier salida = new CyclicBarrier(2);

                Future<Object> una = hilos.submit(desactivando(efectivo, administrador, salida));
                Future<Object> otra = hilos.submit(desactivando(tarjeta, administrador, salida));
                List<Object> resultados = List.of(una.get(60, TimeUnit.SECONDS), otra.get(60, TimeUnit.SECONDS));

                assertEquals(1, resultados.stream().filter(r -> r instanceof MedioDePago).count(),
                        "ronda " + ronda + ": exactamente una desactivación tiene éxito: " + resultados);
                assertEquals(1, resultados.stream().filter(r -> r instanceof UltimoMedioActivoException).count(),
                        "ronda " + ronda + ": exactamente la otra recibe el 409: " + resultados);
                assertEquals(1, medios.listarActivos(empresa).size(),
                        "ronda " + ronda + ": la empresa conserva un medio activo");
            }
        } finally {
            hilos.shutdownNow();
            hilos.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /** Devuelve el medio resultante si tuvo éxito, o la excepción si no; el hilo nunca lanza. */
    private Callable<Object> desactivando(Integer medio, UsuarioAutenticado administrador, CyclicBarrier salida) {
        return () -> {
            SecurityContextHolder.setContext(contexto(administrador));
            try {
                salida.await(30, TimeUnit.SECONDS);
                return servicio.cambiarActivo(medio, false);
            } catch (Throwable t) {
                return t;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private Integer idDe(Integer empresa, TipoMedio tipo) {
        return medios.listar(empresa).stream()
                .filter(m -> m.tipo() == tipo).findFirst().orElseThrow().id();
    }
}
