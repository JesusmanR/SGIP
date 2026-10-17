package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.EmpresaEnPlataforma;
import co.com.bspstore.sgip.modelo.AccionPlataforma;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Las tablas que gobierna la plataforma: empresas, suscripciones,
 * empresa_config, parametros_plataforma y la bitácora de la V21.
 *
 * No es JPA, y por un motivo distinto al de {@link ConsultaCatalogo}.
 * Allí era que una vista no es un agregado; aquí es que ya existe una
 * entidad {@code modelo.Empresa} mapeada a esta misma tabla, y lo dice
 * con claridad en su comentario: trae solo «lo que el comercio consulta
 * o cambia de sí mismo», y deja fuera el estado y la suscripción para
 * que ninguna pantalla de empresa los toque.
 *
 * Mapear una segunda entidad sobre {@code empresas} para añadirle esas
 * columnas desharía esa decisión: a partir de ahí habría dos caminos
 * para escribir la misma fila, y el que ninguna pantalla debía tener lo
 * tendría cualquiera que inyectara el repositorio equivocado. Con
 * sentencias explícitas, la única forma de cambiar el estado de una
 * empresa es llamar al método que lo cambia, que vive en esta clase y
 * exige pasar por el servicio de plataforma.
 *
 * Ninguna consulta de aquí filtra por empresa, al contrario que todas
 * las demás del proyecto. Es correcto y conviene decirlo en voz alta: la
 * RN-17 pone al super administrador por encima de las empresas para
 * administrar su ciclo de vida. Lo que ninguna consulta de aquí hace es
 * tocar datos operativos —catálogo, inventario, ventas—, que es la otra
 * mitad de esa misma regla.
 */
@Repository
public class RepositorioPlataforma {

    /**
     * El valor por omisión si el parámetro no está cargado.
     *
     * Coincide con el que la V22 inserta. Está duplicado a propósito: sin
     * él, una base a la que le falte la fila daría un periodo de prueba
     * nulo y la suscripción no se podría crear, de modo que el alta
     * fallaría por una razón que no tiene nada que ver con el alta.
     */
    private static final int DIAS_PRUEBA_POR_OMISION = 30;

    /**
     * Los nombres de columna van en snake_case y no en camelCase porque
     * es el mapeo que el proyecto ya tiene rodado: {@link ConsultaCatalogo}
     * selecciona {@code producto_id} contra un componente
     * {@code productoId} y funciona. Las dos columnas {@code estado} —la
     * de la empresa y la de su suscripción— sí necesitan alias, o la
     * segunda taparía a la primera.
     */
    private static final String SELECCION = """
            SELECT e.empresa_id,
                   e.nit,
                   e.razon_social,
                   e.correo_contacto,
                   e.telefono,
                   e.ciudad,
                   e.estado,
                   e.creada_en,
                   s.plan,
                   s.inicio_prueba,
                   s.fin_prueba,
                   s.estado AS estado_suscripcion,
                   (SELECT COUNT(*) FROM usuarios u
                     WHERE u.empresa_id = e.empresa_id) AS usuarios
              FROM empresas e
              LEFT JOIN suscripciones s ON s.empresa_id = e.empresa_id
            """;

    private final JdbcClient jdbc;

