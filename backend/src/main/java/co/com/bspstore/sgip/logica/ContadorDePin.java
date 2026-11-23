package co.com.bspstore.sgip.logica;

import java.time.LocalDateTime;

/**
 * Los fallos seguidos de un PIN y, si se llegó al máximo, desde cuándo está
 * bloqueado. Es el mismo estado dos veces en cada usuario: los fallos contra su
 * PIN y los que comete contra un objetivo que no vale (ADR-0032).
 *
 * Inmutable y sin reloj ni base: cada método recibe el instante. El plazo lo dice
 * {@link PoliticaDeBloqueo}; esta clase solo cuenta.
 */
record ContadorDePin(int fallos, LocalDateTime bloqueadoEn) {

    static final ContadorDePin LIMPIO = new ContadorDePin(0, null);

    boolean bloqueado() {
        return bloqueadoEn != null;
    }

    /**
     * Cuenta un fallo. Con el contador ya bloqueado no hace nada: ni alarga el plazo
     * (el instante es el del bloqueo) ni sigue sumando, que la columna admite hasta 50.
     */
    ContadorDePin conUnFallo(int maximo, LocalDateTime ahora) {
        if (bloqueado()) {
            return this;
        }
        int total = fallos + 1;
        return total >= maximo ? new ContadorDePin(total, ahora) : new ContadorDePin(total, null);
    }
}
