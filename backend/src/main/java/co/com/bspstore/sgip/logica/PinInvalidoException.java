package co.com.bspstore.sgip.logica;

/**
 * El PIN no autoriza: está errado, o el administrador indicado no puede
 * autorizar a esta persona (es de otra empresa, no es administrador, está
 * inactivo o no tiene PIN).
 *
 * Es una sola excepción, con un solo texto, para las dos causas: quien tantea no
 * debe poder distinguir un PIN errado de un administrador que no existe para él.
 * El plazo del bloqueo viaja solo para que el mensaje diga cuánto dura.
 */
public class PinInvalidoException extends RuntimeException {

    private final int minutosDeBloqueo;

    public PinInvalidoException(int minutosDeBloqueo) {
        super("El administrador o el PIN no son correctos.");
        this.minutosDeBloqueo = minutosDeBloqueo;
    }

    public int getMinutosDeBloqueo() {
        return minutosDeBloqueo;
    }
}
