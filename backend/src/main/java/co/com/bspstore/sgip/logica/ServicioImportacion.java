package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.FilaDeCarga;
import co.com.bspstore.sgip.api.dto.ResumenCarga;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioImportacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * La importación de listas de precios (RF-24, RF-26).
 *
 * El trabajo de esta clase es decidir, fila por fila, si entra o no y
 * por qué. Leer el archivo es de {@link LectorListaPrecios} e
 * interpretar el costo es de {@link Costos}: aquí queda solo el juicio,
 * que es lo que el requisito enumera.
 *
 * ## Validar antes de tocar nada
 *
 * La carga se registra entera —aceptadas y rechazadas— sin cambiar un
 * solo costo. Aplicar es un paso aparte que el RF-27 describe, y que la
 * persona da después de mirar la vista previa que pide el RF-24.
 *
 * Esto no es ceremonia. El ADR-0007 lo anticipó: la correspondencia
 * entre columnas del proveedor y campos del sistema es configuración, y
 * los errores de configuración se manifiestan al ejecutar, no al
 * compilar. Una lista aplicada a ciegas con la columna equivocada
 * reescribe el costo de todo el catálogo con el número de otra columna,
 * y el kárdex no registra nada porque un cambio de costo no es un
 * movimiento: no habría de dónde reconstruirlo.
 */
@Service
public class ServicioImportacion {

    /** Tope del RF-24. */
    public static final int MAXIMO_FILAS = 20_000;
    public static final long MAXIMO_BYTES = 10L * 1024 * 1024;

    /** Cuántas filas acompañan al resumen para la vista previa (RF-24). */
    private static final int FILAS_DE_MUESTRA = 50;

    private final RepositorioImportacion importacion;
    private final List<LectorListaPrecios> lectores;

    public ServicioImportacion(RepositorioImportacion importacion,
                               List<LectorListaPrecios> lectores) {
        this.importacion = importacion;
        this.lectores = lectores;
    }

    /**
     * Lee, valida y registra la carga sin aplicar ningún costo.
     *
     * @param tamanoBytes el tamaño declarado del archivo, para el tope
     *                    del RF-24. Se comprueba antes de leer: un
     *                    archivo de cien megabytes no debe llegar a
     *                    abrirse para descubrir que no cabía.
     */
    @Transactional
    public ResumenCarga cargar(Integer proveedorId, String nombreArchivo,
                               long tamanoBytes, InputStream contenido) throws IOException {
        Integer empresa = ContextoEmpresa.empresaActual();

        if (!importacion.proveedorPropio(empresa, proveedorId)) {
            throw new ReferenciaAjenaException(
                    "El proveedor " + proveedorId + " no es de esta empresa.");
        }
        if (tamanoBytes > MAXIMO_BYTES) {
            throw new ArchivoInvalidoException(
                    "El archivo pesa " + (tamanoBytes / 1024 / 1024) + " MB y el tope son "
                            + (MAXIMO_BYTES / 1024 / 1024) + " MB. Divídelo en varias listas.");
        }

        LectorListaPrecios lector = lectores.stream()
                .filter(l -> l.entiende(nombreArchivo))
                .findFirst()
                .orElseThrow(() -> new ArchivoInvalidoException(
                        "No hay forma de leer «" + nombreArchivo + "». "
                                + "Se admiten .xlsx y .csv."));

        List<FilaLeida> leidas = lector.leer(contenido, nombreArchivo, MAXIMO_FILAS);
        List<FilaDeCarga> juzgadas = juzgar(empresa, leidas);

        int aceptados = (int) juzgadas.stream().filter(FilaDeCarga::aceptado).count();
        Long cargaId = importacion.insertarCarga(empresa, proveedorId, responsable(),
                nombreArchivo, juzgadas.size(), aceptados, juzgadas.size() - aceptados);
        importacion.insertarDetalles(empresa, cargaId, juzgadas);

        return buscar(cargaId).orElseThrow(() -> new IllegalStateException(
                "La carga " + cargaId + " no se encontró justo después de crearla."));
    }

