package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.logica.SituacionDePrueba;

import java.time.format.DateTimeFormatter;

/**
 * La respuesta de GET /api/v1/empresa/prueba (RF-73).
 *
 * {@code venceEn} se escribe aquí como texto ISO-8601 con su desplazamiento
 * (-05:00) y no se deja a Jackson: lo que importa es que el cliente lea la hora
 * de Bogotá tal cual, y cómo serializa Jackson un {@code OffsetDateTime} depende
 * de su configuración, que puede cambiar sin que nadie toque este endpoint.
 */
public record EstadoDePrueba(String estado, String venceEn, Long segundosRestantes) {

    public static EstadoDePrueba de(SituacionDePrueba s) {
        return new EstadoDePrueba(s.estado().name(),
                s.venceEn() == null ? null : s.venceEn().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                s.segundosRestantes());
    }
}
