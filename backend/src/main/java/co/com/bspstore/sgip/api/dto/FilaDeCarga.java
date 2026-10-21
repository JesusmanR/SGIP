package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * Una fila de la carga tal como quedó registrada, aceptada o no.
 *
 * `costoAnterior` acompaña a cada fila aceptada porque es lo que permite
 * revisar la lista antes de aplicarla: un costo que se multiplica por
 * diez salta a la vista al lado del que regía, y no al lado de nada.
 *
 * `motivoRechazo` es nulo en las aceptadas. En las rechazadas nunca lo
 * es —la restricción `ck_detalle_rechazo` lo impide en la base— porque
 * el RF-26 pide listar los registros rechazados «con su causa», y una
 * causa en blanco no es una causa.
 *
 * `productoId` es nulo en una fila rechazada por referencia inexistente:
 * no hay producto al que apuntar, y aun así la fila tiene que quedar
 * registrada. `numeroFila` es nulo cuando la carga no viene de un
 * archivo, que es el caso del RF-29.
 */
public record FilaDeCarga(
        Integer numeroFila,
        Integer productoId,
        String referencia,
        String descripcion,
        BigDecimal costo,
        BigDecimal costoAnterior,
        boolean aceptado,
        String motivoRechazo
) {}
