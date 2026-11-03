package co.com.bspstore.sgip.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * La contraseña inicial se cambia antes de hacer cualquier otra cosa.
 *
 * Todo usuario nace con {@code cambio_clave_req} en cierto: la clave con
 * la que entra la primera vez la conoce quien lo creó. Marcar la bandera
 * y confiar en que la pantalla la respete no obliga a nada, porque un
 * cliente que no sea la pantalla simplemente la ignora. Por eso la regla
 * se aplica aquí, delante de todos los controladores: mientras el cambio
 * esté pendiente, la sesión sirve para dos cosas —cambiar la clave y
 * salir— y para nada más.
 *
 * ## La lista de excepciones
 *
 * Son dos pares de método y ruta, comparados de forma exacta:
 * {@code POST /api/v1/auth/clave} y {@code POST /api/v1/auth/logout}.
 * Nada de prefijos ni de «empieza por»: cualquier variante de la ruta
 * —barra final, doble barra, un carácter codificado, un parámetro de
 * ruta— no coincide y cae del lado que rechaza. Se compara contra la
 * ruta sin el contexto de la aplicación, porque {@code getRequestURI()}
 * lo incluye; sin quitarlo, la excepción dejaría de aplicar si algún día
 * la aplicación se sirve bajo un prefijo.
 *
 * ## De dónde sale el estado
 *
 * De {@link UsuarioAutenticado#debeCambiarClave()}, que {@link FiltroSesion}
 * reconstruye en cada petición desde el usuario de la base. No hay nada
 * que invalidar al cambiar la clave: la petición siguiente, con el mismo
 * token, ya viene sin la restricción.
 *
 * ## A quién no afecta
 *
 * A las peticiones sin autenticar —el ingreso es una de ellas, y tiene
 * que seguir funcionando— ni a quien no tiene el cambio pendiente.
 */
@Component
public class FiltroCambioClave extends OncePerRequestFilter {

    private static final String RUTA_CLAVE = "/api/v1/auth/clave";
    private static final String RUTA_SALIDA = "/api/v1/auth/logout";

    @Override
    protected void doFilterInternal(HttpServletRequest peticion,
                                    HttpServletResponse respuesta,
                                    FilterChain cadena)
            throws ServletException, IOException {

        boolean pendiente = ContextoEmpresa.actual()
                .map(UsuarioAutenticado::debeCambiarClave)
                .orElse(false);

        if (!pendiente || esClaveOSalida(peticion)) {
            cadena.doFilter(peticion, respuesta);
            return;
        }

        rechazar(respuesta);
    }

    /**
     * Si la petición es exactamente un POST a cambiar la clave o a salir.
     * Lo comparte {@link FiltroEmpresaActiva}: las dos cosas son del
     * usuario y no del negocio, y una empresa en modo de consulta no debe
     * impedirlas.
     */
    static boolean esClaveOSalida(HttpServletRequest peticion) {
        if (!"POST".equals(peticion.getMethod())) {
            return false;
        }
        String uri = peticion.getRequestURI();
        String contexto = peticion.getContextPath();
        if (uri == null) {
            return false;
        }
        String ruta = (contexto != null && !contexto.isEmpty() && uri.startsWith(contexto))
                ? uri.substring(contexto.length())
                : uri;
        return RUTA_CLAVE.equals(ruta) || RUTA_SALIDA.equals(ruta);
    }

    /**
     * A mano, por lo mismo que en {@link FiltroEmpresaActiva}: a esta
     * altura de la cadena no hay controlador del que colgar el manejador
     * de errores, y una excepción saldría como un 500 del contenedor.
     * Lleva el {@code codigo} para que el cliente distinga este 403 de
     * los demás sin comparar textos.
     */
    private void rechazar(HttpServletResponse respuesta) throws IOException {
        respuesta.setStatus(HttpStatus.FORBIDDEN.value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding("UTF-8");
        respuesta.getWriter().write("""
                {"type":"about:blank",\
                "title":"Cambio de contraseña obligatorio",\
                "status":403,\
                "detail":"Debes cambiar tu contraseña inicial antes de seguir.",\
                "codigo":"CAMBIO_CLAVE_REQUERIDO"}""");
    }

    /**
     * Que el despacho de error no vuelva a pasar por aquí: la decisión se
     * tomó en la petición original, y repetirla sobre /error solo
     * escondería el error de verdad.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }
}
