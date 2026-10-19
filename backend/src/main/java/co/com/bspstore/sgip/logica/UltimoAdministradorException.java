package co.com.bspstore.sgip.logica;

/**
 * La operación dejaría a la empresa sin administrador activo, o el
 * administrador se la está haciendo a sí mismo (RF-12).
 *
 * No es una formalidad. Una empresa sin administrador activo no tiene
 * quién le cree usuarios —eso es cosa de su propio administrador, y el
 * super administrador no entra en los comercios (RN-17)—, de modo que
 * recuperarla pediría intervenir la base de datos a mano.
 */
public class UltimoAdministradorException extends RuntimeException {
    public UltimoAdministradorException(String mensaje) {
        super(mensaje);
    }
}
