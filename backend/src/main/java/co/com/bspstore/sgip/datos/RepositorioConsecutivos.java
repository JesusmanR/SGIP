package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.TipoConsecutivo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

/**
 * Los consecutivos de cada empresa (tabla {@code consecutivos}, V44): el número que lleva el
 * siguiente documento de cada tipo.
 *
 * <h2>El bloqueo</h2>
 *
 * El consecutivo es el <b>último</b> bloqueo de la caja antes de insertar (turno, venta, productos,
 * saldo del cliente, consecutivo, kárdex). Se toma con {@code SELECT ... FOR UPDATE} y se suelta al
 * terminar la transacción de quien lo pidió: dos ventas a la vez se numeran una detrás de otra, sin
 * huecos ni repetidos, y si la venta se revierte el número vuelve a quedar libre.
 *
 * <h2>La fila de una empresa nace con su primer uso</h2>
 *
 * Se lee primero con {@code FOR UPDATE}; solo si no existe se crea con
 * {@code INSERT ... ON DUPLICATE KEY UPDATE siguiente = siguiente} y se vuelve a leer con el mismo
 * bloqueo. La cláusula no cambia nada: existe para que, si la fila ya está (la creó otra transacción
 * que llegó antes), el motor pida directamente el bloqueo EXCLUSIVO y espere. Un {@code INSERT IGNORE}
 * toma en ese caso un bloqueo compartido, y con tres transacciones a la vez las dos que esperan lo
 * tienen y después piden el exclusivo: un ciclo, y el motor mata a una. En régimen normal (la fila
 * ya existe) este {@code INSERT} no se ejecuta.
 */
@Repository
public class RepositorioConsecutivos {

    private final JdbcClient jdbc;

    public RepositorioConsecutivos(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * El número que toca y deja el siguiente. Tiene que llamarse dentro de la transacción del
     * documento: fuera de una, el bloqueo se soltaría al terminar la sentencia y dos documentos
     * podrían llevar el mismo número.
     */
    public long tomarSiguiente(Integer empresaId, TipoConsecutivo tipo) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("El consecutivo se toma dentro de la transacción del documento.");
        }
        Optional<Long> actual = leerConBloqueo(empresaId, tipo);
        if (actual.isEmpty()) {
            jdbc.sql("""
                            INSERT INTO consecutivos (empresa_id, tipo) VALUES (:empresa, :tipo)
                            ON DUPLICATE KEY UPDATE siguiente = siguiente
                            """)
                    .param("empresa", empresaId)
                    .param("tipo", tipo.name())
                    .update();
            actual = leerConBloqueo(empresaId, tipo);
        }
        long numero = actual.orElseThrow(() -> new IllegalStateException(
                "El consecutivo " + tipo + " de la empresa " + empresaId + " no existe después de crearlo."));
        jdbc.sql("""
                        UPDATE consecutivos SET siguiente = :siguiente
                         WHERE empresa_id = :empresa AND tipo = :tipo
                        """)
                .param("siguiente", numero + 1)
                .param("empresa", empresaId)
                .param("tipo", tipo.name())
                .update();
        return numero;
    }

    private Optional<Long> leerConBloqueo(Integer empresaId, TipoConsecutivo tipo) {
        return jdbc.sql("""
                        SELECT siguiente FROM consecutivos
                         WHERE empresa_id = :empresa AND tipo = :tipo
                           FOR UPDATE
                        """)
                .param("empresa", empresaId)
                .param("tipo", tipo.name())
                .query(Long.class)
                .optional();
    }
}
