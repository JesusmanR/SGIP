package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.config.SinEmpresaException;
import co.com.bspstore.sgip.logica.AutorizacionRequeridaException;
import co.com.bspstore.sgip.logica.PinBloqueadoException;
import co.com.bspstore.sgip.logica.PinInvalidoException;
import co.com.bspstore.sgip.logica.PinNuevoInvalidoException;
import co.com.bspstore.sgip.logica.CategoriaEnUsoException;
import co.com.bspstore.sgip.logica.ClaveActualIncorrectaException;
import co.com.bspstore.sgip.logica.ClaveNuevaInvalidaException;
import co.com.bspstore.sgip.logica.DatoTributarioInvalidoException;
import co.com.bspstore.sgip.logica.EstadoDeCargaInvalidoException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.logica.CorreoRepetidoException;
import co.com.bspstore.sgip.logica.NitRepetidoException;
import co.com.bspstore.sgip.logica.CredencialesInvalidasException;
import co.com.bspstore.sgip.logica.CuentaBloqueadaException;
import co.com.bspstore.sgip.logica.FiltroInvalidoException;
import co.com.bspstore.sgip.logica.ImpuestoNoDisponibleException;
import co.com.bspstore.sgip.logica.AlmacenArchivos;
import co.com.bspstore.sgip.logica.ArchivoInvalidoException;
import co.com.bspstore.sgip.logica.ClienteNoEditableException;
import co.com.bspstore.sgip.logica.DatoDeClienteInvalidoException;
import co.com.bspstore.sgip.logica.DatoDeTurnoInvalidoException;
import co.com.bspstore.sgip.logica.SinTurnoAbiertoException;
import co.com.bspstore.sgip.logica.TurnoYaAbiertoException;
import co.com.bspstore.sgip.logica.DatosDeDomicilioIncompletosException;
import co.com.bspstore.sgip.logica.MedioDePagoInvalidoException;
import co.com.bspstore.sgip.logica.MovimientoInvalidoException;
import co.com.bspstore.sgip.logica.NombreRepetidoException;
import co.com.bspstore.sgip.logica.RegistroRepetidoException;
import co.com.bspstore.sgip.logica.ReglaInvalidaException;
import co.com.bspstore.sgip.logica.RecepcionInvalidaException;
import co.com.bspstore.sgip.logica.ReferenciaAjenaException;
import co.com.bspstore.sgip.logica.RolNoAsignableException;
import co.com.bspstore.sgip.logica.UltimoAdministradorException;
import co.com.bspstore.sgip.logica.UltimoMedioActivoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
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

