package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.modelo.Usuario;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Dueño del plazo del bloqueo por intentos fallidos (ADR-0028).
 *
 * El ingreso, el cambio de contraseña y la lista de usuarios de la plataforma
 * tienen que decir lo mismo sobre si una cuenta está bloqueada. Por eso la
 * propiedad {@code sgip.seguridad.minutos-de-bloqueo} y su valor por omisión se
 * leen aquí y en ningún otro sitio: quien necesite el plazo recibe esta clase.
 *
 * El instante actual llega por parámetro y no se lee de un reloj propio: cada
 * servicio mide con el reloj de la aplicación que ya usa para el resto.
 */
@Component
public class PoliticaDeBloqueo {

    private final int minutos;

    public PoliticaDeBloqueo(@Value("${sgip.seguridad.minutos-de-bloqueo:15}") int minutos) {
        this.minutos = minutos;
    }

    public int minutos() {
        return minutos;
    }

    /** Marcada como bloqueada y con el plazo cumplido (o sin instante que contar). */
    public boolean vencido(Usuario usuario, LocalDateTime ahora) {
        return vencido(usuario.isBloqueado(), usuario.getBloqueadoEn(), ahora);
    }

    /** Lo que el ingreso diría hoy: marcada y con el plazo sin vencer. */
    public boolean bloqueadoHoy(Usuario usuario, LocalDateTime ahora) {
        return bloqueadoHoy(usuario.isBloqueado(), usuario.getBloqueadoEn(), ahora);
    }

    /**
     * La misma regla para quien no es un {@link Usuario}: el PIN del administrador
     * guarda su «marcado» y su «desde» en columnas propias (ADR-0032). Sin instante
     * (un bloqueo anterior a la V37) no hay desde cuándo contar y se toma por vencido.
     */
    public boolean vencido(boolean marcado, LocalDateTime desde, LocalDateTime ahora) {
        return marcado && (desde == null || !ahora.isBefore(desde.plusMinutes(minutos)));
    }

    public boolean bloqueadoHoy(boolean marcado, LocalDateTime desde, LocalDateTime ahora) {
        return marcado && !vencido(marcado, desde, ahora);
    }
}
