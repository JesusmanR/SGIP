package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.modelo.Sesion;
import co.com.bspstore.sgip.modelo.Usuario;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Traduce la cabecera {@code Authorization: Bearer <token>} en una
 * autenticación de Spring Security.
 *
 * El filtro nunca rechaza por sí mismo: si el token falta o no vale,
 * deja la petición sin autenticar y que decida la cadena de seguridad.
 * Así un endpoint público sigue siendo público aunque llegue con un
 * token caducado.
 */
@Component
public class FiltroSesion extends OncePerRequestFilter {

    private static final String PREFIJO = "Bearer ";

    private final ServicioSesion sesiones;

    public FiltroSesion(ServicioSesion sesiones) {
        this.sesiones = sesiones;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion,
                                    HttpServletResponse respuesta,
                                    FilterChain cadena)
            throws ServletException, IOException {

        String token = extraerToken(peticion);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            sesiones.validar(token)
                    .map(Sesion::getUsuario)
                    // Una cuenta desactivada o bloqueada después del ingreso
                    // deja de valer de inmediato, sin esperar a que expire.
                    .filter(Usuario::puedeIngresar)
                    .ifPresent(this::autenticar);
        }
        cadena.doFilter(peticion, respuesta);
    }

    /**
     * Ejecutarse también cuando la petición acaba en error.
     *
     * OncePerRequestFilter se salta el despacho de error por omisión, y
     * eso convierte cualquier fallo en un 401 engañoso: al fallar algo,
     * Spring reenvía la petición a /error; como este filtro no corre, el
     * contexto de seguridad queda vacío —la aplicación es sin estado y no
     * hay sesión HTTP donde recordarlo— y la cadena responde «no estás
     * autenticado» en vez del error que de verdad ocurrió.
     *
     * El cliente entonces cree que su sesión caducó y echa al usuario, por
     * un archivo demasiado grande o por una imagen corrupta.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private String extraerToken(HttpServletRequest peticion) {
        String cabecera = peticion.getHeader(HttpHeaders.AUTHORIZATION);
        if (cabecera == null || !cabecera.startsWith(PREFIJO)) {
            return null;
        }
        String token = cabecera.substring(PREFIJO.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void autenticar(Usuario usuario) {
        // El prefijo ROLE_ no es decorativo: hasRole('ADMINISTRADOR') lo
        // antepone por su cuenta, de modo que sin él la comprobación
        // nunca encontraría la autoridad.
        var autoridad = new SimpleGrantedAuthority("ROLE_" + usuario.getRol().getNombre());
        // El principal es el usuario y no su correo: lleva la empresa, que
        // es lo que acota cada consulta del catálogo y de las ventas.
        var autenticacion = new UsernamePasswordAuthenticationToken(
                UsuarioAutenticado.de(usuario), null, List.of(autoridad));

        SecurityContext contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(autenticacion);
        SecurityContextHolder.setContext(contexto);
    }
}
