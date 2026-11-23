package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.ClienteEnLista;
import co.com.bspstore.sgip.logica.ServicioClientes;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Los clientes de cada empresa (RF-66).
 *
 * Toda sentencia lleva {@code empresa_id} (ADR-0011): el cliente de otra empresa
 * es, para quien pregunta, un cliente que no existe. La tabla conserva los nombres
 * de columna con que nació para empresas ({@code razon_social}, {@code nit}); aquí
 * se leen como {@code nombre} e {@code identificacion}.
 */
@Repository
public class RepositorioClientes {

    private final JdbcClient jdbc;

    public RepositorioClientes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Una página de la lista de clientes, ordenada por nombre y, a igual nombre, por identificador, con
     * lo que la tienda le debe a cada uno ({@code LEFT JOIN saldos_cliente}: sin fila, cero).
     *
     * Los dos patrones llegan ya escritos y con los comodines del texto escapados; los dos son nulos
     * cuando no hay búsqueda, y entonces el filtro se anula a sí mismo (como en {@code ConsultaCatalogo}).
     * Si hay, basta que cumpla uno: el documento empieza por el texto o el nombre lo contiene. La página
     * cuesta una consulta y el total, otra, sin importar cuántos clientes haya (RNF-03); el índice
     * {@code ix_clientes_empresa_razon} (V47) sirve el orden sin recorrer a las otras empresas.
     *
     * {@code editable} es falso para el Consumidor final: el marcado como tal y, por si acaso, el que lleva
     * su documento genérico.
     */
    public List<ClienteEnLista> listar(Integer empresa, String prefijoDeIdentificacion, String contieneNombre,
                                       int limite, long desplazamiento) {
        return jdbc.sql("""
                        SELECT c.cliente_id AS id, c.tipo_documento AS tipoDocumento, c.nit AS identificacion,
                               c.razon_social AS nombre, c.telefono, c.correo, c.direccion, c.ciudad,
                               c.autorizacion_datos IS NOT NULL AS autorizoDatos,
                               COALESCE(s.saldo, 0.00) AS saldoAFavor,
                               NOT (c.consumidor_final = 1
                                    OR (c.tipo_documento = :tipoGenerico AND c.nit = :identificacionGenerica))
                                   AS editable
                          FROM clientes c
                          LEFT JOIN saldos_cliente s
                                 ON s.empresa_id = c.empresa_id AND s.cliente_id = c.cliente_id
                         WHERE c.empresa_id = :empresa
                           AND (:prefijo IS NULL
                                OR c.nit LIKE :prefijo
                                OR c.razon_social LIKE :contiene)
                         ORDER BY c.razon_social, c.cliente_id
                         LIMIT :limite OFFSET :desplazamiento
                        """)
                .param("tipoGenerico", ServicioClientes.TIPO_CONSUMIDOR_FINAL.name())
                .param("identificacionGenerica", ServicioClientes.IDENTIFICACION_CONSUMIDOR_FINAL)
                .param("empresa", empresa)
                .param("prefijo", prefijoDeIdentificacion)
                .param("contiene", contieneNombre)
                .param("limite", limite)
                .param("desplazamiento", desplazamiento)
                .query(ClienteEnLista.class)
                .list();
    }

