package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.logica.ServicioAutenticacion;
import co.com.bspstore.sgip.modelo.Usuario;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class FiltroToken extends OncePerRequestFilter {

    private static final String CABECERA = "Authorization";
    private static final String PREFIJO = "Bearer ";

    private final ServicioAutenticacion servicio;

    public FiltroToken(ServicioAutenticacion servicio) {
        this.servicio = servicio;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion,
                                    HttpServletResponse respuesta,
                                    FilterChain cadena)
            throws ServletException, IOException {

        String token = extraerToken(peticion);
        logger.info("Token extraído: " + (token == null ? "NINGUNO" : token.substring(0, 8) + "..."));
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            var usuario = servicio.validarToken(token);
        }
        cadena.doFilter(peticion, respuesta);
    }

    private String extraerToken(HttpServletRequest peticion) {
        String cabecera = peticion.getHeader(CABECERA);
        if (cabecera == null || !cabecera.startsWith(PREFIJO)) {
            return null;
        }
        String token = cabecera.substring(PREFIJO.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void autenticar(Usuario usuario) {
        var rol = new SimpleGrantedAuthority("ROLE_" + usuario.getRol().getNombre());
        var autenticacion = new UsernamePasswordAuthenticationToken(usuario, null, List.of(rol));
        SecurityContextHolder.getContext().setAuthentication(autenticacion);
    }
}