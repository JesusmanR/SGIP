package co.com.bspstore.sgip.modelo;

/**
 * Los tres estados en que la plataforma puede tener a una empresa.
 *
 * Coincide con el ENUM de la columna {@code empresas.estado} que creó la
 * V10, y existe para que la capa de Java no mueva estados con cadenas
 * sueltas: un «ACTIVO» por «ACTIVA» no lo detecta el compilador y sí lo
 * detecta el motor, pero ya en producción.
 *
 * Una nota de vocabulario que conviene no perder. El informe llama
 * «modo de consulta» al tercer estado y la RN-15 lo enumera como
 * CONSULTA; el esquema lo llama DESACTIVADA. Es la misma cosa: la
 * empresa conserva el acceso de lectura sobre la totalidad de su
 * información y pierde la escritura. Se conserva el nombre del esquema
 * porque es el que está escrito en la base, y la diferencia queda dicha
 * aquí para que nadie la descubra buscando un estado que no existe.
 */
public enum EstadoEmpresa {

    /** Recién dada de alta, con su mes de prueba corriendo (RN-14). */
    PRUEBA,

    /** Operación completa. */
    ACTIVA,

    /** El modo de consulta de la RN-15: lee todo, no escribe nada. */
    DESACTIVADA;

    /**
     * Si los usuarios de esta empresa pueden escribir.
     *
     * Es la única pregunta que el resto del sistema le hace a este tipo,
     * y vive aquí para que la respuesta no quede repartida en cada sitio
     * que la necesite. PRUEBA escribe: el mes gratuito es para usar el
     * producto, no para mirarlo.
     */
    public boolean permiteEscritura() {
        return this != DESACTIVADA;
    }
}
