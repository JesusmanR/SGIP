package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.TipoMedio;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * El recibo de una venta (RF-70): lo mismo que se imprime en el mostrador, para reimprimir una venta pasada
 * o sacarla en PDF. Nunca lleva costo ni utilidad, tampoco al administrador.
 *
 * {@code iva} (el total y el de cada línea) es nulo cuando la empresa no es responsable de IVA (RF-34): el
 * recibo no discrimina un impuesto que la empresa no cobra. {@code marcas} son los avisos del recibo, ya
 * escritos, en el orden en que se imprimen.
 */
public record Recibo(String empresa, String nitEmpresa, String consecutivo, OffsetDateTime fecha, String cajero,
                     String cliente, String identificacionCliente, List<LineaDeRecibo> lineas,
                     BigDecimal subtotal, BigDecimal descuento, BigDecimal iva, BigDecimal total,
                     List<PagoDeRecibo> pagos, BigDecimal cambio, List<String> marcas) {

    /** La frase con que termina todo recibo: no sustituye a la factura electrónica. */
    public static final String PIE = "Este recibo no es una factura electrónica.";

    /** Una línea: lo vendido, con su descuento y su IVA; {@code total} ya los incluye. */
    public record LineaDeRecibo(String nombre, int cantidad, BigDecimal precioUnitario, BigDecimal descuento,
                                BigDecimal iva, BigDecimal total) {}

    /**
     * Un pago: el medio y su clase (el saldo a favor aparece como tal), lo cobrado por la venta y, si se
     * recibió más en efectivo, lo recibido.
     */
    public record PagoDeRecibo(String medio, TipoMedio tipo, BigDecimal monto, BigDecimal recibido,
                               String referencia) {}
}
