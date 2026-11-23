package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.AdministradorParaAutorizar;
import co.com.bspstore.sgip.modelo.MotivoRechazo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * El PIN del administrador y sus contadores de intentos (ADR-0032).
 *
 * Es el único camino a las cinco columnas de la V42, para leer y para escribir:
 * ninguna está en la entidad {@code Usuario}. Los instantes del bloqueo los escribe
 * y los lee JDBC tal cual, sin la conversión de zona que Hibernate haría entre la
 * conexión y la JVM, de modo que lo que el reloj de la aplicación escribe es lo
 * mismo que lee (ver {@code BloqueoDeCuentaConBaseTest}).
 *
 * Toda sentencia lleva {@code empresa_id} (ADR-0011). Los dos métodos «bloquear»
 * toman la fila con {@code FOR UPDATE}: quien verifica un PIN sostiene la fila hasta
 * el final de su transacción, y dos intentos simultáneos contra el mismo PIN no se
 * saltan el contador.
 */
@Repository
public class RepositorioPin {

    /**
     * Lo que la autorización necesita de un usuario. {@code pinHash} y
     * {@code claveHash} son derivados; nada de aquí es un PIN ni una contraseña.
     */
    public record UsuarioConPin(Integer usuarioId, String nombre, String correo, String claveHash,
                                String pinHash, int intentos, LocalDateTime bloqueadoEn,
                                int erradosEmitidos, LocalDateTime emisionBloqueadaEn) {
        @Override
        public String toString() {
            return "UsuarioConPin[usuarioId=" + usuarioId + "]";
        }
    }

    private final JdbcClient jdbc;

