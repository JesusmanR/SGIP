package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.TipoMedio;

import java.math.BigDecimal;
import java.util.List;

/**
 * Una venta tal como la consulta el mostrador para devolverla (RF-68): su estado, su cliente, lo que se
 * vendió y cuánto se ha devuelto de cada línea, y cómo se pagó. {@code reembolsadoSinSaldo} es lo que ya se
 * reembolsó de la venta por medios que no son el saldo a favor, acumulado: con él la pantalla acota cuánto se puede
 * devolver todavía por esos medios. Nunca lleva costo ni utilidad.
 */
public record VentaConsultada(Long id, String consecutivo, String estado, String estadoPago, BigDecimal total,
                              Cliente cliente, List<Linea> lineas, List<Pago> pagos,
                              BigDecimal reembolsadoSinSaldo) {

    /** El cliente de la venta; {@code consumidorFinal} no puede tener saldo a favor. */
    public record Cliente(Integer id, String nombre, boolean consumidorFinal) {}

    /** Una línea: lo vendido, lo devuelto y lo que valen (con descuento e IVA). */
    public record Linea(Integer productoId, String nombre, int cantidad, int cantidadDevuelta, BigDecimal total,
                        BigDecimal valorDevuelto) {}

    /** Un pago: el medio, su clase y el monto (lo pagado por la venta, no lo recibido en efectivo). */
    public record Pago(Integer medioId, String medio, TipoMedio tipo, BigDecimal monto) {}
}
