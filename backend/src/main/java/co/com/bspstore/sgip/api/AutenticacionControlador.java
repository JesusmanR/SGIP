package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.RespuestaLogin;
import co.com.bspstore.sgip.api.dto.SolicitudLogin;
import co.com.bspstore.sgip.logica.ServicioAutenticacion;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.modelo.Usuario;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AutenticacionControlador {

    private static final String PREFIJO = "Bearer ";

    private final ServicioAutenticacion servicio;
    private final ServicioSesion sesiones;

    public AutenticacionControlador(ServicioAutenticacion servicio, ServicioSesion sesiones) {
        this.servicio = servicio;
        this.sesiones = sesiones;
    }

    /** CU-01 · Iniciar sesión. */
    @PostMapping("/login")
    public RespuestaLogin ingresar(@Valid @RequestBody SolicitudLogin solicitud,
                                   HttpServletRequest peticion) {
        String origen = peticion.getRemoteAddr();
        Usuario usuario = servicio.autenticar(solicitud.correo(), solicitud.clave(), origen);
        String token = sesiones.abrir(usuario, origen, peticion.getHeader(HttpHeaders.USER_AGENT));
        return RespuestaLogin.de(usuario, token);
    }

    /**
     * CU-02 · Cerrar sesión.
     *
     * Responde 204 tanto si cerró la sesión como si el token ya no valía.
     * Cerrar algo que ya estaba cerrado no es un error, y contestar
     * distinto en cada caso le diría a un atacante si un token existe.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> salir(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String cabecera) {
        if (cabecera != null && cabecera.startsWith(PREFIJO)) {
            sesiones.cerrar(cabecera.substring(PREFIJO.length()).trim());
        }
        return ResponseEntity.noContent().build();
    }
}
