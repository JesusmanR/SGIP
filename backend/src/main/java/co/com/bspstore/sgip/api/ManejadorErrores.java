package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.config.SinEmpresaException;
import co.com.bspstore.sgip.logica.CategoriaEnUsoException;
import co.com.bspstore.sgip.logica.ClaveActualIncorrectaException;
import co.com.bspstore.sgip.logica.ClaveNuevaInvalidaException;
import co.com.bspstore.sgip.logica.DatoTributarioInvalidoException;
import co.com.bspstore.sgip.logica.EstadoDeCargaInvalidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.CredencialesInvalidasException;
import co.com.bspstore.sgip.logica.FiltroInvalidoException;
import co.com.bspstore.sgip.logica.ImpuestoNoDisponibleException;
import co.com.bspstore.sgip.logica.AlmacenArchivos;
import co.com.bspstore.sgip.logica.ArchivoInvalidoException;
import co.com.bspstore.sgip.logica.MovimientoInvalidoException;
import co.com.bspstore.sgip.logica.NombreRepetidoException;
import co.com.bspstore.sgip.logica.RegistroRepetidoException;
import co.com.bspstore.sgip.logica.ReglaInvalidaException;
import co.com.bspstore.sgip.logica.RecepcionInvalidaException;
import co.com.bspstore.sgip.logica.ReferenciaAjenaException;
import co.com.bspstore.sgip.logica.RolNoAsignableException;
import co.com.bspstore.sgip.logica.UltimoAdministradorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.util.stream.Collectors;