    public RepositorioPlataforma(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- lectura

    public List<EmpresaEnPlataforma> listar() {
        return jdbc.sql(SELECCION + " ORDER BY e.razon_social")
                .query(EmpresaEnPlataforma.class)
                .list();
    }

    public Optional<EmpresaEnPlataforma> buscar(Integer empresaId) {
        return jdbc.sql(SELECCION + " WHERE e.empresa_id = :empresa")
                .param("empresa", empresaId)
                .query(EmpresaEnPlataforma.class)
                .optional();
    }

    public boolean existeNit(String nit) {
        return jdbc.sql("SELECT COUNT(*) FROM empresas WHERE nit = :nit")
                .param("nit", nit)
                .query(Integer.class)
                .single() > 0;
    }

    /**
     * El estado actual, que es lo que hay que anotar como valor anterior
     * antes de cambiarlo. Vacío si la empresa no existe.
     */
    public Optional<EstadoEmpresa> estadoDe(Integer empresaId) {
        return jdbc.sql("SELECT estado FROM empresas WHERE empresa_id = :empresa")
                .param("empresa", empresaId)
                .query(String.class)
                .optional()
                .map(EstadoEmpresa::valueOf);
    }

    /**
     * Los días de prueba vigentes hoy (RN-14).
     *
     * La vigencia se filtra en la consulta, no en Java: la tabla guarda
     * el histórico y lo que importa es el valor que rige en la fecha de
     * la operación. Si hubiera varias filas vigentes —la clave única lo
     * impide para la misma fecha de inicio— gana la más reciente.
     */
    public int diasDePrueba() {
        return jdbc.sql("""
                        SELECT valor FROM parametros_plataforma
                         WHERE clave = 'sgip.plataforma.prueba.dias'
                           AND vigente_desde <= CURDATE()
                           AND (vigente_hasta IS NULL OR vigente_hasta >= CURDATE())
                         ORDER BY vigente_desde DESC
                         LIMIT 1
                        """)
                .query(String.class)
                .optional()
                .map(Integer::parseInt)
                .orElse(DIAS_PRUEBA_POR_OMISION);
    }

    // ---------------------------------------------------------------- escritura

    /** Devuelve el identificador que el motor asignó. */
    public Integer insertarEmpresa(String nit, String razonSocial, String correoContacto,
                                   String telefono, String ciudad) {
        KeyHolder llaves = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO empresas
                               (nit, razon_social, correo_contacto, telefono, ciudad, estado)
                        VALUES (:nit, :razon, :correo, :telefono, :ciudad, 'PRUEBA')
                        """)
                .param("nit", nit)
                .param("razon", razonSocial)
                .param("correo", correoContacto)
                .param("telefono", telefono)
                .param("ciudad", ciudad)
                .update(llaves);
        Number clave = llaves.getKey();
        if (clave == null) {
            throw new IllegalStateException(
                    "El motor no devolvió el identificador de la empresa recién creada.");
        }
        return clave.intValue();
    }

    public void insertarSuscripcion(Integer empresaId, String plan,
                                    LocalDate inicioPrueba, LocalDate finPrueba) {
        jdbc.sql("""
                        INSERT INTO suscripciones
                               (empresa_id, plan, inicio_prueba, fin_prueba, estado)
                        VALUES (:empresa, :plan, :inicio, :fin, 'PRUEBA')
                        """)
                .param("empresa", empresaId)
                .param("plan", plan)
                .param("inicio", inicioPrueba)
                .param("fin", finPrueba)
                .update();
    }

    /**
     * La configuración tributaria arranca en sus valores por omisión, que
     * la V12 eligió conservadores: sin responsabilidad de impuesto sobre
     * las ventas, sin exclusión por umbral y sin retenciones.
     *
     * La fila se crea vacía en lugar de dejarla sin crear porque las
     * vistas de precios la enlazan, y una empresa sin ella saldría con
     * {@code tributacion_sin_configurar} por una fila que falta y no por
     * una decisión pendiente. Con la fila presente y
     * {@code configuracion_completa} en falso, la pantalla puede decir
     * «falta configurar» en lugar de no encontrar nada.
     */
    public void insertarConfiguracion(Integer empresaId) {
        jdbc.sql("INSERT INTO empresa_config (empresa_id) VALUES (:empresa)")
                .param("empresa", empresaId)
                .update();
    }

    /** Devuelve las filas afectadas: cero si la empresa no existe. */
    public int cambiarEstado(Integer empresaId, EstadoEmpresa nuevo) {
        return jdbc.sql("UPDATE empresas SET estado = :estado WHERE empresa_id = :empresa")
                .param("estado", nuevo.name())
                .param("empresa", empresaId)
                .update();
    }

    /**
     * Anota una decisión en la bitácora de la plataforma (V21).
     *
     * Las dos comprobaciones de arriba repiten en Java lo que la tabla ya
     * exige con restricciones. No es desconfianza del motor: es que un
     * CHECK rechazado llega como una excepción de integridad genérica,
     * sin decir qué faltaba, mientras que fallar aquí nombra el problema.
     * El CHECK sigue siendo la red de seguridad para cualquier otro
     * camino hacia la tabla.
     */
    public void registrar(Integer usuarioId, Integer empresaId, AccionPlataforma accion,
                          String entidad, String referencia,
                          String valorAnterior, String valorNuevo, String motivo) {
        if (accion.esDeEmpresa() != (empresaId != null)) {
            throw new IllegalArgumentException(
                    "La acción " + accion + (accion.esDeEmpresa()
                            ? " recae sobre una empresa y llegó sin empresa."
                            : " es de alcance nacional y llegó atribuida a una empresa."));
        }
        if (accion.esCambio() && valorAnterior == null) {
            throw new IllegalArgumentException(
                    "La acción " + accion + " modifica algo que ya existía y llegó "
                            + "sin valor anterior, de modo que la bitácora no diría de qué se cambió.");
        }
        jdbc.sql("""
                        INSERT INTO auditoria_plataforma
                               (usuario_id, empresa_id, accion, entidad, referencia,
                                valor_anterior, valor_nuevo, motivo)
                        VALUES (:usuario, :empresa, :accion, :entidad, :referencia,
                                :anterior, :nuevo, :motivo)
                        """)
                .param("usuario", usuarioId)
                .param("empresa", empresaId)
                .param("accion", accion.name())
                .param("entidad", entidad)
                .param("referencia", referencia)
                .param("anterior", valorAnterior)
                .param("nuevo", valorNuevo)
                .param("motivo", motivo)
                .update();
    }
}
