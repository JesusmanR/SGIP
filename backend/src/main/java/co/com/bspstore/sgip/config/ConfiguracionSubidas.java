package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.logica.AlmacenArchivos;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Límites de las subidas, derivados del único tope que existe.
 *
 * Antes vivían en el application.yml, que no se versiona: cambiarlos no
 * llegaba a las demás máquinas con un «git pull», y había que mantener
 * de acuerdo a mano tres números —el tope de la aplicación y los dos de
 * multipart—. Aquí se calculan a partir de
 * {@link AlmacenArchivos#BYTES_MAXIMOS} y no pueden discrepar.
 *
 * Definir este bean hace además que Spring Boot se aparte: su
 * configuración automática de multipart solo actúa si nadie más la hizo,
 * de modo que un application.yml antiguo con «max-file-size: 2MB» deja
 * de tener efecto sin que haya que ir a corregirlo equipo por equipo.
 *
 * <h2>Por qué el límite del contenedor es MÁS ALTO que el nuestro</h2>
 *
 * Parece al revés, y es deliberado. Quien rechaza una imagen por su peso
 * debe ser {@code AlmacenArchivos}, porque es el único que puede
 * explicarlo: responde un 400 con «la imagen pesa más de 10 MB» y la
 * conexión sigue viva.
 *
 * Si el que rechaza es el contenedor, el usuario ve algo peor. Tomcat,
 * al descartar un cuerpo por tamaño, todavía tiene que leer lo que queda
 * de él para contestar por esa misma conexión; lo que alcanza a leer lo
 * gobierna «maxSwallowSize», 2 MB por omisión. Pasado eso cierra la
 * conexión, y el navegador no recibe un 413 con su explicación: ve que
 * la conexión murió, que es justo lo que fetch reporta como error de
 * red. Ese era el segundo síntoma al cambiar el logo.
 *
 * Así que el contenedor se queda con un margen holgado y actúa solo como
 * freno contra el abuso —no se puede dejar sin límite: un cuerpo sin
 * tope se escribe en disco antes de que nuestro código llegue a opinar—,
 * y el rechazo que un usuario puede encontrarse de verdad es siempre el
 * nuestro, con su mensaje.
 */
@Configuration
public class ConfiguracionSubidas {

    /** Holgura sobre nuestro tope: el freno del contenedor no debe ser el que dispare. */
    private static final long HOLGURA = AlmacenArchivos.BYTES_MAXIMOS;

    /**
     * Los límites de multipart miden la petición entera —cabeceras de
     * cada parte, separadores, nombre del campo—, no solo el archivo, y
     * una subida puede traer varias partes.
     */
    private static final long SOBRE = 512L * 1024;

    @Bean
    MultipartConfigElement configuracionMultipart() {
        long porArchivo = AlmacenArchivos.BYTES_MAXIMOS + HOLGURA;
        // El umbral en 0 escribe siempre a disco en vez de acumular la
        // imagen en memoria: diez usuarios subiendo a la vez no deben
        // poder llenar el montón del servidor.
        return new MultipartConfigElement("", porArchivo, porArchivo + SOBRE, 0);
    }
}
