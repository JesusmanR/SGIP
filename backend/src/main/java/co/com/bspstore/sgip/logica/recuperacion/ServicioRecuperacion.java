package co.com.bspstore.sgip.logica.recuperacion;

import co.com.bspstore.sgip.api.dto.MensajeRecuperacion;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * El lado de la petición al pedir el código de recuperación (RF-65, §5.3 de la spec).
 *
 * Normaliza lo que llega, entrega el trabajo a {@link TareaDeRecuperacion} (que corre en otro
 * hilo) y responde siempre lo mismo. No tiene acceso a la base: que la respuesta no dependa de
 * si el correo existe, ni en su texto ni en lo que tarda, es la razón de ser de la clase.
 */
@Service
public class ServicioRecuperacion {

    static final String MENSAJE =
            "Si el correo está registrado, te enviamos un código. Revisa también la carpeta de spam.";

    /** Lo que se registra cuando no llega correo: la bitácora no admite nulos en esa columna. */
    static final String CORREO_VACIO = "(vacío)";

    private static final String ORIGEN_DESCONOCIDO = "(desconocido)";
    private static final int CORREO_MAXIMO = 120;
    private static final int ORIGEN_MAXIMO = 60;

    private final TareaDeRecuperacion tarea;

    public ServicioRecuperacion(TareaDeRecuperacion tarea) {
        this.tarea = tarea;
    }

    public MensajeRecuperacion solicitar(String correo, String origen) {
        // El origen se recorta aquí, una sola vez: es el valor que la bitácora guarda y el mismo con
        // el que se cuenta el tope por IP. Si se recortara solo al guardar, el conteo no lo vería.
        tarea.procesar(normalizar(correo), recortarOrigen(origen));
        return new MensajeRecuperacion(MENSAJE);
    }

    /**
     * Quita espacios, pasa a minúsculas y recorta a lo que cabe en la bitácora. Se recorta al final
     * porque al bajar ciertas mayúsculas el texto puede alargarse. Un correo ausente se registra
     * como «(vacío)». Es pública porque la confirmación del código busca al usuario con el mismo
     * criterio.
     */
    public static String normalizar(String correo) {
        String normalizado = correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);
        if (normalizado.isEmpty()) {
            return CORREO_VACIO;
        }
        return normalizado.length() <= CORREO_MAXIMO ? normalizado : normalizado.substring(0, CORREO_MAXIMO);
    }

    private static String recortarOrigen(String origen) {
        if (origen == null || origen.isBlank()) {
            return ORIGEN_DESCONOCIDO;
        }
        return origen.length() <= ORIGEN_MAXIMO ? origen : origen.substring(0, ORIGEN_MAXIMO);
    }
}
