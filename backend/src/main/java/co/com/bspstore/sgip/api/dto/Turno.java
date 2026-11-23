package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Un turno de caja (RF-45): quién lo tiene, cuándo se abrió, con cuánto efectivo y, si ya
 * se cerró, cuándo.
 *
 * Las horas son de Bogotá y viajan con su desplazamiento ({@code -05:00}), para que el
 * cliente las lea tal cual. {@code baseEfectivo} va en pesos enteros. {@code cierre} es
 * nulo mientras el turno está abierto.
 */
public record Turno(Long id, Integer cajeroId, String cajero, OffsetDateTime apertura,
                    BigDecimal baseEfectivo, OffsetDateTime cierre) {
}
