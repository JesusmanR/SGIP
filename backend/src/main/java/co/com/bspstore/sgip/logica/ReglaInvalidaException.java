package co.com.bspstore.sgip.logica;

/**
 * Una regla de ganancia viene bien formada y aun así no se puede
 * guardar (RF-30, RF-32).
 *
 * Son las comprobaciones que la validación declarativa no alcanza
 * porque dependen de otra cosa: el tope del factor depende de la base
 * de cálculo —hasta 2 en el margen sobre el precio, sin tope en el
 * recargo sobre el costo—, y el cierre de una vigencia depende de
 * cuándo empezó a regir la regla.
 *
 * La base también las rechazaría, pero como error de integridad, y
 * entonces quien configura lee «CONSTRAINT ck_reglas_margen_sobre_precio
 * failed» en lugar de por qué un margen del 100 % no existe.
 */
public class ReglaInvalidaException extends RuntimeException {
    public ReglaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