    public RepositorioPin(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static UsuarioConPin fila(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new UsuarioConPin(rs.getInt("usuario_id"), rs.getString("nombre"),
                rs.getString("correo"), rs.getString("clave_hash"), rs.getString("pin_hash"),
                rs.getInt("pin_intentos"), rs.getObject("pin_bloqueado_en", LocalDateTime.class),
                rs.getInt("pin_errados_emitidos"),
                rs.getObject("pin_emision_bloqueada_en", LocalDateTime.class));
    }

    /** Un usuario de la empresa, con la fila bloqueada para escribir. */
    public Optional<UsuarioConPin> bloquearUsuario(Integer usuarioId, Integer empresaId) {
        return jdbc.sql("""
                        SELECT u.usuario_id, u.nombre, u.correo, u.clave_hash, u.pin_hash,
                               u.pin_intentos, u.pin_bloqueado_en, u.pin_errados_emitidos,
                               u.pin_emision_bloqueada_en
                          FROM usuarios u
                         WHERE u.usuario_id = :usuario
                           AND u.empresa_id = :empresa
                           FOR UPDATE
                        """)
                .param("usuario", usuarioId)
                .param("empresa", empresaId)
                .query(RepositorioPin::fila)
                .optional();
    }

    /**
     * Si ese usuario es un administrador activo de la empresa con PIN. Es una
     * lectura sin bloqueo, y existe para no bloquear la fila de quien no vale: un
     * objetivo de otra empresa o que no es administrador no debe ver su fila
     * tomada por quien tantea.
     */
    public boolean esAdministradorConPin(Integer usuarioId, Integer empresaId) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                          FROM usuarios u
                          JOIN roles r ON r.rol_id = u.rol_id
                         WHERE u.usuario_id = :usuario
                           AND u.empresa_id = :empresa
                           AND u.activo = 1
                           AND r.nombre = 'ADMINISTRADOR'
                           AND u.pin_hash IS NOT NULL
                        """)
                .param("usuario", usuarioId)
                .param("empresa", empresaId)
                .query(Integer.class)
                .single() > 0;
    }

    /**
     * El administrador activo con PIN, con su fila bloqueada. Repite las condiciones
     * de {@link #esAdministradorConPin}: entre las dos lecturas el administrador
     * pudo cambiar, y lo que vale es lo que se lee con la fila ya tomada. El bloqueo
     * es solo de la fila de {@code usuarios} ({@code OF u}); la de {@code roles}, que
     * comparten todas las empresas, no se toca.
     */
    public Optional<UsuarioConPin> bloquearAdministradorConPin(Integer usuarioId, Integer empresaId) {
        return jdbc.sql("""
                        SELECT u.usuario_id, u.nombre, u.correo, u.clave_hash, u.pin_hash,
                               u.pin_intentos, u.pin_bloqueado_en, u.pin_errados_emitidos,
                               u.pin_emision_bloqueada_en
                          FROM usuarios u
                          JOIN roles r ON r.rol_id = u.rol_id
                         WHERE u.usuario_id = :usuario
                           AND u.empresa_id = :empresa
                           AND u.activo = 1
                           AND r.nombre = 'ADMINISTRADOR'
                           AND u.pin_hash IS NOT NULL
                           FOR UPDATE OF u
                        """)
                .param("usuario", usuarioId)
                .param("empresa", empresaId)
                .query(RepositorioPin::fila)
                .optional();
    }

    /** Si el usuario ya tiene PIN. */
    public boolean tienePin(Integer usuarioId, Integer empresaId) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                          FROM usuarios
                         WHERE usuario_id = :usuario
                           AND empresa_id = :empresa
                           AND pin_hash IS NOT NULL
                        """)
                .param("usuario", usuarioId)
                .param("empresa", empresaId)
                .query(Integer.class)
                .single() > 0;
    }

    /** Los administradores activos de la empresa que ya tienen PIN, por nombre. */
    public List<AdministradorParaAutorizar> administradoresConPin(Integer empresaId) {
        return jdbc.sql("""
                        SELECT u.usuario_id AS id, u.nombre
                          FROM usuarios u
                          JOIN roles r ON r.rol_id = u.rol_id
                         WHERE u.empresa_id = :empresa
                           AND u.activo = 1
                           AND r.nombre = 'ADMINISTRADOR'
                           AND u.pin_hash IS NOT NULL
                         ORDER BY u.nombre, u.usuario_id
                        """)
                .param("empresa", empresaId)
                .query(AdministradorParaAutorizar.class)
                .list();
    }

    /** Los fallos contra el PIN de este usuario y, si hay bloqueo, desde cuándo. */
    public void guardarContadorDelPin(Integer usuarioId, Integer empresaId,
                                      int intentos, LocalDateTime bloqueadoEn) {
        jdbc.sql("""
                        UPDATE usuarios
                           SET pin_intentos = :intentos,
                               pin_bloqueado_en = :bloqueadoEn
                         WHERE usuario_id = :usuario
                           AND empresa_id = :empresa
                        """)
                .param("intentos", intentos)
                .param("bloqueadoEn", bloqueadoEn)
                .param("usuario", usuarioId)
                .param("empresa", empresaId)
                .update();
    }

    /** Los fallos de este usuario contra un objetivo que no vale y su bloqueo. */
    public void guardarContadorEmitido(Integer usuarioId, Integer empresaId,
                                       int errados, LocalDateTime bloqueadaEn) {
        jdbc.sql("""
                        UPDATE usuarios
                           SET pin_errados_emitidos = :errados,
                               pin_emision_bloqueada_en = :bloqueadaEn
                         WHERE usuario_id = :usuario
                           AND empresa_id = :empresa
                        """)
                .param("errados", errados)
                .param("bloqueadaEn", bloqueadaEn)
                .param("usuario", usuarioId)
                .param("empresa", empresaId)
                .update();
    }

    /** Fija el PIN ya derivado y deja limpio el contador del PIN. */
    public void guardarPin(Integer usuarioId, Integer empresaId, String pinHash) {
        jdbc.sql("""
                        UPDATE usuarios
                           SET pin_hash = :hash,
                               pin_intentos = 0,
                               pin_bloqueado_en = NULL
                         WHERE usuario_id = :usuario
                           AND empresa_id = :empresa
                        """)
                .param("hash", pinHash)
                .param("usuario", usuarioId)
                .param("empresa", empresaId)
                .update();
    }

    /**
     * Anota un rechazo en la bitácora de accesos, la misma de los ingresos fallidos.
     * La fecha la pone la base, como en los demás rechazos.
     */
    public void registrarRechazo(Integer usuarioId, String correo, MotivoRechazo motivo, String origen) {
        jdbc.sql("""
                        INSERT INTO auditoria_accesos (usuario_id, correo, exitoso, motivo, origen)
                        VALUES (:usuario, :correo, 0, :motivo, :origen)
                        """)
                .param("usuario", usuarioId)
                .param("correo", correo)
                .param("motivo", motivo.name())
                .param("origen", origen)
                .update();
    }
}
