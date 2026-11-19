package co.com.bspstore.sgip.logica.venta;

/**
 * Los topes de una venta en el mostrador. Los dos primeros acotan el trabajo de una sola
 * petición; los demás son lo que caben en las columnas de {@code ventas}, {@code venta_items}
 * y {@code pagos_venta}. Todos viven aquí para que el DTO y el servicio digan lo mismo.
 */
public final class LimitesDeVenta {

    /** Líneas distintas que caben en una venta (el mostrador no pasa de unas decenas). */
    public static final int LINEAS = 200;

    /** Pagos de una venta: un cobro mixto real usa dos o tres medios. */
    public static final int PAGOS = 10;

    /**
     * Unidades de un producto en una sola línea. Cabe de sobra en {@code INT}, y junto con el
     * piso de existencia de −9 999 deja un encargo en una cifra razonable.
     */
    public static final int CANTIDAD = 99_999;

    /** {@code pagos_venta.referencia}. */
    public static final int REFERENCIA = 60;

    /** {@code ventas.motivo_sin_existencia}. */
    public static final int MOTIVO_DEL_ENCARGO = 200;

    /**
     * Productos nuevos que caben en una venta. Cada uno cuesta unas consultas propias (su código,
     * su fila y su precio) y el resto de la venta no, así que el tope acota las consultas de una
     * sola petición. Una venta real trae uno, rara vez dos.
     */
    public static final int PRODUCTOS_NUEVOS = 10;

    /** {@code devoluciones.motivo}. */
    public static final int MOTIVO_DE_LA_DEVOLUCION = 200;

    /** {@code productos.nombre}. */
    public static final int NOMBRE_DEL_PRODUCTO = 160;

    /** {@code productos.codigo_barras}. */
    public static final int CODIGO_DE_BARRAS = 60;

    private LimitesDeVenta() {}
}
