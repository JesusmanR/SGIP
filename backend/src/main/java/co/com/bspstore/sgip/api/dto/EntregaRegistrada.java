package co.com.bspstore.sgip.api.dto;

import java.time.OffsetDateTime;

/**
 * La entrega de un domicilio o de un encargo, tal como quedó: quién la marcó y cuándo (la hora de
 * Bogotá de la aplicación).
 */
public record EntregaRegistrada(Long id, OffsetDateTime entregadoEn, Integer entregadoPor) {}
