package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.SolicitudAltaEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.PoliticaDeBloqueo;
import co.com.bspstore.sgip.logica.ServicioPlataforma;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Los medios de pago de cada empresa (RF-43, M17), contra el esquema real.
 *
 * Dos mitades:
 *
 *   1. La migración V40. Flyway ya la aplicó al arrancar el contexto, así que
 *      «al migrar» no se puede ver sobre las tablas reales: se arma el estado
 *      de antes (el ENUM de la V10 con CREDITO, empresas con y sin medios, una
 *      venta que apunta a «Crédito») en un esquema desechable y se ejecuta el
 *      guion que vive en {@code db/migration}. Las tablas del esquema
 *      desechable llevan solo las columnas que la V40 toca, más las claves de la
 *      V11 (la compuesta de {@code ventas} a {@code medios_pago}, que impide borrar
 *      un medio mientras una venta lo use): lo que esas
 *      pruebas afirman es lo que el guion hace con ellas, y que el guion corre
 *      sobre las tablas de verdad lo demuestra Flyway al arrancar.
 *   2. El alta de una empresa, con el {@code darDeAlta} de verdad: qué medios
 *      deja sembrados.
 *
 * Crea su esquema, su super administrador y sus empresas, y lo borra todo al
 * terminar, también si algo falla.
 */
@Tag("conBase")
@SpringBootTest
class MediosPagoConBaseTest {

    private static final String MIGRACION = "db/migration/V40__medios_de_pago_de_cada_empresa.sql";

    private static final String ENUM_DE_ANTES =
            "ENUM('EFECTIVO','TARJETA','TRANSFERENCIA','CREDITO')";

    private static final int SIN_MEDIOS = 1;
    private static final int CON_UN_MEDIO = 2;
    private static final int CON_CREDITO_Y_VENTA = 3;
    private static final int SOLO_CREDITO = 4;

    private static final List<Medio> LOS_TRES = List.of(
            new Medio("Efectivo", "EFECTIVO", true),
            new Medio("Tarjeta", "TARJETA", true),
            new Medio("Transferencia", "TRANSFERENCIA", true));

    private static final Medio SALDO_A_FAVOR = new Medio("Saldo a favor", "SALDO_A_FAVOR", false);

    @Autowired DataSource origen;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired UsuarioRepositorio usuarios;
    @Autowired RolRepositorio roles;
    @Autowired JdbcClient jdbc;

    private String marca;
    private String esquema;
    private Integer superAdminId;

    private record Medio(String nombre, String tipo, boolean activo) {}

