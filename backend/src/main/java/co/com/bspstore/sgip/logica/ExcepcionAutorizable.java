package co.com.bspstore.sgip.logica;

/**
 * Lo que un vendedor no puede hacer solo y el administrador autoriza con su PIN
 * (ADR-0032). Una autorización puede cubrir varias a la vez: un solo PIN autoriza,
 * por ejemplo, un descuento y un encargo de la misma venta.
 */
public enum ExcepcionAutorizable {
    DESCUENTO,
    ENCARGO,
    PRODUCTO_NUEVO,
    DEVOLUCION
}
