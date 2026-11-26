package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.datos.RepositorioCodigosRecuperacion.CodigoVigente;
import co.com.bspstore.sgip.util.Hoy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El almacén de los códigos de recuperación de la contraseña (V52, RF-65) y las dos
 * cuentas de la bitácora que fijan sus topes.
 *
 * Todas las horas salen de {@code Hoy.ahora(reloj)} con un reloj fijo en UTC: si algo
 * del camino convirtiera la zona entre la JVM y la base, el instante que se escribe
 * dejaría de ser el que se compara y {@code vencidoNoEsVigente} y los conteos
 * fallarían. Las filas de la bitácora se escriben con su {@code fecha} explícita, sin
 * depender del reloj de la base.
 *
 * Necesita la base con el esquema aplicado: se excluye sin ella con
 * {@code -DexcludedGroups=conBase}. Crea su propia empresa y sus usuarios y los borra
 * al terminar.
 */
@Tag("conBase")
@SpringBootTest
class CodigosRecuperacionConBaseTest {

    // 2030-01-15 03:30 UTC es el 14 a las 22:30 de Bogotá: el día de la JVM y el de la
    // aplicación difieren, como en un servidor en la nube.
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2030-01-15T03:30:45Z"), ZoneOffset.UTC);

    @Autowired RepositorioCodigosRecuperacion codigos;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transacciones;

    private LocalDateTime ahora;
    private String marca;
    private Integer empresaId;
    private Integer usuarioId;
    private Integer otroUsuarioId;
    private String correo;
    private String correoDelOtro;

    @BeforeEach
    void prepararDatosPropios() {
        ahora = Hoy.ahora(RELOJ);
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        empresaId = plataforma.insertarEmpresa("R" + marca, "Prueba recuperacion " + marca, null, null, null);
        correo = "rec-" + marca.toLowerCase() + "@ejemplo.co";
        correoDelOtro = "rec-otro-" + marca.toLowerCase() + "@ejemplo.co";
        usuarioId = insertarUsuario(correo);
        otroUsuarioId = insertarUsuario(correoDelOtro);
    }

    @AfterEach
    void borrarLoQueCreo() {
        if (empresaId == null) {
            return;
        }
        jdbc.sql("DELETE FROM auditoria_accesos WHERE correo LIKE :patron")
                .param("patron", "rec-%" + marca.toLowerCase() + "@%").update();
        // Los códigos se borran con su usuario (ON DELETE CASCADE).
        jdbc.sql("DELETE FROM usuarios WHERE empresa_id = :empresa").param("empresa", empresaId).update();
        jdbc.sql("DELETE FROM empresas WHERE empresa_id = :empresa").param("empresa", empresaId).update();
    }

    private Integer insertarUsuario(String correoDelUsuario) {
        jdbc.sql("""
                INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash)
                VALUES (:empresa, (SELECT rol_id FROM roles WHERE nombre = 'ADMINISTRADOR'),
                        'Prueba', :correo, 'no-sirve-para-ingresar')
                """)
                .param("empresa", empresaId)
                .param("correo", correoDelUsuario)
                .update();
        return jdbc.sql("SELECT usuario_id FROM usuarios WHERE correo = :correo")
                .param("correo", correoDelUsuario).query(Integer.class).single();
    }

    private long crear(Integer usuario, LocalDateTime creadoEn, LocalDateTime expira) {
        return codigos.crear(usuario, "hash-" + UUID.randomUUID(), creadoEn, expira, "10.0.0.7");
    }

    private void auditar(Integer usuario, String correoAuditado, String motivo, String origen,
                         LocalDateTime fecha) {
        jdbc.sql("""
                INSERT INTO auditoria_accesos (usuario_id, correo, exitoso, motivo, origen, fecha)
                VALUES (:usuario, :correo, 0, :motivo, :origen, :fecha)
                """)
                .param("usuario", usuario)
                .param("correo", correoAuditado)
                .param("motivo", motivo)
                .param("origen", origen)
                .param("fecha", fecha)
                .update();
    }

    private LocalDateTime usadoEn(long codigoId) {
        return jdbc.sql("SELECT usado_en FROM codigos_recuperacion WHERE codigo_id = :id")
                .param("id", codigoId).query(LocalDateTime.class).optional().orElse(null);
    }

    private <T> T enTransaccion(Supplier<T> trabajo) {
        return new TransactionTemplate(transacciones).execute(estado -> trabajo.get());
    }