    @BeforeEach
    void prepararSuperAdministrador() {
        marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase();
        esquema = "v40_" + marca;
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
        var u = new UsuarioAutenticado(superAdminId, correo, "SUPER_ADMIN", null);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    @AfterEach
    void borrarLoQueCreo() {
        SecurityContextHolder.clearContext();
        jdbc.sql("DROP DATABASE IF EXISTS " + esquema).update();
        List<Integer> empresas = jdbc.sql("SELECT empresa_id FROM empresas WHERE nit IN (:nit, :otro)")
                .param("nit", nit()).param("otro", nit() + "-B").query(Integer.class).list();
        // De las hojas a la raíz: la bitácora y lo sembrado cuelgan de la empresa.
        for (Integer empresa : empresas) {
            for (String tabla : List.of("auditoria_plataforma", "clientes", "medios_pago", "empresa_config",
                    "suscripciones", "usuarios", "empresas")) {
                jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                        .param("empresa", empresa).update();
            }
        }
        jdbc.sql("DELETE FROM usuarios WHERE usuario_id = :id").param("id", superAdminId).update();
    }

    private String nit() {
        return "M" + marca.toUpperCase();
    }

    // ------------------------------------------------------------ la migración

    @Test
    void unaEmpresaSinMediosRecibeLosTresAlMigrar() throws Exception {
        migrarElEstadoDeAntes();

        assertEquals(LOS_TRES, activosDe(SIN_MEDIOS));
    }

    @Test
    void unaEmpresaConMediosNoSeToca() throws Exception {
        migrarElEstadoDeAntes();

        // Se compara el conjunto de activos y no el número de filas: la empresa
        // gana «Saldo a favor», que es de sistema e inactivo.
        assertEquals(List.of(new Medio("Caja principal", "EFECTIVO", true)), activosDe(CON_UN_MEDIO));
    }

    @Test
    void elMedioCreditoSeRetiraAunqueUnaVentaLoUsara() throws Exception {
        migrarElEstadoDeAntes();

        // El MODIFY no falló (si no, no habría llegado hasta aquí) y la venta
        // quedó sin medio en lugar de apuntar a una fila que ya no existe.
        try (Escritorio e = escritorio()) {
            assertEquals(0, e.jdbc().sql(
                            "SELECT COUNT(*) FROM ventas WHERE empresa_id = :e AND medio_id IS NOT NULL")
                    .param("e", CON_CREDITO_Y_VENTA).query(Integer.class).single());
            assertEquals(1, e.jdbc().sql("SELECT COUNT(*) FROM ventas WHERE empresa_id = :e")
                    .param("e", CON_CREDITO_Y_VENTA).query(Integer.class).single());
            assertEquals(0, e.jdbc().sql("SELECT COUNT(*) FROM medios_pago WHERE nombre = 'Crédito'")
                    .query(Integer.class).single());
            String tipo = e.jdbc().sql("""
                    SELECT COLUMN_TYPE FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'medios_pago'
                       AND column_name = 'tipo'
                    """).query(String.class).single();
            assertFalse(tipo.contains("CREDITO"), "el tipo CREDITO se quita del ENUM: " + tipo);
            assertTrue(tipo.contains("SALDO_A_FAVOR"), "el tipo SALDO_A_FAVOR se añade: " + tipo);
        }
        // Una empresa que solo tenía «Crédito» queda con los tres, como una vacía.
        assertEquals(LOS_TRES, activosDe(SOLO_CREDITO));
        // Y la que tenía Efectivo y Crédito conserva solo el Efectivo.
        assertEquals(List.of(new Medio("Efectivo", "EFECTIVO", true)), activosDe(CON_CREDITO_Y_VENTA));
    }

    @Test
    void todaEmpresaTieneElSaldoAFavorInactivo() throws Exception {
        migrarElEstadoDeAntes();

        for (int empresa : List.of(SIN_MEDIOS, CON_UN_MEDIO, CON_CREDITO_Y_VENTA, SOLO_CREDITO)) {
            assertEquals(List.of(SALDO_A_FAVOR), deSistemaDe(empresa), "empresa " + empresa);
        }
    }

    /** La base real, la que Flyway dejó al arrancar: lo mismo pero sobre el esquema de verdad. */
    @Test
    void laBaseRealYaNoTieneCreditoYTieneSaldoAFavor() {
        assertNotNull(getClass().getClassLoader().getResource(MIGRACION),
                "falta la migración " + MIGRACION);
        String tipo = jdbc.sql("""
                SELECT COLUMN_TYPE FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'medios_pago'
                   AND column_name = 'tipo'
                """).query(String.class).single();
        assertFalse(tipo.contains("CREDITO"), "el tipo CREDITO se quita del ENUM: " + tipo);
        assertTrue(tipo.contains("SALDO_A_FAVOR"), "el tipo SALDO_A_FAVOR se añade: " + tipo);
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM medios_pago WHERE nombre = 'Crédito'")
                .query(Integer.class).single());
        // La empresa de demostración (la 1) tiene su medio de sistema, inactivo, y sus tres activos.
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM medios_pago
                 WHERE empresa_id = 1 AND tipo = 'SALDO_A_FAVOR' AND activo = 0
                """).query(Integer.class).single());
        assertEquals(3, jdbc.sql("SELECT COUNT(*) FROM medios_pago WHERE empresa_id = 1 AND activo = 1")
                .query(Integer.class).single());
    }

    // ------------------------------------------------------------------- el alta

    @Test
    void elAltaSiembraEfectivoTarjetaYTransferencia() {
        darDeAlta();

        assertEquals(LOS_TRES, activosDe(nit()));
    }

    @Test
    void elAltaSiembraElSaldoAFavorInactivo() {
        darDeAlta();

        assertEquals(List.of(SALDO_A_FAVOR), deSistemaDe(nit()));
        assertEquals(3, activosDe(nit()).size());
    }

    // ------------------------------------------------- el repositorio, con SQL real

    @Test
    void elRepositorioListaCreaBuscaYActualizaSoloLoDeSuEmpresa() {
        darDeAlta();
        Integer empresa = empresaDelAlta();
        Integer otra = otraEmpresa();
        var repositorio = new RepositorioMediosPago(jdbc);

        // «Saldo a favor» existe pero no se ofrece: son tres de cuatro.
        assertEquals(3, repositorio.listarActivos(empresa).size());
        assertEquals(4, repositorio.listar(empresa).size());
        assertEquals(4, repositorio.bloquearDeLaEmpresa(empresa).size());
        assertEquals(List.of(), repositorio.listar(otra));

        // La comparación del nombre es la de la clave única: sin mayúsculas ni tildes.
        assertTrue(repositorio.existeNombre(empresa, "EFECTIVO"));
        assertFalse(repositorio.existeNombre(otra, "Efectivo"));

        Integer id = repositorio.insertar(empresa, "Nequi", TipoMedio.TRANSFERENCIA);
        var creado = repositorio.buscar(empresa, id).orElseThrow();
        assertEquals("Nequi", creado.nombre());
        assertEquals(TipoMedio.TRANSFERENCIA, creado.tipo());
        assertTrue(creado.activo());
        assertEquals(4, repositorio.listarActivos(empresa).size());

        // Un medio de la empresa A no se ve ni se toca desde la B.
        assertTrue(repositorio.buscar(otra, id).isEmpty());
        repositorio.actualizarActivo(otra, id, false);
        assertTrue(repositorio.buscar(empresa, id).orElseThrow().activo());

        repositorio.actualizarActivo(empresa, id, false);
        assertFalse(repositorio.buscar(empresa, id).orElseThrow().activo());
        assertEquals(3, repositorio.listarActivos(empresa).size());
    }

    private Integer empresaDelAlta() {
        return jdbc.sql("SELECT empresa_id FROM empresas WHERE nit = :nit")
                .param("nit", nit()).query(Integer.class).single();
    }

    /** Otra empresa, sin medios, para comprobar que no ve los de la primera. */
    private Integer otraEmpresa() {
        jdbc.sql("INSERT INTO empresas (nit, razon_social, estado) VALUES (:nit, 'Otra', 'PRUEBA')")
                .param("nit", nit() + "-B").update();
        return jdbc.sql("SELECT empresa_id FROM empresas WHERE nit = :nit")
                .param("nit", nit() + "-B").query(Integer.class).single();
    }

    private void darDeAlta() {
        new ServicioPlataforma(plataforma, usuarios, roles, Clock.systemUTC(), new PoliticaDeBloqueo(15))
                .darDeAlta(new SolicitudAltaEmpresa(nit(), "Medios de pago " + marca, null, null, null,
                        null, "Administración", "admin-" + marca + "@ejemplo.co", "ClaveDePrueba2026"));
    }

    private List<Medio> activosDe(String nit) {
        return jdbc.sql("""
                SELECT m.nombre, m.tipo, m.activo
                  FROM medios_pago m JOIN empresas e ON e.empresa_id = m.empresa_id
                 WHERE e.nit = :nit AND m.activo = 1
                 ORDER BY m.nombre
                """).param("nit", nit).query(Medio.class).list();
    }

    private List<Medio> deSistemaDe(String nit) {
        return jdbc.sql("""
                SELECT m.nombre, m.tipo, m.activo
                  FROM medios_pago m JOIN empresas e ON e.empresa_id = m.empresa_id
                 WHERE e.nit = :nit AND m.tipo = 'SALDO_A_FAVOR'
                 ORDER BY m.nombre
                """).param("nit", nit).query(Medio.class).list();
    }

    // ------------------------------------------- el esquema desechable de la V40

    private List<Medio> activosDe(int empresa) throws Exception {
        try (Escritorio e = escritorio()) {
            return e.jdbc().sql("""
                    SELECT nombre, tipo, activo FROM medios_pago
                     WHERE empresa_id = :e AND activo = 1 ORDER BY nombre
                    """).param("e", empresa).query(Medio.class).list();
        }
    }

    private List<Medio> deSistemaDe(int empresa) throws Exception {
        try (Escritorio e = escritorio()) {
            return e.jdbc().sql("""
                    SELECT nombre, tipo, activo FROM medios_pago
                     WHERE empresa_id = :e AND tipo = 'SALDO_A_FAVOR' ORDER BY nombre
                    """).param("e", empresa).query(Medio.class).list();
        }
    }

    /**
     * Una conexión apuntada al esquema desechable. Al cerrarla vuelve al esquema
     * de la aplicación, para que la conexión que regresa al pool no quede
     * apuntando a otra parte.
     */
    private Escritorio escritorio() throws SQLException {
        return new Escritorio(origen.getConnection(), esquema);
    }

    private static final class Escritorio implements AutoCloseable {
        private final Connection conexion;
        private final String esquemaOriginal;
        private final JdbcClient jdbc;

        Escritorio(Connection conexion, String esquema) throws SQLException {
            this.conexion = conexion;
            this.esquemaOriginal = conexion.getCatalog();
            conexion.setCatalog(esquema);
            this.jdbc = JdbcClient.create(new SingleConnectionDataSource(conexion, true));
        }

        JdbcClient jdbc() {
            return jdbc;
        }

        Connection conexion() {
            return conexion;
        }

        @Override
        public void close() throws SQLException {
            conexion.setCatalog(esquemaOriginal);
            conexion.close();
        }
    }

    /**
     * Arma el estado que había justo antes de la V40 y ejecuta el guion tal como
     * está en el classpath:
     *
     *   empresa 1  sin ningún medio
     *   empresa 2  con un solo medio propio
     *   empresa 3  con «Efectivo» y «Crédito», y una venta pagada con «Crédito»
     *   empresa 4  con «Crédito» como único medio
     */
    private void migrarElEstadoDeAntes() throws Exception {
        jdbc.sql("CREATE DATABASE " + esquema).update();
        try (Escritorio e = escritorio()) {
            JdbcClient bd = e.jdbc();
            bd.sql("CREATE TABLE empresas (empresa_id INT NOT NULL PRIMARY KEY, nit VARCHAR(20) NOT NULL)")
                    .update();
            bd.sql("""
                    CREATE TABLE medios_pago (
                      medio_id   INT         NOT NULL AUTO_INCREMENT,
                      empresa_id INT         NOT NULL,
                      nombre     VARCHAR(60) NOT NULL,
                      tipo       %s NOT NULL,
                      activo     BOOL        NOT NULL DEFAULT 1,
                      CONSTRAINT pk_medios_pago PRIMARY KEY (medio_id),
                      CONSTRAINT uq_medios_pago UNIQUE (empresa_id, nombre),
                      CONSTRAINT uq_medios_empresa UNIQUE (empresa_id, medio_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(ENUM_DE_ANTES)).update();
            bd.sql("""
                    CREATE TABLE ventas (
                      venta_id   BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      empresa_id INT    NOT NULL,
                      medio_id   INT    NULL,
                      CONSTRAINT fk_ventas_medio_emp FOREIGN KEY (empresa_id, medio_id)
                        REFERENCES medios_pago (empresa_id, medio_id)
                        ON DELETE RESTRICT ON UPDATE RESTRICT
                    ) ENGINE=InnoDB
                    """).update();
            for (int empresa : List.of(SIN_MEDIOS, CON_UN_MEDIO, CON_CREDITO_Y_VENTA, SOLO_CREDITO)) {
                bd.sql("INSERT INTO empresas (empresa_id, nit) VALUES (:e, :nit)")
                        .param("e", empresa).param("nit", "E" + empresa).update();
            }
            bd.sql("""
                    INSERT INTO medios_pago (empresa_id, nombre, tipo) VALUES
                      (2, 'Caja principal', 'EFECTIVO'),
                      (3, 'Efectivo', 'EFECTIVO'),
                      (3, 'Crédito', 'CREDITO'),
                      (4, 'Crédito', 'CREDITO')
                    """).update();
            bd.sql("""
                    INSERT INTO ventas (empresa_id, medio_id)
                    SELECT 3, medio_id FROM medios_pago WHERE empresa_id = 3 AND nombre = 'Crédito'
                    """).update();

            var recurso = new ClassPathResource(MIGRACION);
            assertTrue(recurso.exists(), "falta la migración " + MIGRACION);
            ScriptUtils.executeSqlScript(e.conexion(), new EncodedResource(recurso, StandardCharsets.UTF_8));
        }
    }
}
