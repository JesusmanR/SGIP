package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.MedioDePago;
import co.com.bspstore.sgip.modelo.TipoMedio;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Los medios de pago de cada empresa (RF-43).
 *
 * Toda sentencia lleva {@code empresa_id} (ADR-0011): el medio de otra empresa
 * es, para quien pregunta, un medio que no existe.
 */
@Repository
public class RepositorioMediosPago {

    private final JdbcClient jdbc;

    public RepositorioMediosPago(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Todos, el de sistema incluido, en el orden en que se crearon. */
    public List<MedioDePago> listar(Integer empresa) {
        return jdbc.sql("""
                        SELECT medio_id AS id, nombre, tipo, activo
                          FROM medios_pago
                         WHERE empresa_id = :empresa
                         ORDER BY medio_id
                        """)
                .param("empresa", empresa)
                .query(MedioDePago.class)
                .list();
    }

    /**
     * Los que el cajero puede elegir: activos y que no sean de sistema. «Saldo a
     * favor» va inactivo por diseño; el filtro por tipo lo deja fuera aunque
     * alguien lo hubiera marcado.
     */
    public List<MedioDePago> listarActivos(Integer empresa) {
        return jdbc.sql("""
                        SELECT medio_id AS id, nombre, tipo, activo
                          FROM medios_pago
                         WHERE empresa_id = :empresa
                           AND activo = 1
                           AND tipo <> 'SALDO_A_FAVOR'
                         ORDER BY medio_id
                        """)
                .param("empresa", empresa)
                .query(MedioDePago.class)
                .list();
    }

    /**
     * Todos los medios de la empresa, con las filas bloqueadas para escribir.
     *
     * Sirve para decidir sin carreras si un medio se puede desactivar: dos
     * administradores que desactivan a la vez los dos últimos medios activos
     * pasarían ambos la comprobación si cada uno mirara sin bloquear. Son pocas
     * filas, y las toma en el mismo orden ({@code medio_id}) quien las pida.
     */
    public List<MedioDePago> bloquearDeLaEmpresa(Integer empresa) {
        return jdbc.sql("""
                        SELECT medio_id AS id, nombre, tipo, activo
                          FROM medios_pago
                         WHERE empresa_id = :empresa
                         ORDER BY medio_id
                           FOR UPDATE
                        """)
                .param("empresa", empresa)
                .query(MedioDePago.class)
                .list();
    }

    /** El nombre se compara como la clave única de la tabla: sin distinguir mayúsculas ni tildes. */
    public boolean existeNombre(Integer empresa, String nombre) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM medios_pago
                         WHERE empresa_id = :empresa AND nombre = :nombre
                        """)
                .param("empresa", empresa)
                .param("nombre", nombre)
                .query(Integer.class)
                .single() > 0;
    }

    /** Inserta un medio activo y devuelve el identificador que el motor asignó. */
    public Integer insertar(Integer empresa, String nombre, TipoMedio tipo) {
        KeyHolder llaves = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO medios_pago (empresa_id, nombre, tipo, activo)
                        VALUES (:empresa, :nombre, :tipo, 1)
                        """)
                .param("empresa", empresa)
                .param("nombre", nombre)
                .param("tipo", tipo.name())
                .update(llaves);
        Number clave = llaves.getKey();
        if (clave == null) {
            throw new IllegalStateException(
                    "El motor no devolvió el identificador del medio de pago recién creado.");
        }
        return clave.intValue();
    }

    public Optional<MedioDePago> buscar(Integer empresa, Integer id) {
        return jdbc.sql("""
                        SELECT medio_id AS id, nombre, tipo, activo
                          FROM medios_pago
                         WHERE empresa_id = :empresa AND medio_id = :id
                        """)
                .param("empresa", empresa)
                .param("id", id)
                .query(MedioDePago.class)
                .optional();
    }

    public void actualizarActivo(Integer empresa, Integer id, boolean activo) {
        jdbc.sql("""
                        UPDATE medios_pago SET activo = :activo
                         WHERE empresa_id = :empresa AND medio_id = :id
                        """)
                .param("activo", activo)
                .param("empresa", empresa)
                .param("id", id)
                .update();
    }
}
