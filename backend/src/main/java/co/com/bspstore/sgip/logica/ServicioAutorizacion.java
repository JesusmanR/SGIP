package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.AdministradorParaAutorizar;
import co.com.bspstore.sgip.api.dto.EstadoPin;
import co.com.bspstore.sgip.api.dto.SolicitudAutorizacion;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPin;
import co.com.bspstore.sgip.datos.RepositorioPin.UsuarioConPin;
import co.com.bspstore.sgip.modelo.MotivoRechazo;
import co.com.bspstore.sgip.util.Seguridad;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * La autorización del administrador con su PIN (ADR-0032).
 *
 * Un vendedor no puede dar un descuento, vender sin existencia, crear un producto
 * ni devolver sin el visto bueno de un administrador. El administrador lo da en la
 * misma pantalla: elige su nombre en la lista y escribe su PIN, y el PIN viaja
 * dentro de la petición que confirma la operación.
 *
 * Todo el estado vive en la base y nada en memoria: los intentos, el bloqueo y la
 * bitácora son filas, y {@link Autorizacion} se crea para una petición y se tira.
 *
 * ## Dos contadores
 *
 * Un PIN errado contra un administrador válido cuenta contra ESE administrador
 * (sus 5 fallos bloquean su PIN). Un objetivo que no vale (de otra empresa, que no
 * es administrador, inactivo o sin PIN) cuenta contra QUIEN PIDE, con el mismo
 * plazo y el mismo máximo: así quien tantea no puede bloquear el PIN de un
 * administrador ajeno ni de otro de su empresa con un id equivocado a propósito.
 * Los dos rechazos tienen el mismo texto, y el segundo gasta el mismo tiempo que el
 * primero (deriva el PIN contra uno ficticio).
 *
 * ## Orden de bloqueos
 *
 * Primero la fila de quien pide, luego la del administrador. Quien pide nunca es
 * administrador (esos no llegan a pedir) y el objetivo válido siempre lo es, de
 * modo que no hay dos peticiones que tomen las mismas dos filas en orden inverso.
 * La fila de un objetivo que no vale no se bloquea nunca: se averigua antes, sin
 * bloqueo.
 *
 * ## La transacción
 *
 * {@link #autorizar} corre en la suya ({@code REQUIRES_NEW}) y no se revierte con
 * un PIN errado ni bloqueado: lo que cuenta tiene que sobrevivir a que la
 * operación que la llamó falle después. Las excepciones del PIN se lanzan desde
 * este mismo método y no desde un bean transaccional anidado, que marcaría la
 * transacción para revertirla y haría saltar {@code UnexpectedRollbackException}.
 *
 * Ningún mensaje ni registro lleva un PIN.
 */
@Service
public class ServicioAutorizacion {

    private static final String ADMINISTRADOR = "ADMINISTRADOR";

    /** De 4 a 6 dígitos ASCII; {@code [0-9]} y no {@code \d}, que admite otros alfabetos. */
    private static final Pattern FORMATO_DEL_PIN = Pattern.compile("[0-9]{4,6}");

    /**
     * Lo más largo que se deriva. Un PIN más largo no puede ser uno de verdad: se
     * toma por vacío y falla igual, sin darle a quien manda un texto enorme el
     * poder de gastar tiempo de servidor.
     */
    private static final int LARGO_MAXIMO_QUE_SE_DERIVA = 12;

    /** Cabe en {@code auditoria_accesos.origen}. */
    private static final int LARGO_DEL_ORIGEN = 60;

    /**
     * Derivado de un PIN que nadie tiene, con el costo de uno real. Las ramas que
     * no tienen un PIN que comprobar gastan igual tiempo que la que sí: si no, lo
     * que tarda la respuesta dice qué administradores valen.
     */
    private static final String HASH_FICTICIO = Seguridad.derivar("pin-que-nadie-tiene");

    private final RepositorioPin pines;
    private final PoliticaDeBloqueo bloqueo;
    private final Clock reloj;
    private final int intentosMaximos;

    /**
     * El plazo lo dice {@link PoliticaDeBloqueo}, el mismo de las cuentas. El
     * máximo de intentos es la misma propiedad que lee el ingreso
     * ({@code sgip.seguridad.intentos-maximos}).
     */
    public ServicioAutorizacion(RepositorioPin pines, PoliticaDeBloqueo bloqueo, Clock reloj,
                                @Value("${sgip.seguridad.intentos-maximos:5}") int intentosMaximos) {
        this.pines = pines;
        this.bloqueo = bloqueo;
        this.reloj = reloj;
        this.intentosMaximos = intentosMaximos;
    }

    /**
     * Comprueba, para esta petición, que un administrador autoriza lo que se pide.
     *
     * Sin nada que autorizar no valida ni cuenta nada y devuelve una autorización
     * vacía. Si quien pide es administrador se autoriza a sí mismo, sin PIN. Si no,
     * la solicitud es obligatoria (403 si falta).
     *
     * <b>Contrato de quien la llama.</b> Se llama ANTES de abrir la transacción de la
     * operación, y sin que el hilo tenga bloqueada, escrita ni referenciada por una
     * clave foránea (una venta con {@code autorizada_por}, por ejemplo) la fila de
     * {@code usuarios} del solicitante o del administrador. Esta transacción es
     * independiente de la de fuera: si la de fuera ya tomó una de esas filas, la de
     * dentro espera a la de fuera hasta el tiempo límite de InnoDB, falla, y el
     * intento no se cuenta. No hay una guarda aquí a propósito (la prueba
     * {@code losIntentosPersistenAunqueLaOperacionFalle} la llama desde un bean
     * transaccional que no toca esas filas); la guarda está en las fachadas de la venta
     * y de la devolución, que son las que no son transaccionales.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = {PinInvalidoException.class, PinBloqueadoException.class})
    public Autorizacion autorizar(SolicitudAutorizacion s, Set<ExcepcionAutorizable> necesarias) {
        Set<ExcepcionAutorizable> pedidas = necesarias == null ? Set.of() : Set.copyOf(necesarias);
        if (pedidas.isEmpty()) {
            return new Autorizacion(null, Set.of());
        }
        UsuarioAutenticado quien = ContextoEmpresa.actual().orElseThrow(() -> new IllegalStateException(
                "No hay usuario autenticado en el contexto. Revise la cadena de seguridad."));
        if (ADMINISTRADOR.equals(quien.rol())) {
            return new Autorizacion(quien.usuarioId(), pedidas);
        }
        // Una solicitud incompleta no es una adivinanza: no hay PIN ni administrador
        // que probar, y por eso no cuenta contra nadie.
        if (s == null || s.administradorId() == null || s.pin() == null) {
            throw new AutorizacionRequeridaException();
        }

        Integer empresa = ContextoEmpresa.empresaActual();
        LocalDateTime ahora = ahora();
        UsuarioConPin solicitante = pines.bloquearUsuario(quien.usuarioId(), empresa)
                .orElseThrow(() -> new RecursoNoEncontradoException("El usuario no existe."));

        ContadorDePin guardadoDelSolicitante =
                new ContadorDePin(solicitante.erradosEmitidos(), solicitante.emisionBloqueadaEn());
        ContadorDePin emitidos = alDia(guardadoDelSolicitante, ahora);
        if (emitidos.bloqueado()) {
            anotar(solicitante, MotivoRechazo.PIN_BLOQUEADO);
            throw new PinBloqueadoException(bloqueo.minutos());
        }

        // Sin bloqueo: la fila de un objetivo que no vale no se toma.
        Optional<UsuarioConPin> objetivo = pines.esAdministradorConPin(s.administradorId(), empresa)
                ? pines.bloquearAdministradorConPin(s.administradorId(), empresa)
                : Optional.empty();
        if (objetivo.isEmpty()) {
            derivarSinPin(s.pin());
            ContadorDePin nuevo = emitidos.conUnFallo(intentosMaximos, ahora);
            guardarEmitidos(empresa, solicitante, guardadoDelSolicitante, nuevo);
            anotar(solicitante, MotivoRechazo.PIN_OBJETIVO_INVALIDO);
            if (nuevo.bloqueado()) {
                anotar(solicitante, MotivoRechazo.PIN_BLOQUEADO);
            }
            throw new PinInvalidoException(bloqueo.minutos());
        }

        UsuarioConPin administrador = objetivo.get();
        ContadorDePin guardadoDelAdministrador =
                new ContadorDePin(administrador.intentos(), administrador.bloqueadoEn());
        ContadorDePin contador = alDia(guardadoDelAdministrador, ahora);
        if (contador.bloqueado()) {
            derivarSinPin(s.pin());
            guardarEmitidos(empresa, solicitante, guardadoDelSolicitante, emitidos);
            anotar(solicitante, MotivoRechazo.PIN_BLOQUEADO);
            throw new PinBloqueadoException(bloqueo.minutos());
        }

        if (!Seguridad.verificar(candidato(s.pin()), administrador.pinHash())) {
            ContadorDePin nuevo = contador.conUnFallo(intentosMaximos, ahora);
            guardarDelPin(empresa, administrador, guardadoDelAdministrador, nuevo);
            guardarEmitidos(empresa, solicitante, guardadoDelSolicitante, emitidos);
            anotar(solicitante, MotivoRechazo.PIN_INCORRECTO);
            if (nuevo.bloqueado()) {
                anotar(administrador, MotivoRechazo.PIN_BLOQUEADO);
            }
            throw new PinInvalidoException(bloqueo.minutos());
        }

        // Un acierto limpia los dos contadores, como un ingreso limpia el suyo.
        guardarDelPin(empresa, administrador, guardadoDelAdministrador, ContadorDePin.LIMPIO);
        guardarEmitidos(empresa, solicitante, guardadoDelSolicitante, ContadorDePin.LIMPIO);
        return new Autorizacion(administrador.usuarioId(), pedidas);
    }

    /**
     * Fija o cambia el PIN de quien tiene la sesión: solo un administrador, y solo el
     * suyo. {@code pinActual} es nulo la primera vez.
     *
     * Cambiarlo exige el actual, y equivocarse cuenta contra el mismo contador del
     * PIN y lo respeta el mismo bloqueo, igual que {@code cambiarClave} con la
     * contraseña (ADR-0028): con una sesión robada no se puede adivinar el PIN por
     * esta puerta sin límite, que de otro modo sería la más barata. Que falte o
     * venga mal formado no es un intento: no puede ser el PIN de nadie.
     */
    @Transactional(noRollbackFor = {PinInvalidoException.class, PinBloqueadoException.class})
    public void fijarPin(String pinActual, String pinNuevo) {
        UsuarioAutenticado quien = administradorEnSesion();
        if (pinNuevo == null || !FORMATO_DEL_PIN.matcher(pinNuevo).matches()) {
            throw new PinNuevoInvalidoException("El PIN debe tener de 4 a 6 dígitos.");
        }
        Integer empresa = ContextoEmpresa.empresaActual();
        LocalDateTime ahora = ahora();
        UsuarioConPin yo = pines.bloquearUsuario(quien.usuarioId(), empresa)
                .orElseThrow(() -> new RecursoNoEncontradoException("El usuario no existe."));

        if (yo.pinHash() != null) {
            if (pinActual == null || !FORMATO_DEL_PIN.matcher(pinActual).matches()) {
                throw new PinNuevoInvalidoException("Escribe tu PIN actual para cambiarlo.");
            }
            ContadorDePin guardado = new ContadorDePin(yo.intentos(), yo.bloqueadoEn());
            ContadorDePin contador = alDia(guardado, ahora);
            if (contador.bloqueado()) {
                anotar(yo, MotivoRechazo.PIN_BLOQUEADO);
                throw new PinBloqueadoException(bloqueo.minutos());
            }
            if (!Seguridad.verificar(pinActual, yo.pinHash())) {
                ContadorDePin nuevo = contador.conUnFallo(intentosMaximos, ahora);
                guardarDelPin(empresa, yo, guardado, nuevo);
                anotar(yo, MotivoRechazo.PIN_INCORRECTO);
                if (nuevo.bloqueado()) {
                    anotar(yo, MotivoRechazo.PIN_BLOQUEADO);
                }
                throw new PinInvalidoException(bloqueo.minutos());
            }
        }
        // Se mira después del PIN actual: lo que dice de la contraseña no se le
        // cuenta a quien no sabe ni su propio PIN.
        if (Seguridad.verificar(pinNuevo, yo.claveHash())) {
            throw new PinNuevoInvalidoException("El PIN debe ser distinto de tu contraseña.");
        }
        pines.guardarPin(yo.usuarioId(), empresa, Seguridad.derivar(pinNuevo));
    }

    /** Si el administrador en sesión ya tiene PIN. Nunca el PIN. */
    @Transactional(readOnly = true)
    public EstadoPin estadoDelPin() {
        UsuarioAutenticado quien = administradorEnSesion();
        return new EstadoPin(pines.tienePin(quien.usuarioId(), ContextoEmpresa.empresaActual()));
    }

    /**
     * Los administradores activos de la empresa que tienen PIN, para que el
     * vendedor elija a quién llamar. Solo el identificador y el nombre.
     */
    @Transactional(readOnly = true)
    public List<AdministradorParaAutorizar> administradores() {
        return pines.administradoresConPin(ContextoEmpresa.empresaActual());
    }

    // ------------------------------------------------------------------ apoyo

    private UsuarioAutenticado administradorEnSesion() {
        UsuarioAutenticado quien = ContextoEmpresa.actual().orElseThrow(() -> new IllegalStateException(
                "No hay usuario autenticado en el contexto. Revise la cadena de seguridad."));
        if (!ADMINISTRADOR.equals(quien.rol())) {
            throw new AutorizacionRequeridaException();
        }
        return quien;
    }

    /**
     * Al vencer el plazo, el contador se limpia antes de contar nada: los intentos
     * vuelven a cero y un fallo no bloquea de inmediato otra vez (ADR-0028).
     */
    private ContadorDePin alDia(ContadorDePin contador, LocalDateTime ahora) {
        return bloqueo.vencido(contador.bloqueado(), contador.bloqueadoEn(), ahora)
                ? ContadorDePin.LIMPIO
                : contador;
    }

    private void guardarDelPin(Integer empresa, UsuarioConPin usuario, ContadorDePin antes, ContadorDePin despues) {
        if (!antes.equals(despues)) {
            pines.guardarContadorDelPin(usuario.usuarioId(), empresa, despues.fallos(), despues.bloqueadoEn());
        }
    }

    private void guardarEmitidos(Integer empresa, UsuarioConPin usuario, ContadorDePin antes, ContadorDePin despues) {
        if (!antes.equals(despues)) {
            pines.guardarContadorEmitido(usuario.usuarioId(), empresa, despues.fallos(), despues.bloqueadoEn());
        }
    }

    /** Lo que se deriva: un texto enorme se toma por vacío y falla como cualquier PIN errado. */
    private static String candidato(String pin) {
        return pin.length() > LARGO_MAXIMO_QUE_SE_DERIVA ? "" : pin;
    }

    private static void derivarSinPin(String pin) {
        Seguridad.verificar(candidato(pin), HASH_FICTICIO);
    }

    /**
     * Queda en la misma bitácora de accesos que los ingresos fallidos, a nombre de
     * quien se anota: quien pidió en un rechazo, la cuenta cuyo PIN se bloqueó en el
     * bloqueo. El origen es la dirección de la petición, si hay una.
     */
    private void anotar(UsuarioConPin usuario, MotivoRechazo motivo) {
        pines.registrarRechazo(usuario.usuarioId(), usuario.correo(), motivo, origen());
    }

    private static String origen() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes web) {
            String direccion = web.getRequest().getRemoteAddr();
            if (direccion != null) {
                return direccion.length() > LARGO_DEL_ORIGEN ? direccion.substring(0, LARGO_DEL_ORIGEN) : direccion;
            }
        }
        return null;
    }

    /**
     * Al segundo: la columna es un DATETIME sin fracción y la base redondea. Lo que
     * se compara después con el reloj es lo que se leyó, no lo que se quiso escribir.
     */
    private LocalDateTime ahora() {
        return LocalDateTime.now(reloj).truncatedTo(ChronoUnit.SECONDS);
    }
}
