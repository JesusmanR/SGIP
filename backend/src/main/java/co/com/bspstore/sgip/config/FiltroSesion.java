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

    /**
     * El contador de la prueba (RF-73). Pregunta solo, cada cinco minutos,
     * y por eso no cuenta como actividad: valida la sesión pero no la alarga
     * (RNF-09, ADR-0031). Es la única ruta así, y se compara exacta.
     */
    private static final String RUTA_CONTADOR_PRUEBA = "/api/v1/empresa/prueba";

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
            (noRenuevaLaSesion(peticion) ? sesiones.validarSinRenovar(token) : sesiones.validar(token))
                    .map(Sesion::getUsuario)
                    // Una cuenta desactivada después del ingreso deja de valer
                    // de inmediato, sin esperar a que expire. El bloqueo por
                    // intentos fallidos no: solo impide entrar de nuevo. Si
                    // expulsara, quien conozca un correo sacaría a su dueño de
                    // la sesión equivocándose cinco veces (ADR-0028).
                    .filter(Usuario::isActivo)
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

    /**
     * Si la petición es exactamente el GET del contador de la prueba. Lo
     * decide la ruta que pidió el cliente, no algo que el cliente declare:
     * cualquier otra ruta, método o variante renueva, que es lo seguro.
     */
    static boolean noRenuevaLaSesion(HttpServletRequest peticion) {
        if (!"GET".equals(peticion.getMethod())) {
            return false;
        }
        String uri = peticion.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contexto = peticion.getContextPath();
        String ruta = (contexto != null && !contexto.isEmpty() && uri.startsWith(contexto))
                ? uri.substring(contexto.length())
                : uri;
        return RUTA_CONTADOR_PRUEBA.equals(ruta);
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