    /**
     * Aplica la carga: los costos aceptados pasan al catálogo (RF-27).
     *
     * «La operación es atómica: si falla a mitad, ningún costo queda
     * modificado.» Lo sostiene la transacción, y dentro de ella cada
     * paso es una sola sentencia sobre toda la lista en vez de una por
     * fila. Con cinco mil registros, cinco mil idas y vueltas no caben
     * en el minuto que el requisito concede, y cada una sería además
     * una oportunidad de quedarse a medias.
     *
     * El costo anterior no se pierde: antes de escribir se cierra el
     * periodo que venía rigiendo y se abre el nuevo en
     * `historico_costos`, que es de lo que vive el RF-28. Hace falta
     * porque un cambio de costo no deja rastro en el kárdex —no es un
     * movimiento de existencias— y sin el histórico no habría forma de
     * reconstruir con qué costo se vendió algo el mes pasado.
     */
    @Transactional
    public Optional<ResumenCarga> aplicar(Long cargaId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        Optional<CabeceraCarga> cabecera = importacion.buscarCarga(empresa, cargaId);
        if (cabecera.isEmpty()) {
            return Optional.empty();
        }
        String estado = cabecera.get().estado();
        if (!"VALIDADA".equals(estado)) {
            // Aplicar dos veces duplicaría el movimiento de costo sin
            // que nada lo delate: el segundo paso encontraría el costo
            // ya cambiado y lo daría por bueno.
            throw new ReglaInvalidaException(
                    "La carga " + cargaId + " está en estado " + estado
                            + " y solo se puede aplicar una carga validada.");
        }

        importacion.cerrarCostoVigente(empresa, cargaId);
        importacion.retirarCostoDeHoy(empresa, cargaId);
        importacion.abrirCostoDeHoy(empresa, cargaId);
        int aplicados = importacion.aplicarCostos(empresa, cargaId);

        if (aplicados != cabecera.get().aceptados()) {
            // Entre validar y aplicar alguien desactivó o borró una
            // referencia. Deshacer es lo correcto: aplicar la lista a
            // medias deja un catálogo donde unos precios son de la lista
            // nueva y otros de la vieja, y nadie sabría cuáles.
            throw new ReglaInvalidaException(
                    "La carga declaraba " + cabecera.get().aceptados()
                            + " referencias y solo se pudieron actualizar " + aplicados
                            + ". El catálogo cambió desde que se validó; no se aplicó nada. "
                            + "Vuelve a cargar la lista.");
        }

        importacion.marcarAplicada(empresa, cargaId);
        return buscar(cargaId);
    }

    /**
     * Descarta una carga validada sin aplicarla.
     *
     * La P-07 ofrece «Rechazar carga» al lado de «Aplicar al catálogo»,
     * y hace falta que sea una acción y no simplemente cerrar la
     * pantalla: una carga que se queda en VALIDADA para siempre es una
     * que alguien puede aplicar tres semanas después, cuando sus costos
     * ya no son los del proveedor. Rechazarla deja dicho que se miró y
     * se decidió que no.
     *
     * No se borra. El detalle con sus motivos de rechazo es lo que
     * permite reclamarle al proveedor que su archivo venía mal.
     */
    @Transactional
    public Optional<ResumenCarga> rechazar(Long cargaId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        Optional<CabeceraCarga> cabecera = importacion.buscarCarga(empresa, cargaId);
        if (cabecera.isEmpty()) {
            return Optional.empty();
        }
        String estado = cabecera.get().estado();
        if ("APLICADA".equals(estado)) {
            throw new ReglaInvalidaException(
                    "La carga " + cargaId + " ya se aplicó y sus costos están en el "
                            + "catálogo. Rechazarla ahora no los devolvería: para eso "
                            + "hay que cargar la lista anterior.");
        }
        importacion.marcarRechazada(empresa, cargaId);
        return buscar(cargaId);
    }

    @Transactional(readOnly = true)
    public Optional<ResumenCarga> buscar(Long cargaId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        return importacion.buscarCarga(empresa, cargaId)
                .map(c -> new ResumenCarga(
                        c.cargaId(), c.proveedorId(), c.proveedor(), c.nombreArchivo(),
                        c.estado(), c.fecha(), c.fechaAplicacion(),
                        c.leidos(), c.aceptados(), c.rechazados(),
                        importacion.detallesDe(empresa, cargaId, FILAS_DE_MUESTRA),
                        // Solo tiene sentido avisar de lo que va a pasar
                        // mientras todavía pueda no pasar. Sobre una
                        // carga ya aplicada la consulta compararía el
                        // costo nuevo contra sí mismo y no encontraría
                        // nada, que sería cierto y engañoso a la vez.
                        "VALIDADA".equals(c.estado())
                                ? importacion.cruzarianAlAplicar(empresa, cargaId)
                                : List.of()));
    }

