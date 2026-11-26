package co.com.bspstore.sgip.logica.recuperacion;

import co.com.bspstore.sgip.datos.AuditoriaAccesoRepositorio;
import co.com.bspstore.sgip.datos.RepositorioCodigosRecuperacion;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.logica.correo.EnviadorDeCorreo;
import co.com.bspstore.sgip.modelo.AuditoriaAcceso;
import co.com.bspstore.sgip.modelo.MotivoRechazo;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Hoy;
import co.com.bspstore.sgip.util.Seguridad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * La parte de la solicitud del código que no debe hacer esperar a quien la pide (RF-65, §5.3 de
 * la spec): decide si se genera un código, lo guarda y manda el correo.
 *
 * Es un bean aparte, y no un método de {@link ServicioRecuperacion}, para que la entrega al
 * ejecutor pase por el proxy: una llamada dentro de la misma clase no sería asíncrona.
 *
 * ## Asíncrona y transaccional a la vez
 *
 * Con las dos anotaciones en el mismo método, Spring pone el aviso asíncrono por fuera del
 * transaccional: la transacción se abre en el hilo del ejecutor, y el hilo de la petición no
 * toca la base ni abre una conexión. Una prueba lo fija ({@code TareaDeRecuperacionConBaseTest}).
 *
 * ## Una sola fila de bitácora por solicitud
 *
 * Cada solicitud termina en exactamente una de las seis salidas del §5.3: un código enviado o un
 * descarte (correo inexistente, cuenta inactiva o un tope). Ninguna de las salidas revela, por
 * fuera, cuál fue: eso solo queda en la bitácora.
 *
 * ## El correo sale al confirmarse la transacción
 *
 * Si la transacción revierte, el código no existe y no hay nada que enviar: por eso el envío va
 * en {@code afterCommit}. Un fallo del envío se anota con la clase del error y nada más: ni el
 * correo, ni el texto, ni el código.
 */
@Component
public class TareaDeRecuperacion {

    private static final Logger log = LoggerFactory.getLogger(TareaDeRecuperacion.class);

    /** Cuánto vale un código, en minutos. */
    public static final int VIGENCIA_MINUTOS = 15;

    /** Tope por IP: con 10 solicitudes en la última hora, la siguiente se descarta. */
    static final int SOLICITUDES_POR_ORIGEN = 10;
    /** Tope por correo: con 3 códigos en 15 minutos, el siguiente no se genera. */
    static final int CODIGOS_POR_VENTANA = 3;
    static final int VENTANA_DE_CODIGOS_MINUTOS = 15;
    /** Tope de fallos: con 10 confirmaciones fallidas en 24 horas no se generan más códigos. */
    static final int FALLOS_POR_DIA = 10;

    static final String ASUNTO = "Tu código para recuperar la contraseña de SGIP";

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final UsuarioRepositorio usuarios;
    private final AuditoriaAccesoRepositorio auditoria;
    private final RepositorioCodigosRecuperacion codigos;
    private final EnviadorDeCorreo enviador;
    private final Clock reloj;

    public TareaDeRecuperacion(UsuarioRepositorio usuarios, AuditoriaAccesoRepositorio auditoria,
                               RepositorioCodigosRecuperacion codigos, EnviadorDeCorreo enviador,
                               Clock reloj) {
        this.usuarios = usuarios;
        this.auditoria = auditoria;
        this.codigos = codigos;
        this.enviador = enviador;
        this.reloj = reloj;
    }

    /**
     * @param correoNormalizado ya con trim, en minúsculas y de a lo sumo 120 caracteres
     * @param origen            ya recortado a lo que cabe en la bitácora: es el mismo valor que se
     *                          guarda y con el que se cuenta el tope por IP
     */
    @Async("ejecutorDeCorreo")
    @Transactional
    public void procesar(String correoNormalizado, String origen) {
        LocalDateTime ahora = Hoy.ahora(reloj);

        // 1. Tope por IP: antes de mirar a quién se pide, para no gastar consultas en un abuso.
        if (codigos.contarSolicitudesDesdeOrigen(origen, ahora.minusHours(1)) >= SOLICITUDES_POR_ORIGEN) {
            descartar(null, correoNormalizado, MotivoRechazo.RECUPERACION_LIMITE, origen);
            return;
        }

        // 2. El usuario. Uno bloqueado sí recibe código: así se desbloquea, y su plazo de bloqueo
        // no se consulta.
        Usuario usuario = usuarios.findByCorreo(correoNormalizado).orElse(null);
        if (usuario == null) {
            descartar(null, correoNormalizado, MotivoRechazo.RECUPERACION_CORREO_INEXISTENTE, origen);
            return;
        }
        if (!usuario.isActivo()) {
            descartar(usuario, correoNormalizado, MotivoRechazo.RECUPERACION_CUENTA_INACTIVA, origen);
            return;
        }

        // 3. Tope por correo.
        if (codigos.contarCreadosDesde(usuario.getId(), ahora.minusMinutes(VENTANA_DE_CODIGOS_MINUTOS))
                >= CODIGOS_POR_VENTANA) {
            descartar(usuario, correoNormalizado, MotivoRechazo.RECUPERACION_LIMITE, origen);
            return;
        }

        // 4. Tope de fallos de la cuenta.
        if (codigos.contarFallosDeRecuperacion(usuario.getId(), ahora.minusHours(24)) >= FALLOS_POR_DIA) {
            descartar(usuario, correoNormalizado, MotivoRechazo.RECUPERACION_LIMITE, origen);
            return;
        }

        // 5. El código nuevo reemplaza a los anteriores: se invalidan antes de crearlo, porque la
        // invalidación alcanza a todo lo vigente y alcanzaría también al nuevo.
        String codigo = String.format("%06d", ALEATORIO.nextInt(1_000_000));
        codigos.invalidarVigentes(usuario.getId(), ahora);
        codigos.crear(usuario.getId(), Seguridad.derivar(codigo), ahora, ahora.plusMinutes(VIGENCIA_MINUTOS), origen);

        // 6. Bitácora.
        auditoria.save(AuditoriaAcceso.evento(usuario, correoNormalizado, true,
                MotivoRechazo.RECUPERACION_SOLICITADA, origen));

        // 7. Correo, solo si la transacción se confirma.
        String destino = usuario.getCorreo();
        String texto = "Tu código es " + codigo + ". Vence en " + VIGENCIA_MINUTOS + " minutos. "
                + "Si pediste más de uno, usa el último que recibas. "
                + "Si no lo pediste, ignora este mensaje: tu contraseña no cambia.";
        despuesDelCommit(() -> enviar(destino, ASUNTO, texto));
    }

    private void descartar(Usuario usuarioONull, String correo, MotivoRechazo motivo, String origen) {
        auditoria.save(AuditoriaAcceso.evento(usuarioONull, correo, false, motivo, origen));
    }

    private void despuesDelCommit(Runnable accion) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Sin transacción todo lo anterior ya quedó escrito: no hay nada que esperar.
            accion.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                accion.run();
            }
        });
    }

    /** El contrato del enviador es no lanzar; esto es el cinturón: nada se propaga ni se anota de más. */
    private void enviar(String destino, String asunto, String texto) {
        try {
            enviador.enviar(destino, asunto, texto);
        } catch (RuntimeException e) {
            log.error("No se pudo enviar el correo de recuperación ({}).", e.getClass().getName());
        }
    }
}
