package co.com.bspstore.sgip.modelo;

/**
 * Los cuatro tipos de movimiento de inventario (RF-49).
 *
 * Coincide con el ENUM de {@code movimientos_inventario.tipo}, y además
 * lleva la dirección, que es lo que la V23 fijó en
 * {@code ck_movimientos_coherencia}:
 *
 * <pre>
 *   INGRESO, DEVOLUCION → posterior = anterior + cantidad
 *   EGRESO              → posterior = anterior − cantidad
 *   AJUSTE              → |posterior − anterior| = cantidad
 * </pre>
 *
 * Tener la regla en los dos sitios es deliberado y no es duplicarla por
 * descuido: la del esquema impide guardar una fila incoherente venga de
 * donde venga, y la de aquí calcula el saldo que se va a guardar. Si
 * discreparan, la base rechaza la escritura, que es el fallo ruidoso y
 * no el silencioso.
 */
public enum TipoMovimiento {

    /** Mercancía que entra: compra, recepción, saldo inicial (RF-48). */
    INGRESO(1),

    /** Mercancía que sale: una venta, una baja. */
    EGRESO(-1),

    /** Lo que un cliente devuelve, que vuelve a la bodega. */
    DEVOLUCION(1),

    /**
     * Corrección del saldo contra un conteo físico.
     *
     * Es el único sin dirección fija: un conteo puede encontrar más o
     * menos de lo que decía el sistema. Por eso su dirección se calcula
     * del conteo y no del tipo, y por eso es el único que la V23 obliga
     * a traer motivo.
     */
    AJUSTE(0);

    private final int direccion;

    TipoMovimiento(int direccion) {
        this.direccion = direccion;
    }

    /**
     * El saldo que queda tras mover {@code cantidad} unidades desde
     * {@code anterior}.
     *
     * No vale para el AJUSTE, que no tiene dirección propia: su saldo
     * posterior es el conteo, y la cantidad se deriva de la diferencia.
     * Llamarlo con AJUSTE es un error de programación, no de datos, de
     * modo que falla en el acto en lugar de devolver el mismo saldo y
     * dejar que la base rechace una fila incoherente más tarde.
     */
    public int saldoTras(int anterior, int cantidad) {
        if (this == AJUSTE) {
            throw new IllegalStateException(
                    "El saldo de un ajuste es el conteo, no se calcula desde la cantidad.");
        }
        return anterior + direccion * cantidad;
    }

    /** Si este tipo exige motivo. Es la mitad en Java de ck_movimientos_ajuste. */
    public boolean exigeMotivo() {
        return this == AJUSTE;
    }
}