    /** Cuántos clientes de la empresa cumplen la misma búsqueda que {@link #listar}. */
    public long contar(Integer empresa, String prefijoDeIdentificacion, String contieneNombre) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM clientes c
                         WHERE c.empresa_id = :empresa
                           AND (:prefijo IS NULL
                                OR c.nit LIKE :prefijo
                                OR c.razon_social LIKE :contiene)
                        """)
                .param("empresa", empresa)
                .param("prefijo", prefijoDeIdentificacion)
                .param("contiene", contieneNombre)
                .query(Long.class)
                .single();
    }

    public Optional<Cliente> buscar(Integer empresa, Integer id) {
        return jdbc.sql("""
                        SELECT cliente_id AS id, razon_social AS nombre, tipo_documento AS tipoDocumento,
                               nit AS identificacion, correo, telefono, direccion, ciudad,
                               consumidor_final AS consumidorFinal
                          FROM clientes
                         WHERE empresa_id = :empresa AND cliente_id = :id
                        """)
                .param("empresa", empresa)
                .param("id", id)
                .query(Cliente.class)
                .optional();
    }

    public Optional<Cliente> buscarPorIdentificacion(Integer empresa, TipoDocumento tipo,
                                                     String identificacion) {
        return jdbc.sql("""
                        SELECT cliente_id AS id, razon_social AS nombre, tipo_documento AS tipoDocumento,
                               nit AS identificacion, correo, telefono, direccion, ciudad,
                               consumidor_final AS consumidorFinal
                          FROM clientes
                         WHERE empresa_id = :empresa
                           AND tipo_documento = :tipo AND nit = :identificacion
                        """)
                .param("empresa", empresa)
                .param("tipo", tipo.name())
                .param("identificacion", identificacion)
                .query(Cliente.class)
                .optional();
    }

    public Optional<Cliente> buscarConsumidorFinal(Integer empresa) {
        return jdbc.sql("""
                        SELECT cliente_id AS id, razon_social AS nombre, tipo_documento AS tipoDocumento,
                               nit AS identificacion, correo, telefono, direccion, ciudad,
                               consumidor_final AS consumidorFinal
                          FROM clientes
                         WHERE empresa_id = :empresa AND consumidor_final = 1
                        """)
                .param("empresa", empresa)
                .query(Cliente.class)
                .optional();
    }

    /**
     * Si otro cliente de la empresa ya tiene ese documento. {@code excluirId} deja
     * fuera al cliente que se está editando; al crear va un identificador que no
     * existe (cero). La comparación es la de la clave única de la tabla.
     */
    public boolean existeOtroConLaIdentificacion(Integer empresa, TipoDocumento tipo,
                                                 String identificacion, Integer excluirId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM clientes
                         WHERE empresa_id = :empresa
                           AND tipo_documento = :tipo AND nit = :identificacion
                           AND cliente_id <> :excluir
                        """)
                .param("empresa", empresa)
                .param("tipo", tipo.name())
                .param("identificacion", identificacion)
                .param("excluir", excluirId)
                .query(Integer.class)
                .single() > 0;
    }

    /** La ciudad de la empresa, que toma el cliente cuando no trae la suya. */
    public Optional<String> ciudadDeLaEmpresa(Integer empresa) {
        return jdbc.sql("SELECT ciudad FROM empresas WHERE empresa_id = :empresa")
                .param("empresa", empresa)
                .query(String.class)
                .optional();
    }

    /**
     * Inserta un cliente que autorizó el tratamiento de sus datos y devuelve el
     * identificador que el motor asignó. El instante y el medio de la autorización
     * van juntos porque {@code ck_clientes_autorizacion} exige el medio cuando hay
     * instante.
     */
    public Integer insertar(Integer empresa, Cliente datos, LocalDateTime ahora, String medioAutorizacion) {
        KeyHolder llaves = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO clientes (empresa_id, razon_social, tipo_documento, nit, correo,
                                              telefono, direccion, ciudad, autorizacion_datos,
                                              medio_autorizacion, activo, creado_en)
                        VALUES (:empresa, :nombre, :tipo, :identificacion, :correo,
                                :telefono, :direccion, :ciudad, :ahora,
                                :medio, 1, :ahora)
                        """)
                .param("empresa", empresa)
                .param("nombre", datos.nombre())
                .param("tipo", datos.tipoDocumento().name())
                .param("identificacion", datos.identificacion())
                .param("correo", datos.correo())
                .param("telefono", datos.telefono())
                .param("direccion", datos.direccion())
                .param("ciudad", datos.ciudad())
                .param("ahora", ahora)
                .param("medio", medioAutorizacion)
                .update(llaves);
        Number clave = llaves.getKey();
        if (clave == null) {
            throw new IllegalStateException(
                    "El motor no devolvió el identificador del cliente recién creado.");
        }
        return clave.intValue();
    }

    /**
     * Actualiza los datos de un cliente que no sea el consumidor final.
     *
     * Si el cliente ya había autorizado, se conservan el instante y el medio
     * originales: editar un teléfono no vuelve a firmar un consentimiento. El medio
     * se asigna antes que el instante a propósito: MySQL evalúa las asignaciones de
     * izquierda a derecha, y la segunda vería ya el instante recién escrito.
     */
    public void actualizar(Integer empresa, Integer id, Cliente datos, LocalDateTime ahora,
                           String medioAutorizacion) {
        jdbc.sql("""
                        UPDATE clientes
                           SET razon_social = :nombre, tipo_documento = :tipo, nit = :identificacion,
                               correo = :correo, telefono = :telefono, direccion = :direccion,
                               ciudad = :ciudad,
                               medio_autorizacion = IF(autorizacion_datos IS NULL, :medio, medio_autorizacion),
                               autorizacion_datos = COALESCE(autorizacion_datos, :ahora)
                         WHERE empresa_id = :empresa AND cliente_id = :id AND consumidor_final = 0
                        """)
                .param("nombre", datos.nombre())
                .param("tipo", datos.tipoDocumento().name())
                .param("identificacion", datos.identificacion())
                .param("correo", datos.correo())
                .param("telefono", datos.telefono())
                .param("direccion", datos.direccion())
                .param("ciudad", datos.ciudad())
                .param("medio", medioAutorizacion)
                .param("ahora", ahora)
                .param("empresa", empresa)
                .param("id", id)
                .update();
    }
}