    @Test
    void creaYBloqueaElVigente() {
        LocalDateTime expira = ahora.plusMinutes(15);
        long id = codigos.crear(usuarioId, "hash-del-codigo", ahora, expira, "10.0.0.7");

        CodigoVigente vigente = codigos.bloquearVigente(usuarioId, ahora).orElseThrow();

        assertEquals(id, vigente.id());
        assertEquals("hash-del-codigo", vigente.hash());
        assertEquals(0, vigente.intentos());
        var fila = jdbc.sql("SELECT creado_en, expira, origen FROM codigos_recuperacion WHERE codigo_id = :id")
                .param("id", id).query().singleRow();
        assertEquals(ahora, fila.get("creado_en"));
        assertEquals(expira, fila.get("expira"));
        assertEquals("10.0.0.7", fila.get("origen"));
        assertNull(usadoEn(id));
    }

    @Test
    void elVigenteEsElMasRecienteDeSuUsuario() {
        crear(usuarioId, ahora.minusMinutes(5), ahora.plusMinutes(10));
        long ultimo = crear(usuarioId, ahora.minusMinutes(1), ahora.plusMinutes(14));
        crear(otroUsuarioId, ahora, ahora.plusMinutes(15));

        assertEquals(ultimo, codigos.bloquearVigente(usuarioId, ahora).orElseThrow().id());
    }

    @Test
    void bloquearVigenteTomaLaFilaHastaElFinDeLaTransaccion() {
        crear(usuarioId, ahora, ahora.plusMinutes(15));

        // Dentro de la transacción la fila queda tomada: otra conexión que intente
        // tomarla sin esperar falla. Es lo que ordena dos confirmaciones a la vez.
        Throwable desdeOtraConexion = enTransaccion(() -> {
            codigos.bloquearVigente(usuarioId, ahora).orElseThrow();
            try {
                CompletableFuture.runAsync(() -> new TransactionTemplate(transacciones).executeWithoutResult(e ->
                        jdbc.sql("SELECT codigo_id FROM codigos_recuperacion WHERE usuario_id = :u FOR UPDATE NOWAIT")
                                .param("u", usuarioId).query(Long.class).list())).get();
                return null;
            } catch (ExecutionException e) {
                return e.getCause();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return e;
            }
        });

        assertInstanceOf(DataAccessException.class, desdeOtraConexion);
    }

    @Test
    void vencidoNoEsVigente() {
        crear(usuarioId, ahora.minusMinutes(15), ahora.minusSeconds(1));

        assertTrue(codigos.bloquearVigente(usuarioId, ahora).isEmpty());
    }

    @Test
    void elQueVenceExactamenteAhoraYaNoEsVigente() {
        crear(usuarioId, ahora.minusMinutes(15), ahora);

        assertTrue(codigos.bloquearVigente(usuarioId, ahora).isEmpty());
    }

    @Test
    void unCodigoUsadoNoEsVigente() {
        long id = crear(usuarioId, ahora, ahora.plusMinutes(15));

        codigos.marcarUsado(id, ahora);

        assertTrue(codigos.bloquearVigente(usuarioId, ahora).isEmpty());
        assertEquals(ahora, usadoEn(id));
    }

    @Test
    void invalidarDejaSinVigente() {
        crear(usuarioId, ahora.minusMinutes(2), ahora.plusMinutes(13));
        crear(usuarioId, ahora.minusMinutes(1), ahora.plusMinutes(14));
        long delOtro = crear(otroUsuarioId, ahora, ahora.plusMinutes(15));

        int invalidados = codigos.invalidarVigentes(usuarioId, ahora);

        assertEquals(2, invalidados);
        assertTrue(codigos.bloquearVigente(usuarioId, ahora).isEmpty());
        assertEquals(delOtro, codigos.bloquearVigente(otroUsuarioId, ahora).orElseThrow().id());
        assertEquals(0, codigos.invalidarVigentes(usuarioId, ahora));
    }

    @Test
    void invalidarNoTocaLosVencidosNiLosYaUsados() {
        long vencido = crear(usuarioId, ahora.minusMinutes(30), ahora.minusMinutes(15));
        long usado = crear(usuarioId, ahora.minusMinutes(10), ahora.plusMinutes(5));
        codigos.marcarUsado(usado, ahora.minusMinutes(9));

        assertEquals(0, codigos.invalidarVigentes(usuarioId, ahora));

        assertNull(usadoEn(vencido));
        assertEquals(ahora.minusMinutes(9), usadoEn(usado));
    }

    @Test
    void sumarIntento() {
        long id = crear(usuarioId, ahora, ahora.plusMinutes(15));

        codigos.sumarIntento(id);
        codigos.sumarIntento(id);

        assertEquals(2, codigos.bloquearVigente(usuarioId, ahora).orElseThrow().intentos());
    }

