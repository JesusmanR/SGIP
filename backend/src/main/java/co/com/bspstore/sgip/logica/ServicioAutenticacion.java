package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.AuditoriaAccesoRepositorio;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.modelo.AuditoriaAcceso;
import co.com.bspstore.sgip.modelo.MotivoRechazo;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class ServicioAutenticacion {

    /** El mismo mínimo que el alta de usuario y el inicializador. */
    private static final int CLAVE_MINIMA = 8;

    /**
     * Hash de una clave que nadie conoce, con el mismo costo que uno real
     * (sale de {@link Seguridad#derivar}). Sirve para que las ramas del ingreso
     * que no tienen una clave que comprobar gasten igual tiempo que la que sí
     * la comprueba: si no, lo que tarda la respuesta dice qué correos existen
     * y cuáles están bloqueados.
     */
    private static final String HASH_FICTICIO = Seguridad.derivar("clave-que-nadie-conoce");

    private final UsuarioRepositorio usuarios;
    private final AuditoriaAccesoRepositorio auditoria;
    private final Clock reloj;
    private final int intentosMaximos;
    private final PoliticaDeBloqueo bloqueo;

    /**
     * El instante del bloqueo y su vencimiento se miden con el reloj de la
     * aplicación, el mismo en los dos lados: la columna solo guarda el instante
     * y la base nunca decide si venció (ADR-0028). El plazo lo dice
     * {@link PoliticaDeBloqueo}, el mismo que usa la lista de usuarios de la plataforma.
     */
    public ServicioAutenticacion(UsuarioRepositorio usuarios,
                                 AuditoriaAccesoRepositorio auditoria,
                                 Clock reloj,
                                 @Value("${sgip.seguridad.intentos-maximos:5}") int intentosMaximos,
                                 PoliticaDeBloqueo bloqueo) {
        this.usuarios = usuarios;
        this.auditoria = auditoria;
        this.reloj = reloj;
        this.intentosMaximos = intentosMaximos;
        this.bloqueo = bloqueo;
    }

    @Transactional(noRollbackFor = CredencialesInvalidasException.class)
    public Usuario autenticar(String correo, String clave, String origen) {
        String normalizado = correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);

        Usuario usuario = usuarios.findByCorreo(normalizado).orElse(null);
        if (usuario == null) {
            rechazarSinClave(null, normalizado, MotivoRechazo.USUARIO_INEXISTENTE, clave, origen);
        }
        if (!usuario.isActivo()) {
            rechazarSinClave(usuario, normalizado, MotivoRechazo.CUENTA_INACTIVA, clave, origen);
        }
        // Al vencer el plazo la cuenta se limpia antes de mirar la clave: los
        // intentos vuelven a cero y un error no la bloquea de inmediato otra vez.
        if (bloqueo.vencido(usuario, ahora())) {
            usuario.desbloquear();
        }
        if (usuario.isBloqueado()) {
            rechazarSinClave(usuario, normalizado, MotivoRechazo.CUENTA_BLOQUEADA, clave, origen);
        }
        if (!comprobarClave(clave, usuario.getClaveHash())) {
            usuario.registrarIntentoFallido(intentosMaximos, ahora());
            rechazar(usuario, normalizado, MotivoRechazo.CLAVE_INCORRECTA, origen);
        }

        usuario.registrarIngresoExitoso();
        auditoria.save(AuditoriaAcceso.exitoso(usuario, origen));
        return usuario;
    }

    /**
     * Levanta el bloqueo por intentos fallidos. Devuelve false si el
     * usuario no existe, para que la capa web conteste 404 sin que esta
     * clase tenga que saber de códigos HTTP.
     */
    @Transactional
    /**
     * Levanta el bloqueo de una cuenta de la propia empresa (RF-12).
     *
     * El identificador llega por la ruta, de modo que el usuario se busca
     * acotado por la empresa de quien pide y no por {@code findById}. Con
     * el buscador heredado, el administrador de un comercio podía
     * desbloquear a un usuario de otro: el identificador es único en toda
     * la plataforma y la anotación de rol del controlador solo comprueba
     * que quien pide sea administrador, no de qué empresa. Es la RN-16
     * aplicada a la tabla de usuarios.
     *
     * Devuelve falso tanto si el usuario no existe como si es de otra
     * empresa, y el controlador responde 404 en los dos casos. No es
     * descuido: un 403 para lo ajeno confirmaría que ese usuario existe,
     * que es más de lo que quien pregunta tiene derecho a saber.
     */
    public boolean desbloquear(Integer usuarioId) {
        return usuarios.findByIdAndEmpresaId(usuarioId, ContextoEmpresa.empresaActual())
                .map(u -> {
                    u.desbloquear();
                    return true;
                })
                .orElse(false);
    }

    /**
     * Cambia la contraseña de quien tiene la sesión abierta y le quita la
     * obligación de cambiarla.
     *
     * El identificador sale de la sesión autenticada y no de la petición,
     * así que no se acota por empresa: el super administrador no tiene
     * ninguna y también debe poder cambiar la suya. Lo que se busca es la
     * identidad de quien pide, no un recurso ajeno.
     *
     * Pedir la actual no es un trámite. Con la sesión robada, la
     * contraseña nueva le daría al ladrón una cuenta propia; por eso
     * equivocarse cuenta como un intento fallido y a los mismos intentos
     * que el ingreso la cuenta se bloquea. Y por eso la transacción no se
     * revierte con esa excepción: si lo hiciera, el contador nunca
     * avanzaría y se podría adivinar sin límite.
     *
     * Ningún mensaje ni registro lleva la contraseña, ni la actual ni la
     * nueva.
     */
    @Transactional(noRollbackFor = {ClaveActualIncorrectaException.class, CuentaBloqueadaException.class})
    public void cambiarClave(Integer usuarioId, String actual, String nueva, String origen) {
        Usuario usuario = usuarios.findById(usuarioId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El usuario no existe."));

        // El mismo vencimiento que en el ingreso: al cumplirse el plazo, los
        // intentos vuelven a cero también por esta puerta.
        if (bloqueo.vencido(usuario, ahora())) {
            usuario.desbloquear();
        }
        // El bloqueo no cierra las sesiones abiertas (ADR-0028), así que esta
        // puerta se alcanza con la cuenta bloqueada y tiene que cerrarla ella:
        // con la actual correcta, de otro modo, la contraseña se cambiaría. No
        // cuenta intento ni alarga el plazo, y queda en la auditoría como en el
        // ingreso.
        if (usuario.isBloqueado()) {
            auditoria.save(AuditoriaAcceso.fallido(usuario, usuario.getCorreo(),
                    MotivoRechazo.CUENTA_BLOQUEADA, origen));
            throw new CuentaBloqueadaException(bloqueo.minutos());
        }
        if (!Seguridad.verificar(actual, usuario.getClaveHash())) {
            usuario.registrarIntentoFallido(intentosMaximos, ahora());
            // Queda en la misma auditoría que los fallos del ingreso: es la
            // misma adivinanza, hecha desde una sesión abierta.
            auditoria.save(AuditoriaAcceso.fallido(usuario, usuario.getCorreo(),
                    MotivoRechazo.CLAVE_INCORRECTA, origen));
            throw new ClaveActualIncorrectaException();
        }
        if (nueva == null || nueva.length() < CLAVE_MINIMA) {
            throw new ClaveNuevaInvalidaException(
                    "La contraseña nueva debe tener al menos " + CLAVE_MINIMA + " caracteres.");
        }
        if (nueva.equals(actual)) {
            throw new ClaveNuevaInvalidaException(
                    "La contraseña nueva debe ser distinta de la actual.");
        }

        usuario.registrarIngresoExitoso();
        usuario.cambiarClave(Seguridad.derivar(nueva));
    }

    /**
     * Único punto por el que el ingreso gasta el tiempo de derivar la clave.
     * Es visible en el paquete para que las pruebas comprueben que cada rama
     * pasa por aquí sin medir tiempos, que serían frágiles.
     */
    boolean comprobarClave(String clave, String hash) {
        return Seguridad.verificar(clave, hash);
    }

    /**
     * Rechaza una rama en la que no hay clave que comprobar, tras gastar el
     * mismo tiempo que la rama de la clave errada. Lo que se audita no cambia.
     */
    private void rechazarSinClave(Usuario usuario, String correo, MotivoRechazo motivo,
                                  String clave, String origen) {
        comprobarClave(clave, HASH_FICTICIO);
        rechazar(usuario, correo, motivo, origen);
    }

    private void rechazar(Usuario usuario, String correo, MotivoRechazo motivo, String origen) {
        auditoria.save(AuditoriaAcceso.fallido(usuario, correo, motivo, origen));
        throw new CredencialesInvalidasException(bloqueo.minutos());
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(reloj);
    }
}