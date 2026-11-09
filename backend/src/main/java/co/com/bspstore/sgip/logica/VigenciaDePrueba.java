package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.util.Hoy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * La prueba gratuita en un solo sitio (RF-04, RF-73, ADR-0031).
 *
 * El alta, {@code FiltroEmpresaActiva} y el contador del encabezado preguntan
 * aquí, y por eso no puede pasar que el contador diga «queda un minuto» mientras
 * el filtro ya rechaza, o al revés.
 *
 * No es un bean: no guarda estado ni lee nada. Quien la usa le pasa el instante
 * de su {@code Clock}, y así el filtro no necesita en su contexto nada que los
 * {@code @WebMvcTest} no traigan ya.
 *
 * Todas las fechas son de Bogotá ({@link Hoy#ZONA}). {@code fin_prueba} es un
 * DATE y significa el último día de prueba, completo: la empresa escribe hasta
 * las 23:59:59 de ese día, y desde las 00:00:00 del siguiente queda en modo
 * consulta, sin que nadie cambie la base.
 */
public final class VigenciaDePrueba {

    /** El último segundo del último día. */
    private static final LocalTime ULTIMO_SEGUNDO = LocalTime.of(23, 59, 59);

    private VigenciaDePrueba() {
        // Clase de utilidad: no se instancia
    }

    /**
     * El último día de prueba: {@code dias} días contados desde el día
     * siguiente al alta. Con 30, el alta del 31 de enero vence el 2 de marzo.
     */
    public static LocalDate finDePrueba(LocalDate diaDeAlta, int dias) {
        return diaDeAlta.plusDays(dias);
    }

    /** Las 23:59:59 del último día, en la hora de Bogotá (-05:00). */
    public static OffsetDateTime venceEn(LocalDate finPrueba) {
        return finPrueba.atTime(ULTIMO_SEGUNDO).atZone(Hoy.ZONA).toOffsetDateTime();
    }

    /**
     * Si la empresa puede escribir en {@code ahora}.
     *
     * La decide el día de Bogotá y no los segundos restantes: a las
     * 23:59:59,5 todavía es el último día y escribe, aunque el contador, que
     * cuenta segundos enteros, ya diga cero. Una PRUEBA sin {@code fin_prueba}
     * no escribe: el alta siempre la escribe, de modo que su falta es un dato
     * roto y aquí se falla cerrado, como con una empresa que no existe.
     */
    public static boolean permiteEscritura(EstadoEmpresa estado, LocalDate finPrueba, Instant ahora) {
        if (estado != EstadoEmpresa.PRUEBA) {
            return estado.permiteEscritura();
        }
        return finPrueba != null && !vencida(finPrueba, ahora);
    }

    /**
     * Segundos enteros hasta el final del último día, nunca negativos.
     *
     * Se cuenta hasta las 23:59:59 más un segundo, de modo que a las 23:59:59
     * todavía queda 1 s, y se redondea hacia abajo.
     */
    public static long segundosRestantes(LocalDate finPrueba, Instant ahora) {
        Instant fin = venceEn(finPrueba).toInstant().plusSeconds(1);
        return Math.max(0, Duration.between(ahora, fin).toMillis() / 1000);
    }

    /** Lo que responde el contador para una empresa en {@code ahora}. */
    public static SituacionDePrueba situacion(EstadoEmpresa estado, LocalDate finPrueba, Instant ahora) {
        return switch (estado) {
            case ACTIVA -> new SituacionDePrueba(SituacionDePrueba.Estado.ACTIVA, null, null);
            case DESACTIVADA -> new SituacionDePrueba(SituacionDePrueba.Estado.DESACTIVADA, null, null);
            case PRUEBA -> {
                if (finPrueba == null) {
                    yield new SituacionDePrueba(SituacionDePrueba.Estado.PRUEBA_VENCIDA, null, 0L);
                }
                yield vencida(finPrueba, ahora)
                        ? new SituacionDePrueba(SituacionDePrueba.Estado.PRUEBA_VENCIDA,
                                venceEn(finPrueba), 0L)
                        : new SituacionDePrueba(SituacionDePrueba.Estado.PRUEBA,
                                venceEn(finPrueba), segundosRestantes(finPrueba, ahora));
            }
        };
    }

    private static boolean vencida(LocalDate finPrueba, Instant ahora) {
        return LocalDate.ofInstant(ahora, Hoy.ZONA).isAfter(finPrueba);
    }
}
