package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Una cotización en la lista de la caja y de la pantalla «Cotizaciones» (RF-37). {@code estado} es
 * {@code VIGENTE}, {@code VENCIDA}, {@code CONVERTIDA} o {@code ANULADA}; {@code VENCIDA} no se guarda: la
 * consulta la calcula con el día de hoy de Bogotá a partir de una cotización vigente cuyo vencimiento ya pasó.
 */
public record CotizacionEnLista(Long id, String numero, LocalDate fechaEmision, String clienteNombre,
                                BigDecimal total, LocalDate fechaVencimiento, String estado) {
}
