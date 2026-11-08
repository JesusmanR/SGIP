package co.com.bspstore.sgip.util;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * La única fuente de «hoy» para toda decisión de vigencia (M5).
 *
 * «Hoy» es el día de Bogotá según el reloj de la aplicación. No es el de la zona
 * de la JVM, que en la CI y en un servidor en la nube es UTC y cambia de día a
 * las 7 de la tarde de Colombia, ni es el {@code CURDATE()} del motor, que
 * depende de cómo esté configurada su zona. Quien decide con una fecha la calcula
 * aquí y se la pasa a la consulta como parámetro; ninguna sentencia de Java
 * llama a {@code CURDATE()} para decidir una vigencia.
 *
 * Se parte de {@link Clock#instant()} y no de {@code LocalDate.now(reloj)} para
 * que un reloj con otra zona (el de la JVM en UTC, o uno fijo en una prueba) dé
 * el mismo día de Bogotá.
 *
 * Lo que esto no alcanza son las vistas del esquema, que llaman a
 * {@code CURDATE()} y no admiten parámetros. Para que coincidan con este día la
 * sesión de la base tiene que ir en la hora de Bogotá (ADR-0029).
 */
public final class Hoy {

    public static final ZoneId ZONA = ZoneId.of("America/Bogota");

    private Hoy() {
        // Clase de utilidad: no se instancia
    }

    public static LocalDate de(Clock reloj) {
        return LocalDate.ofInstant(reloj.instant(), ZONA);
    }
}
