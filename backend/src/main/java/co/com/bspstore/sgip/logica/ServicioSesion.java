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
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        LocalDateTime ahora = LocalDateTime.now(reloj);
        Optional<Sesion> sesion = sesiones.findByTokenHash(Seguridad.hashToken(token))
                .filter(s -> s.estaVigente(ahora));
        sesion.ifPresent(s -> s.renovar(ahora, minutos));
        return sesion;
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
}
