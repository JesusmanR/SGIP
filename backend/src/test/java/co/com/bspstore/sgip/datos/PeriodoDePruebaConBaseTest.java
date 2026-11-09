package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.SolicitudAltaEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.logica.PoliticaDeBloqueo;
import co.com.bspstore.sgip.logica.ServicioPlataforma;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * El alta escribe {@code fin_prueba} con el parámetro de días de la plataforma
 * (RF-04, ADR-0031), contra el esquema real.
 *
 * El reloj se fija de noche en Bogotá, cuando en UTC ya es el día siguiente: así
 * la prueba distingue el día de Bogotá del de la JVM, que en la CI corre en UTC
 * mientras la conexión va en America/Bogota. Las fechas se escriben por el mismo
 * camino que la aplicación (el {@code darDeAlta} de verdad, con sus repositorios)
 * y se leen por JDBC como {@code LocalDate}.
 *
 * La primera prueba siembra su propio parámetro, con un valor que no es 30 y en
 * un año lejano: si el alta usara una constante o el parámetro de meses, la fecha
 * saldría otra. La segunda usa el que siembra la migración.
 *
 * Crea su empresa, su super administrador y su parámetro, y lo borra todo al
 * terminar.
 */
@Tag("conBase")
@SpringBootTest
class PeriodoDePruebaConBaseTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final String CLAVE_DIAS = "sgip.plataforma.prueba.dias";
    private static final LocalDate DESDE_PROPIO = LocalDate.of(2040, 1, 1);

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
        var u = new UsuarioAutenticado(superAdminId, correo, "SUPER_ADMIN", null);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    @AfterEach
    void borrarLoQueCreo() {
        SecurityContextHolder.clearContext();
        List<Integer> empresas = jdbc.sql("SELECT empresa_id FROM empresas WHERE nit = :nit")
                .param("nit", nit()).query(Integer.class).list();
        // De las hojas a la raíz: la bitácora y lo sembrado cuelgan de la empresa.
        for (Integer empresa : empresas) {
            for (String tabla : List.of("auditoria_plataforma", "medios_pago", "empresa_config",
                    "suscripciones", "usuarios", "empresas")) {
                jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                        .param("empresa", empresa).update();
            }
        }
        jdbc.sql("DELETE FROM usuarios WHERE usuario_id = :id").param("id", superAdminId).update();
        jdbc.sql("DELETE FROM parametros_plataforma WHERE clave = :clave AND vigente_desde = :desde")
                .param("clave", CLAVE_DIAS).param("desde", DESDE_PROPIO).update();
    }

    private String nit() {
        return "P" + marca.toUpperCase();
    }

    private static Clock deNocheEnBogota(LocalDate dia) {
        // 21:30 en Bogotá son las 02:30 del día siguiente en UTC.
        return Clock.fixed(LocalDateTime.of(dia, LocalTime.of(21, 30))
                .atZone(BOGOTA).toInstant(), ZoneId.of("UTC"));
    }

    private ServicioPlataforma servicioEl(LocalDate dia) {
        return new ServicioPlataforma(plataforma, usuarios, roles, deNocheEnBogota(dia),
                new PoliticaDeBloqueo(15));
    }

    private SolicitudAltaEmpresa solicitud() {
        return new SolicitudAltaEmpresa(nit(), "Prueba de periodo " + marca, null, null, null,
                null, "Administración", "admin-" + marca + "@ejemplo.co", "ClaveDePrueba2026");
    }

    private record Periodo(LocalDate inicio, LocalDate fin) {}

    private Periodo periodoGuardado() {
        return jdbc.sql("""
                SELECT s.inicio_prueba AS inicio, s.fin_prueba AS fin
                  FROM suscripciones s
                  JOIN empresas e ON e.empresa_id = s.empresa_id
                 WHERE e.nit = :nit
                """)
                .param("nit", nit()).query(Periodo.class).single();
    }

    @Test
    void elAltaSumaLosDiasDelParametroVigenteAlDiaDeBogota() {
        jdbc.sql("""
                INSERT INTO parametros_plataforma (clave, valor, tipo_dato, descripcion,
                                                   vigente_desde, vigente_hasta)
                VALUES (:clave, '45', 'ENTERO', 'Prueba con base del periodo de prueba',
                        :desde, :hasta)
                """)
                .param("clave", CLAVE_DIAS)
                .param("desde", DESDE_PROPIO)
                .param("hasta", LocalDate.of(2040, 12, 31))
                .update();

        servicioEl(LocalDate.of(2040, 3, 15)).darDeAlta(solicitud());

        // 15 de marzo + 45 días = 29 de abril. Con el día de UTC saldría el 30;
        // con un mes de calendario, el 15 de abril.
        assertEquals(new Periodo(LocalDate.of(2040, 3, 15), LocalDate.of(2040, 4, 29)),
                periodoGuardado());
    }

    @Test
    void conElParametroSembradoLaPruebaSonTreintaDiasDesdeElDiaSiguienteAlAlta() {
        servicioEl(LocalDate.of(2041, 1, 31)).darDeAlta(solicitud());

        // RF-04: el alta del 31 de enero vence el 2 de marzo. Un mes de
        // calendario habría dado el 28 de febrero.
        assertEquals(new Periodo(LocalDate.of(2041, 1, 31), LocalDate.of(2041, 3, 2)),
                periodoGuardado());
    }
}
