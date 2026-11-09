package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.datos.SesionRepositorio;
import co.com.bspstore.sgip.modelo.Sesion;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Abre, valida y cierra sesiones.
 *
 * El token en claro existe en un solo instante: el que lo devuelve
 * {@link #abrir}. De ahí en adelante solo circula su hash, de modo que
 * ni esta clase ni la base de datos pueden reconstruirlo.
 */
@Service
public class ServicioSesion {

    private final SesionRepositorio sesiones;
    private final int minutos;
    private final Clock reloj;

    public ServicioSesion(SesionRepositorio sesiones,
                          @Value("${sgip.seguridad.sesion-minutos:30}") int minutos,
                          Clock reloj) {
        this.sesiones = sesiones;
        this.minutos = minutos;
        this.reloj = reloj;
    }

    /** Devuelve el token en claro, que es lo único que el cliente guarda. */
    @Transactional
    public String abrir(Usuario usuario, String origen, String dispositivo) {
        String token = Seguridad.nuevoToken();
        LocalDateTime ahora = LocalDateTime.now(reloj);
        sesiones.save(new Sesion(usuario, Seguridad.hashToken(token),
                ahora, ahora.plusMinutes(minutos), origen, dispositivo));
        return token;
    }

    /**
     * Resuelve el token a su sesión y, si sigue viva, le corre la
     * expiración. Un token desconocido, caducado o cerrado devuelve
     * vacío sin distinguir cuál de los tres: quien pregunta no tiene por
     * qué saber si el token existió alguna vez.
     */
    @Transactional
    public Optional<Sesion> validar(String token) {
        LocalDateTime ahora = LocalDateTime.now(reloj);
        Optional<Sesion> sesion = vigente(token, ahora);
        sesion.ifPresent(s -> s.renovar(ahora, minutos));
        return sesion;
    }

    /**
     * Como {@link #validar}, pero sin correr la expiración: una sesión
     * caducada sigue sin valer, y una viva no se alarga.
     *
     * Es para las peticiones que el cliente hace solo, sin que la persona
     * toque nada —el contador de la prueba pregunta cada cinco minutos—. La
     * expiración es por inactividad (RNF-09), y si esas peticiones contaran
     * como actividad, una pestaña olvidada no caducaría nunca. Quién la usa
     * lo decide el servidor ({@code FiltroSesion}), no una cabecera del cliente.
     */
    @Transactional(readOnly = true)
    public Optional<Sesion> validarSinRenovar(String token) {
        return vigente(token, LocalDateTime.now(reloj));
    }

    private Optional<Sesion> vigente(String token, LocalDateTime ahora) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sesiones.findByTokenHash(Seguridad.hashToken(token))
                .filter(s -> s.estaVigente(ahora));
    }

    /**
     * Cierra la sesión del token. Es idempotente: cerrar dos veces, o
     * cerrar una sesión que ya expiró, devuelve false sin fallar, porque
     * el resultado que le interesa a quien llama ya se cumplió.
     */
    @Transactional
    public boolean cerrar(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return sesiones.findByTokenHash(Seguridad.hashToken(token))
                .filter(s -> !s.isCerrada())
                .map(s -> {
                    s.cerrar();
                    return true;
                })
                .orElse(false);
    }

    /** Cierra todas las sesiones abiertas de un usuario. */
    @Transactional
    public int cerrarLasDe(Integer usuarioId) {
        return sesiones.cerrarLasDe(usuarioId);
    }

    /**
     * Cierra todas las sesiones de un usuario salvo la del token dado.
     *
     * Sin token no cierra nada: no saber cuál es la sesión en curso
     * podría llevar a cerrar justo la que acaba de cambiar la clave.
     * Se excluye por el hash porque es lo único que la base conoce.
     */
    @Transactional
    public int cerrarLasDemasDe(Integer usuarioId, String tokenActual) {
        if (tokenActual == null || tokenActual.isBlank()) {
            return 0;
        }
        return sesiones.cerrarLasDemasDe(usuarioId, Seguridad.hashToken(tokenActual));
    }
}
