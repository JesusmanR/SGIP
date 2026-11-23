package co.com.bspstore.sgip.logica;

/**
 * Un administrador sin turno abierto intentó devolver dinero EN EFECTIVO. Sin turno puede devolver por
 * tarjeta, transferencia o saldo a favor, pero el efectivo sale de un cajón y el cajón es de un turno.
 * Es un 409: la solicitud es válida y falta el turno que la ampara.
 */
public class TurnoRequeridoParaEfectivoException extends RuntimeException {

    public static final String MENSAJE = "Abre un turno de caja para devolver efectivo.";

    public TurnoRequeridoParaEfectivoException() {
        super(MENSAJE);
    }
}
