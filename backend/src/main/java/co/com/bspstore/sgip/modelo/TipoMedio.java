package co.com.bspstore.sgip.modelo;

/**
 * La clase de un medio de pago (RF-43).
 *
 * Coincide con el ENUM de la columna {@code medios_pago.tipo} tal como la dejó
 * la V40: el crédito no es un medio de pago, es una forma de pago de la venta.
 *
 * {@link #SALDO_A_FAVOR} es el medio de sistema con que se paga con el saldo del
 * cliente (ADR-0033). No lo crea ni lo activa el comercio: nace con la empresa,
 * inactivo, y lo gestiona el sistema.
 */
public enum TipoMedio {
    EFECTIVO,
    TARJETA,
    TRANSFERENCIA,
    SALDO_A_FAVOR;

    /** Si el medio lo gestiona el sistema y no el comercio. */
    public boolean esDeSistema() {
        return this == SALDO_A_FAVOR;
    }
}
