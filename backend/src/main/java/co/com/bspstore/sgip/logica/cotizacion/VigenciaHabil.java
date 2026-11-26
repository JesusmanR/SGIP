package co.com.bspstore.sgip.logica.cotizacion;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * La fecha en que vence una cotización (RF-37): la vigencia se cuenta en días hábiles.
 *
 * Se cuenta desde el día siguiente a la emisión y no cuentan los sábados, los domingos ni las fechas
 * de {@code calendario_habil}. No usa la función SQL {@code fn_sumar_dias_habiles} porque esa depende del
 * reloj de la base y no avisa si falta un año de festivos; aquí el calendario llega ya leído.
 */
public final class VigenciaHabil {

    private VigenciaHabil() {
    }

    /**
     * @param emision      el día en que se emite; no cuenta, la cuenta arranca al día siguiente
     * @param diasHabiles  cuántos días hábiles dura la vigencia; al menos 1
     * @param festivos     las fechas que no son hábiles aunque caigan entre semana
     * @return el último día de la vigencia: el {@code diasHabiles}-ésimo día hábil después de la emisión
     * @throws IllegalArgumentException si {@code diasHabiles} es menor que 1
     */
    public static LocalDate vence(LocalDate emision, int diasHabiles, Set<LocalDate> festivos) {
        if (diasHabiles < 1) {
            throw new IllegalArgumentException("La vigencia es de al menos un día hábil: " + diasHabiles);
        }
        LocalDate dia = emision;
        int contados = 0;
        while (contados < diasHabiles) {
            dia = dia.plusDays(1);
            if (esHabil(dia, festivos)) {
                contados++;
            }
        }
        return dia;
    }

    private static boolean esHabil(LocalDate dia, Set<LocalDate> festivos) {
        DayOfWeek semana = dia.getDayOfWeek();
        return semana != DayOfWeek.SATURDAY && semana != DayOfWeek.SUNDAY && !festivos.contains(dia);
    }
}