import java.net.URI;
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

    /**
     * El {@code type} del 409 «el producto cambió desde que lo abriste». El 409
     * tiene otras causas (nombre repetido, carga en otro estado) y solo esta
     * justifica que el formulario ofrezca ver los datos actuales: el cliente
     * lo reconoce por este valor, no por el estado.
     */
    public static final URI TIPO_PRODUCTO_CAMBIO = URI.create("urn:sgip:producto-cambio");

    /**
     * Los otros errores que un formulario necesita reconocer sin leer el texto,
     * con la misma forma: un {@code type} propio en el ProblemDetail. El cliente
     * decide por el tipo; lo que no reconoce va al aviso general.
     */
    public static final URI TIPO_NIT_REPETIDO = URI.create("urn:sgip:nit-repetido");
    public static final URI TIPO_CORREO_REPETIDO = URI.create("urn:sgip:correo-repetido");
    public static final URI TIPO_CLAVE_ACTUAL_INCORRECTA = URI.create("urn:sgip:clave-actual-incorrecta");

    // Códigos de error de MySQL (no los SQLState, que son los mismos
    // 23000 para casi todo y no distinguen un duplicado de una referencia).
    private static final int MYSQL_ENTRADA_DUPLICADA = 1062;
    private static final int MYSQL_CLAVE_FORANEA_AL_ESCRIBIR = 1452;
    private static final int MYSQL_FILA_REFERENCIADA = 1451;
    private static final int MYSQL_COLUMNA_NULA = 1048;
    private static final int MYSQL_DATO_DEMASIADO_LARGO = 1406;

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

    @ExceptionHandler(CuentaBloqueadaException.class)
    public ProblemDetail cuentaBloqueada(CuentaBloqueadaException e) {
        // 423 y no 401: el cliente trata todo 401 como «la sesión expiró» y
        // echaría a quien sigue dentro. Aquí quien pregunta ya está
        // autenticado, de modo que decirle la causa no revela nada.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.LOCKED);
        p.setTitle("Cuenta bloqueada");
        int minutos = e.getMinutosDeBloqueo();
        p.setDetail("Tu cuenta está bloqueada por intentos fallidos. "
                + "No puedes cambiar la contraseña hasta que pasen "
                + minutos + (minutos == 1 ? " minuto" : " minutos")
                + " desde el bloqueo.");
        return p;
    }

    @ExceptionHandler(ClaveActualIncorrectaException.class)
    public ProblemDetail claveActualIncorrecta(ClaveActualIncorrectaException e) {
        // 400 y no 401: el cliente trata todo 401 como «la sesión expiró»
        // y echaría al usuario por un error de tecleo. La sesión es
        // válida; lo que falla es un dato del cuerpo.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        // El tipo propio distingue este 400 de los de validación (que llegan con
        // el prefijo «campo: »): el formulario lo marca en su campo sin leer el texto.
        p.setType(TIPO_CLAVE_ACTUAL_INCORRECTA);
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

    @ExceptionHandler(PinNuevoInvalidoException.class)
    public ProblemDetail pinNuevoInvalido(PinNuevoInvalidoException e) {
        // 400 como la contraseña nueva: es un dato del cuerpo. El texto es fijo y
        // nunca lleva el PIN.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("PIN nuevo no válido");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(PinInvalidoException.class)
    public ProblemDetail pinInvalido(PinInvalidoException e) {
        // 403 y no 401: el cliente trata todo 401 como «la sesión expiró». Un solo
        // texto para un PIN errado y para un administrador que no vale (de otra
        // empresa, sin PIN, inactivo): quien tantea no debe poder distinguirlos
        // (ADR-0032).
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        p.setTitle("PIN incorrecto");
        int minutos = e.getMinutosDeBloqueo();
        p.setDetail("El administrador o el PIN no son correctos. Si te equivocas varias veces, "
                + "la autorización se bloquea durante " + minutos
                + (minutos == 1 ? " minuto." : " minutos."));
        return p;
    }

    @ExceptionHandler(PinBloqueadoException.class)
    public ProblemDetail pinBloqueado(PinBloqueadoException e) {
        // 403 por la misma razón que el PIN errado. No distingue quién está
        // bloqueado, si el administrador o quien pide: a los dos les sirve esperar.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        p.setTitle("Autorización bloqueada");
        int minutos = e.getMinutosDeBloqueo();
        p.setDetail("Se superó el número de intentos con el PIN. Espera " + minutos
                + (minutos == 1 ? " minuto" : " minutos")
                + " desde el bloqueo e inténtalo de nuevo.");
        return p;
    }

    @ExceptionHandler(AutorizacionRequeridaException.class)
    public ProblemDetail autorizacionRequerida(AutorizacionRequeridaException e) {
        // 403: se sabe quién pide y aun así no puede. Texto fijo: no dice qué se
        // quería hacer ni qué datos llegaron.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        p.setTitle("Autorización requerida");
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

    @ExceptionHandler(UltimoMedioActivoException.class)
    public ProblemDetail ultimoMedioActivo(UltimoMedioActivoException e) {
        // 409: la petición es válida y es el estado actual lo que impide
        // atenderla. Con otro medio activo, la misma petición funciona.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("La empresa quedaría sin medios de pago");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(MedioDePagoInvalidoException.class)
    public ProblemDetail medioDePagoInvalido(MedioDePagoInvalidoException e) {
        // 400: el medio de sistema lo gestiona el sistema; ninguna empresa
        // puede crearlo, activarlo ni desactivarlo, en el estado que esté.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Medio de pago no modificable");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(DatoDeClienteInvalidoException.class)
    public ProblemDetail datoDeClienteInvalido(DatoDeClienteInvalidoException e) {
        // 400: el cuerpo está bien formado, pero el consentimiento y la ciudad
        // dependen de la empresa y de la decisión del cliente, que una
        // anotación no puede mirar.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Datos del cliente inválidos");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(DatosDeDomicilioIncompletosException.class)
    public ProblemDetail datosDeDomicilioIncompletos(DatosDeDomicilioIncompletosException e) {
        // 400: la venta a domicilio está mal armada para este cliente; con
        // otro cliente, o con sus datos completos, la misma petición funciona.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Datos de domicilio incompletos");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(ClienteNoEditableException.class)
    public ProblemDetail clienteNoEditable(ClienteNoEditableException e) {
        // 409: la petición es válida y es la naturaleza del cliente genérico,
        // que lo gestiona el sistema, lo que impide atenderla.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Cliente no editable");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(DatoDeTurnoInvalidoException.class)
    public ProblemDetail datoDeTurnoInvalido(DatoDeTurnoInvalidoException e) {
        // 400: el cuerpo está bien formado, pero la base de efectivo no es un monto
        // que el turno admita. Texto fijo: no repite lo enviado.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Turno no válido");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(TurnoYaAbiertoException.class)
    public ProblemDetail turnoYaAbierto(TurnoYaAbiertoException e) {
        // 409: la petición es válida y es el estado del cajero, que ya tiene su turno
        // abierto, lo que impide atenderla.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Ya hay un turno abierto");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(SinTurnoAbiertoException.class)
    public ProblemDetail sinTurnoAbierto(SinTurnoAbiertoException e) {
        // 409: la operación es válida y falta el turno abierto que la ampara, o se
        // cerró mientras tanto.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Sin turno abierto");
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
        // registrado» sirve a dos reglas del mismo alcance a la vez dentro de
        // una empresa, a una orden con consecutivo repetido y a lo demás que
        // choque; el detalle dice cuál es el dato. El NIT y el correo repetidos
        // tienen su propio manejador, con un tipo que el formulario reconoce.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Dato ya registrado");
        p.setDetail(e.getMessage());
        return p;
    }

    /**
     * Los dos choques del alta de una empresa (y el correo del alta de un
     * usuario) llevan tipo propio. Spring elige el manejador de la clase más
     * específica, de modo que estos dos ganan al de {@link RegistroRepetidoException}.
     */
    @ExceptionHandler(NitRepetidoException.class)
    public ProblemDetail nitRepetido(NitRepetidoException e) {
        return conflictoDeDatoRepetido(e, TIPO_NIT_REPETIDO);
    }

    @ExceptionHandler(CorreoRepetidoException.class)
    public ProblemDetail correoRepetido(CorreoRepetidoException e) {
        return conflictoDeDatoRepetido(e, TIPO_CORREO_REPETIDO);
    }

    private static ProblemDetail conflictoDeDatoRepetido(RegistroRepetidoException e, URI tipo) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setType(tipo);
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
     *
     * El nivel del registro sigue a la causa. Un duplicado, una referencia
     * inexistente o una fila que otra todavía usa son choques que se esperan
     * (dos personas escribiendo a la vez, un dato mal elegido): quedan en
     * {@code info}. Un dato ausente o demasiado largo que llega hasta el motor
     * es una validación que faltó en la solicitud: {@code warn}, para que se
     * vea y se corrija. Lo demás no se esperaba y sigue en {@code error}.
     */
    private static final java.util.regex.Pattern RESTRICCION =
            java.util.regex.Pattern.compile("for key '([^']+)'");

    /** El nombre de la restricción violada, sin el valor que la violó. */
    private static String restriccion(Throwable e) {
        var m = RESTRICCION.matcher(String.valueOf(e.getMessage()));
        return m.find() ? m.group(1) : "desconocida";
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integridad(DataIntegrityViolationException e) {
        // Hibernate envuelve el SQLException una o dos veces, así que
        // getCause() a secas no llega; se baja por la cadena entera.
        Integer codigo = null;
        for (Throwable t = e; t != null && codigo == null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql) {
                codigo = sql.getErrorCode();
            }
        }
        int motor = codigo == null ? 0 : codigo;

        ProblemDetail p;
        switch (motor) {
            case MYSQL_ENTRADA_DUPLICADA -> {
                // Solo la restricción: el valor repetido suele ser un correo o un NIT.
                LOG.info("Operación rechazada por un dato repetido (código {}, restricción {})",
                        motor, restriccion(e));
                p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
                p.setTitle("Dato ya registrado");
                p.setDetail("Ya existe un registro con esos datos.");
            }
            case MYSQL_CLAVE_FORANEA_AL_ESCRIBIR -> {
                LOG.info("Operación rechazada por una referencia inexistente (código {}): {}", motor, e.getMessage());
                p = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
                p.setTitle("Referencia no válida");
                p.setDetail("Uno de los datos indicados no existe o no corresponde a esta empresa.");
            }
            case MYSQL_FILA_REFERENCIADA -> {
                LOG.info("Operación rechazada porque otros registros dependen de la fila (código {}): {}",
                        motor, e.getMessage());
                p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
                p.setTitle("Registro en uso");
                p.setDetail("No se puede quitar o cambiar porque otros registros dependen de él.");
            }
            case MYSQL_COLUMNA_NULA -> {
                LOG.warn("Un dato obligatorio llegó al motor sin valor (código {}): falta una validación. {}",
                        motor, e.getMessage());
                p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
                p.setTitle("Dato obligatorio");
                p.setDetail("Falta un dato obligatorio.");
            }
            case MYSQL_DATO_DEMASIADO_LARGO -> {
                LOG.warn("Un texto más largo que su columna llegó al motor (código {}): falta una validación. {}",
                        motor, e.getMessage());
                p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
                p.setTitle("Dato demasiado largo");
                p.setDetail("Uno de los textos es más largo de lo que se admite.");
            }
            default -> {
                LOG.error("El motor rechazó la operación por integridad de datos", e);
                p = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
                p.setTitle("Error interno");
                p.setDetail("No se pudo completar la operación. Inténtalo de nuevo; "
                        + "si persiste, avisa al administrador.");
            }
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
        p.setType(TIPO_PRODUCTO_CAMBIO);
        p.setTitle("El producto cambió mientras lo editabas");
        p.setDetail("Otra persona o un proceso modificó este producto antes de que guardaras. "
                + "Vuelve a abrirlo para ver los datos actuales y repite tu cambio.");
        return p;
    }

    /**
     * Dos escrituras se esperaron entre sí (interbloqueo, 1213) o una esperó demasiado
     * un bloqueo (1205). Es una red de seguridad: el orden de bloqueos del código
     * evita los ciclos conocidos, pero si aparece uno nuevo la persona no debe ver un
     * 500, sino un aviso de que repita. 409: la petición es válida y fue el estado del
     * momento lo que la impidió.
     *
     * No se reenvía ni se registra el mensaje de la excepción: trae la sentencia SQL
     * con sus valores. Se registra solo la clase.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ProblemDetail bloqueoPerdido(PessimisticLockingFailureException e) {
        LOG.warn("Escritura rechazada por bloqueo en la base: {}", e.getClass().getSimpleName());
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Otra persona estaba guardando al mismo tiempo");
        p.setDetail("Otra persona estaba guardando estos datos al mismo tiempo. Vuelve a intentarlo.");
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
