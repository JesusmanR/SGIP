package co.com.bspstore.sgip.logica;

/**
 * El administrador de una empresa pidió un rol que no le toca repartir
 * (RF-12).
 *
 * El caso que importa es SUPER_ADMIN. Ese rol gobierna el ciclo de vida
 * de todas las empresas y los datos tributarios del país, y si un
 * administrador pudiera asignarlo se daría el mando de la plataforma
 * desde la pantalla de usuarios de su propia tienda. La RN-17 lo reserva
 * a quien opera la plataforma.
 */
public class RolNoAsignableException extends RuntimeException {
    public RolNoAsignableException(String mensaje) {
        super(mensaje);
    }
}
