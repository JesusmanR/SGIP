package co.com.bspstore.sgip.config;

/**
 * El usuario autenticado no pertenece a ninguna empresa y pidió una
 * operación que sí la necesita. Le ocurre al usuario de plataforma, que
 * administra empresas pero no opera dentro de ninguna (RN-17).
 *
 * Es 403 y no 401: sabemos quién es y aun así no puede.
 */
public class SinEmpresaException extends RuntimeException {

    public SinEmpresaException() {
        super("El usuario de plataforma no opera dentro de una empresa.");
    }
}