/**
 * Con {@code spring.mvc.problemdetails.enabled=true} Spring registra su propio
 * manejador de las excepciones estándar, con orden 0 y con texto en inglés. Este
 * va por delante a propósito: los textos de la API son estos, y no dependen de
 * una propiedad que vive en un archivo que no se versiona.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorErrores.class);

    // Códigos de error de MySQL (no los SQLState, que son los mismos
    // 23000 para casi todo y no distinguen un duplicado de una referencia).
    private static final int MYSQL_ENTRADA_DUPLICADA = 1062;
    private static final int MYSQL_CLAVE_FORANEA_AL_ESCRIBIR = 1452;

    @ExceptionHandler(CredencialesInvalidasException.class)
    public ProblemDetail credencialesInvalidas(CredencialesInvalidasException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        p.setTitle("Credenciales inválidas");
        int minutos = e.getMinutosDeBloqueo();
        p.setDetail("El correo o la contraseña no son correctos. Si te equivocas varias veces, "
                + "la cuenta se bloquea durante " + minutos
                + (minutos == 1 ? " minuto." : " minutos."));
        return p;
    }

    @ExceptionHandler(ClaveActualIncorrectaException.class)
    public ProblemDetail claveActualIncorrecta(ClaveActualIncorrectaException e) {
        // 400 y no 401: el cliente trata todo 401 como «la sesión expiró»
        // y echaría al usuario por un error de tecleo. La sesión es
        // válida; lo que falla es un dato del cuerpo.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Contraseña actual incorrecta");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(ClaveNuevaInvalidaException.class)
    public ProblemDetail claveNuevaInvalida(ClaveNuevaInvalidaException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Contraseña nueva no válida");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(SinEmpresaException.class)
    public ProblemDetail sinEmpresa(SinEmpresaException e) {
        // 403 y no 401: sabemos quién es y aun así no puede.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        p.setTitle("Operación propia de una empresa");
        p.setDetail(e.getMessage());
        return p;
    }

    /**
     * Lo que deniega un controlador o un {@code @PreAuthorize}. Sin este
     * manejador la excepción llega a Spring Security, que contesta un 403
     * con el cuerpo vacío del contenedor, y el cliente solo lee el
     * {@code detail}. {@code AuthorizationDeniedException}, la de la
     * seguridad de métodos, hereda de esta y queda cubierta.
     *
     * El texto es fijo y no el de la excepción: el de Spring es el inglés
     * «Access Denied», y un rol sin permiso no necesita más explicación.
     * Las otras dos puertas que responden 403, la del
     * cambio de clave y la del modo de consulta, son filtros y escriben su
     * propio cuerpo; este manejador no las alcanza.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail accesoDenegado(AccessDeniedException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        p.setTitle("Sin permiso para esta operación");
        p.setDetail("Tu rol no tiene permiso para hacer esto.");
        return p;
    }

    @ExceptionHandler(NombreRepetidoException.class)
    public ProblemDetail nombreRepetido(NombreRepetidoException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Nombre repetido");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(DatoTributarioInvalidoException.class)
    public ProblemDetail datoTributarioInvalido(DatoTributarioInvalidoException e) {
        // 400 y no 422: el cuerpo está bien formado, pero la regla que
        // incumple depende del tipo que se está cambiando y el cliente
        // puede corregirla con lo que el detalle le dice.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Dato tributario inválido");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(RolNoAsignableException.class)
    public ProblemDetail rolNoAsignable(RolNoAsignableException e) {
        // 403 y no 400: el cuerpo está bien y el rol existe. Lo que no
        // existe es el permiso para repartirlo.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        p.setTitle("Rol fuera de tu alcance");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(UltimoAdministradorException.class)
    public ProblemDetail ultimoAdministrador(UltimoAdministradorException e) {
        // 409: la petición es válida y el estado actual es lo que impide
        // atenderla. Con otro administrador activo, la misma petición
        // funciona.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("La empresa quedaría sin administrador");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(MovimientoInvalidoException.class)
    public ProblemDetail movimientoInvalido(MovimientoInvalidoException e) {
        // 400: el cuerpo está bien formado y la regla que incumple
        // depende del saldo que el producto tiene ahora, que el cliente
        // no podía conocer al escribir la petición.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Movimiento de inventario inválido");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(RecepcionInvalidaException.class)
    public ProblemDetail recepcionInvalida(RecepcionInvalidaException e) {
        // 400, que es lo que el RF-23 pide para «recibir más de lo
        // ordenado». El cuerpo está bien formado; lo que no encaja es
        // contra el estado de la orden, y el detalle dice las tres cifras
        // para que quien recibe sepa cuánto cabe.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Recepción inválida");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(ReglaInvalidaException.class)
    public ProblemDetail reglaInvalida(ReglaInvalidaException e) {
        // 400: el cuerpo está bien formado y la regla que incumple
        // depende de la base de cálculo o de la vigencia que ya tenía,
        // cosas que una anotación no puede mirar.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Regla de ganancia inválida");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(RegistroRepetidoException.class)
    public ProblemDetail registroRepetido(RegistroRepetidoException e) {
        // 409 igual que el nombre repetido, pero con otro título: «dato ya
        // registrado» sirve a la unicidad de toda la plataforma (el NIT, el
        // correo) y también a dos reglas del mismo alcance a la vez dentro de
        // una empresa; el detalle dice cuál es el dato que choca.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Dato ya registrado");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(ReferenciaAjenaException.class)
    public ProblemDetail referenciaAjena(ReferenciaAjenaException e) {
        // 422 y no 404: la petición está bien formada y el recurso puede
        // existir; lo que no es válido es usarlo desde esta empresa.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        p.setTitle("Referencia de otra empresa");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(ImpuestoNoDisponibleException.class)
    public ProblemDetail impuestoNoDisponible(ImpuestoNoDisponibleException e) {
        // 422 como la referencia ajena, y con su propio título: el catálogo
        // de impuestos no es de ninguna empresa, de modo que «otra empresa»
        // sería mentir sobre dónde está el problema.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        p.setTitle("Impuesto no disponible");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ProblemDetail recursoNoEncontrado(RecursoNoEncontradoException e) {
        // 404: el identificador está en la ruta y no corresponde a nada
        // de esta empresa. Si fuera una referencia del cuerpo sería 422.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        p.setTitle("Recurso no encontrado");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(EstadoDeCargaInvalidoException.class)
    public ProblemDetail estadoDeCargaInvalido(EstadoDeCargaInvalidoException e) {
        // 409: la petición es válida y es el estado actual de la carga lo
        // que impide atenderla.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("La carga no admite esa operación");
        p.setDetail(e.getMessage());
        return p;
    }

    /**
     * La red de seguridad: lo que ninguna comprobación previa atrapó y el
     * motor rechazó. Se traduce por el código del motor y el cliente
     * recibe siempre un texto fijo: el mensaje del motor trae nombres de
     * restricciones, tablas y a veces el valor que chocó, y nada de eso
     * es asunto suyo. El error completo queda en el log.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integridad(DataIntegrityViolationException e) {
        LOG.error("El motor rechazó la operación por integridad de datos", e);

        // Hibernate envuelve el SQLException una o dos veces, así que
        // getCause() a secas no llega; se baja por la cadena entera.
        Integer codigo = null;
        for (Throwable t = e; t != null && codigo == null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql) {
                codigo = sql.getErrorCode();
            }
        }

        ProblemDetail p;
        if (codigo != null && codigo == MYSQL_ENTRADA_DUPLICADA) {
            p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            p.setTitle("Dato ya registrado");
            p.setDetail("Ya existe un registro con esos datos.");
        } else if (codigo != null && codigo == MYSQL_CLAVE_FORANEA_AL_ESCRIBIR) {
            p = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
            p.setTitle("Referencia no válida");
            p.setDetail("Uno de los datos indicados no existe o no corresponde a esta empresa.");
        } else {
            p = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
            p.setTitle("Error interno");
            p.setDetail("No se pudo completar la operación. Inténtalo de nuevo; "
                    + "si persiste, avisa al administrador.");
        }
        return p;
    }

    /**
     * Alguien guardó el producto después de que esta persona abriera el
     * formulario (otra edición, o se aplicó una carga de precios): la versión
     * que trae la edición ya no es la del producto. También salta si dos
     * escrituras se pisan dentro de una misma transacción. 409: la petición es
     * válida y es el estado actual lo que impide atenderla. El mensaje de la
     * excepción nombra la clase y el identificador, así que no se
     * reenvía; el cliente recibe un texto fijo.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail edicionConcurrente(ObjectOptimisticLockingFailureException e) {
        LOG.warn("Edición rechazada por bloqueo optimista: {}", e.getMessage());
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("El producto cambió mientras lo editabas");
        p.setDetail("Otra persona o un proceso modificó este producto antes de que guardaras. "
                + "Vuelve a abrirlo para ver los datos actuales y repite tu cambio.");
        return p;
    }

    @ExceptionHandler(CategoriaEnUsoException.class)
    public ProblemDetail categoriaEnUso(CategoriaEnUsoException e) {
        // 409 y no 422: la referencia es propia y válida; lo que impide
        // atender la petición es el estado actual del catálogo.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Categoría en uso");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(FiltroInvalidoException.class)
    public ProblemDetail filtroInvalido(FiltroInvalidoException e) {
        // Llega por la dirección y no por el cuerpo, de modo que la
        // validación declarativa no lo alcanza.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Filtro no válido");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(ArchivoInvalidoException.class)
    public ProblemDetail archivoInvalido(ArchivoInvalidoException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Archivo no admitido");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail archivoDemasiadoGrande(MaxUploadSizeExceededException e) {
        // Este tope lo aplica el contenedor antes de que el controlador
        // vea nada, de modo que no puede comprobarlo AlmacenArchivos.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        p.setTitle("Archivo demasiado grande");
        p.setDetail("La imagen supera el tamaño permitido. Usa una de hasta "
                + (AlmacenArchivos.BYTES_MAXIMOS / 1024 / 1024) + " MB.");
        return p;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail datosInvalidos(MethodArgumentNotValidException e) {
        String mensajes = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Datos inválidos");
        p.setDetail(mensajes);
        return p;
    }

    /**
     * JSON mal formado, o con un valor de un tipo que el campo no admite. El
     * mensaje de Jackson cita el fragmento que no pudo leer y nombra clases
     * internas, así que no se reenvía: el cliente recibe un texto fijo.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail cuerpoNoLegible(HttpMessageNotReadableException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Cuerpo no válido");
        p.setDetail("El cuerpo de la petición no es un JSON válido o tiene datos de un tipo equivocado.");
        return p;
    }

    /**
     * Un identificador de la ruta o un parámetro con un valor del tipo
     * equivocado («abc» donde va un número). Se nombra el parámetro, que es
     * del código, y nunca lo escrito, que es del cliente.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail valorNoValido(MethodArgumentTypeMismatchException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Valor no válido");
        p.setDetail("El valor de «" + e.getName() + "» no es válido.");
        return p;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail parametroAusente(MissingServletRequestParameterException e) {
        return datoAusente(e.getParameterName());
    }

    /** Lo mismo cuando lo que falta es una parte de un envío con archivo. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ProblemDetail parteAusente(MissingServletRequestPartException e) {
        return datoAusente(e.getRequestPartName());
    }

    private static ProblemDetail datoAusente(String nombre) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Falta un dato");
        p.setDetail("Falta el dato obligatorio «" + nombre + "».");
        return p;
    }
}
