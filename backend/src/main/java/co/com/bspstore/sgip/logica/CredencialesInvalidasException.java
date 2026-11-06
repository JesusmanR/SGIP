package co.com.bspstore.sgip.logica;

/**
 * El ingreso no se pudo hacer. Es la misma excepción con la clave errada, la
 * cuenta bloqueada, la cuenta inactiva o el usuario inexistente: distinguirlas
 * le diría a quien sondea qué correos están registrados.
 *
 * Lleva el plazo de bloqueo configurado porque el aviso al usuario lo menciona
 * siempre (ADR-0028): quien se equivoca varias veces y deja de poder entrar
 * necesita saber que esperar sirve, y decirlo a todos por igual no revela nada.
 */
public class CredencialesInvalidasException extends RuntimeException {

    private final int minutosDeBloqueo;

    public CredencialesInvalidasException(int minutosDeBloqueo) {
        super("Credenciales inválidas");
        this.minutosDeBloqueo = minutosDeBloqueo;
    }

    public int getMinutosDeBloqueo() {
        return minutosDeBloqueo;
    }
}
