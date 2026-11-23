package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.TipoMedio;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * El resumen de un turno de caja (RF-45): lo que el sistema espera encontrar en el cajón, lo que el cajero
 * contó y lo cobrado por cada tipo de medio.
 *
 * <ul>
 *   <li>{@code efectivoEsperado}: la base, más los pagos en efectivo del turno (ventas y cobros contra
 *       entrega), menos los reembolsos en efectivo de las devoluciones del turno. El cambio entregado no
 *       cuenta: {@code monto} ya es el neto.</li>
 *   <li>{@code efectivoContado}, {@code diferencia} (contado menos esperado) y {@code cierre}: nulos
 *       mientras el turno está abierto.</li>
 *   <li>{@code porMedio}: lo cobrado en el turno por tipo de medio, solo los tipos con pagos. Los
 *       reembolsos no se restan aquí, solo del efectivo esperado, y el pago con saldo a favor no lleva
 *       turno y no aparece.</li>
 * </ul>
 *
 * Las horas son de Bogotá y viajan con su desplazamiento. El dinero va en pesos enteros.
 */
public record ResumenDeTurno(Long id, String cajero, OffsetDateTime apertura, OffsetDateTime cierre,
                             BigDecimal baseEfectivo, BigDecimal efectivoEsperado, BigDecimal efectivoContado,
                             BigDecimal diferencia, Map<TipoMedio, BigDecimal> porMedio, String observacion) {
}
