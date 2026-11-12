package co.com.bspstore.sgip.logica;

import java.util.Set;

/**
 * Lo que el administrador autorizó en ESTA petición (ADR-0032).
 *
 * Vive en memoria y nadie la guarda: la crea {@link ServicioAutorizacion#autorizar}
 * y la consume la operación que la pidió, dentro de la misma petición. No hay forma
 * de reutilizarla en otra, porque no existe en ningún sitio al que otra petición
 * pueda llegar.
 *
 * {@code administradorId} es nulo cuando no se pidió nada que autorizar: la
 * autorización existe y no cubre nada.
 */
public record Autorizacion(Integer administradorId, Set<ExcepcionAutorizable> cubre) {

    public Autorizacion {
        // Una copia inmutable: quien reciba la autorización no puede ampliarla.
        cubre = cubre == null ? Set.of() : Set.copyOf(cubre);
    }

    /** Falla con un 403 si lo que se está por hacer no está cubierto. */
    public void exigir(ExcepcionAutorizable excepcion) {
        if (!cubre.contains(excepcion)) {
            throw new AutorizacionRequeridaException();
        }
    }
}
