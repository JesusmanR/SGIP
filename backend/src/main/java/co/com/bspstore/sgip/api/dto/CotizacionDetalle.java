package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Una cotización completa (RF-37): la cabecera, el cliente, quién la creó y sus líneas.
 *
 * {@code estado} es {@code VIGENTE}, {@code VENCIDA}, {@code CONVERTIDA} o {@code ANULADA}. {@code VENCIDA} no se
 * guarda: se calcula al leer, con el día de hoy de Bogotá, a partir de una cotización vigente cuya fecha de
 * vencimiento ya pasó. {@code ventaId} solo lo tiene una convertida y {@code motivoAnulacion}, una anulada.
 * Nunca lleva costo ni utilidad.
 */
public record CotizacionDetalle(Long id, String numero, String estado, LocalDate fechaEmision,
                                LocalDate fechaVencimiento, int vigenciaDias, Integer clienteId,
                                String clienteNombre, String clienteIdentificacion, Integer usuarioId,
                                String creadaPor, List<LineaDeCotizacion> lineas, BigDecimal subtotal,
                                BigDecimal iva, BigDecimal total, String observaciones, Long ventaId,
                                String motivoAnulacion) {
}