    @Test
    void contarCreadosDesde() {
        crear(usuarioId, ahora.minusMinutes(20), ahora.minusMinutes(5));
        crear(usuarioId, ahora.minusMinutes(15), ahora);
        crear(usuarioId, ahora.minusMinutes(1), ahora.plusMinutes(14));
        crear(otroUsuarioId, ahora, ahora.plusMinutes(15));

        assertEquals(3, codigos.contarCreadosDesde(usuarioId, ahora.minusMinutes(30)));
        // el límite es inclusivo: el creado justo en «desde» cuenta
        assertEquals(2, codigos.contarCreadosDesde(usuarioId, ahora.minusMinutes(15)));
        assertEquals(1, codigos.contarCreadosDesde(usuarioId, ahora.minusMinutes(14)));
        assertEquals(0, codigos.contarCreadosDesde(usuarioId, ahora.plusSeconds(1)));
    }

    @Test
    void contarFallosDeRecuperacion() {
        String motivo = "RECUPERACION_CODIGO_INVALIDO";
        auditar(usuarioId, correo, motivo, "10.0.0.7", ahora.minusHours(25));
        auditar(usuarioId, correo, motivo, "10.0.0.7", ahora.minusHours(23));
        auditar(usuarioId, correo, motivo, "10.0.0.8", ahora.minusMinutes(1));
        // no cuentan: otro motivo, otro usuario
        auditar(usuarioId, correo, "RECUPERACION_SOLICITADA", "10.0.0.7", ahora.minusMinutes(1));
        auditar(usuarioId, correo, "CLAVE_INCORRECTA", "10.0.0.7", ahora.minusMinutes(1));
        auditar(otroUsuarioId, correoDelOtro, motivo, "10.0.0.7", ahora.minusMinutes(1));

        assertEquals(2, codigos.contarFallosDeRecuperacion(usuarioId, ahora.minusHours(24)));
        assertEquals(3, codigos.contarFallosDeRecuperacion(usuarioId, ahora.minusHours(26)));
        assertEquals(0, codigos.contarFallosDeRecuperacion(usuarioId, ahora));
    }

    @Test
    void contarSolicitudesDesdeOrigen() {
        String origen = "ip-" + marca;
        auditar(null, "rec-a-" + marca.toLowerCase() + "@ejemplo.co", "RECUPERACION_CORREO_INEXISTENTE",
                origen, ahora.minusMinutes(59));
        auditar(usuarioId, correo, "RECUPERACION_SOLICITADA", origen, ahora.minusMinutes(10));
        auditar(usuarioId, correo, "RECUPERACION_LIMITE", origen, ahora.minusMinutes(1));
        // no cuentan: fuera de la hora, otro origen y motivos que no son de recuperación
        auditar(usuarioId, correo, "RECUPERACION_SOLICITADA", origen, ahora.minusMinutes(61));
        auditar(usuarioId, correo, "RECUPERACION_SOLICITADA", "otra-" + marca, ahora.minusMinutes(1));
        auditar(usuarioId, correo, "CLAVE_INCORRECTA", origen, ahora.minusMinutes(1));
        auditar(usuarioId, correo, "RECUPERACIONX", origen, ahora.minusMinutes(1));

        assertEquals(3, codigos.contarSolicitudesDesdeOrigen(origen, ahora.minusHours(1)));
        assertEquals(0, codigos.contarSolicitudesDesdeOrigen("nadie-" + marca, ahora.minusHours(1)));
    }

    @Test
    void elIndiceExiste() {
        List<String> columnasDeCadaIndice = jdbc.sql("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                  FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'codigos_recuperacion'
                   AND index_name <> 'PRIMARY'
                 GROUP BY index_name
                """)
                .query(String.class).list();

        assertTrue(columnasDeCadaIndice.contains("usuario_id,usado_en,expira"), columnasDeCadaIndice.toString());
    }

    @Test
    void laMigracionV52SeAplico() {
        assertEquals(Boolean.TRUE, jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '52'")
                .query(Boolean.class).optional().orElse(null));
    }

    @Test
    void borrarUsuarioBorraSusCodigos() {
        crear(usuarioId, ahora, ahora.plusMinutes(15));
        long delOtro = crear(otroUsuarioId, ahora, ahora.plusMinutes(15));

        jdbc.sql("DELETE FROM usuarios WHERE usuario_id = :id").param("id", usuarioId).update();

        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM codigos_recuperacion WHERE usuario_id = :id")
                .param("id", usuarioId).query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM codigos_recuperacion WHERE codigo_id = :id")
                .param("id", delOtro).query(Integer.class).single());
    }

    @Test
    void noSeAceptaUnUsuarioInexistente() {
        assertThrows(DataAccessException.class,
                () -> codigos.crear(-1, "hash", ahora, ahora.plusMinutes(15), null));
        assertFalse(codigos.bloquearVigente(-1, ahora).isPresent());
    }

    @Test
    void elToStringDelVigenteNoMuestraElHash() {
        assertFalse(new CodigoVigente(1L, "secreto-derivado", 0).toString().contains("secreto-derivado"));
    }
}