    /**
     * El juicio del RF-26, fila por fila.
     *
     * El orden de las comprobaciones importa, porque cada fila guarda un
     * solo motivo y hay filas que incumplen varias cosas a la vez. Va
     * primero lo que se arregla en el archivo y después lo que se
     * arregla en el catálogo:
     *
     *   1. referencia vacía — no se puede ni buscar;
     *   2. código repetido en el archivo — es un defecto del archivo, y
     *      señalarlo como «referencia inexistente» mandaría a quien lo
     *      corrige a mirar el catálogo, que está bien;
     *   3. referencia inexistente;
     *   4. costo no numérico;
     *   5. costo menor o igual a cero.
     *
     * Las cuatro últimas son, literalmente, las que el requisito
     * enumera.
     */
    private List<FilaDeCarga> juzgar(Integer empresa, List<FilaLeida> leidas) {
        Map<String, ProductoDelCatalogo> catalogo = importacion.catalogoPorReferencia(empresa);
        Map<String, Integer> vistas = new HashMap<>();
        List<FilaDeCarga> juzgadas = new ArrayList<>(leidas.size());

        for (FilaLeida fila : leidas) {
            String referencia = RepositorioImportacion.normalizar(fila.referencia());

            if (referencia.isEmpty()) {
                juzgadas.add(rechazo(fila, null, null, "REFERENCIA_VACIA",
                        "La fila no trae referencia: no hay producto que actualizar."));
                continue;
            }

            Integer repetidaEn = vistas.putIfAbsent(referencia, fila.numeroFila());
            if (repetidaEn != null) {
                juzgadas.add(rechazo(fila, null, null, "REFERENCIA_REPETIDA",
                        "La referencia " + fila.referencia() + " ya venía en la fila "
                                + repetidaEn + " de este archivo."));
                continue;
            }

            ProductoDelCatalogo producto = catalogo.get(referencia);
            if (producto == null) {
                juzgadas.add(rechazo(fila, null, null, "REFERENCIA_INEXISTENTE",
                        "La referencia " + fila.referencia()
                                + " no existe en el catálogo de esta empresa."));
                continue;
            }

            Optional<BigDecimal> costo = Costos.interpretar(fila.costoCrudo());
            if (costo.isEmpty()) {
                juzgadas.add(rechazo(fila, producto.productoId(), producto.costo(),
                        "COSTO_NO_NUMERICO",
                        "El costo «" + recortar(fila.costoCrudo()) + "» no es un número."));
                continue;
            }
            if (costo.get().signum() <= 0) {
                juzgadas.add(rechazo(fila, producto.productoId(), producto.costo(),
                        "COSTO_NO_POSITIVO",
                        "El costo es " + costo.get().toPlainString()
                                + " y tiene que ser mayor que cero."));
                continue;
            }

            juzgadas.add(new FilaDeCarga(fila.numeroFila(), producto.productoId(),
                    fila.referencia(), fila.descripcion(), costo.get(), producto.costo(),
                    true, null, null));
        }
        return juzgadas;
    }

    private static FilaDeCarga rechazo(FilaLeida fila, Integer productoId,
                                       BigDecimal costoAnterior, String codigo,
                                       String motivo) {
        // El costo NO se guarda en una fila rechazada: `ck_detalle_costo`
        // exige que sea mayor que cero cuando está, y justamente los
        // rechazos por costo son los que no lo cumplen.
        return new FilaDeCarga(fila.numeroFila(), productoId, fila.referencia(),
                fila.descripcion(), null, costoAnterior, false, motivo, codigo);
    }

    /** El motivo cabe en 200 caracteres en la base; el texto crudo no. */
    private static String recortar(String texto) {
        if (texto == null) {
            return "";
        }
        return texto.length() <= 40 ? texto : texto.substring(0, 40) + "…";
    }

    private Integer responsable() {
        return ContextoEmpresa.actual()
                .map(UsuarioAutenticado::usuarioId)
                .orElseThrow(() -> new IllegalStateException(
                        "Carga de lista de precios sin usuario en el contexto. "
                                + "Revise la cadena de seguridad."));
    }
}
