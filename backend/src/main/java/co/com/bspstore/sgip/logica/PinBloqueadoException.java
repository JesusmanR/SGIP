package co.com.bspstore.sgip.logica;

/**
 * Se superó el número de intentos con el PIN y el plazo del bloqueo no ha vencido.
 *
 * Vale para las dos cuentas del contador: el administrador cuyo PIN se erró y quien
 * apuntó a un objetivo que no vale. Se rechaza aunque el PIN que llegue sea el
 * correcto (ADR-0032, como el ingreso en la ADR-0028).
 */
public class PinBloqueadoException extends RuntimeException {

    private final int minutosDeBloqueo;

    public PinBloqueadoException(int minutosDeBloqueo) {
        super("La autorización con PIN está bloqueada por intentos fallidos.");
        this.minutosDeBloqueo = minutosDeBloqueo;
    }

    public int getMinutosDeBloqueo() {
        return minutosDeBloqueo;
    }
}
