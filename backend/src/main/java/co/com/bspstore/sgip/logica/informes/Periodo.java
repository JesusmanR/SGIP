package co.com.bspstore.sgip.logica.informes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * El período de un informe: dos fechas, las dos incluidas.
 *
 * Se consulta como un intervalo semiabierto, {@code [desde 00:00, hasta + 1 día 00:00)}: así
 * {@code desde = hasta = hoy} trae lo de todo el día hasta las 23:59:59 y no se pierde nada del último día
 * por comparar contra su medianoche. Ver {@link #inicio()} y {@link #fin()}.
 *
 * @param desde primer día, incluido
 * @param hasta último día, incluido
 */
public record Periodo(LocalDate desde, LocalDate hasta) {

    /** Un año, contando los dos extremos: del 1 de enero de un año al 1 de enero del siguiente son 366 días. */
    static final long MAXIMO_DE_DIAS = 366;

    static final String FECHA_NO_VALIDA = "La fecha no es válida.";
    static final String INVERTIDO = "La fecha inicial no puede ser posterior a la final.";
    static final String MUY_LARGO = "Elige un período de máximo un año.";

    /**
     * Arma el período a partir de lo que llegó por la dirección.
     *
     * Un valor ausente o en blanco se reemplaza: {@code hasta} por {@code hoy} y {@code desde} por
     * {@code desdePorOmision}. Las fechas son {@code yyyy-MM-dd}, exactas (sin espacios, sin «2026-2-5»,
     * sin «2026-02-30»). Una fecha futura se acepta.
     *
     * @throws PeriodoInvalidoException si una fecha no se entiende, si {@code desde} es posterior a
     *                                  {@code hasta} o si el período pasa de 366 días
     */
    public static Periodo de(String desde, String hasta, LocalDate desdePorOmision, LocalDate hoy) {
        LocalDate fin = hasta == null || hasta.isBlank() ? hoy : fecha(hasta);
        LocalDate inicio = desde == null || desde.isBlank() ? desdePorOmision : fecha(desde);
        if (inicio.isAfter(fin)) {
            throw new PeriodoInvalidoException(INVERTIDO);
        }
        if (ChronoUnit.DAYS.between(inicio, fin) + 1 > MAXIMO_DE_DIAS) {
            throw new PeriodoInvalidoException(MUY_LARGO);
        }
        return new Periodo(inicio, fin);
    }

    private static LocalDate fecha(String texto) {
        try {
            LocalDate fecha = LocalDate.parse(texto);
            // Años de cuatro cifras: una fecha de otro orden de magnitud es un error de tecleo, y sumarle
            // un día al máximo del tipo desbordaría.
            if (fecha.getYear() < 1 || fecha.getYear() > 9999) {
                throw new PeriodoInvalidoException(FECHA_NO_VALIDA);
            }
            return fecha;
        } catch (DateTimeParseException e) {
            throw new PeriodoInvalidoException(FECHA_NO_VALIDA);
        }
    }

    /** El primer instante del período: la medianoche con que empieza {@code desde}. Incluido. */
    public LocalDateTime inicio() {
        return desde.atStartOfDay();
    }

    /** El primer instante después del período: la medianoche siguiente a {@code hasta}. Excluido. */
    public LocalDateTime fin() {
        return hasta.plusDays(1).atStartOfDay();
    }
}
