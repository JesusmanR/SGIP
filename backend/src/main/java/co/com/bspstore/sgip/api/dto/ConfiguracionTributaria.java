package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * Lo que la empresa decidió sobre sus impuestos y sus retenciones
 * (ADR-0016, ADR-0024).
 *
 * Los interruptores arrancan en falso porque nadie eligió todavía, y desde
 * el ADR-0024 eso no impide vender: una empresa que no es responsable de IVA
 * vende al precio, sin impuesto. {@code configuracionCompleta} no es un
 * permiso para operar sino una marca para la pantalla: dice si alguien ya
 * pasó por esta configuración y la guardó.
 *
 * {@code tarifaIca} es la única tarifa que decide la empresa y no la
 * plataforma, porque la fija el municipio (ADR-0016). Admite nulo: sin ICA.
 */
public record ConfiguracionTributaria(
        boolean responsableIva,
        boolean aplicaExclusionUmbral,
        boolean practicaRetenciones,
        BigDecimal tarifaIca,
        boolean manejaEnvios,
        int cotizacionVigenciaDias,
        boolean configuracionCompleta
) {}
