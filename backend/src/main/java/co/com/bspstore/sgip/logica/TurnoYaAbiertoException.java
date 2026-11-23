package co.com.bspstore.sgip.logica;

/**
 * El cajero ya tiene un turno abierto y pidió abrir otro. Es un 409: la petición es
 * válida y es el estado del cajero lo que impide atenderla. El texto es fijo y no dice
 * nada del turno que ya existe.
 */
public class TurnoYaAbiertoException extends RuntimeException {

    public TurnoYaAbiertoException() {
        super("Ya tienes un turno de caja abierto. Ciérralo antes de abrir otro.");
    }
}
