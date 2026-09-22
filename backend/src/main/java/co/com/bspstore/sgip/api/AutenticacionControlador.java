package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.RespuestaLogin;
import co.com.bspstore.sgip.api.dto.SolicitudLogin;
import co.com.bspstore.sgip.logica.ServicioAutenticacion;
import co.com.bspstore.sgip.modelo.Usuario;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AutenticacionControlador {

    private final ServicioAutenticacion servicio;

    public AutenticacionControlador(ServicioAutenticacion servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/login")
    public RespuestaLogin ingresar(@Valid @RequestBody SolicitudLogin solicitud,
                                   HttpServletRequest peticion) {
        Usuario usuario = servicio.autenticar(
                solicitud.correo(), solicitud.clave(), peticion.getRemoteAddr());
        return RespuestaLogin.de(usuario);
    }
}